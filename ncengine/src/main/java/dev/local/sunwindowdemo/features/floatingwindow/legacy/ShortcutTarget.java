package dev.local.sunwindowdemo;

import android.content.Context;
import android.graphics.drawable.Drawable;
import java.util.List;

/** An application shortcut or a quick-function action in one ordered list. */
final class ShortcutTarget {
    final String pkg,label,section,action;
    final Drawable icon;
    final boolean quick;
    private ShortcutTarget(AppCatalog.App app){pkg=app.pkg;label=app.label;section=app.section;icon=app.icon;quick=false;action=null;}
    private ShortcutTarget(Context c,QuickActionCatalog.Action value){pkg="";label=value.label();section="#";icon=QuickActionCatalog.icon(c,value);quick=true;action=value.id();}
    String key(){return quick?QuickActionCatalog.key(action):pkg;}
    static ShortcutTarget app(Context c,AppCatalog.App app){return new ShortcutTarget(app);}
    static boolean validKey(String key){return Rules.validPackage(key)||QuickActionCatalog.validKey(key);}
    static ShortcutTarget fromKey(Context c,String key,List<AppCatalog.App> apps){
        QuickActionCatalog.Action action=QuickActionCatalog.fromKey(key);
        if(action!=null)return new ShortcutTarget(c,action);
        if(!validKey(key))return null;
        for(AppCatalog.App app:apps)if(app.pkg.equals(key))return new ShortcutTarget(app);
        return null;
    }
}
