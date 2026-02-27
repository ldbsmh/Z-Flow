package io.relimus.zflow.broadcast

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import io.relimus.zflow.xposed.services.FreeformService

class StartFreeformReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra("packageName")
        val activityName = intent.getStringExtra("activityName")
        val userId = intent.getIntExtra("userId", -1)
        intent.getStringExtra("extras")
        val miniMode = intent.getBooleanExtra("miniMode", false)
        val taskId = intent.getIntExtra(FreeformService.EXTRA_TASK_ID, -1)
        val parcelable = intent.getParcelableExtra(Intent.EXTRA_INTENT, Parcelable::class.java)
        var target = Intent()
        if (packageName != null && activityName != null)
            target = Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(packageName, activityName))
                .setPackage(packageName)
                .addCategory(Intent.CATEGORY_LAUNCHER)
        if (parcelable is Intent)
            target = parcelable
        context.startService(
            Intent(context, FreeformService::class.java)
                .setAction(FreeformService.ACTION_START_INTENT)
                .putExtra(Intent.EXTRA_INTENT, target)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, target.component)
                .putExtra(Intent.EXTRA_USER, userId)
                .putExtra(FreeformService.EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_MINI_MODE, miniMode)
        )
    }

    companion object {
        const val EXTRA_MINI_MODE = "mini_mode"
    }
}
