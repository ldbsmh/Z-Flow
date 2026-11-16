package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Config {
    static final int MAX_TARGETS=12;
    static final int DEFAULT_WINDOW_DIM=20;
    public static final String HOST_PREFERENCES=dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.hostPreferences();
    final SharedPreferences prefs;
    static volatile SharedPreferences hostPreferences;
    public Config(Context c) {
        prefs = hostPreferences!=null?hostPreferences:c.getSharedPreferences("corner", Context.MODE_PRIVATE);
        if(hostPreferences==null&&dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.packageName().equals(c.getPackageName()))synchronized(Config.class){
            if(!prefs.contains("enabled")||!prefs.contains("longBackWindow")){
                // Sample before either migration writes a key; a fresh migration is not legacy evidence.
                boolean existing=!prefs.getAll().isEmpty();
                if(!existing)try{
                    var info=c.getPackageManager().getPackageInfo(dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.packageName(),0);
                    existing=info.firstInstallTime>0&&info.lastUpdateTime>info.firstInstallTime;
                }catch(android.content.pm.PackageManager.NameNotFoundException|RuntimeException ignored){}
                var edit=prefs.edit();
                if(!prefs.contains("enabled"))edit.putBoolean("enabled",existing);
                // Existing settings/upgrade retain the formerly always-on gesture. Unknown/fresh stays off.
                if(!prefs.contains("longBackWindow"))edit.putBoolean("longBackWindow",existing);
                if(!edit.commit())throw new IllegalStateException("window settings migration failed");
            }
        }
    }
    boolean longBackWindow(){return prefs.getBoolean("longBackWindow",false);}
    boolean enabled(){return prefs.getBoolean("enabled",true);}
    int number(String key, int def, int min, int max) { return Rules.clamp(prefs.getInt(key, def), min, max); }
    static final int HEAT_MIN=36,HEAT_MAX=72;
    int heat() { return number("heat", HEAT_MIN, HEAT_MIN, HEAT_MAX); }
    boolean hideTopHint() { return prefs.getBoolean("hideTopHint", false); }
    int distance() { return number("distance", 42, 24, 100); }
    int selectionDistance() { return number("selectionDistance", 90, 50, 100); }
    int radius() { return number("radius", 180, 120, 260); }
    int icon() { return number("icon", 44, 32, 56); }
    int fanCount() { return number("fanCount",4,1,5); }
    boolean landscapeOff() { return prefs.getBoolean("landscape", true); }
    boolean keyboardOff() { return prefs.getBoolean("keyboard", true); }
    boolean hideRecents() { return prefs.getBoolean("hideRecents", false); }
    // Keep the 0.9.7 automatic-mini behavior for existing installations without this key.
    boolean autoMinimizeOnOpen() { return prefs.getBoolean("autoMinimizeOnOpen", true); }
    boolean endOnClose() { return prefs.getBoolean("endOnClose", false); }
    boolean keepEdgeAwake() { return prefs.getBoolean("keepEdgeAwake", false); }
    boolean doubleClose() { return prefs.getBoolean("doubleClose", false); }
    boolean notificationWindow() { return prefs.getBoolean("notificationWindow", true); }
    boolean landscapeShareWindow() { return prefs.getBoolean("landscapeShareWindow", true); }
    boolean showHeat() { return prefs.getBoolean("showHeat", false); }
    boolean menuBlur() { return prefs.getBoolean("menuBlur", false); }
    int windowDim() { return number("windowDim",DEFAULT_WINDOW_DIM,0,100); }
    List<String> apps() {
        Set<String> unique = new LinkedHashSet<>();
        for (String item : prefs.getString("apps", "").split(",")) {
            if (Rules.validPackage(item) && unique.size() < MAX_TARGETS) unique.add(item);
        }
        return new ArrayList<>(unique);
    }
    public List<String> targets() {
        if(!prefs.contains("targets"))return apps();
        Set<String> unique=new LinkedHashSet<>();
        try{org.json.JSONArray array=new org.json.JSONArray(prefs.getString("targets","[]"));
            for(int n=0;n<array.length()&&unique.size()<MAX_TARGETS;n++){
                String key=array.optString(n,"");if(ShortcutTarget.validKey(key))unique.add(key);
            }
        }catch(org.json.JSONException ignored){}
        List<String> result=new ArrayList<>(unique);
        String clean=new org.json.JSONArray(result).toString();
        if(hostPreferences==null&&!clean.equals(prefs.getString("targets","[]"))){
            org.json.JSONObject labels=new org.json.JSONObject();
            try{org.json.JSONObject old=new org.json.JSONObject(prefs.getString("targetLabels","{}"));
                for(String key:result)labels.put(key,old.optString(key,""));
            }catch(org.json.JSONException ignored){}
            List<String> packages=new ArrayList<>();for(String key:result)if(Rules.validPackage(key))packages.add(key);
            prefs.edit().putString("targets",clean).putString("apps",String.join(",",packages)).putString("targetLabels",labels.toString()).apply();
        }
        return result;
    }
    java.util.Map<String,String> targetLabels(){
        java.util.Map<String,String> result=new java.util.HashMap<>();
        try{org.json.JSONObject labels=new org.json.JSONObject(prefs.getString("targetLabels","{}"));
            for(String key:targets()){String label=labels.optString(key,"");if(!label.isBlank())result.put(key,label);}
        }catch(org.json.JSONException ignored){}
        return result;
    }
    void targets(List<String> keys){targets(keys,null);}
    static org.json.JSONObject validateTargets(List<String> keys,List<String> names) {
        if(keys==null||keys.size()>MAX_TARGETS)throw new IllegalArgumentException("shortcut limit");
        if(names==null||names.size()!=keys.size()||names.stream().anyMatch(n->n==null||n.length()>200))throw new IllegalArgumentException("shortcut names");
        Set<String> unique=new LinkedHashSet<>();org.json.JSONObject labels=new org.json.JSONObject();
        for(int n=0;n<keys.size();n++){
            String key=keys.get(n);
            if(!ShortcutTarget.validKey(key)||!unique.add(key))throw new IllegalArgumentException("shortcut key");
            try{labels.put(key,names.get(n));}catch(org.json.JSONException e){throw new IllegalArgumentException(e);}
        }
        if(new org.json.JSONArray(keys).toString().length()+labels.toString().length()>131072)throw new IllegalArgumentException("shortcut payload");
        return labels;
    }
    void targets(List<String> keys,List<String> names) {
        if(names==null&&keys!=null){
            java.util.Map<String,String> previous=targetLabels();names=new ArrayList<>();
            for(String key:keys)names.add(previous.getOrDefault(key,""));
        }
        org.json.JSONObject labels=validateTargets(keys,names);
        List<String> packages=new ArrayList<>();for(String key:keys)if(Rules.validPackage(key))packages.add(key);
        if(!prefs.edit().putString("targets",new org.json.JSONArray(keys).toString())
            .putString("apps",String.join(",",packages)).putString("targetLabels",labels.toString()).commit())throw new IllegalStateException("shortcut save failed");
    }
    void apps(List<String> apps) { targets(apps); }
    Set<String> blocked() { return new LinkedHashSet<>(prefs.getStringSet("blocked", Set.of())); }
}
