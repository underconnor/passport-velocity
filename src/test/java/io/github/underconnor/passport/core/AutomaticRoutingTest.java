package io.github.underconnor.passport.core;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AutomaticRoutingTest {
    final Instant now=Instant.parse("2026-09-30T00:00:10Z");
    Policy active= new Policy(UUID.randomUUID(),"active",Set.of("lobby","survival"),"member","Test",1,now.minusSeconds(10),now.plusSeconds(50));
    @Test void completedAuthenticationLeavesOnlyWaitingRoom() {
        assertEquals(Optional.of("lobby"),AutomaticRouting.target(active,"passport-limbo","passport-limbo","lobby",now));
        assertTrue(AutomaticRouting.target(active,"survival","passport-limbo","lobby",now).isEmpty());
        assertTrue(AutomaticRouting.target(active,"lobby","passport-limbo","lobby",now).isEmpty());
        assertTrue(AutomaticRouting.target(active,"","passport-limbo","lobby",now).isEmpty());
    }
    @Test void waitingRoomDoesNotOverrideScopeOrLease() {
        assertTrue(AutomaticRouting.target(active,"passport-limbo","passport-limbo","admin",now).isEmpty());
        assertTrue(AutomaticRouting.target(active,"passport-limbo","passport-limbo","lobby",active.expiresAt()).isEmpty());
        Policy revoked=new Policy(active.minecraftUuid(),"revoked",Set.of(),"","",2,now,now.plusSeconds(60));
        assertTrue(AutomaticRouting.target(revoked,"passport-limbo","passport-limbo","lobby",now).isEmpty());
    }
}
