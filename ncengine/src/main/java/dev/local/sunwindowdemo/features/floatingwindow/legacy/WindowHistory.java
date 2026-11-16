package dev.local.sunwindowdemo;

import java.util.*;

/** A closed floating task stays hidden until a real fullscreen frame has been restored. */
final class WindowHistory {
    record Entry(int taskId,int userId,String pkg,String component,String baseId){}
    private final Map<Integer,Entry> entries=new LinkedHashMap<>();
    private final Map<Integer,Long> fullscreenSeen=new HashMap<>();
    /** Observe stability without removing history: the adapter must snapshot before committing release. */
    synchronized boolean observeFullscreen(int id,boolean ready,long now){
        if(!ready){fullscreenSeen.remove(id);return false;}
        Long seen=fullscreenSeen.putIfAbsent(id,now);
        return seen!=null&&now-seen>=120;
    }
    synchronized void clearFullscreen(int id){fullscreenSeen.remove(id);}
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
