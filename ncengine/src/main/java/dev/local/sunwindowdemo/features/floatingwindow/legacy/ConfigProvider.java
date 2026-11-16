package dev.local.sunwindowdemo;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.*;

/** UID-checked settings snapshot and a narrow shortcut-list write endpoint. */
public final class ConfigProvider extends ContentProvider {
    static final String AUTHORITY=Wire.PKG+".settings", CHANGED=Wire.PKG+".SETTINGS_CHANGED";
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        int uid=Binder.getCallingUid();
        try {
            if(uid!=getContext().getApplicationInfo().uid && uid!=getContext().getPackageManager().getApplicationInfo(BackBridge.PACKAGE,0).uid)
                throw new SecurityException("settings reader");
        } catch(android.content.pm.PackageManager.NameNotFoundException e){throw new SecurityException(e);}
        if("targets".equals(method)){
            ArrayList<String> keys=extras==null?null:extras.getStringArrayList("targets");
            new Config(getContext()).targets(keys,extras==null?null:extras.getStringArrayList("labels"));
            changed(getContext());Bundle saved=new Bundle();saved.putBoolean("saved",true);return saved;
        }
        if(!"read".equals(method))throw new IllegalArgumentException("method");
        new Config(getContext()); // Migrate before exporting, even if the manager has not opened yet.
        dev.local.sunwindowdemo.core.WindowBackendEligibility.refreshAsync(getContext());
        Bundle out=new Bundle();
        for(var e:getContext().getSharedPreferences("corner",0).getAll().entrySet()){
            Object v=e.getValue();String k=e.getKey();
            if(v instanceof Boolean)out.putBoolean(k,(Boolean)v);
            else if(v instanceof Integer)out.putInt(k,(Integer)v);
            else if(v instanceof String)out.putString(k,(String)v);
            else if(v instanceof Set<?>){ArrayList<String> list=new ArrayList<>();for(Object item:(Set<?>)v)if(item instanceof String)list.add((String)item);out.putStringArrayList(k,list);}
        }
        return out;
    }
    static void changed(Context c){c.sendBroadcast(new Intent(CHANGED).setPackage(BackBridge.PACKAGE),null,android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());}
    static void copy(Bundle source,SharedPreferences destination){
        var edit=destination.edit().clear();
        for(String key:source.keySet()){
            Object value=source.get(key);
            if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)edit.putInt(key,(Integer)value);
            else if(value instanceof String)edit.putString(key,(String)value);
            else if(value instanceof ArrayList<?>)edit.putStringSet(key,new HashSet<>(source.getStringArrayList(key)));
        }
        // Called on the host worker; ready is published only after the complete snapshot is stored.
        if(!edit.commit())throw new IllegalStateException("settings snapshot write failed");
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
