package dev.local.sunwindowdemo;

/** SunOS GestureLongSwipeUtils defaults: inward distance relative to display width. */
final class BackSwipe {
    static float threshold(int width,int height){
        if(width<=0||height<=0)throw new IllegalArgumentException("display");
        return width*(width>height?.28f:.5f);
    }
    static boolean armed(float inward,float threshold,boolean previous,float hysteresis){
        if(!Float.isFinite(inward)||!Float.isFinite(threshold)||threshold<=0||!Float.isFinite(hysteresis)||hysteresis<0)return false;
        return inward>=(previous?Math.max(1,threshold-hysteresis):threshold);
    }
}
