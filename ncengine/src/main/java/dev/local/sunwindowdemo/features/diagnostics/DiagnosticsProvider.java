package dev.local.sunwindowdemo.features.diagnostics;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;

/** Exported deliberately: authorization is UID-based, never based on caller-supplied package names. */
public final class DiagnosticsProvider extends ContentProvider {
    @Override public boolean onCreate() { DiagnosticsStore.connect(getContext());return true; }

    private boolean packageUid(int uid, String name) {
        try { return getContext().getPackageManager().getApplicationInfo(name, 0).uid == uid; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { return false; }
    }
    private String source(int uid) {
        if (uid == getContext().getApplicationInfo().uid) return "app";
        // Exact UIDs also reject secondary-user callers and isolated processes.
        if (UserHandle.getUserHandleForUid(uid).equals(Process.myUserHandle())) {
            if (packageUid(uid, "android")) return "system";
            if (packageUid(uid, "com.android.systemui")) return "systemui";
            if (packageUid(uid, "com.google.android.apps.nexuslauncher")) return "launcher";
        }
        throw new SecurityException("diagnostics caller");
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        String caller = source(Binder.getCallingUid());
        // No arbitrary Bundle, Parcelable, caller-formatted values or generic command channel.
        if (extras != null) throw new IllegalArgumentException("extras unsupported");
        if ("state".equals(method) && arg == null) {
            DiagnosticsStore.seen(caller);
            return DiagnosticsStore.state();
        }
        if ("events".equals(method)) {
            String[][] batch = DiagnosticsBuffer.batch(caller, arg);
            for (String[] parts : batch) DiagnosticsStore.add(parts[0], parts[1], parts[2]);
            return Bundle.EMPTY;
        }
        // Recording/export management is exclusively in-process app API, not available to hosts.
        throw new IllegalArgumentException("method");
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
