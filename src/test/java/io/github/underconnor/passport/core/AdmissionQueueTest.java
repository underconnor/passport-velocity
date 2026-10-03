package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static io.github.underconnor.passport.core.AdmissionQueue.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionQueueTest {
    final Instant now=Instant.parse("2026-10-03T00:00:00Z");
    final Map<String,Set<AdmissionQueue.Key>> online=new ConcurrentHashMap<>();
    final AdmissionQueue queue=new AdmissionQueue(Map.of("lobby",1,"build",1,"limbo",1),server -> online.getOrDefault(server,Set.of()));
    AdmissionQueue.Key key() { return new AdmissionQueue.Key(UUID.randomUUID(),UUID.randomUUID().toString()); }
    Policy policy(AdmissionQueue.Key key,boolean admin) {
        return new Policy(key.uuid(),"active",Set.of("lobby","build"),"","",1,now,now.plusSeconds(60),false,null,admin,Map.of());
    }
    AdmissionQueue.Decision request(AdmissionQueue.Key key,String server,boolean admin,long clock) {
        return queue.request(key,server,policy(key,admin),false,now,clock);
    }
    AdmissionQueue.Ticket ticket(AdmissionQueue.Key key) { return queue.position(key).orElseThrow().ticket(); }
    void full(String server) { online.put(server,Set.of(key())); }
    @Test void simultaneousBurstReservesOnlyTheAvailableSlot() throws Exception {
        try(ExecutorService pool=Executors.newFixedThreadPool(16)) {
            List<Callable<AdmissionQueue.Decision>> tasks=new ArrayList<>();
            for(int i=0;i<100;i++) { AdmissionQueue.Key key=key(); tasks.add(() -> request(key,"lobby",false,0)); }
            int allowed=0,queued=0;
            for(Future<AdmissionQueue.Decision> future:pool.invokeAll(tasks)) {
                if(future.get().status()==ALLOWED) allowed++; else if(future.get().status()==QUEUED) queued++;
            }
            assertEquals(1,allowed); assertEquals(99,queued);
        }
    }
    @Test void existingWaiterCannotBeOvertakenByANewConnectionEvenWhenSpaceOpens() {
        full("lobby"); AdmissionQueue.Key first=key(),second=key(),newcomer=key();
        request(first,"lobby",false,0); request(second,"lobby",false,0); online.remove("lobby");
        assertEquals(QUEUED,request(newcomer,"lobby",false,0).status());
        assertEquals(1,queue.position(first).orElseThrow().position());
        assertEquals(2,queue.position(second).orElseThrow().position());
        assertEquals(3,queue.position(newcomer).orElseThrow().position());
        assertEquals(ALLOWED,queue.promote(ticket(first),policy(first,false),false,now,0).status());
        assertFalse(queue.canAttempt(ticket(second),false,0));
    }
    @Test void repeatingPreConnectForTheReservationDoesNotConsumeASecondSlot() {
        AdmissionQueue.Key player=key(); AdmissionQueue.Decision first=request(player,"lobby",false,0);
        AdmissionQueue.Decision repeated=request(player,"lobby",false,0);
        assertEquals(first.reservation(),repeated.reservation()); assertFalse(repeated.joined());
        assertEquals(QUEUED,request(key(),"lobby",false,0).status());
    }
    @Test void onlyOnePhysicalConnectionCanBeginBehindAReservation() {
        AdmissionQueue.Key player=key(); AdmissionQueue.Reservation pending=request(player,"lobby",false,0).reservation();
        assertTrue(queue.begin(pending)); assertFalse(queue.begin(pending));
        assertEquals(BUSY,request(player,"lobby",false,0).status());
        assertFalse(queue.releaseUnstarted(pending));
        assertEquals(QUEUED,request(key(),"lobby",false,0).status());
    }
    @Test void arrivalNeverCreatesAGapBetweenReservedAndActualOccupancy() {
        AdmissionQueue.Key player=key(),waiter=key(); request(player,"lobby",false,0);
        queue.arrived(player,"lobby"); // event arrived before occupancy collection updated
        assertTrue(queue.reservation(player).isPresent());
        assertEquals(QUEUED,request(waiter,"lobby",false,0).status());
        online.put("lobby",Set.of(player)); queue.arrived(player,"lobby");
        assertTrue(queue.reservation(player).isEmpty());
        assertFalse(queue.canAttempt(ticket(waiter),false,0));
        online.remove("lobby"); assertTrue(queue.canAttempt(ticket(waiter),false,0));
    }
    @Test void reconnectingUuidCannotReuseTheOldSessionsActualSlot() {
        AdmissionQueue.Key old=key(),fresh=new AdmissionQueue.Key(old.uuid(),"new-login");
        online.put("lobby",Set.of(old));
        assertEquals(QUEUED,request(fresh,"lobby",false,0).status());
        queue.disconnected(old); online.remove("lobby");
        assertEquals(ALLOWED,queue.promote(ticket(fresh),policy(fresh,false),false,now,0).status());
    }
    @Test void limboReservationPreservesTheInitialLobbyQueue() {
        full("lobby"); AdmissionQueue.Key player=key(); request(player,"lobby",false,0);
        AdmissionQueue.Ticket lobby=ticket(player);
        assertEquals(ALLOWED,queue.waiting(player,"limbo",0).status());
        assertEquals(lobby,ticket(player));
        online.put("limbo",Set.of(player)); queue.arrived(player,"limbo");
        assertEquals(lobby,ticket(player));
        online.remove("lobby"); assertTrue(queue.canAttempt(lobby,false,0));
    }
    @Test void limboFullDoesNotCreateALimboQueueOrExemptUnverifiedPlayers() {
        AdmissionQueue.Key first=key(),second=key(); assertEquals(ALLOWED,queue.waiting(first,"limbo",0).status());
        assertEquals(FULL,queue.waiting(second,"limbo",0).status()); assertTrue(queue.position(second).isEmpty());
    }
    @Test void onePlayerHasOneQueueAndChangingTargetInvalidatesTheOldTicket() {
        full("lobby"); full("build"); AdmissionQueue.Key player=key(); request(player,"lobby",false,0);
        AdmissionQueue.Ticket old=ticket(player); request(player,"build",false,0);
        assertEquals("build",ticket(player).server());
        assertEquals(STALE,queue.promote(old,policy(player,false),false,now,0).status());
        assertFalse(queue.cancel(old)); assertEquals("build",ticket(player).server());
    }
    @Test void changingTargetCannotReleaseAConnectionStillInFlight() {
        AdmissionQueue.Key player=key(); AdmissionQueue.Reservation pending=request(player,"lobby",false,0).reservation();
        assertEquals(BUSY,request(player,"build",false,0).status()); assertEquals(pending,queue.reservation(player).orElseThrow());
        assertEquals(QUEUED,request(key(),"lobby",false,0).status());
    }
    @Test void cancellationAndRejoinDoNotAllowAnOldAsyncPromotion() {
        full("lobby"); AdmissionQueue.Key player=key(); request(player,"lobby",false,0);
        AdmissionQueue.Ticket old=ticket(player); assertTrue(queue.cancel(old)); request(player,"lobby",false,0);
        assertNotEquals(old,ticket(player)); online.remove("lobby");
        assertEquals(STALE,queue.promote(old,policy(player,false),false,now,0).status());
        assertEquals(ALLOWED,queue.promote(ticket(player),policy(player,false),false,now,0).status());
    }
    @Test void disconnectCleansQueueAndReservationWithoutAffectingANewLogin() {
        full("lobby"); AdmissionQueue.Key old=key(),fresh=new AdmissionQueue.Key(old.uuid(),"new");
        request(old,"lobby",false,0); queue.waiting(old,"limbo",0); queue.disconnected(old);
        assertTrue(queue.position(old).isEmpty()); assertTrue(queue.reservation(old).isEmpty());
        queue.waiting(fresh,"limbo",0); queue.disconnected(old);
        assertTrue(queue.reservation(fresh).isPresent());
    }
    @Test void registeredAdministratorCanBypassQueueAndFullWithoutCreatingNormalSpace() {
        full("lobby"); AdmissionQueue.Key waiter=key(),admin=key(); request(waiter,"lobby",false,0);
        AdmissionQueue.Decision admitted=request(admin,"lobby",true,0);
        assertEquals(ALLOWED,admitted.status()); assertTrue(admitted.reservation().bypass());
        online.remove("lobby"); assertFalse(queue.canAttempt(ticket(waiter),false,0));
        online.put("lobby",Set.of(admin)); queue.arrived(admin,"lobby");
        assertFalse(queue.canAttempt(ticket(waiter),false,0));
        assertEquals(QUEUED,request(key(),"lobby",false,0).status());
    }
    @Test void AdministratorBypassStillRequiresUuidLeaseTargetScopeAndNoLocalDeny() {
        full("lobby"); AdmissionQueue.Key player=key(); Policy admin=policy(player,true);
        assertTrue(AdmissionQueue.bypass(admin,player.uuid(),"lobby",false,now));
        assertFalse(AdmissionQueue.bypass(admin,player.uuid(),"lobby",true,now));
        assertFalse(AdmissionQueue.bypass(admin,UUID.randomUUID(),"lobby",false,now));
        assertFalse(AdmissionQueue.bypass(admin,player.uuid(),"secret",false,now));
        assertFalse(AdmissionQueue.bypass(admin,player.uuid(),"lobby",false,admin.expiresAt()));
        assertEquals(QUEUED,queue.request(player,"lobby",admin,true,now,0).status());
        assertEquals(DENIED,queue.request(player,"secret",admin,false,now,0).status());
        assertEquals(DENIED,queue.request(player,"lobby",admin,false,admin.expiresAt(),0).status());
        assertEquals(DENIED,queue.request(player,"lobby",policy(key(),true),false,now,0).status());
    }
    @Test void adminDowngradeBeforePreConnectRechecksCapacityAndJoinsBehindExistingWaiters() {
        full("lobby"); AdmissionQueue.Key waiter=key(),admin=key(); request(waiter,"lobby",false,0); request(admin,"lobby",true,0);
        assertEquals(QUEUED,request(admin,"lobby",false,0).status());
        assertTrue(queue.reservation(admin).isEmpty()); assertEquals(2,queue.position(admin).orElseThrow().position());
    }
    @Test void promotedAdminCanBypassFromTheMiddleButAnOrdinaryUserCannot() {
        full("lobby"); AdmissionQueue.Key first=key(),second=key(); request(first,"lobby",false,0); request(second,"lobby",false,0);
        assertFalse(queue.canAttempt(ticket(second),false,0)); assertTrue(queue.canAttempt(ticket(second),true,0));
        assertEquals(ALLOWED,queue.promote(ticket(second),policy(second,true),false,now,0).status());
        assertEquals(1,queue.position(first).orElseThrow().position());
    }
    @Test void schoolExpiryOrRevocationCannotPromoteFromAQueue() {
        full("lobby"); AdmissionQueue.Key player=key(); request(player,"lobby",false,0); AdmissionQueue.Ticket ticket=ticket(player);
        online.remove("lobby"); Policy old=policy(player,true);
        assertEquals(DENIED,queue.promote(ticket,old,false,old.expiresAt(),0).status());
        Policy revoked=new Policy(player.uuid(),"revoked",Set.of(),"","",2,now,now.plusSeconds(60),false,null,true,Map.of());
        assertEquals(DENIED,queue.promote(ticket,revoked,false,now,0).status());
        assertTrue(queue.reservation(player).isEmpty());
    }
    @Test void failedConnectionReleasesOnlyItsOwnReservationAndBacksOffTheServer() {
        AdmissionQueue.Key player=key(),waiter=key(); AdmissionQueue.Reservation old=request(player,"lobby",false,0).reservation();
        request(waiter,"lobby",false,0); assertTrue(queue.release(old,true,0));
        assertFalse(queue.canAttempt(ticket(waiter),false,TimeUnit.SECONDS.toNanos(4)));
        long later=TimeUnit.SECONDS.toNanos(5);
        AdmissionQueue.Reservation next=queue.promote(ticket(waiter),policy(waiter,false),false,now,later).reservation();
        assertNotNull(next); assertFalse(queue.release(old,true,later));
        assertEquals(next,queue.reservation(waiter).orElseThrow());
    }
    @Test void staleCompletionCannotReleaseANewerReservationForTheSameSession() {
        AdmissionQueue.Key player=key(); AdmissionQueue.Reservation old=request(player,"lobby",false,0).reservation();
        queue.release(old,false,0); AdmissionQueue.Reservation next=request(player,"build",false,0).reservation();
        assertFalse(queue.release(old,true,0)); assertEquals(next,queue.reservation(player).orElseThrow());
    }
    @Test void timeoutIsReportedButCapacityIsHeldUntilTheConnectionIsClosed() {
        AdmissionQueue.Key player=key(),waiter=key(); AdmissionQueue.Reservation pending=request(player,"lobby",false,0).reservation();
        request(waiter,"lobby",false,0);
        assertTrue(queue.overdue(TimeUnit.SECONDS.toNanos(44)).isEmpty());
        assertEquals(List.of(pending),queue.overdue(TimeUnit.SECONDS.toNanos(45)));
        assertFalse(queue.canAttempt(ticket(waiter),false,TimeUnit.SECONDS.toNanos(46)));
        queue.disconnected(player); assertTrue(queue.canAttempt(ticket(waiter),false,TimeUnit.SECONDS.toNanos(46)));
    }
    @Test void simultaneousPromotionOfTheSameHeadCreatesOnlyOneReservation() throws Exception {
        full("lobby"); AdmissionQueue.Key player=key(); request(player,"lobby",false,0); AdmissionQueue.Ticket ticket=ticket(player); online.remove("lobby");
        try(ExecutorService pool=Executors.newFixedThreadPool(8)) {
            List<Callable<AdmissionQueue.Decision>> calls=Collections.nCopies(32,() -> queue.promote(ticket,policy(player,false),false,now,0));
            int admitted=0;
            for(Future<AdmissionQueue.Decision> result:pool.invokeAll(calls)) if(result.get().status()==ALLOWED) admitted++;
            assertEquals(1,admitted); assertTrue(queue.reservation(player).isPresent());
        }
    }
    @Test void failedPromotionRestoresTheOriginalOrderAndBacksOffBeforeRetry() {
        full("lobby"); var first=key(); var second=key();
        request(first,"lobby",false,0); request(second,"lobby",false,0);
        var original=ticket(first); online.remove("lobby");
        var slot=queue.promote(original,policy(first,false),false,now,0).reservation();
        assertFalse(queue.restore(original,0)); // A physical connection still owns the slot.
        queue.release(slot,true,0); assertTrue(queue.restore(original,0));
        assertEquals(original,ticket(first)); assertEquals(1,queue.position(first).orElseThrow().position());
        assertEquals(2,queue.position(second).orElseThrow().position());
        assertFalse(queue.canAttempt(original,false,TimeUnit.SECONDS.toNanos(4)));
        assertTrue(queue.canAttempt(original,false,TimeUnit.SECONDS.toNanos(5)));
        assertFalse(queue.canAttempt(ticket(second),false,TimeUnit.SECONDS.toNanos(5)));
    }
    @Test void restoreCannotReplaceAnotherSelectionOrDuplicateAnArrivedPlayer() {
        full("lobby"); full("build"); var player=key(); request(player,"lobby",false,0);
        var original=ticket(player); request(player,"build",false,0);
        assertFalse(queue.restore(original,0)); assertEquals("build",ticket(player).server());
        queue.cancel(player); online.put("lobby",Set.of(player));
        assertFalse(queue.restore(original,0)); assertTrue(queue.position(player).isEmpty());
    }
    @Test void fallbackLimboReservationDoesNotPreventRestoringTheFailedDestination() {
        full("lobby"); var player=key(); request(player,"lobby",false,0);
        var original=ticket(player); queue.cancel(player);
        var fallback=queue.waiting(player,"limbo",0).reservation();
        assertTrue(queue.restore(original,0)); assertEquals(fallback,queue.reservation(player).orElseThrow());
        assertEquals(original,ticket(player));
    }
}
