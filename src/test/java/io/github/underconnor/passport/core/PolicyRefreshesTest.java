package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyRefreshesTest {
    Policy policy(UUID uuid) { Instant now=Instant.now(); return new Policy(uuid,"revoked",Set.of(),"","",1,now,now.plusSeconds(60)); }
    @Test void eventAndPeriodicRequestForSameUuidShareWorkAndFailureCanBeRetried() {
        UUID uuid=UUID.randomUUID(); AtomicInteger count=new AtomicInteger(); List<CompletableFuture<Policy>> requests=new ArrayList<>();
        PolicyRefreshes refreshes=new PolicyRefreshes(id -> { count.incrementAndGet(); CompletableFuture<Policy> f=new CompletableFuture<>();requests.add(f);return f; });
        CompletableFuture<Policy> first=refreshes.fetch(uuid); assertSame(first,refreshes.fetch(uuid)); assertEquals(1,count.get());
        requests.getFirst().completeExceptionally(new IllegalStateException("timeout")); assertThrows(CompletionException.class,first::join);
        CompletableFuture<Policy> retry=refreshes.fetch(uuid); assertEquals(2,count.get()); requests.get(1).complete(policy(uuid)); assertEquals(uuid,retry.join().minecraftUuid());
    }
    @Test void resetWaitsThenFetchesAfterOlderInflightRequest() {
        UUID uuid=UUID.randomUUID(); List<CompletableFuture<Policy>> requests=new ArrayList<>();
        PolicyRefreshes refreshes=new PolicyRefreshes(id->{CompletableFuture<Policy> f=new CompletableFuture<>();requests.add(f);return f;});
        CompletableFuture<Policy> older=refreshes.fetch(uuid),reset=refreshes.fresh(uuid),secondReset=refreshes.fresh(uuid);
        assertEquals(1,requests.size()); requests.getFirst().complete(policy(uuid)); assertTrue(older.isDone());
        assertFalse(reset.isDone()); assertEquals(2,requests.size()); requests.get(1).complete(policy(uuid));
        assertEquals(reset.join(),secondReset.join()); assertEquals(2,requests.size());
    }
    @Test void largeResetBatchUsesBoundedFanoutAndEveryUuidCompletes() {
        Map<UUID,CompletableFuture<Policy>> requests=new LinkedHashMap<>(); AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
        PolicyRefreshes refreshes=new PolicyRefreshes(uuid->{
            peak.accumulateAndGet(active.incrementAndGet(),Math::max); CompletableFuture<Policy> f=new CompletableFuture<>(); requests.put(uuid,f); return f;
        },2);
        List<CompletableFuture<Policy>> work=new ArrayList<>(); List<UUID> ids=new ArrayList<>();
        for(int i=0;i<50;i++){UUID id=UUID.randomUUID();ids.add(id);work.add(refreshes.fetch(id));}
        assertEquals(2,requests.size());
        for(UUID uuid:ids){ assertTrue(requests.containsKey(uuid));active.decrementAndGet();requests.get(uuid).complete(policy(uuid)); }
        CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).join(); assertEquals(50,requests.size()); assertEquals(2,peak.get());
    }
    @Test void synchronousSourceFailureDoesNotLeakAQueueSlot() {
        AtomicInteger count=new AtomicInteger(); UUID uuid=UUID.randomUUID();
        PolicyRefreshes refreshes=new PolicyRefreshes(id->{if(count.getAndIncrement()==0)throw new IllegalStateException("closed");return CompletableFuture.completedFuture(policy(id));},1);
        assertThrows(CompletionException.class,()->refreshes.fetch(uuid).join()); assertEquals(uuid,refreshes.fetch(uuid).join().minecraftUuid());
    }

    @Test void freshAdmissionCannotReuseAnOlderAllowedLeaseWhenTheNewRequestFails() {
        UUID uuid=UUID.randomUUID(); List<CompletableFuture<Policy>> requests=new ArrayList<>();
        var refreshes=new PolicyRefreshes(id->{var f=new CompletableFuture<Policy>();requests.add(f);return f;});
        var cachedRequest=refreshes.fetch(uuid); var admission=refreshes.fresh(uuid);
        var now=Instant.now(); var active=new Policy(uuid,"active",Set.of("lobby"),"","",1,now,now.plusSeconds(60));
        requests.getFirst().complete(active); assertTrue(cachedRequest.join().allows("lobby",now));assertFalse(admission.isDone());
        requests.get(1).completeExceptionally(new IllegalStateException("unavailable"));
        assertThrows(CompletionException.class,admission::join); assertEquals(2,requests.size());
    }
}
