package dev.local.sunwindowdemo;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import dev.local.sunwindowdemo.core.WindowBackendEligibility;

/**
 * 供宿主（Z-Flow）调用的公开门面。
 *
 * 上游的小窗命令通道（{@link Wire}）与配置（{@link Config}）都是包内可见，
 * Z-Flow 的入口在 io.relimus.zflow 包下无法直接调用，因此这里只暴露
 * 「开关 + 打开」两类最小能力，其余窗口控制仍由 SystemUI 控件层负责。
 */
public final class Engine {
    private Engine() {}

    /** 小窗总开关（对应上游 corner 偏好里的 enabled）。 */
    public static boolean enabled(Context context){return new Config(context).enabled();}

    /** 写入总开关，并把状态投影成 PM 组件状态供下次 system_server 启动读取。 */
    public static void setEnabled(Context context,boolean enabled){
        new Config(context); // 先完成旧偏好迁移
        WindowBackendEligibility.saveEnabled(context,enabled);
    }

    /** 以真实 freeform 任务方式打开指定包名的小窗（前台任务，默认窗形态）。 */
    public static boolean openApp(Context context,String packageName){
        return openApp(context,packageName,-1,false);
    }

    /**
     * 以真实 freeform 任务方式打开小窗。
     *
     * @param taskId 目标任务；-1 表示当前前台任务。多任务卡片上滑时传入被上滑的卡片任务，
     *               引擎直接接管那个任务，不会另外从图标重新启动一份。
     * @param mini   true 表示打开后立即进入迷你窗形态（Z-Flow 的「小窗」语义）。
     */
    public static boolean openApp(Context context,String packageName,int taskId,boolean mini){
        return openApp(context,packageName,taskId,mini,0);
    }

    /**
     * @param maxWindows 同时存在的小窗上限（1~5）；0 表示引擎默认值（2）。
     *                   Z-Flow 的「最多同时小窗数量」就是通过它下发的。
     */
    public static boolean openApp(Context context,String packageName,int taskId,boolean mini,int maxWindows){
        return openApp(context,packageName,taskId,mini,maxWindows,null);
    }

    /**
     * @param windowSettings Z-Flow 的外观设置（尺寸、迷你比例、变暗、贴边样式、横屏），可为 null。
     */
    public static boolean openApp(Context context,String packageName,int taskId,boolean mini,int maxWindows,android.os.Bundle windowSettings){
        Wire.openApp(context,packageName,taskId,true,mini,maxWindows,windowSettings);
        return true;
    }

    /** 设置页改了小窗外观：让引擎刷新能立刻生效的部分（变暗）。 */
    public static void reloadSettings(Context context,android.os.Bundle windowSettings){
        Wire.reloadSettings(context,windowSettings);
    }

    /** 通过通知的 PendingIntent 打开小窗，保留原始着陆 Activity。 */
    public static boolean openNotification(Context context,String packageName,PendingIntent launch,Intent fill){
        return openNotification(context,packageName,launch,fill,null);
    }

    /** @param windowSettings Z-Flow 的外观设置，可为 null（引擎按默认值开窗）。 */
    public static boolean openNotification(Context context,String packageName,PendingIntent launch,Intent fill,android.os.Bundle windowSettings){
        Wire.openNotification(context,packageName,launch,fill,null,windowSettings);
        return true;
    }

    /**
     * 宿主改写了引擎原生偏好（本应用 `corner` 偏好）后，通知 SystemUI 宿主重新拉取快照。
     *
     * 接收方是 SystemUI 里注册的运行时接收器（上游 `BackBridge.PACKAGE`），显式广播
     * 必须定向到 com.android.systemui 才投得进去；接收方再按发送方 UID 校验必须是本应用。
     * 带 share identity 是为了让接收方读到发送方身份。
     */
    public static void notifySettingsChanged(Context context){
        context.sendBroadcast(new Intent(ConfigProvider.CHANGED).setPackage("com.android.systemui"),
            null,android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    public static String modulePackage(){return Wire.PKG;}
}
