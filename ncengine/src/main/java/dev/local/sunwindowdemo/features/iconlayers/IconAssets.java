package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;
import android.graphics.drawable.Drawable;

/**
 * 小窗用的图标适配入口（Z-Flow 移植版的最小实现）。
 *
 * 上游这里会读取“图标适配”功能的莫奈/彩色图标快照，并订阅刷新广播；
 * Z-Flow 没有该功能，因此统一返回“未适配”，调用方会自然回退到系统原图标，
 * 小窗与扇区的其余行为不受影响。
 */
public final class IconAssets {
    private IconAssets() {}
    public static boolean ready(){return false;}
    public static Drawable colored(Context context,String pkg){return null;}
    public static void started(String pkg){}
    public static void connect(Context context){}
    public static void onReload(Runnable listener){}
    public static void removeOnReload(Runnable listener){}
}
