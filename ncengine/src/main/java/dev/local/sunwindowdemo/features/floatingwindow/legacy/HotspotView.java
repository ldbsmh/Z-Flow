package dev.local.sunwindowdemo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Paints within the actual input window: the indicator cannot disagree with its hitbox. */
final class HotspotView extends View {
    private final boolean right,visible;
    private int state;
    private final Paint paint=new Paint(3);
    private final Runnable reset=()->{state=0;invalidate();};
    HotspotView(Context c,boolean right,boolean visible){super(c);this.right=right;this.visible=visible;}
    void mark(int next){removeCallbacks(reset);state=next;invalidate();if(next==0||next==2)postDelayed(reset,700);}
    @Override protected void onDraw(Canvas c){
        if(!visible)return;
        int color=state==1?0xff58e5a1:state==2?0xffffa447:0xff69b9ff;
        float d=getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);paint.setColor((color&0xffffff)|0x66000000);
        c.drawRect(0,0,getWidth(),getHeight(),paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f*d);paint.setColor(color);
        c.drawRect(new RectF(d,d,getWidth()-d,getHeight()-d),paint);
        float x0=getWidth()*(right?.75f:.25f),x1=getWidth()*(right?.25f:.75f);
        float y0=getHeight()*.75f,y1=getHeight()*.25f;
        c.drawLine(x0,y0,x1,y1,paint);
        float head=getWidth()*.18f;
        c.drawLine(x1,y1,x1+(right?head:-head),y1,paint);
        c.drawLine(x1,y1,x1,y1+head,paint);
    }
    @Override protected void onDetachedFromWindow(){removeCallbacks(reset);super.onDetachedFromWindow();}
}
