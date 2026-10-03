package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DiscordInvitationTest {
    final UUID uuid = UUID.fromString("11111111-1111-4111-8111-111111111111");
    final Instant now = Instant.parse("2026-10-03T00:00:00Z");
    Policy policy(Boolean linked) { return policy(uuid,linked,now.minusSeconds(1),now.plusSeconds(59)); }
    Policy policy(UUID id,Boolean linked,Instant issued,Instant expires) {
        return new Policy(id,"active",Set.of("lobby"),"member","",1,issued,expires,
            false,null,false,Map.of(),false,null,Set.of(),false,Map.of(),linked);
    }
    @Test void waitsForPlayStateThenSendsOnceAcrossTransfersAndRefreshes() {
        DiscordInvitation invitation = new DiscordInvitation(uuid);
        Policy unlinked = policy(false); invitation.observe(unlinked,now);
        assertFalse(invitation.claim(unlinked,true,false,now));
        assertTrue(invitation.claim(unlinked,true,true,now));
        assertFalse(invitation.claim(unlinked,true,true,now));
        Policy refreshed = policy(uuid,false,now,now.plusSeconds(60)); invitation.observe(refreshed,now);
        assertFalse(invitation.claim(refreshed,true,true,now));
    }
    @Test void missingOrFailedPolicyCanRetryWhenTheApiRecovers() {
        DiscordInvitation invitation = new DiscordInvitation(uuid);
        invitation.observe(null,now);
        assertFalse(invitation.claim(null,true,true,now));
        Policy unknown = policy(null); invitation.observe(unknown,now);
        assertFalse(invitation.claim(unknown,true,true,now));
        Policy unlinked = policy(false); invitation.observe(unlinked,now);
        assertTrue(invitation.claim(unlinked,true,true,now));
    }
    @Test void linkedBeforePlaySuppressesThisWholeLoginEvenAfterLaterUnlink() {
        DiscordInvitation invitation = new DiscordInvitation(uuid);
        Policy linked = policy(true); invitation.observe(linked,now);
        assertFalse(invitation.claim(linked,true,false,now));
        assertFalse(invitation.claim(linked,true,true,now));
        Policy unlinked = policy(uuid,false,now,now.plusSeconds(60)); invitation.observe(unlinked,now);
        assertFalse(invitation.claim(unlinked,true,true,now));
    }
    @Test void reconnectMustObserveItsOwnResponseBeforeSendingAgain() {
        Policy unlinked = policy(false);
        DiscordInvitation first = new DiscordInvitation(uuid); first.observe(unlinked,now);
        assertTrue(first.claim(unlinked,true,true,now));
        DiscordInvitation reconnected = new DiscordInvitation(uuid);
        assertFalse(reconnected.claim(unlinked,true,true,now)); // cached previous login is insufficient
        reconnected.observe(unlinked,now);
        assertTrue(reconnected.claim(unlinked,true,true,now));
    }
    @Test void disconnectedOrSupersededSessionCannotSendOrConsumeDecision() {
        DiscordInvitation invitation = new DiscordInvitation(uuid);
        Policy unlinked = policy(false); invitation.observe(unlinked,now);
        assertFalse(invitation.claim(unlinked,false,true,now));
        assertTrue(invitation.claim(unlinked,true,true,now));
    }
    @Test void expiredFutureAndWrongPlayerResponsesDoNotCountAsObservations() {
        for (Policy invalid : List.of(
            policy(uuid,false,now.minusSeconds(60),now),
            policy(uuid,true,now.plusSeconds(3),now.plusSeconds(60)),
            policy(UUID.randomUUID(),true,now.minusSeconds(1),now.plusSeconds(59)))) {
            DiscordInvitation invitation = new DiscordInvitation(uuid); invitation.observe(invalid,now);
            assertFalse(invitation.claim(invalid,true,true,now));
            Policy unlinked = policy(false); invitation.observe(unlinked,now);
            assertTrue(invitation.claim(unlinked,true,true,now));
        }
    }
    @Test void leaseMustStillBeValidAtSendTime() {
        DiscordInvitation invitation = new DiscordInvitation(uuid);
        Policy unlinked = policy(false); invitation.observe(unlinked,now);
        assertFalse(invitation.claim(unlinked,true,true,unlinked.expiresAt()));
    }
    @Test void latestCachePolicyMustMatchTheSessionObservation() {
        DiscordInvitation invitation = new DiscordInvitation(uuid);
        Policy old = policy(false), latest = policy(uuid,null,now,now.plusSeconds(60));
        invitation.observe(old,now);
        assertFalse(invitation.claim(latest,true,true,now));
        invitation.observe(latest,now);
        assertFalse(invitation.claim(old,true,true,now));
        assertFalse(invitation.claim(latest,true,true,now));
    }
    @Test void invitationDoesNotRequireGameAdmission() {
        DiscordInvitation invitation = new DiscordInvitation(uuid);
        Policy waiting = new Policy(uuid,"unlinked",Set.of(),"","",1,now,now.plusSeconds(60),
            false,null,false,Map.of(),false,null,Set.of(),false,Map.of(),false);
        invitation.observe(waiting,now);
        assertTrue(invitation.claim(waiting,true,true,now));
        assertFalse(waiting.allows("lobby",now));
    }
    @Test void concurrentPostConnectAndTickCanOnlyClaimOnce() throws Exception {
        DiscordInvitation invitation = new DiscordInvitation(uuid);
        Policy unlinked = policy(false); invitation.observe(unlinked,now);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> tasks = Collections.nCopies(32,() -> invitation.claim(unlinked,true,true,now));
            int sent = 0;
            for (Future<Boolean> result : pool.invokeAll(tasks)) if (result.get()) sent++;
            assertEquals(1,sent);
        }
    }
}
