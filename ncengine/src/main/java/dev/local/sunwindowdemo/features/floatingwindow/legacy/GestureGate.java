package dev.local.sunwindowdemo;

import java.util.function.BooleanSupplier;

/** Pure, per-stream policy. Never hand an orphan MOVE to a consumer that missed DOWN. */
public final class GestureGate {
    private volatile State state;
    private boolean owned;
    private int device;
    private long down;
    static final class State {
        final int[] rects;
        final BooleanSupplier alive;
        State(int[] rects,BooleanSupplier alive){this.rects=rects.clone();this.alive=alive;}
    }
    public boolean update(int width,int height,int[] rects,BooleanSupplier alive) {
        return update(width,height,rects,new int[0],alive);
    }
    public boolean update(int width,int height,int[] rects,int[] controls,BooleanSupplier alive) {
        state=null;
        if(width<=0||height<=0||rects==null||controls==null||(rects.length==0&&controls.length==0)||rects.length>8||rects.length%4!=0||alive==null)return false;
        for(int i=0;i<rects.length;i+=4){
            int l=rects[i],t=rects[i+1],r=rects[i+2],b=rects[i+3];
            if(l<0||t<0||r>width||b!=height||l>=r||t>=b||r-l>width/4||b-t>height/4||(l!=0&&r!=width))return false;
        }
        if(controls.length>8||controls.length%4!=0)return false;
        for(int i=0;i<controls.length;i+=4){
            int l=controls[i],t=controls[i+1],r=controls[i+2],b=controls[i+3];
            if(l<0||t<0||r>width||b>height||l>=r||t>=b||r-l>width/2||b-t>height/2)return false;
        }
        int[] all=java.util.Arrays.copyOf(rects,rects.length+controls.length);
        System.arraycopy(controls,0,all,rects.length,controls.length);
        state=new State(all,alive);
        return true;
    }
    public void disable(){state=null;}
    public boolean event(int action,float x,float y,boolean finger,int display,int deviceId,long downTime){
        if(action==0){
            if(!finger||display!=0||(owned&&deviceId!=device))return false;
            // A new DOWN always clears ownership of a lost or cancelled previous stream.
            owned=false;State snapshot=state;
            if(snapshot==null)return false;
            try{if(!snapshot.alive.getAsBoolean())return false;}catch(RuntimeException failure){return false;}
            for(int i=0;i<snapshot.rects.length;i+=4){
                if(x>=snapshot.rects[i]&&y>=snapshot.rects[i+1]&&x<snapshot.rects[i+2]&&y<snapshot.rects[i+3]){
                    owned=true;device=deviceId;down=downTime;return true;
                }
            }
            return false;
        }
        if(!owned||display!=0||deviceId!=device||downTime!=down)return false;
        if(action==1||action==3)owned=false;
        return true;
    }
}
