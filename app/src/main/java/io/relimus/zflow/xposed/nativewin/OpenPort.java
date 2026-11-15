package io.relimus.zflow.xposed.nativewin;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Z-Flow 应用进程 → system_server 的开窗请求通道（替代旧实现的 VirtualDisplay + AIDL）。
 *
 * 应用侧用半双工 transact 调用；binder 在这里跑时不在 WM 锁内，
 * 所以真正的窗口操作全部 post 到后端的 worker 线程执行。
 */
final class OpenPort extends Binder {
    static final String DESCRIPTOR = "io.relimus.zflow.nativewin.OPEN";
    private static final int OPEN = IBinder.FIRST_CALL_TRANSACTION;
    private static final int OPEN_PENDING = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int CLOSE = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int STATUS = IBinder.FIRST_CALL_TRANSACTION + 3;
    private static final int OPEN_PENDING_INTENT = IBinder.FIRST_CALL_TRANSACTION + 4;

    private final WindowEngine engine;
    private volatile int clientUid = -1;

    OpenPort(WindowEngine engine) { this.engine = engine; }

    void client(Context context) {
        try { clientUid = context.getPackageManager().getApplicationInfo(Wire.PKG, 0).uid; } catch (Exception ignored) { }
    }

    /** 首次使用时绑定当前应用 UID；system_server 自身（测试入口）也放行。 */
    private boolean allowed() {
        int uid = Binder.getCallingUid();
        return uid == clientUid || uid == android.os.Process.SYSTEM_UID;
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code >= OPEN && code <= OPEN_PENDING_INTENT) {
            if (!allowed()) return false;
            data.enforceInterface(DESCRIPTOR);
            switch (code) {
                case OPEN: case OPEN_PENDING: case OPEN_PENDING_INTENT: {
                    String pkg = data.readString();
                    String token = data.readString();
                    boolean mini = data.readInt() != 0;
                    Intent intent = data.readTypedObject(Intent.CREATOR);
                    data.readString(); // 旧实现的 pendingPackage 占位，后端不再使用
                    Bundle options = data.readTypedObject(Bundle.CREATOR);
                    int taskId = data.readInt();
                    boolean accepted = false;
                    try {
                        ActivityOptions launchOptions = options == null ? null : ActivityOptions.fromBundle(options);
                        accepted = engine.launchPackage(pkg, token, mini, intent, launchOptions, taskId);
                    } catch (Throwable error) {
                        android.util.Log.e("ZFlowWindow", "OPEN_PORT_FAILED " + FrameworkAccess.reason(error), error);
                    }
                    if (reply != null) { reply.writeNoException(); reply.writeInt(accepted ? 1 : 0); }
                    return true;
                }
                case CLOSE: {
                    String token = data.readString();
                    String pkg = data.readString();
                    engine.closeExternally(token, pkg);
                    if (reply != null) { reply.writeNoException(); }
                    return true;
                }
                case STATUS: {
                    if (reply != null) { reply.writeNoException(); reply.writeInt(engine.ready() ? 1 : 0); reply.writeInt(engine.openWindowCount()); }
                    return true;
                }
                default: return false;
            }
        }
        return super.onTransact(code, data, reply, flags);
    }

    // ---- 应用侧调用封装（同一份代码在 app 进程里编译） ----

    static boolean send(IBinder port, String pkg, String token, boolean mini, Intent intent, String pendingPackage,
                        Bundle options, int taskId) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(pkg);
            data.writeString(token);
            data.writeInt(mini ? 1 : 0);
            data.writeTypedObject(intent, 0);
            data.writeString(pendingPackage);
            data.writeTypedObject(options, 0);
            data.writeInt(taskId);
            if (!port.transact(OPEN, data, reply, 0)) return false;
            reply.readException();
            return reply.readInt() != 0;
        } catch (Exception e) {
            return false;
        } finally { data.recycle(); reply.recycle(); }
    }

    static boolean close(IBinder port, String token, String pkg) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(token);
            data.writeString(pkg);
            if (!port.transact(CLOSE, data, reply, 0)) return false;
            reply.readException();
            return true;
        } catch (Exception e) {
            return false;
        } finally { data.recycle(); reply.recycle(); }
    }

    static int[] status(IBinder port) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!port.transact(STATUS, data, reply, 0)) return new int[]{0, 0};
            reply.readException();
            return new int[]{reply.readInt(), reply.readInt()};
        } catch (Exception e) {
            return new int[]{0, 0};
        } finally { data.recycle(); reply.recycle(); }
    }

}
