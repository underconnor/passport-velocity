package io.github.underconnor.passport.core;

import java.time.Instant;
import java.util.*;

/** Unexpired requests are never evicted to admit a new request. */
public final class ProxyCommandReplayGuard {
    private final Map<UUID,Long> seen=new HashMap<>();
    private final int capacity;

    public ProxyCommandReplayGuard(int capacity) {
        if(capacity<1) throw new IllegalArgumentException("capacity");
        this.capacity=capacity;
    }

    public synchronized boolean claim(ProxyCommandMessage request,Instant now) {
        long time=now.toEpochMilli();
        seen.values().removeIf(expiry -> expiry<=time);
        if(request==null || !request.valid(now) || seen.containsKey(request.requestId()) || seen.size()>=capacity) return false;
        seen.put(request.requestId(),request.expiresAt());
        return true;
    }

    public synchronized void clear() { seen.clear(); }
}
