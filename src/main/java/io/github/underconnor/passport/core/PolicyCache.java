package io.github.underconnor.passport.core;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Retains UUID watermarks across logout; process restart begins with no usable permission. */
public final class PolicyCache {
    private final Map<UUID, Policy> policies = new ConcurrentHashMap<>();
    public synchronized boolean accept(Policy next) {
        Policy old = policies.get(next.minecraftUuid());
        if (old != null && (next.version() < old.version()
            || (next.version() == old.version() && !next.issuedAt().isAfter(old.issuedAt())))) return false;
        policies.put(next.minecraftUuid(), next);
        return true;
    }
    /** A freshly fetched exact duplicate confirms the same lease without extending it. */
    public synchronized boolean acceptOrCurrent(Policy next) {
        return accept(next) || next.equals(policies.get(next.minecraftUuid()));
    }
    public Optional<Policy> get(UUID uuid) { return Optional.ofNullable(policies.get(uuid)); }
    public boolean allows(UUID uuid, String server, Instant now) {
        return get(uuid).map(p -> p.allows(server, now)).orElse(false);
    }
}
