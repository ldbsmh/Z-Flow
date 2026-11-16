package dev.local.sunwindowdemo;

import android.app.Activity;
import android.content.*;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

abstract class DemoActivity extends Activity {
    LinearLayout body;
    TextView status;
    private BroadcastReceiver receiver;
    boolean backendSeen;
    boolean backendCompatible;
    boolean hostConnected;
    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(16), dp(20), dp(20)); scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(scroll);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!Wire.RESULT.equals(i.getAction()) || getSentFromUid() != 1000) return;
                backendSeen = true;
                backendCompatible=i.getIntExtra("protocol",0)==Wire.PROTOCOL;
                hostConnected=i.getBooleanExtra("hostConnected",false);
                if(!backendCompatible){if(status!=null)status.setText("请重启，加载 0.2.0 系统后端后再测试。");return;}
                if (status != null) status.setText(i.getStringExtra("report"));
                Wire.notification(c, "active".equals(i.getStringExtra("state")) || "recovery".equals(i.getStringExtra("state")));
                onBackendResult(i);
            }
        };
        registerReceiver(receiver, new IntentFilter(Wire.RESULT), Wire.PERMISSION, null, Context.RECEIVER_EXPORTED);
    }
    void onBackendResult(Intent i) { }
    TextView text(String value, int size) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size);
        v.setPadding(0, dp(6), 0, dp(8)); body.addView(v); return v;
    }
    void button(String value, Runnable action) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false);
        body.addView(b, new LinearLayout.LayoutParams(-1, dp(52)));
        b.setOnClickListener(v -> action.run());
    }
    @Override public void onResume() { super.onResume(); Wire.send(this, "STATUS"); }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); }
    @Override public void onDestroy() { unregisterReceiver(receiver); super.onDestroy(); }
}
