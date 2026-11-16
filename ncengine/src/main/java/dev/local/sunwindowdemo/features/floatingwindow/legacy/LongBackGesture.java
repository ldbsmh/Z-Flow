package dev.local.sunwindowdemo;

/** Long-Back recognition only. Native admission/cancellation and controller identity stay in the adapter. */
final class LongBackGesture {
    record Step(boolean retain, boolean tryAdmit, int armChange, int hint) {}
    private float startX,threshold,density;
    private boolean left,armed,tracking;
    private long down;
    private int revision,hintStage;

    static boolean mayStart(boolean live,int pointers,boolean finger,int display){
        return live&&pointers==1&&finger&&display==0;
    }
    void begin(float x,long downTime,int width,int height,float density,int revision){
        startX=x;left=x<width/2f;threshold=BackSwipe.threshold(width,height);
        this.density=density;down=downTime;this.revision=revision;
        armed=false;tracking=true;hintStage=0;
    }
    long down(){return down;}
    int revision(){return revision;}
    Step event(int action,float x,long downTime,int pointers,boolean live){
        if(!tracking)return new Step(false,false,0,-1);
        if(!live||down!=downTime||action==3||pointers!=1||action==5){
            tracking=false;return new Step(false,false,0,0);
        }
        float inward=left?x-startX:startX-x;
        boolean next=BackSwipe.armed(inward,threshold,armed,12*density);
        int change=next==armed?0:next?1:-1;armed=next;
        if(action==1){tracking=false;return new Step(false,armed,change,0);}
        return new Step(true,false,change,action==2?(armed?2:inward>threshold*.35f?1:0):-1);
    }
    /** Also used when the adapter clears hints on configuration change or native destruction. */
    boolean hintChanged(int stage){
        if(hintStage==stage)return false;
        hintStage=stage;return true;
    }
}
