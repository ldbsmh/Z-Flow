package dev.local.sunwindowdemo.features.diagnostics;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import dev.local.sunwindowdemo.BuildConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** App process only. Memory retention is intentional: process death deletes the session. */
public final class DiagnosticsStore {
    private static final DiagnosticsBuffer BUFFER = new DiagnosticsBuffer();
    private static volatile Context application;
    static void connect(Context context){application=context.getApplicationContext();}
    private static void changed(){Context c=application;if(c!=null)c.getContentResolver().notifyChange(Diagnostics.URI,null);}
    private static final TreeSet<String> SEEN = new TreeSet<>();
    private static final Map<String, Boolean> SETTINGS = new LinkedHashMap<>();
    private static final ScheduledExecutorService EXPIRY = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "diagnostics-expiry"); t.setDaemon(true); return t;
    });
    private static volatile boolean previousActive;
    static { EXPIRY.scheduleWithFixedDelay(() -> {
        boolean active=BUFFER.active(now());
        if(previousActive&&!active)changed();
        previousActive=active;
    }, 1, 1, TimeUnit.SECONDS); }
    private DiagnosticsStore() {}
    private static long now() { return SystemClock.elapsedRealtime(); }
    static synchronized void seen(String source) { SEEN.add(source); }
    static void add(String source, String event, String exception) { BUFFER.add(now(), source, event, exception); }
    public static synchronized Bundle state() {
        Bundle out = new Bundle();
        out.putBoolean("recording", BUFFER.active(now()));
        out.putInt("bytes", BUFFER.size(now()));
        out.putString("seen", String.join(", ", SEEN));
        return out;
    }
    private static long startedWall,startedElapsed;
    public static void start() { synchronized(DiagnosticsStore.class){startedWall=System.currentTimeMillis();startedElapsed=now();SEEN.clear();BUFFER.start(startedElapsed);previousActive=true;}changed(); }
    public static void stop() { BUFFER.stop(now());changed(); }
    public static void clear() { synchronized(DiagnosticsStore.class){BUFFER.clear();SEEN.clear();startedWall=startedElapsed=0;}changed(); }
    /** Parent explicitly supplies individual booleans, never a preferences object or arbitrary map. */
    public static synchronized void setting(String name, boolean enabled) {
        if (name == null || !DiagnosticsBuffer.SETTINGS.contains(name)) throw new IllegalArgumentException("setting");
        SETTINGS.put(name, enabled);
    }
    private static String safeSystemValue(String value) {
        String safe = value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9 ._+-]", "_");
        return safe.substring(0, Math.min(safe.length(), 80));
    }
    /** Run on IO dispatcher; only a user-selected SAF URI, never network/upload or filesystem path. */
    public static void export(Context context, Uri destination) throws IOException {
        if (!"content".equals(destination.getScheme())) throw new IOException("SAF document required");
        final String events;
        synchronized (BUFFER) {
            if (BUFFER.active(now())) throw new IOException("Stop recording before export");
            events = BUFFER.snapshot(now());
            if (events.isEmpty()) throw new IOException("No retained events");
        }
        StringBuilder info = new StringBuilder("Module events only; NOT logcat or LSPosed/system logs.\n")
                .append("No message bodies, phone numbers, clipboard, SSID, raw exceptions or preferences.\n")
                .append("event_time=provider_receive_elapsed_since_start; batching may delay events by about 0.5 seconds\n")
                .append("settings_snapshot=diagnostics_page_entry\n")
                .append("version=").append(BuildConfig.MODULE_VERSION_NAME).append('\n')
                .append("versionCode=").append(BuildConfig.MODULE_VERSION_CODE).append('\n')
                .append("sdk=").append(Build.VERSION.SDK_INT).append('\n')
                .append("model=").append(safeSystemValue(Build.MODEL)).append('\n')
                .append("manufacturer=").append(safeSystemValue(Build.MANUFACTURER)).append('\n');
        synchronized (DiagnosticsStore.class) {
            info.append("recording_started_wall_ms=").append(startedWall).append('\n');
            info.append("recording_started_elapsed_ms=").append(startedElapsed).append('\n');
            info.append("observed_module_processes=").append(String.join(",", SEEN)).append('\n');
            info.append("Unlisted processes/settings are unknown, not disabled.\n");
            SETTINGS.forEach((key, value) -> info.append(key).append('=').append(value).append('\n'));
        }
        OutputStream output = context.getContentResolver().openOutputStream(destination, "wt");
        if (output == null) throw new IOException("Cannot open document");
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            entry(zip, "scope-and-state.txt", info.toString());
            entry(zip, "module-events.tsv", "elapsed_ms\tsource\tevent\texception\n" + events);
        }
    }
    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
