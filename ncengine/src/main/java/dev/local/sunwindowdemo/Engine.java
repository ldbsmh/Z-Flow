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
        Wire.openApp(context,packageName,taskId,true,mini,maxWindows);
        return true;
    }

    /** 通过通知的 PendingIntent 打开小窗，保留原始着陆 Activity。 */
    public static boolean openNotification(Context context,String packageName,PendingIntent launch,Intent fill){
        Wire.openNotification(context,packageName,launch,fill,null);
        return true;
    }

    public static String modulePackage(){return Wire.PKG;}
}
