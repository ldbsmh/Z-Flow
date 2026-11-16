package dev.local.sunwindowdemo;

/** A bounded quiet interval shared by the task chain of one launch. */
final class LaunchCoverFlow {
    static final long QUIET_MS = 1000;
    static final long TIMEOUT_MS = 5000;
    private long began;
    private long changed, readyAt = -1;
    private long hiddenAt = -1;
    private boolean finished;
    LaunchCoverFlow(long now) { began = changed = now; }
    boolean active() { return !finished; }
    boolean continuing(long now) { return !finished && !timedOut(now); }
    void changed(long now) { if (!finished) { changed = hiddenAt < 0 ? now : hiddenAt; readyAt = -1; } }
    void visibility(boolean visible, long now) {
        if (!visible) { if (hiddenAt < 0) hiddenAt = now; return; }
        if (hiddenAt < 0) return;
        long paused = Math.max(0, now - hiddenAt);
        began += paused; changed += paused;
        if (readyAt >= 0) readyAt += paused;
        hiddenAt = -1;
    }
    boolean ready(boolean drawn, long now) {
        if (finished) return drawn;
        if (!drawn) { readyAt = -1; return false; }
        if (readyAt < 0) readyAt = now;
        return now - began >= TIMEOUT_MS || now - Math.max(changed, readyAt) >= QUIET_MS;
    }
    boolean timedOut(long now) { return !finished && now - began >= TIMEOUT_MS; }
    void finish() { finished = true; }
}
