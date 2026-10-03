package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProxyCommandReplayGuardTest {
    private static final Instant NOW=Instant.parse("2026-10-04T00:00:00Z");
    private ProxyCommandMessage request(Instant now) { return ProxyCommandMessage.request(UUID.randomUUID(),"ssu_lobby","passport web",now); }

    @Test void capacityCannotEvictAnUnexpiredRequest() {
        var guard=new ProxyCommandReplayGuard(1); var first=request(NOW); var second=request(NOW.plusSeconds(1));
        assertTrue(guard.claim(first,NOW)); assertFalse(guard.claim(first,NOW));
        assertFalse(guard.claim(second,NOW.plusSeconds(1))); assertFalse(guard.claim(first,NOW.plusSeconds(1)));
        assertFalse(guard.claim(first,NOW.plusSeconds(10))); assertTrue(guard.claim(second,NOW.plusSeconds(10)));
    }

    @Test void sameNonceCannotBeReusedForAnotherPlayerServerOrCommand() {
        var guard=new ProxyCommandReplayGuard(10); var first=request(NOW);
        assertTrue(guard.claim(first,NOW));
        var altered=new ProxyCommandMessage(first.requestId(),UUID.randomUUID(),"ssu_build_2609",first.expiresAt(),"passport help");
        assertFalse(guard.claim(altered,NOW));
    }

    @Test void invalidOrFutureRequestsDoNotConsumeCapacity() {
        var guard=new ProxyCommandReplayGuard(1); var first=request(NOW);
        assertFalse(guard.claim(null,NOW)); assertFalse(guard.claim(first,NOW.minusMillis(1)));
        assertFalse(guard.claim(request(NOW.minusSeconds(10)),NOW)); assertTrue(guard.claim(first,NOW));
    }

    @Test void clearResetsLifecycleStateAndInvalidCapacityIsRejected() {
        for(int capacity:new int[]{0,-1}) assertThrows(IllegalArgumentException.class,() -> new ProxyCommandReplayGuard(capacity));
        var guard=new ProxyCommandReplayGuard(1); var first=request(NOW);
        assertTrue(guard.claim(first,NOW)); guard.clear(); assertTrue(guard.claim(first,NOW));
    }

    @Test void concurrentCopiesOfOneRequestExecuteAtMostOnce() throws Exception {
        var guard=new ProxyCommandReplayGuard(100); var first=request(NOW);
        try(var executor=Executors.newFixedThreadPool(8)) {
            var jobs=new ArrayList<Callable<Boolean>>();
            for(int i=0;i<100;i++) jobs.add(() -> guard.claim(first,NOW));
            int accepted=0;
            for(var result:executor.invokeAll(jobs)) if(result.get()) accepted++;
            assertEquals(1,accepted);
        }
    }
}
