package dev.local.sunwindowdemo;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.SystemClock;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Delays in-process maintenance requests for applications acquired by this window engine. */
final class TaskCleanupGuard {
    static final long GRACE_MS = 5 * 60_000L;
    record Key(String pkg, int user) {}
    static final class State {
        final int uid;
        long absentSince = -1;
        Request pending;
        State(int uid) { this.uid = uid; }
        void observe(boolean present, long now) {
            if (present) absentSince = -1;
            else if (absentSince < 0) absentSince = now;
        }
        boolean protectedAt(long now) { return absentSince < 0 || now < absentSince || now - absentSince < GRACE_MS; }
    }
    record Request(Object owner, Object[] args, Method method) {}
    private final Map<Key, State> states = new HashMap<>();
    private final ThreadLocal<Request> replay = new ThreadLocal<>();
    private final Consumer<String> log;
    private Context context;
    private Handler handler;
    private Function<Set<Key>, Set<Key>> visible;
    private boolean queued;
    private final Runnable check = this::check;

    TaskCleanupGuard(Consumer<String> log) { this.log = log; }

    boolean install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> service = Class.forName("com.android.server.am.ActivityManagerService", false, loader);
            Method stop = service.getDeclaredMethod("forceStopPackage", String.class, int.class);
            if (stop.getReturnType() != void.class) throw new NoSuchMethodException("forceStopPackage");
            stop.setAccessible(true);
            module.hook(stop).setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Key key = new Key((String) chain.getArg(0), (Integer) chain.getArg(1));
                    boolean automatic = automatic(Binder.getCallingUid(), Binder.getCallingPid(),
                        android.os.Process.myPid(), Thread.currentThread().getStackTrace());
                    Request incoming = new Request(chain.getThisObject(), new Object[]{key.pkg, key.user}, stop);
                    if (delay(key, incoming, automatic, SystemClock.elapsedRealtime(), replay.get())) return null;
                    return chain.proceed();
                });
            log.accept("CLEANUP_GUARD_READY graceMs=" + GRACE_MS);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            log.accept("CLEANUP_GUARD_UNAVAILABLE " + FrameworkAccess.reason(failure));
            return false;
        }
    }

    boolean delay(Key key, Request incoming, boolean automatic, long now, Request retry) {
        synchronized (states) {
            State state = states.get(key);
            if (retry != null) {
                if (state == null || state.pending != retry || state.protectedAt(now)) return true;
                states.remove(key);
                log.accept("CLEANUP_GRACE_EXPIRED package=" + key.pkg + " user=" + key.user);
                return false;
            }
            if (!automatic || key.user < 0) {
                states.keySet().removeIf(k -> Objects.equals(k.pkg, key.pkg) && (key.user < 0 || k.user == key.user));
            } else if (state != null && state.protectedAt(now)) {
                boolean first = state.pending == null;
                state.pending = incoming;
                scheduleLocked();
                if (first) log.accept("CLEANUP_DEFERRED package=" + key.pkg + " user=" + key.user);
                return true;
            } else states.remove(key);
            return false;
        }
    }

    /** External Binder commands and explicit window close actions retain native semantics. */
    static boolean automatic(int uid, int pid, int selfPid, StackTraceElement[] stack) {
        if (uid != 1000 || pid != selfPid) return false;
        for (StackTraceElement frame : stack) {
            if (frame.getMethodName().equals("onTransact")) return false;
            if (frame.getClassName().equals(WindowEngine.class.getName())
                    && frame.getMethodName().equals("endClosedApplications")) return false;
        }
        return true;
    }

    void connect(Context context, Handler handler, Function<Set<Key>, Set<Key>> visible) {
        synchronized (states) {
            this.context = context;
            this.handler = handler;
            this.visible = visible;
            scheduleLocked();
        }
    }

    void acquired(String pkg, int user, int uid) {
        if (uid < 10000 || !Wire.allowedPackage(pkg)) return;
        synchronized (states) {
            Key key = new Key(pkg, user);
            State state = states.get(key);
            if (state == null || state.uid != uid) { state = new State(uid); states.put(key, state); }
            state.observe(true, 0);
            scheduleLocked();
        }
    }

    void resumed(String pkg, int user, int uid) {
        synchronized (states) {
            State state = states.get(new Key(pkg, user));
            if (state == null || state.uid != uid) return;
            state.observe(true, 0);
            scheduleLocked();
        }
    }

    private void scheduleLocked() {
        if (handler != null && !queued && !states.isEmpty()) {
            queued = true;
            handler.postDelayed(check, 1000);
        }
    }

    private void check() {
        Set<Key> keys;
        synchronized (states) { queued = false; keys = Set.copyOf(states.keySet()); }
        try {
            // No state monitor is held while taking the window lock or querying package metadata.
            Set<Key> present = visible.apply(keys);
            List<Map.Entry<Key, State>> due = new ArrayList<>();
            synchronized (states) {
                long now = SystemClock.elapsedRealtime();
                for (Key key : keys) {
                    State state = states.get(key);
                    if (state == null) continue;
                    state.observe(present.contains(key), now);
                    if (!state.protectedAt(now)) {
                        if (state.pending == null) states.remove(key);
                        else due.add(Map.entry(key, state));
                    }
                }
            }
            for (var item : due) retry(item.getKey(), item.getValue());
        } catch (RuntimeException failure) {
            log.accept("CLEANUP_VISIBILITY_PENDING " + FrameworkAccess.reason(failure));
        } finally { synchronized (states) { scheduleLocked(); } }
    }

    private void retry(Key key, State state) {
        Request request;
        synchronized (states) { request = state.pending; }
        try {
            Object manager = context.getPackageManager();
            int uid = (Integer) manager.getClass().getMethod("getPackageUidAsUser", String.class, int.class)
                .invoke(manager, key.pkg, key.user);
            if (uid != state.uid) { synchronized (states) { states.remove(key, state); } return; }
            replay.set(request);
            // Re-enter the original request boundary so a newly acquired session cancels this retry.
            request.method.invoke(request.owner, request.args);
        } catch (Exception failure) {
            synchronized (states) { if (states.get(key) == state && state.pending == request) state.pending = null; }
            log.accept("CLEANUP_RETRY_FAILED package=" + key.pkg + " " + FrameworkAccess.reason(failure));
        } finally { replay.remove(); }
    }
}
