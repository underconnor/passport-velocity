package io.github.underconnor.passport.core;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** One pending link in one live connection. Manual and automatic confirmation share the same gate. */
public final class LinkCompletionPoller {
    private final String id;
    private final Instant expiresAt;
    private final BooleanSupplier current;
    private final Supplier<Instant> clock;
    private final Supplier<CompletableFuture<LinkInspection>> inspect;
    private final Supplier<CompletableFuture<String>> confirm;
    private final Runnable linked;
    private final Consumer<LinkFeedback> feedback;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile boolean stopped;
    private volatile Instant nextPoll = Instant.MIN;
    private int failures;
    private boolean outageNotified;

    public LinkCompletionPoller(String id, Instant expiresAt, BooleanSupplier current, Supplier<Instant> clock,
        Supplier<CompletableFuture<LinkInspection>> inspect, Supplier<CompletableFuture<String>> confirm,
        Runnable linked, Consumer<LinkFeedback> feedback) {
        this.id=id; this.expiresAt=expiresAt; this.current=current; this.clock=clock;
        this.inspect=inspect; this.confirm=confirm; this.linked=linked; this.feedback=feedback;
    }
    public void stop() { stopped=true; }
    public boolean stopped() { return stopped; }
    public void tick() {
        if (clock.get().isBefore(nextPoll)) return;
        run(false);
    }
    public void confirmManually() { run(true); }
    private boolean live() { return !stopped && current.getAsBoolean(); }
    private boolean usable() {
        if (!live()) return false;
        if (expiresAt.isAfter(clock.get())) return true;
        stopped=true;
        feedback.accept(new LinkFeedback("연결 요청이 만료되었습니다. /passport 로 새 링크를 받아 웹에서 연결을 확인하세요.",false));
        return false;
    }
    private void run(boolean manual) {
        if (!live() || !inFlight.compareAndSet(false,true)) return;
        if (!usable()) { inFlight.set(false); return; }
        CompletableFuture<String> operation;
        AtomicBoolean confirmation = new AtomicBoolean(manual);
        try {
            operation = manual ? confirm.get() : inspect.get().thenCompose(state -> {
                if (!usable()) return CompletableFuture.completedFuture(null);
                if (!id.equals(state.id()) || !expiresAt.equals(state.expiresAt()))
                    return CompletableFuture.failedFuture(new IllegalArgumentException("Link inspection does not match current request"));
                if (state.status().equals("linked")) return CompletableFuture.completedFuture("linked");
                if (state.webConfirmed() && !state.gameConfirmed()) {
                    if (!usable()) return CompletableFuture.completedFuture(null);
                    confirmation.set(true);
                    return confirm.get();
                }
                return CompletableFuture.completedFuture("pending");
            });
        } catch (RuntimeException error) { operation=CompletableFuture.failedFuture(error); }
        operation.whenComplete((status,error) -> {
            try {
                if (!usable()) return;
                if (error != null) { failed(error,manual,confirmation.get()); return; }
                failures=0;
                nextPoll=clock.get().plusSeconds(2);
                if ("linked".equals(status)) { stopped=true; linked.run(); }
                else if (manual && "pending".equals(status))
                    feedback.accept(new LinkFeedback("게임 확인 완료. 웹의 계정 연결 화면에서 연결을 확인하세요.",false));
            } finally { inFlight.set(false); }
        });
    }
    private void failed(Throwable error, boolean manual, boolean confirmation) {
        ApiFailure.Reason reason=ApiFailure.reasonOf(error);
        // A manual/remote confirmation can win a race. Inspect again instead of repeating a mutation.
        if (reason==ApiFailure.Reason.GAME_CONFIRMATION_CONSUMED || (confirmation && reason==ApiFailure.Reason.LINK_CONSUMED)) {
            nextPoll=clock.get().plusSeconds(2);
            if (manual) feedback.accept(LinkFeedback.confirmation(error));
            return;
        }
        Throwable root=error;
        while ((root instanceof CompletionException || root instanceof ExecutionException) && root.getCause()!=null) root=root.getCause();
        if (reason==ApiFailure.Reason.LINK_EXPIRED || reason==ApiFailure.Reason.LINK_CONSUMED
            || reason==ApiFailure.Reason.GAME_SESSION_MISMATCH || reason==ApiFailure.Reason.LINK_NOT_FOUND
            || (root instanceof ApiFailure failure && failure.status()>=400 && failure.status()<500 && failure.status()!=429)
            || root instanceof IllegalArgumentException) {
            stopped=true;
            feedback.accept(LinkFeedback.confirmation(error));
            return;
        }
        failures=Math.min(failures+1,4);
        nextPoll=clock.get().plusSeconds(Math.min(15,1L<<failures));
        if (!outageNotified) {
            outageNotified=true;
            feedback.accept(new LinkFeedback("연결 상태를 확인할 수 없습니다. 게임 접속을 유지하면 자동으로 다시 확인합니다.",false));
        }
    }
}
