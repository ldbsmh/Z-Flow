package io.relimus.zflow.broadcast

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import io.relimus.zflow.room.DatabaseRepository
import io.relimus.zflow.xposed.services.FreeformService

class StartFreeformReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra("packageName")
        val activityName = intent.getStringExtra("activityName")
        val userId = intent.getIntExtra("userId", -1)
        val miniMode = intent.getBooleanExtra(EXTRA_MINI_MODE, false)
        val taskId = intent.getIntExtra(FreeformService.EXTRA_TASK_ID, -1)
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: ""

        // ===== 读取来源方向信息 =====
        val sourceRotation = intent.getIntExtra(EXTRA_SOURCE_ROTATION, -1)
        val sourceScreenWidth = intent.getIntExtra(EXTRA_SOURCE_SCREEN_WIDTH, 0)
        val sourceScreenHeight = intent.getIntExtra(EXTRA_SOURCE_SCREEN_HEIGHT, 0)

        val parcelable = intent.getParcelableExtra(Intent.EXTRA_INTENT, Parcelable::class.java)

        var target = Intent()

        if (packageName != null && activityName != null) {
            target = Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(packageName, activityName))
                .setPackage(packageName)
                .addCategory(Intent.CATEGORY_LAUNCHER)
        }

        if (parcelable is Intent) {
            target = parcelable
        }

        val targetComponent = target.component
        val targetPackageName = targetComponent?.packageName
            ?: target.`package`
            ?: packageName

        if (!targetPackageName.isNullOrBlank()) {
            val repository = DatabaseRepository(context)

            val blocked = if (userId >= 0) {
                repository.isBlacklisted(targetPackageName, userId)
            } else {
                repository.isBlacklisted(targetPackageName, 0)
            }

            if (blocked) {
                return
            }
        }

        context.startService(
            Intent(context, FreeformService::class.java)
                .setAction(FreeformService.ACTION_START_INTENT)
                .putExtra(Intent.EXTRA_INTENT, target)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, targetComponent)
                .putExtra(Intent.EXTRA_USER, if (userId >= 0) userId else 0)
                .putExtra(FreeformService.EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_MINI_MODE, miniMode)
                .putExtra(EXTRA_SOURCE, source)
                // ===== 传递方向信息 =====
                .putExtra(EXTRA_SOURCE_ROTATION, sourceRotation)
                .putExtra(EXTRA_SOURCE_SCREEN_WIDTH, sourceScreenWidth)
                .putExtra(EXTRA_SOURCE_SCREEN_HEIGHT, sourceScreenHeight)
        )
    }

    companion object {
        const val EXTRA_MINI_MODE = "mini_mode"
        const val EXTRA_SOURCE = "source"

        const val SOURCE_POPUP = "popup"
        const val SOURCE_RECENT_SWIPE = "recentSwipe"

        // ===== 来源方向信息常量 =====
        const val EXTRA_SOURCE_ROTATION = "sourceRotation"
        const val EXTRA_SOURCE_SCREEN_WIDTH = "sourceScreenWidth"
        const val EXTRA_SOURCE_SCREEN_HEIGHT = "sourceScreenHeight"
    }
}