package io.github.underconnor.passport.core;

import java.util.concurrent.TimeUnit;

/** Prevent concurrent default-server connections and waiting-room reconnect loops. */
public final class RoutingAttempts {
    private static final int MAX_FAILURES = 6;
    private long sequence, active, retryAfter;
    private int failures;
    private boolean notified;
    public synchronized boolean inProgress() { return active != 0; }
    public synchronized long begin(long now) {
        if (active!=0 || now<retryAfter) return 0;
        active=++sequence;
        return active;
    }
    public synchronized boolean complete(long attempt, boolean success, long now) {
        if (active!=attempt || attempt==0) return false;
        active=0;
        if (success) { succeeded(); return false; }
        return failure(now);
    }
    public synchronized boolean failed(long now) {
        active=0; sequence++;
        return failure(now);
    }
    public synchronized void succeeded() {
        active=0; sequence++; failures=0; retryAfter=0; notified=false;
    }
    public synchronized void retryManually() {
        if (active==0) succeeded();
    }
    private boolean failure(long now) {
        failures=Math.min(failures+1,MAX_FAILURES);
        retryAfter=now+TimeUnit.SECONDS.toNanos(Math.min(30,1L<<failures));
        boolean show=!notified; notified=true;
        return show;
    }
}
