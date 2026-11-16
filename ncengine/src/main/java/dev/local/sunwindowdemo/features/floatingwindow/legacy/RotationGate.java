package dev.local.sunwindowdemo;

/** Presentation timing only: this gate never changes a task, focus or Activity lifecycle. */
final class RotationGate {
    private boolean active,fading;
    private int generation;
    private long deadline,stableSince,fadeSince;
    private Object signature;
    boolean active(){return active;}
    boolean fading(){return fading;}
    int generation(){return generation;}
    void begin(long now){
        generation++;active=true;fading=false;deadline=now+3000;stableSince=-1;signature=null;
    }
    void defer(long now){deadline=now+3000;stableSince=-1;signature=null;}
    boolean expired(long now){return active&&now>=deadline;}
    boolean sample(long now,boolean ready,Object value){
        if(!active||fading)return false;
        if(!ready){stableSince=-1;signature=null;return false;}
        if(!java.util.Objects.equals(signature,value)){
            signature=value;stableSince=now;return false;
        }
        return stableSince>=0&&now-stableSince>=64;
    }
    int startFade(long now){fading=true;fadeSince=now;return generation;}
    float alpha(long now){return !active?1:!fading?0:Math.max(0,Math.min(1,(now-fadeSince)/150f));}
    void clear(){generation++;active=false;fading=false;signature=null;}
}
