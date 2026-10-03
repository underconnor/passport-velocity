package io.github.underconnor.passport.core;

/** Invalidates asynchronous queue admissions when a user cancels or selects another destination. */
public final class QueueIntent {
    private long generation;
    private boolean automaticPaused;
    public synchronized long change() { return ++generation; }
    public synchronized long generation() { return generation; }
    public synchronized boolean current(long expected) { return generation==expected; }
    public synchronized boolean automaticPaused() { return automaticPaused; }
    public synchronized void cancel() { generation++; automaticPaused=true; }
    public synchronized void resume() { automaticPaused=false; }
    public synchronized void failed() { automaticPaused=true; }
}
