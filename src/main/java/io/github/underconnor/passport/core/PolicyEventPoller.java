package io.github.underconnor.passport.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** At most one batch in flight; acknowledges its cursor only after all required refreshes succeed. */
public final class PolicyEventPoller {
    private final Function<String, CompletableFuture<PolicyEvents>> feed;
    private final Supplier<Set<UUID>> online;
    private final BiFunction<UUID, Boolean, CompletableFuture<Policy>> refresh;
    private volatile String cursor;
    private CompletableFuture<Void> inFlight;
    public PolicyEventPoller(Function<String, CompletableFuture<PolicyEvents>> feed, Supplier<Set<UUID>> online,
                             BiFunction<UUID, Boolean, CompletableFuture<Policy>> refresh) {
        this.feed = feed; this.online = online; this.refresh = refresh;
    }
    public Optional<String> cursor() { return Optional.ofNullable(cursor); }
    public synchronized CompletableFuture<Void> poll() {
        if (inFlight != null) return inFlight;
        CompletableFuture<Void> result = new CompletableFuture<>();
        inFlight = result;
        String before = cursor;
        try {
            feed.apply(before).thenCompose(batch -> {
                batch.validateAfter(before);
                Set<UUID> connected = Set.copyOf(online.get());
                Map<UUID, Long> required = new HashMap<>();
                if (batch.reset()) connected.forEach(uuid -> required.put(uuid, 0L));
                for (PolicyEvents.Change event : batch.events()) {
                    if (connected.contains(event.minecraftUuid())) required.merge(event.minecraftUuid(), event.policyVersion(), Math::max);
                }
                List<CompletableFuture<?>> checks = new ArrayList<>();
                for (var entry : required.entrySet()) {
                    checks.add(refresh.apply(entry.getKey(), batch.reset()).thenAccept(policy -> {
                        if (!online.get().contains(entry.getKey())) return;
                        if (policy == null || !policy.minecraftUuid().equals(entry.getKey()) || policy.version() < entry.getValue())
                            throw new CompletionException(new IllegalStateException("Policy has not reached event version"));
                    }));
                }
                return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).thenRun(() -> cursor = batch.cursor());
            }).whenComplete((ignored, error) -> finish(result, error));
        } catch (RuntimeException error) { finish(result, error); }
        return result;
    }
    private void finish(CompletableFuture<Void> result, Throwable error) {
        synchronized (this) { if (inFlight == result) inFlight = null; }
        if (error == null) result.complete(null); else result.completeExceptionally(error);
    }
}
