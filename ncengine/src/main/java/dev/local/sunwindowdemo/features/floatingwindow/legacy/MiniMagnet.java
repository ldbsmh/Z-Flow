package dev.local.sunwindowdemo;

/**
 * 拖动关闭的渐隐进度。
 *
 * 这里不再有「关闭按钮」这种要瞄准的目标：只要把迷你窗拖进屏幕底部那条关闭区，
 * 就按越过的比例一路淡下去，松手即关闭（Z-Flow 的滑动到底部渐变消失）。
 * 进度是连续量，所以用一条平滑过渡把外部算出的目标值追上，避免数值抖动。
 */
final class MiniMagnet {
    private static final float DURATION_NANOS=120_000_000f;
    private boolean latched;
    private float phase,target;

    /** @param desired 0=不在关闭区，1=完全进入底部关闭区。 */
    void follow(float desired){
        target=clamp(desired);
        latched=target>=.5f;
    }
    boolean latched(){return latched;}
    boolean animating(){return Math.abs(phase-target)>.001f;}
    void advance(long elapsedNanos){
        float step=Math.max(0,elapsedNanos)/DURATION_NANOS;
        phase=phase<target?Math.min(target,phase+step):Math.max(target,phase-step);
    }
    float progress(){return phase*phase*(3-2*phase);}
    void reset(){latched=false;phase=0;target=0;}
    static float scaleFactor(float progress){return 1-.65f*clamp(progress);}
    /** 滑到底部时几乎看不见了，避免用户误以为窗口还在。 */
    static float alphaFactor(float progress){return 1-.85f*clamp(progress);}
    private static float clamp(float p){return Float.isFinite(p)?Math.max(0,Math.min(1,p)):0;}
}
