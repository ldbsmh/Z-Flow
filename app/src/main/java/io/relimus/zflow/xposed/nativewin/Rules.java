package io.relimus.zflow.xposed.nativewin;

/** Pure geometry and validation, shared by UI and JVM checks. */
public final class Rules {
    public static boolean blocked(boolean locked, boolean landscape, boolean landscapeOff,
            boolean keyboard, boolean keyboardOff, boolean excluded) {
        return locked || (landscape && landscapeOff) || (keyboard && keyboardOff) || excluded;
    }
    public static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    public static double angle(int index, int count) {
        if (count < 1 || count > 6 || index < 0 || index >= count) throw new IllegalArgumentException();
        return (index + .5) * 90.0 / count;
    }
    public static double radius(double requested, double icon, int count) {
        if (count < 1 || count > 6) throw new IllegalArgumentException();
        return count == 1 ? requested : Math.max(requested, (icon + 8) / (2 * Math.sin(Math.PI / (4 * count))));
    }
    public static boolean triggered(float dx, float dy, boolean right, float distance) {
        float inward = right ? -dx : dx;
        return inward >= distance * .35f && -dy >= distance * .35f && Math.hypot(dx, dy) >= distance;
    }
    /** Distances use the currently drawn arc, including menu entry animation and screen fitting. */
    public static int fanSelection(float dx,float dy,float radius,float icon,int count,int percent){
        if(count<1||count>6||percent<50||percent>100)throw new IllegalArgumentException("selection settings");
        if(!Float.isFinite(dx)||!Float.isFinite(dy)||!Float.isFinite(radius)||!Float.isFinite(icon)
            ||dx<0||dy<0||radius<=0||icon<=0)return -1;
        double distance=Math.hypot(dx,dy);
        if(distance<radius*(percent/100.0)||distance>radius+icon)return -1;
        double angle=Math.toDegrees(Math.atan2(dy,dx));
        return Math.min(count-1,(int)(angle/90*count));
    }
    public static boolean validPackage(String pkg) {
        return pkg != null && pkg.length() <= 240 && pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }
}
