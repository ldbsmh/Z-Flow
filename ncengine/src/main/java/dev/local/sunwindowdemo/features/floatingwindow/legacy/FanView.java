package dev.local.sunwindowdemo;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.List;

final class FanView extends View {
    private final List<ShortcutTarget> apps;
    private final boolean right;
    private final float icon, requested, bottom;
    private final int selectionDistance;
    private final Paint paint=new Paint(3);
    private float progress=0;
    private int selected=-1;
    private android.animation.ValueAnimator animator;
    private boolean firstFrame;
    private int themeColor(int attr,int fallback){
        android.util.TypedValue v=new android.util.TypedValue();
        try{if(getContext().getTheme().resolveAttribute(attr,v,true))return v.resourceId!=0?getContext().getColor(v.resourceId):v.data;}
        catch(RuntimeException ignored){}return fallback;
    }
    private final Runnable startEntry=()->{
        if(!isAttachedToWindow())return;
        animator=android.animation.ValueAnimator.ofFloat(0,1);
        animator.setDuration(160);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(a->{progress=(float)a.getAnimatedValue();invalidate();});
        animator.start();
    };
    FanView(Context c,List<ShortcutTarget> apps,boolean right,float icon,float radius,float bottom,int selectionDistance) {
        super(c);this.apps=new java.util.ArrayList<>(apps);this.right=right;this.icon=icon;requested=radius;this.bottom=bottom;
        this.selectionDistance=selectionDistance;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    private float ox(){return right?getWidth()-icon/2-12:icon/2+12;}
    private float bottomInset(){
        float reserved=bottom;
        if(getWidth()>getHeight()){
            android.view.WindowInsets insets=getRootWindowInsets();
            if(insets!=null)reserved=insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
        }
        return reserved;
    }
    private float oy(){return getHeight()-bottomInset()-icon/2-12;}
    private float radius(){return (float)Math.min(Rules.radius(requested,icon,apps.size()+1),Math.min(getWidth()-icon-28,getHeight()-bottomInset()-icon-28));}
    private float drawnRadius(){return radius()*(.88f+.12f*progress);}
    int pointer(float rawX,float rawY) {
        int[] loc=new int[2];getLocationOnScreen(loc);
        float x=rawX-loc[0],y=rawY-loc[1];float dx=right?ox()-x:x-ox(),dy=oy()-y;
        int next=Rules.fanSelection(dx,dy,drawnRadius(),icon,apps.size()+1,selectionDistance);
        selected=next;invalidate();return next;
    }
    String picked(){return selected<0?null:selected==0?"":apps.get(selected-1).key();}
    void updateApps(List<AppCatalog.App> catalog){
        for(int i=0;i<apps.size();i++){
            ShortcutTarget updated=ShortcutTarget.fromKey(getContext(),apps.get(i).key(),catalog);
            if(updated!=null)apps.set(i,updated);
        }
        invalidate();
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);float x=ox(),y=oy(),r=drawnRadius();
        
        paint.setColor(0xee20252c);paint.setAlpha((int)(235*progress));
        
        for(int i=0;i<=apps.size();i++) {
            double a=Math.toRadians(Rules.angle(i,apps.size()+1));
            float cx=x+(right?-1:1)*r*(float)Math.cos(a),cy=y-r*(float)Math.sin(a);
            float size=icon*(selected==i?1.12f:1);
            if(i==0){paint.setColor(themeColor(android.R.attr.colorAccent,0xff6750a4));paint.setAlpha((int)(255*progress));c.drawCircle(cx,cy,size/2+5,paint);}
            if(i>0&&apps.get(i-1).icon!=null) {
                ShortcutTarget target=apps.get(i-1);
                android.graphics.drawable.Drawable drawable=target.icon;
                int saved=c.save();Rect bounds=new Rect(drawable.getBounds());int alpha=drawable.getAlpha();
                try{
                    if(target.quick){
                        paint.setColor(Color.WHITE);paint.setAlpha((int)(255*progress));c.drawCircle(cx,cy,size/2,paint);
                        drawable.setTint(0xff1b1c1f);
                    }
                    c.translate(cx-size/2,cy-size/2);c.clipRect(0,0,size,size);
                    if(target.quick){float inset=size*.22f;drawable.setBounds(Math.round(inset),Math.round(inset),Math.round(size-inset),Math.round(size-inset));}
                    else drawable.setBounds(0,0,Math.round(size),Math.round(size));
                    drawable.setAlpha((int)(255*progress));drawable.draw(c);
                }finally{drawable.setBounds(bounds);drawable.setAlpha(alpha);if(target.quick)drawable.setTintList(null);c.restoreToCount(saved);}
            } else {
                int background=themeColor(android.R.attr.colorAccent,0xff6750a4);
                paint.setColor(Color.luminance(background)>.45?Color.BLACK:Color.WHITE);paint.setAlpha((int)(255*progress));
                for(int dot=-1;dot<=1;dot++)c.drawCircle(cx+dot*size*.2f,cy,size*.065f,paint);
            }
        }
        // Submit one fully transparent frame before beginning the only entry animation.
        if(!firstFrame){firstFrame=true;postOnAnimation(startEntry);}
    }
    @Override protected void onDetachedFromWindow(){
        removeCallbacks(startEntry);
        if(animator!=null){animator.cancel();animator.removeAllUpdateListeners();animator=null;}
        super.onDetachedFromWindow();
    }
}
