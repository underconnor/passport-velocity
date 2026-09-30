package io.github.underconnor.passport.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Shares one request per UUID and limits policy fan-out so event batches cannot exhaust HTTP slots. */
public final class PolicyRefreshes {
    private final Function<UUID, CompletableFuture<Policy>> source;
    private final Map<UUID, CompletableFuture<Policy>> inFlight = new HashMap<>();
    private final Queue<UUID> pending = new ArrayDeque<>();
    private final int parallelism;
    private int active;
    private boolean draining;
    public PolicyRefreshes(Function<UUID, CompletableFuture<Policy>> source) { this(source, 8); }
    public PolicyRefreshes(Function<UUID, CompletableFuture<Policy>> source, int parallelism) {
        if (parallelism < 1 || parallelism > 16) throw new IllegalArgumentException("Policy concurrency limit");
        this.source = source; this.parallelism = parallelism;
    }
    public synchronized CompletableFuture<Policy> fetch(UUID uuid) {
        CompletableFuture<Policy> existing = inFlight.get(uuid);
        if (existing != null) return existing;
        CompletableFuture<Policy> result = new CompletableFuture<>();
        inFlight.put(uuid, result); pending.add(uuid); drain();
        return result;
    }
    private void drain() {
        if (draining) return;
        draining = true;
        try {
            while (active < parallelism && !pending.isEmpty()) {
                UUID uuid = pending.remove(); CompletableFuture<Policy> result = inFlight.get(uuid); active++;
                try { source.apply(uuid).whenComplete((policy, error) -> finish(uuid, result, policy, error)); }
                catch (RuntimeException error) { finish(uuid, result, null, error); }
            }
        } finally { draining = false; }
    }
    private void finish(UUID uuid, CompletableFuture<Policy> result, Policy policy, Throwable error) {
        synchronized (this) { active--; inFlight.remove(uuid, result); drain(); }
        if (error == null) result.complete(policy); else result.completeExceptionally(error);
    }
    /** Resets need a request started after the snapshot, never an older in-flight response. */
    public synchronized CompletableFuture<Policy> fresh(UUID uuid) {
        CompletableFuture<Policy> existing = inFlight.get(uuid);
        return existing == null ? fetch(uuid) : existing.handle((policy, error) -> null).thenCompose(ignored -> fetch(uuid));
    }
}
