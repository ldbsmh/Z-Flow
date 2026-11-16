package dev.local.sunwindowdemo;

import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.Locale;

public final class DiagnosticActivity extends DemoActivity {
    private String token;
    private boolean landscape;
    private boolean leaving;
    private TextView metrics;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        token = getIntent().getIdentifier();
        if (!Wire.validToken(token)) { finish(); return; }
        TaskTools.liveDiagnostic=getTaskId();
        landscape = state != null && state.getBoolean("landscape");
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
            if (!backendCompatible) { finishAndRemoveTask(); return; }
            leaving = true; Wire.send(this, "RESTORE");
        });
        text("小窗触摸与键盘诊断", 21);
        button("开始本页小窗测试", () -> {
            if(!backendCompatible||!hostConnected){Toast.makeText(this,"请先连接系统后端与 SystemUI 宿主",Toast.LENGTH_LONG).show();return;}
            landscape=false;padReset();Wire.send(this, "START", Wire.PKG, getTaskId(), token, false);
        });
        text("缩小后点击四角和中心圆点，观察是否分别变绿；再试双指、横屏和输入框。", 13);
        metrics = text("等待尺寸与输入数据", 12);
        TouchPad pad = new TouchPad();
        body.addView(pad, new LinearLayout.LayoutParams(-1, dp(185)));
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("点这里弹出键盘并输入文字");
        body.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
        button("切换应用逻辑画布：竖屏 / 横屏", () -> {
            landscape = !landscape;
            Wire.send(this, "CANVAS", Wire.PKG, getTaskId(), token, landscape);
        });
        button("收起键盘并恢复原窗口", () -> {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
            Wire.send(this, "RESTORE");
        });
        button("关闭本页小窗并清理后台卡片",()->{leaving=true;if(backendCompatible)Wire.send(this,"RESTORE");else finishAndRemoveTask();});
        status = text("先点击开始；预检未通过会显示具体原因。", 12); status.setTextIsSelectable(true);
        input.setOnApplyWindowInsetsListener((v, insets) -> { updateMetrics("IME=" + insets.isVisible(WindowInsets.Type.ime())
            + " bottom=" + insets.getInsets(WindowInsets.Type.ime()).bottom); return insets; });
        pad.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> updateMetrics("测试区=" + (r-l) + "×" + (b-t)));
    }
    private void updateMetrics(String extra) {
        if (metrics != null) metrics.setText("应用配置=" + getResources().getConfiguration().screenWidthDp + "×"
            + getResources().getConfiguration().screenHeightDp + " dp；窗口="
            + getWindowManager().getCurrentWindowMetrics().getBounds().toShortString() + "\n" + extra);
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean("landscape", landscape); super.onSaveInstanceState(state); }
    @Override void onBackendResult(Intent i) {
        if("idle".equals(i.getStringExtra("state"))&&(leaving||("CLOSED".equals(i.getStringExtra("event"))&&i.getIntExtra("endedTaskId",-1)==getTaskId())))finishAndRemoveTask();
    }
    private TouchPad testPad;
    private void padReset(){if(testPad!=null){java.util.Arrays.fill(testPad.hit,false);testPad.invalidate();}}
    @Override public void onConfigurationChanged(android.content.res.Configuration c){super.onConfigurationChanged(c);padReset();}
    @Override public void onDestroy(){if(TaskTools.liveDiagnostic==getTaskId())TaskTools.liveDiagnostic=-1;super.onDestroy();}
    private final class TouchPad extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean[] hit = new boolean[5];
        TouchPad() { super(DiagnosticActivity.this);testPad=this; setContentDescription("触摸测试区：四角和中心共五个圆点"); }
        private float x(int i) { return i == 4 ? getWidth()/2f : (i % 2 == 0 ? dp(30) : getWidth()-dp(30)); }
        private float y(int i) { return i == 4 ? getHeight()/2f : (i < 2 ? dp(30) : getHeight()-dp(30)); }
        @Override protected void onDraw(Canvas c) {
            paint.setColor(0xff263238); c.drawRoundRect(0,0,getWidth(),getHeight(),dp(14),dp(14),paint);
            for (int i=0;i<5;i++) {
                paint.setColor(hit[i] ? 0xff66bb6a : 0xff90caf9); c.drawCircle(x(i),y(i),dp(22),paint);
                paint.setColor(0xff102027); paint.setTextSize(dp(16)); paint.setTextAlign(Paint.Align.CENTER);
                c.drawText(String.valueOf(i+1),x(i),y(i)+dp(6),paint);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            getParent().requestDisallowInterceptTouchEvent(true);
            StringBuilder s = new StringBuilder("action=").append(e.getActionMasked()).append(" pointers=").append(e.getPointerCount());
            for (int j=0;j<e.getPointerCount();j++) {
                float px=e.getX(j), py=e.getY(j);
                for (int i=0;i<5;i++) if (Math.hypot(px-x(i),py-y(i))<dp(26)) hit[i]=true;
                s.append(String.format(Locale.ROOT,"\n#%d local=(%.1f,%.1f) raw=(%.1f,%.1f)",e.getPointerId(j),px,py,e.getRawX(j),e.getRawY(j)));
            }
            updateMetrics(s.toString()); invalidate();
            if (e.getActionMasked()==MotionEvent.ACTION_UP) { performClick(); getParent().requestDisallowInterceptTouchEvent(false); }
            if (e.getActionMasked()==MotionEvent.ACTION_CANCEL) getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
