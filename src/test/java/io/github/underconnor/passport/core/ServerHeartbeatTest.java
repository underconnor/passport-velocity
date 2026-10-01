package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerHeartbeatTest {
    @Test void overlappingTicksShareOneRequestAndNextTickCanRunAfterCompletion() {
        AtomicInteger sent=new AtomicInteger(); List<CompletableFuture<Void>> requests=new ArrayList<>();
        var heartbeat=new ServerHeartbeat(()->{sent.incrementAndGet();var request=new CompletableFuture<Void>();requests.add(request);return request;},available->fail("No outage occurred"));
        var first=heartbeat.poll(); assertFalse(first.isDone()); assertSame(first,heartbeat.poll()); assertEquals(1,sent.get());
        requests.getFirst().complete(null); first.join();
        var second=heartbeat.poll(); assertNotSame(first,second); assertEquals(2,sent.get()); requests.get(1).complete(null); second.join();
    }
    @Test void repeatedFailuresNotifyOnceThenRecoveryOnceWithoutStoppingFutureHeartbeats() {
        AtomicInteger sent=new AtomicInteger(); List<Boolean> availability=new ArrayList<>();
        var heartbeat=new ServerHeartbeat(()->sent.getAndIncrement()<3 ? CompletableFuture.failedFuture(new IllegalStateException("synthetic unavailable")) : CompletableFuture.completedFuture(null),availability::add);
        for(int i=0;i<3;i++) assertThrows(CompletionException.class,()->heartbeat.poll().join());
        assertEquals(List.of(false),availability);
        heartbeat.poll().join(); heartbeat.poll().join(); assertEquals(List.of(false,true),availability); assertEquals(5,sent.get());
    }
    @Test void synchronousValidationFailureReleasesGateAndCanRecover() {
        AtomicBoolean invalid=new AtomicBoolean(true); List<Boolean> availability=new ArrayList<>();
        var heartbeat=new ServerHeartbeat(()->{if(invalid.get())throw new IllegalArgumentException("invalid metadata");return CompletableFuture.completedFuture(null);},availability::add);
        assertThrows(CompletionException.class,()->heartbeat.poll().join()); invalid.set(false);heartbeat.poll().join();
        assertEquals(List.of(false,true),availability);
    }
    @Test void discoveryFailureDoesNotRevokeOrExtendExistingPolicyLease() {
        UUID id=UUID.randomUUID(); var now=java.time.Instant.now(); var cache=new PolicyCache();
        var policy=new Policy(id,"active",Set.of("lobby"),"","",1,now,now.plusSeconds(60));
        assertTrue(cache.acceptOrCurrent(policy));
        var heartbeat=new ServerHeartbeat(()->CompletableFuture.failedFuture(new IllegalStateException("unavailable")),available->{});
        assertThrows(CompletionException.class,()->heartbeat.poll().join());
        assertTrue(cache.allows(id,"lobby",now)); assertFalse(cache.allows(id,"lobby",now.plusSeconds(60)));
        assertSame(policy,cache.get(id).orElseThrow());
    }
}
