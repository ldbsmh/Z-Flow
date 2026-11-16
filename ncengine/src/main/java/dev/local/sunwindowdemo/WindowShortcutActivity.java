package dev.local.sunwindowdemo;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.Intent;
import android.os.Bundle;

/** No UI or window conversion here: SystemUI selects the foreground after this finishes. */
public final class WindowShortcutActivity extends Activity {
    static final String ID="current_window",ACTION=Wire.PKG+".FLOAT_CURRENT";
    static boolean isCurrent(String pkg,String id){return Wire.PKG.equals(pkg)&&ID.equals(id);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(Intent.ACTION_CREATE_SHORTCUT.equals(getIntent().getAction())){
            Intent run=new Intent(this,WindowShortcutActivity.class).setAction(ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION);
            setResult(RESULT_OK,new Intent().putExtra(Intent.EXTRA_SHORTCUT_INTENT,run)
                .putExtra(Intent.EXTRA_SHORTCUT_NAME,getString(R.string.shortcut_window))
                .putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,Intent.ShortcutIconResource.fromContext(this,R.drawable.ic_shortcut_window)));
        }else if(ACTION.equals(getIntent().getAction())){
            sendBroadcast(new Intent(ACTION).setPackage(BackBridge.PACKAGE),null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        }
        finish();
    }
}
