package dev.local.sunwindowdemo.core;

import dev.local.sunwindowdemo.BuildConfig;
import java.io.File;

/** Product-owned names only; this does not select or arbitrate an active backend. */
public final class ModuleIdentity {
    public static final String FULL_PACKAGE="dev.nativecomplements.module";
    public static final String WINDOW_PACKAGE="dev.nativecomplements.window";
    public static final ModuleIdentity CURRENT=forPackage(BuildConfig.MODULE_PACKAGE);
    private final String packageName;
    private final boolean window;

    private ModuleIdentity(String packageName,boolean window){this.packageName=packageName;this.window=window;}
    public static ModuleIdentity forPackage(String packageName){
        if(FULL_PACKAGE.equals(packageName))return new ModuleIdentity(packageName,false);
        if(WINDOW_PACKAGE.equals(packageName))return new ModuleIdentity(packageName,true);
        // 宿主集成（Z-Flow）：任意合法包名都按主产品身份处理，不再限定上游两个包名。
        if(packageName!=null&&packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
            return new ModuleIdentity(packageName,false);
        throw new IllegalArgumentException("Unsupported module identity: "+packageName);
    }
    public String packageName(){return packageName;}
    public boolean standalone(){return window;}
    public String action(String suffix){return packageName+"."+suffix;}
    public String authority(String feature){return packageName+"."+feature;}
    public String controlPermission(){return action("CONTROL");}
    public String hostStorageName(){return window?"nativecomplements-window":"nativecomplements";}
    public String hostPreferences(){return window?"nativecomplements_window_corner":"nativecomplements_corner";}
    /** Preserve the installed main product's recovery filenames verbatim. No I/O is performed. */
    public File systemFile(String legacyName){
        if(legacyName==null||!legacyName.matches("[A-Za-z0-9_.-]+")||legacyName.equals(".")||legacyName.equals(".."))
            throw new IllegalArgumentException("system filename");
        return new File(window?"/data/system/nativecomplements-window":"/data/system",legacyName);
    }
}
