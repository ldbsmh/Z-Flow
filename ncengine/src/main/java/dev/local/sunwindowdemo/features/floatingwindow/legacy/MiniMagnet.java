package dev.local.sunwindowdemo;

/** Drag-only dismiss animation; progress always scales the original mini placement. */
final class MiniMagnet {
    private static final float DURATION_NANOS=120_000_000f;
    private boolean latched;
    private float phase;

    void target(int left,int top,int right,int bottom,float px,float py){
        latched=contains(left,top,right,bottom,px,py,latched?2:1.5f);
    }
    boolean latched(){return latched;}
    boolean animating(){return phase!=(latched?1:0);}
    void advance(long elapsedNanos){
        float step=Math.max(0,elapsedNanos)/DURATION_NANOS;
        phase=latched?Math.min(1,phase+step):Math.max(0,phase-step);
    }
    float progress(){return phase*phase*(3-2*phase);}
    void reset(){latched=false;phase=0;}
    static float scaleFactor(float progress){return 1-.65f*clamp(progress);}
    static float alphaFactor(float progress){return 1-.75f*clamp(progress);}
    private static float clamp(float p){return Float.isFinite(p)?Math.max(0,Math.min(1,p)):0;}
    static boolean contains(int left,int top,int right,int bottom,float px,float py,float radiusFactor){
        if(right<=left||bottom<=top||!Float.isFinite(px)||!Float.isFinite(py))return false;
        float radius=Math.min(right-left,bottom-top)*.5f*radiusFactor;
        float dx=px-(left+(right-left)/2f),dy=py-(top+(bottom-top)/2f);
        return dx*dx+dy*dy<=radius*radius;
    }
}
