package io.github.underconnor.passport.core;

import java.util.concurrent.*;
import java.util.function.*;

/** At most one heartbeat in flight; only outage/recovery transitions are reported. */
public final class ServerHeartbeat {
    private final Supplier<CompletableFuture<Void>> sender;
    private final Consumer<Boolean> availability;
    private CompletableFuture<Void> inFlight;
    private boolean failed;
    public ServerHeartbeat(Supplier<CompletableFuture<Void>> sender, Consumer<Boolean> availability) {
        this.sender = sender; this.availability = availability;
    }
    public synchronized CompletableFuture<Void> poll() {
        if (inFlight != null) return inFlight;
        CompletableFuture<Void> result = new CompletableFuture<>(); inFlight = result;
        try { sender.get().whenComplete((ignored, error) -> finish(result, error)); }
        catch (RuntimeException error) { finish(result, error); }
        return result;
    }
    private void finish(CompletableFuture<Void> result, Throwable error) {
        boolean changed;
        synchronized (this) {
            changed = failed != (error != null); failed = error != null; inFlight = null;
        }
        try { if (changed) availability.accept(error == null); } catch (RuntimeException ignored) { /* Diagnostics cannot affect transport. */ }
        if (error == null) result.complete(null); else result.completeExceptionally(error);
    }
}
