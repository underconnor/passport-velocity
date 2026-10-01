package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class LinkCompletionPollerTest {
    private static final String ID="22222222-2222-2222-2222-222222222222";
    private static final Instant START=Instant.parse("2026-10-01T00:00:00Z"), EXPIRY=START.plusSeconds(300);
    private static class Harness {
        final AtomicReference<Instant> now=new AtomicReference<>(START);
        final AtomicBoolean current=new AtomicBoolean(true);
        final AtomicInteger inspections=new AtomicInteger(), confirmations=new AtomicInteger(), linked=new AtomicInteger();
        final List<LinkFeedback> feedback=new ArrayList<>();
        Supplier<CompletableFuture<LinkInspection>> inspect=() -> CompletableFuture.completedFuture(state(false,false,"pending"));
        Supplier<CompletableFuture<String>> confirm=() -> CompletableFuture.completedFuture("linked");
        final LinkCompletionPoller poller=new LinkCompletionPoller(ID,EXPIRY,current::get,now::get,
            () -> { inspections.incrementAndGet(); return inspect.get(); },
            () -> { confirmations.incrementAndGet(); return confirm.get(); },linked::incrementAndGet,feedback::add);
        void advance(int seconds) { now.set(now.get().plusSeconds(seconds)); }
    }
    private static LinkInspection state(boolean web, boolean game, String status) { return new LinkInspection(ID,status,EXPIRY,web,game); }
    private static ApiFailure failure(int status,String code) { return ApiFailure.fromResponse(status,("{\"code\":\""+code+"\"}").getBytes(StandardCharsets.UTF_8)); }

    @Test void webConfirmationCompletesLiveGameWithoutAnotherCommand() {
        Harness h=new Harness(); h.poller.tick();
        assertEquals(1,h.inspections.get()); assertEquals(0,h.confirmations.get()); assertEquals(0,h.linked.get());
        h.inspect=() -> CompletableFuture.completedFuture(state(true,false,"pending"));
        h.advance(1); h.poller.tick(); assertEquals(1,h.inspections.get());
        h.advance(1); h.poller.tick();
        assertEquals(1,h.confirmations.get()); assertEquals(1,h.linked.get()); assertTrue(h.poller.stopped());
        h.advance(2); h.poller.tick(); h.poller.confirmManually();
        assertEquals(1,h.confirmations.get()); assertTrue(h.feedback.isEmpty());
    }
    @Test void pollingAndManualConfirmationShareOneInflightGate() {
        Harness h=new Harness(); CompletableFuture<LinkInspection> inspection=new CompletableFuture<>();
        CompletableFuture<String> confirmation=new CompletableFuture<>();
        h.inspect=() -> inspection; h.confirm=() -> confirmation;
        h.poller.tick(); h.poller.tick(); h.poller.confirmManually();
        assertEquals(1,h.inspections.get()); assertEquals(0,h.confirmations.get());
        inspection.complete(state(true,false,"pending")); h.poller.confirmManually(); h.poller.tick();
        assertEquals(1,h.confirmations.get()); confirmation.complete("linked"); assertEquals(1,h.linked.get());
    }
    @Test void disconnectOrNewGenerationDuringInspectionCannotConfirm() {
        Harness h=new Harness(); CompletableFuture<LinkInspection> response=new CompletableFuture<>(); h.inspect=() -> response;
        h.poller.tick(); h.current.set(false); response.complete(state(true,false,"pending"));
        assertEquals(0,h.confirmations.get()); assertEquals(0,h.linked.get()); assertTrue(h.feedback.isEmpty());
    }
    @Test void disconnectOrCancelDuringConfirmationCannotApplyCompletion() {
        Harness h=new Harness(); CompletableFuture<String> response=new CompletableFuture<>();
        h.inspect=() -> CompletableFuture.completedFuture(state(true,false,"pending")); h.confirm=() -> response;
        h.poller.tick(); h.poller.stop(); response.complete("linked");
        assertEquals(1,h.confirmations.get()); assertEquals(0,h.linked.get()); assertTrue(h.feedback.isEmpty());
    }
    @Test void expiredResponseNeverConfirmsAndOnlyNotifiesOnce() {
        Harness h=new Harness(); CompletableFuture<LinkInspection> response=new CompletableFuture<>(); h.inspect=() -> response;
        h.poller.tick(); h.now.set(EXPIRY); response.complete(state(true,false,"pending")); h.poller.tick();
        assertEquals(0,h.confirmations.get()); assertEquals(0,h.linked.get()); assertTrue(h.poller.stopped());
        assertEquals(1,h.feedback.size()); assertTrue(h.feedback.getFirst().message().contains("만료"));
    }
    @Test void manualConfirmationBeforeWebDoesNotRepeatTheMutation() {
        Harness h=new Harness(); h.confirm=() -> CompletableFuture.completedFuture("pending");
        h.poller.confirmManually(); assertEquals(1,h.confirmations.get());
        h.inspect=() -> CompletableFuture.completedFuture(state(false,true,"pending")); h.advance(2); h.poller.tick();
        assertEquals(1,h.confirmations.get()); assertEquals(0,h.linked.get());
        h.inspect=() -> CompletableFuture.completedFuture(state(true,true,"linked")); h.advance(2); h.poller.tick();
        assertEquals(1,h.confirmations.get()); assertEquals(1,h.linked.get()); assertEquals(1,h.feedback.size());
    }
    @Test void automaticConfirmationConflictRecoversByInspection() {
        for(String code:List.of("game_confirmation_consumed","link_consumed")) {
            Harness h=new Harness(); h.inspect=() -> CompletableFuture.completedFuture(state(true,false,"pending"));
            h.confirm=() -> CompletableFuture.failedFuture(failure(409,code)); h.poller.tick();
            assertFalse(h.poller.stopped()); assertEquals(0,h.linked.get());
            h.inspect=() -> CompletableFuture.completedFuture(state(true,true,"linked")); h.advance(2); h.poller.tick();
            assertEquals(1,h.linked.get()); assertEquals(1,h.confirmations.get());
        }
    }
    @Test void cancelledInspectionAndMismatchedGameSessionStopWithoutCompletion() {
        for(ApiFailure error:List.of(failure(409,"link_consumed"),failure(403,"game_session_mismatch"),failure(404,"link_not_found"),failure(401,"invalid_service_token"))) {
            Harness h=new Harness(); h.inspect=() -> CompletableFuture.failedFuture(error); h.poller.tick(); h.advance(20); h.poller.tick();
            assertTrue(h.poller.stopped()); assertEquals(1,h.inspections.get()); assertEquals(0,h.confirmations.get()); assertEquals(0,h.linked.get());
        }
    }
    @Test void networkFailureBacksOffAndNeverSpamsChat() {
        Harness h=new Harness(); h.inspect=() -> CompletableFuture.failedFuture(new TimeoutException());
        h.poller.tick(); h.advance(1); h.poller.tick(); assertEquals(1,h.inspections.get());
        h.advance(1); h.poller.tick(); assertEquals(2,h.inspections.get());
        h.advance(3); h.poller.tick(); assertEquals(2,h.inspections.get());
        h.advance(1); h.poller.tick(); assertEquals(3,h.inspections.get());
        h.advance(8); h.poller.tick(); assertEquals(4,h.inspections.get());
        h.advance(14); h.poller.tick(); assertEquals(4,h.inspections.get());
        h.advance(1); h.poller.tick(); assertEquals(5,h.inspections.get());
        assertEquals(1,h.feedback.size()); assertEquals(0,h.confirmations.get()); assertFalse(h.poller.stopped());
    }
    @Test void inspectionCannotExtendTheOriginalLinkLease() {
        Harness h=new Harness(); h.inspect=() -> CompletableFuture.completedFuture(new LinkInspection(ID,"linked",EXPIRY.plusSeconds(1),true,true));
        h.poller.tick(); assertTrue(h.poller.stopped()); assertEquals(0,h.linked.get()); assertEquals(0,h.confirmations.get());
    }
}
