package dev.local.sunwindowdemo.features.diagnostics;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Pure Java policy and memory-only bounded session; no raw message/throwable input. */
final class DiagnosticsBuffer {
    static final long MAX_DURATION_MS = 10 * 60_000L;
    static final long RETENTION_MS = 24 * 60 * 60_000L;
    static final int MAX_BYTES = 256 * 1024;
    static final Set<String> SOURCES = Set.of("app", "system", "systemui", "launcher");
    static final Set<String> EVENTS = Set.of("process_loaded", "hook_installed", "hook_failed",
            "enabled", "disabled", "operation_ok", "operation_failed",
            "rotation_proposed", "rotation_shown", "rotation_cancelled", "rotation_clicked",
            "window_opened", "window_closed",
            "call_allowed", "call_blocked", "contacts_unavailable", "fan_shown");
    static final Set<String> EXCEPTIONS = Set.of("", "SecurityException", "IllegalStateException",
            "IllegalArgumentException", "NoSuchMethodException", "ClassNotFoundException",
            "UnsupportedOperationException", "RuntimeException", "OtherException");
    static final Set<String> SETTINGS = Set.of("floating_window", "barrage", "call_blocking",
            "edge_touch", "suggestions", "motion", "icon_layers", "shade");
    private final StringBuilder lines = new StringBuilder();
    private boolean recording;
    private int bytes;
    private long started, stopped;

    static boolean valid(String source, String event, String exception) {
        return SOURCES.contains(source) && EVENTS.contains(event) && EXCEPTIONS.contains(exception);
    }
    /** Validate the entire value-only batch before the provider publishes any event. */
    static String[][] batch(String caller, String value) {
        if (value == null || value.length() > 4096) throw new IllegalArgumentException("events");
        String[] lines = value.split("\n");
        if (lines.length == 0 || lines.length > 32) throw new IllegalArgumentException("batch size");
        String[][] batch = new String[lines.length][];
        for (int i = 0; i < lines.length; i++) {
            batch[i] = lines[i].split(":", -1);
            String[] parts = batch[i];
            if (parts.length != 3 || !caller.equals(parts[0]) || !valid(parts[0], parts[1], parts[2]))
                throw new IllegalArgumentException("event policy");
        }
        return batch;
    }
    synchronized void start(long now) {
        lines.setLength(0); bytes = 0; started = now; stopped = 0; recording = true;
    }
    synchronized void stop(long now) { if (recording) { recording = false; stopped = now; } }
    synchronized void clear() { lines.setLength(0); bytes = 0; recording = false; started = stopped = 0; }
    synchronized void expire(long now) {
        if (recording && now - started >= MAX_DURATION_MS) stop(started + MAX_DURATION_MS);
        if (!recording && stopped != 0 && now - stopped >= RETENTION_MS) clear();
    }
    synchronized boolean active(long now) { expire(now); return recording; }
    synchronized int size(long now) { expire(now); return bytes; }
    synchronized boolean add(long now, String source, String event, String exception) {
        if (!valid(source, event, exception) || !active(now)) return false;
        String line = (now - started) + "\t" + source + "\t" + event + "\t" + exception + "\n";
        int length = line.getBytes(StandardCharsets.UTF_8).length;
        if (bytes + length > MAX_BYTES) { stop(now); return false; }
        lines.append(line); bytes += length; return true;
    }
    synchronized String snapshot(long now) { expire(now); return lines.toString(); }
}
