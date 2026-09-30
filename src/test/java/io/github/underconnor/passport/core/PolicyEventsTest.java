package io.github.underconnor.passport.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyEventsTest {
    final UUID first=UUID.fromString("11111111-1111-4111-8111-111111111111");
    final UUID second=UUID.fromString("22222222-2222-4222-8222-222222222222");
    Policy policy(UUID uuid,long version) {
        Instant now=Instant.now(); return new Policy(uuid,"revoked",Set.of(),"","",version,now,now.plusSeconds(60));
    }
    PolicyEvents batch(String cursor,boolean reset,PolicyEvents.Change... changes) { return new PolicyEvents(cursor,reset,List.of(changes)); }
    PolicyEvents.Change change(String id,UUID uuid,long version) { return new PolicyEvents.Change(id,uuid,version); }
    @Test void parserPreservesBigintCursorAndRejectsMalformedEvents() {
        PolicyEvents parsed=PolicyEvents.parse("""
            {"cursor":"9223372036854775807","reset":false,"events":[
             {"id":"9223372036854775807","minecraftUuid":"11111111-1111-4111-8111-111111111111","policyVersion":2147483647}]}
            """);
        assertEquals("9223372036854775807",parsed.cursor());
        assertEquals(Integer.MAX_VALUE,parsed.events().getFirst().policyVersion());
        for(String cursor:List.of("01","-1","9223372036854775808","1&other=true"))
            assertThrows(IllegalArgumentException.class,()->PolicyEvents.cursorNumber(cursor));
        assertThrows(IllegalArgumentException.class,()->PolicyEvents.parse("{\"cursor\":1,\"reset\":true,\"events\":[]}"));
        assertThrows(IllegalArgumentException.class,()->PolicyEvents.parse("{\"cursor\":\"1\",\"reset\":\"true\",\"events\":[]}"));
        assertThrows(IllegalArgumentException.class,()->PolicyEvents.parse("{\"cursor\":\"1\",\"reset\":false,\"events\":[{\"id\":\"2\",\"minecraftUuid\":\""+first+"\",\"policyVersion\":1}]}"));
        assertThrows(IllegalArgumentException.class,()->batch("8",false,change("8",first,1)).validateAfter("9"));
        assertThrows(IllegalArgumentException.class,()->batch("8",false).validateAfter(null));
    }
    @Test void initialResetRefreshesAllAndSharesOnePollUntilEveryPolicyIsProcessed() {
        AtomicInteger requests=new AtomicInteger(); Map<UUID,CompletableFuture<Policy>> pending=new HashMap<>();
        pending.put(first,new CompletableFuture<>()); pending.put(second,new CompletableFuture<>());
        PolicyEventPoller poller=new PolicyEventPoller(after -> {
            requests.incrementAndGet(); assertNull(after); return CompletableFuture.completedFuture(batch("10",true));
        },()->Set.of(first,second),(uuid,reset)->{ assertTrue(reset); return pending.get(uuid); });
        CompletableFuture<Void> initial=poller.poll(); assertSame(initial,poller.poll());
        assertEquals(1,requests.get()); assertTrue(poller.cursor().isEmpty());
        pending.get(first).complete(policy(first,3)); assertTrue(poller.cursor().isEmpty());
        pending.get(second).complete(policy(second,4)); initial.join(); assertEquals(Optional.of("10"),poller.cursor());
    }
    @Test void duplicateUuidEventsRequireHighestVersionAndOfflineEventsNeedNoFetch() {
        AtomicInteger feedCalls=new AtomicInteger(),refreshCalls=new AtomicInteger(); AtomicLong responseVersion=new AtomicLong(7);
        Set<UUID> online=new HashSet<>();
        PolicyEventPoller poller=new PolicyEventPoller(after -> CompletableFuture.completedFuture(feedCalls.getAndIncrement()==0
            ? batch("10",true) : batch("13",false,change("11",first,7),change("12",second,100),change("13",first,8))),
            ()->online,(uuid,reset)->{ refreshCalls.incrementAndGet(); assertEquals(first,uuid); assertFalse(reset); return CompletableFuture.completedFuture(policy(uuid,responseVersion.get())); });
        poller.poll().join(); online.add(first);
        assertThrows(CompletionException.class,()->poller.poll().join());
        assertEquals(Optional.of("10"),poller.cursor()); assertEquals(1,refreshCalls.get());
        responseVersion.set(8); poller.poll().join(); assertEquals(Optional.of("13"),poller.cursor()); assertEquals(2,refreshCalls.get());
    }
    @Test void failedFeedAndFailedPolicyNeverAcknowledgeOrAuthorizeFromHint() {
        AtomicInteger feedCalls=new AtomicInteger(); AtomicInteger phase=new AtomicInteger();
        PolicyCache cache=new PolicyCache(); cache.accept(policy(first,1));
        Set<UUID> online=new HashSet<>();
        PolicyEventPoller poller=new PolicyEventPoller(after -> {
            feedCalls.incrementAndGet();
            if(phase.get()==0) return CompletableFuture.completedFuture(batch("0",true));
            if(phase.get()==1) return CompletableFuture.failedFuture(new IllegalStateException("offline"));
            return CompletableFuture.completedFuture(batch("1",false,change("1",first,2)));
        },()->online,(uuid,reset)->CompletableFuture.failedFuture(new IllegalStateException("policy timeout")));
        poller.poll().join(); online.add(first);
        phase.set(1); assertThrows(CompletionException.class,()->poller.poll().join());
        phase.set(2); assertThrows(CompletionException.class,()->poller.poll().join());
        assertEquals(Optional.of("0"),poller.cursor()); assertEquals(1,cache.get(first).orElseThrow().version());
        assertFalse(cache.allows(first,"lobby",Instant.now())); assertEquals(3,feedCalls.get());
    }
    @Test void resetCanRewindCursorButNeverClearsPolicyWatermark() {
        AtomicInteger feedCalls=new AtomicInteger(); AtomicLong version=new AtomicLong(3);
        Set<UUID> online=new HashSet<>(); PolicyCache cache=new PolicyCache(); cache.accept(policy(first,9));
        PolicyEventPoller poller=new PolicyEventPoller(after -> CompletableFuture.completedFuture(batch(feedCalls.getAndIncrement()==0?"100":"2",true)),
            ()->online,(uuid,reset)->{
                assertTrue(reset); Policy next=policy(uuid,version.get());
                return cache.acceptOrCurrent(next)?CompletableFuture.completedFuture(next):CompletableFuture.failedFuture(new IllegalStateException("version regression"));
            });
        poller.poll().join(); online.add(first);
        assertThrows(CompletionException.class,()->poller.poll().join()); assertEquals(Optional.of("100"),poller.cursor());
        assertEquals(9,cache.get(first).orElseThrow().version());
        version.set(10); poller.poll().join(); assertEquals(Optional.of("2"),poller.cursor());
    }
    @Test void disconnectedPlayerDoesNotPreventAcknowledgment() {
        Set<UUID> online=ConcurrentHashMap.newKeySet(); online.add(first);
        CompletableFuture<Policy> pending=new CompletableFuture<>();
        PolicyEventPoller poller=new PolicyEventPoller(after->CompletableFuture.completedFuture(batch("0",true)),()->online,(uuid,reset)->pending);
        CompletableFuture<Void> result=poller.poll(); online.clear(); pending.complete(null);
        result.join(); assertEquals(Optional.of("0"),poller.cursor());
    }
}
