package io.relimus.zflow.xposed.nativewin;
package io.relimus.zflow.xposed.nativewin;

import android.os.IBinder;

/** Shared admission and wake rules. An app hint is not the identity of a launched task. */
final class WindowNavigation {
    static String wakeForm(String frame, boolean hidden, boolean step) {
        return step && hidden && "mini".equals(frame) ? "restore-mini" : "normal";
    }

    /** One explicit native PendingIntent send; matched by its binder, never by recent-task order. */
    static final class Launch {
        final long generation;
        final String hint, expectedPackage, foregroundPackage;
        final int userId;
        final IBinder sender;
        final long sentAt;
        volatile Object task;
        volatile int taskId = -1;
        volatile String targetPackage;
        volatile boolean dispatched;
        WindowEngine.NativeForeground foreground;
        volatile boolean nativeTarget;

        Launch(long generation, String hint, String expectedPackage, int userId, IBinder sender, long sentAt,
               String foregroundPackage) {
            this.generation=generation;this.hint=hint;this.expectedPackage=expectedPackage;
            this.userId=userId;this.sender=sender;this.sentAt=sentAt;this.foregroundPackage=foregroundPackage;
        }

        boolean accepts(long current, IBinder originatingSender, Object sourceTask) {
            return generation==current && (sender!=null && sender.equals(originatingSender)
                || task!=null && task==sourceTask);
        }

        boolean targetAllowed(String pkg, int user) {
            return user==userId && Wire.allowedPackage(pkg)
                && (expectedPackage==null || expectedPackage.equals(pkg));
        }

        /** The send landed in the exact fullscreen task that was in front (or covered) before it. */
        boolean keepNativeForeground() {
            return nativeTarget;
        }
    }
}
