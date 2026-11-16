package dev.local.sunwindowdemo;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.VolumeShaper;
import android.os.Handler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Per-UID mute using a separate native volume shaper, never the media stream volume.
 * Player volume, native duck/fade shapers and the user's volume setting stay intact.
 * All player IPC runs on the module worker, outside the WM lock.
 */
final class WindowAudio {
    // Dedicated positive shaper id; separate from Android's small system shaper ids.
    private static final int SHAPER_ID=0x5357;
    private final AudioManager audio;
    private final Handler handler;
    private final Consumer<String> log;
    private final Method uid,id,proxy,apply;
    private final VolumeShaper.Configuration mute,reference;
    private final VolumeShaper.Operation terminate;
    private final Set<Integer> muted=ConcurrentHashMap.newKeySet();
    private final Map<Integer,AudioPlaybackConfiguration> shaped=new HashMap<>();
    private boolean registered;
    private final AudioManager.AudioPlaybackCallback callback=new AudioManager.AudioPlaybackCallback(){
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs){sync(configs);}
    };
    // This helper executes only in hooked system_server, which may use these
    // archived API37 volume-shaper members; the app process never invokes them.
    @android.annotation.SuppressLint({"BlockedPrivateApi","SoonBlockedPrivateApi"})
    private WindowAudio(Context context,Handler worker,Consumer<String> logger)throws Exception{
        audio=context.getSystemService(AudioManager.class);handler=worker;log=logger;
        if(audio==null)throw new IllegalStateException("AudioManager missing");
        uid=AudioPlaybackConfiguration.class.getDeclaredMethod("getClientUid");
        id=AudioPlaybackConfiguration.class.getDeclaredMethod("getPlayerInterfaceId");
        proxy=AudioPlaybackConfiguration.class.getDeclaredMethod("getPlayerProxy");
        for(Method m:new Method[]{uid,id,proxy})m.setAccessible(true);
        apply=proxy.getReturnType().getDeclaredMethod("applyVolumeShaper",VolumeShaper.Configuration.class,VolumeShaper.Operation.class);
        apply.setAccessible(true);
        VolumeShaper.Configuration.Builder builder=new VolumeShaper.Configuration.Builder()
            .setDuration(1).setCurve(new float[]{0,1},new float[]{0,0});
        Method setId=builder.getClass().getDeclaredMethod("setId",int.class);setId.setAccessible(true);setId.invoke(builder,SHAPER_ID);
        mute=builder.build();
        Constructor<VolumeShaper.Configuration> constructor=VolumeShaper.Configuration.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);reference=constructor.newInstance(SHAPER_ID);
        Class<?> operationBuilder=Class.forName("android.media.VolumeShaper$Operation$Builder");
        Constructor<?> createOperation=operationBuilder.getDeclaredConstructor();createOperation.setAccessible(true);
        Object operation=createOperation.newInstance();
        Method end=operationBuilder.getDeclaredMethod("terminate"),build=operationBuilder.getDeclaredMethod("build");
        end.setAccessible(true);build.setAccessible(true);end.invoke(operation);
        terminate=(VolumeShaper.Operation)build.invoke(operation);
    }
    static WindowAudio create(Context context,Handler handler,Consumer<String> log){
        try{WindowAudio result=new WindowAudio(context,handler,log);log.accept("WINDOW_AUDIO_READY native per-UID volume shaper");return result;}
        catch(Exception|LinkageError e){log.accept("WINDOW_AUDIO_UNAVAILABLE "+FrameworkAccess.reason(e));return null;}
    }
    boolean isMuted(int clientUid){return muted.contains(clientUid);}
    boolean setMuted(int clientUid,boolean value){
        if(clientUid<android.os.Process.FIRST_APPLICATION_UID)return false;
        try{
            if(value){
                if(!registered){audio.registerAudioPlaybackCallback(callback,handler);registered=true;}
                muted.add(clientUid);
            }else muted.remove(clientUid);
            sync(audio.getActivePlaybackConfigurations());
            log.accept("WINDOW_AUDIO uid="+clientUid+" muted="+value);return true;
        }catch(RuntimeException e){
            muted.remove(clientUid);sync(List.of());
            log.accept("WINDOW_AUDIO_FAILED "+FrameworkAccess.reason(e));return false;
        }
    }
    private void shape(AudioPlaybackConfiguration config,boolean value)throws Exception{
        Object player=proxy.invoke(config);
        if(player!=null)apply.invoke(player,value?mute:reference,value?VolumeShaper.Operation.PLAY:terminate);
    }
    private void sync(List<AudioPlaybackConfiguration> configs){
        Map<Integer,AudioPlaybackConfiguration> live=new HashMap<>();
        try{
            for(AudioPlaybackConfiguration config:configs){
                int player=(Integer)id.invoke(config);live.put(player,config);
                if(!muted.contains((Integer)uid.invoke(config))||shaped.containsKey(player))continue;
                try{shape(config,true);shaped.put(player,config);}
                catch(Exception e){log.accept("WINDOW_AUDIO_PLAYER_FAILED player="+player+" "+FrameworkAccess.reason(e));}
            }
            for(Iterator<Map.Entry<Integer,AudioPlaybackConfiguration>> it=shaped.entrySet().iterator();it.hasNext();){
                Map.Entry<Integer,AudioPlaybackConfiguration> entry=it.next();
                if(live.containsKey(entry.getKey())&&muted.contains((Integer)uid.invoke(entry.getValue())))continue;
                try{shape(entry.getValue(),false);}
                catch(Exception e){log.accept("WINDOW_AUDIO_RELEASED_PLAYER player="+entry.getKey()+" "+FrameworkAccess.reason(e));}
                it.remove();
            }
        }catch(Exception e){log.accept("WINDOW_AUDIO_SYNC_FAILED "+FrameworkAccess.reason(e));}
        if(muted.isEmpty()&&registered){
            try{audio.unregisterAudioPlaybackCallback(callback);registered=false;}
            catch(RuntimeException e){log.accept("WINDOW_AUDIO_UNREGISTER_FAILED "+FrameworkAccess.reason(e));}
        }
    }
}
