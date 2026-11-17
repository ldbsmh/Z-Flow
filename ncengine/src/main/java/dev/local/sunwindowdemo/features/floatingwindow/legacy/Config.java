package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Config {
    static final int DEFAULT_WINDOW_DIM=20;
    public static final String HOST_PREFERENCES=dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.hostPreferences();
    final SharedPreferences prefs;
    static volatile SharedPreferences hostPreferences;
    public Config(Context c) {
        prefs = hostPreferences!=null?hostPreferences:c.getSharedPreferences("corner", Context.MODE_PRIVATE);
        if(hostPreferences==null&&dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.packageName().equals(c.getPackageName()))synchronized(Config.class){
            if(!prefs.contains("enabled")){
                // Sample before the migration writes a key; a fresh migration is not legacy evidence.
                boolean existing=!prefs.getAll().isEmpty();
                if(!existing)try{
                    var info=c.getPackageManager().getPackageInfo(dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.packageName(),0);
                    existing=info.firstInstallTime>0&&info.lastUpdateTime>info.firstInstallTime;
                }catch(android.content.pm.PackageManager.NameNotFoundException|RuntimeException ignored){}
                if(!prefs.edit().putBoolean("enabled",existing).commit())throw new IllegalStateException("window settings migration failed");
            }
        }
    }
    boolean enabled(){return prefs.getBoolean("enabled",true);}
    int number(String key, int def, int min, int max) { return Rules.clamp(prefs.getInt(key, def), min, max); }
    boolean hideRecents() { return prefs.getBoolean("hideRecents", false); }
    // Keep the 0.9.7 automatic-mini behavior for existing installations without this key.
    boolean autoMinimizeOnOpen() { return prefs.getBoolean("autoMinimizeOnOpen", true); }
    boolean endOnClose() { return prefs.getBoolean("endOnClose", false); }
    boolean keepEdgeAwake() { return prefs.getBoolean("keepEdgeAwake", false); }
    boolean doubleClose() { return prefs.getBoolean("doubleClose", false); }
    boolean landscapeShareWindow() { return prefs.getBoolean("landscapeShareWindow", true); }
    int windowDim() { return number("windowDim",DEFAULT_WINDOW_DIM,0,100); }
    /** 贴边把手露出比例（百分比）。28% 只露一条边，100% 完整露出图标。 */
    int dockStyle() { return number("dockStyle",100,28,100); }
}
