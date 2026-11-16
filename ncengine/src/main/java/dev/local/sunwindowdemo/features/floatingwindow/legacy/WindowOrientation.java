package dev.local.sunwindowdemo;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import org.json.JSONObject;

/** Small page-aware orientation memory. File access is confined to the engine handler, outside WM locks. */
final class WindowOrientation {
    private final AtomicFile file;
    private final LinkedHashMap<String, Boolean> values = new LinkedHashMap<>();
    WindowOrientation(File path) { file=new AtomicFile(path); }
    private static String key(int user, String pkg, String page) { return user+":"+pkg+":"+page; }
    synchronized Boolean get(int user, String pkg, String page) { return values.get(key(user,pkg,page)); }
    synchronized boolean remember(int user, String pkg, String page, boolean horizontal) {
        String key=key(user,pkg,page);
        if(Boolean.valueOf(horizontal).equals(values.get(key)))return false;
        values.remove(key);values.put(key,horizontal);
        while(values.size()>512)values.remove(values.keySet().iterator().next());
        return true;
    }
    void load() throws Exception {
        if(!file.getBaseFile().exists())return;
        byte[] bytes;
        try(InputStream in=file.openRead()){bytes=in.readNBytes(131073);}
        if(bytes.length>131072)throw new IOException("orientation memory too large");
        JSONObject object=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        synchronized(this){
            for(var keys=object.keys();keys.hasNext() && values.size()<512;){String key=keys.next();if(object.get(key) instanceof Boolean value)values.putIfAbsent(key,value);}
        }
    }
    void flush() throws Exception {
        JSONObject object=new JSONObject();
        synchronized(this){for(var entry:values.entrySet())object.put(entry.getKey(),entry.getValue());}
        FileOutputStream out=null;
        try{out=file.startWrite();out.write(object.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception failure){if(out!=null)file.failWrite(out);throw failure;}
    }
}
