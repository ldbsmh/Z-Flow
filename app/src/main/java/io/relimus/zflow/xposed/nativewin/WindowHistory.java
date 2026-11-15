package io.relimus.zflow.xposed.nativewin;

import java.util.*;

/** A closed floating task stays hidden until a real fullscreen frame has been restored. */
final class WindowHistory {
    record Entry(int taskId,int userId,String pkg,String component,String baseId){}
    private final Map<Integer,Entry> entries=new LinkedHashMap<>();
    synchronized void windowed(Entry e){entries.put(e.taskId(),e);}
    synchronized Entry get(int id){return entries.get(id);}
    synchronized boolean fullscreenReady(int id,boolean owned,boolean focused,boolean drawn,boolean correctBounds,boolean animating){
        if(owned||!focused||!drawn||!correctBounds||animating)return false;
        return entries.remove(id)!=null;
    }
    synchronized void removed(int id){entries.remove(id);}
    synchronized List<Entry> entries(){return new ArrayList<>(entries.values());}
    synchronized int[] ids(){return entries.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();}
}
