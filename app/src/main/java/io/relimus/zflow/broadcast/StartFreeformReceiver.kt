package io.relimus.zflow.broadcast

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import io.relimus.zflow.security.BroadcastAuth
import io.relimus.zflow.service.FreeformRequestHandler
import io.relimus.zflow.xposed.services.FreeformService

/** Request construction/validation only. FreeformRequestReceiver is the sole registered receiver. */
class StartFreeformReceiver private constructor() {
    companion object {
        private const val TAG = "StartFreeformReceiver"
        const val TARGET_PACKAGE = "io.relimus.zflow"
        const val ACTION_START = "io.relimus.zflow.start_freeform"
        /** Compatibility metadata only; public strings are NOT authentication. */
        const val EXTRA_SENDER = "io.relimus.zflow.extra.sender"
        const val EXTRA_TOKEN = BroadcastAuth.TOKEN
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_ACTIVITY_NAME = "activityName"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_MINI_MODE = "mini_mode"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SOURCE_ROTATION = "source_rotation"
        const val EXTRA_SOURCE_SCREEN_WIDTH = "source_screen_width"
        const val EXTRA_SOURCE_SCREEN_HEIGHT = "source_screen_height"
        const val SOURCE_POPUP = "popup"
        const val SOURCE_RECENT_SWIPE = "recent_swipe"
        const val SOURCE_NOTIFICATION = "notification"

        /** Context must belong to the actual hook host. Fetch failure throws; never send unauthenticated. */
        fun newIntent(
            context: Context,
            packageName: String? = null,
            activityName: String? = null,
            userId: Int = 0,
            miniMode: Boolean = false,
            taskId: Int = -1,
            source: String = "",
            sourceRotation: Int = -1,
            sourceScreenWidth: Int = 0,
            sourceScreenHeight: Int = 0
        ): Intent = Intent(ACTION_START).apply {
            component = ComponentName(TARGET_PACKAGE, FreeformRequestReceiver::class.java.name)
            setPackage(TARGET_PACKAGE)
            putExtra(EXTRA_SENDER, TARGET_PACKAGE)
            if (packageName != null) putExtra(EXTRA_PACKAGE_NAME, packageName)
            if (activityName != null) putExtra(EXTRA_ACTIVITY_NAME, activityName)
            putExtra(EXTRA_USER_ID, userId)
            putExtra(EXTRA_MINI_MODE, miniMode)
            putExtra(FreeformService.EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_SOURCE_ROTATION, sourceRotation)
            putExtra(EXTRA_SOURCE_SCREEN_WIDTH, sourceScreenWidth)
            putExtra(EXTRA_SOURCE_SCREEN_HEIGHT, sourceScreenHeight)
            BroadcastAuth.attach(context, this)
        }

        /** Callback belongs to the handler ONLY when it accepts. Receiver finishes every other path. */
        fun dispatch(context: Context, intent: Intent, onComplete: (() -> Unit)? = null): Boolean {
            try {
                if (intent.action != ACTION_START || intent.component !=
                    ComponentName(TARGET_PACKAGE, FreeformRequestReceiver::class.java.name)) return false
                val appContext = context.applicationContext ?: context
                if (!BroadcastAuth.verify(appContext, intent)) return false
                validateTypes(intent)
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                val activityName = intent.getStringExtra(EXTRA_ACTIVITY_NAME)
                require(packageName == null || (packageName.isNotBlank() && packageName.length <= 256))
                require(activityName == null || (activityName.isNotBlank() && activityName.length <= 1024))
                var target = Intent()
                if (packageName != null && activityName != null) {
                    target.component = ComponentName(packageName, activityName)
                }
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.let { inner ->
                    require(inner.component != null)
                    target = Intent().setComponent(inner.component)
                }
                require(target.component != null || packageName != null)
                require(packageName == null || target.component == null || target.component!!.packageName == packageName)
                val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
                val taskId = intent.getIntExtra(FreeformService.EXTRA_TASK_ID, -1)
                val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
                val rotation = intent.getIntExtra(EXTRA_SOURCE_ROTATION, -1)
                val width = intent.getIntExtra(EXTRA_SOURCE_SCREEN_WIDTH, 0)
                val height = intent.getIntExtra(EXTRA_SOURCE_SCREEN_HEIGHT, 0)
                require(userId >= 0 && taskId >= -1 && rotation in -1..3)
                require(width in 0..32768 && height in 0..32768 && source.length <= 64)
                return FreeformRequestHandler.enqueueFromBroadcast(
                    context = appContext, target = target, packageName = packageName,
                    userId = userId, taskId = taskId,
                    miniMode = intent.getBooleanExtra(EXTRA_MINI_MODE, false),
                    pendingIntent = intent.getParcelableExtra(FreeformService.EXTRA_PENDING_INTENT, PendingIntent::class.java),
                    source = source, sourceRotation = rotation,
                    sourceScreenWidth = width, sourceScreenHeight = height,
                    onComplete = onComplete
                )
            } catch (_: RuntimeException) {
                // Includes malformed parcels, type mismatches and failed auth/PM/key access.
                // Do not log attacker-controlled extras or capability contents.
                Log.w(TAG, "Rejected malformed or unavailable-auth freeform request")
                return false
            }
        }

        @Suppress("DEPRECATION")
        private fun validateTypes(intent: Intent) {
            val extras = intent.extras ?: throw IllegalArgumentException("Missing extras")
            val strings = setOf(EXTRA_PACKAGE_NAME, EXTRA_ACTIVITY_NAME, EXTRA_SOURCE, EXTRA_SENDER, EXTRA_TOKEN)
            val ints = setOf(EXTRA_USER_ID, FreeformService.EXTRA_TASK_ID, EXTRA_SOURCE_ROTATION,
                EXTRA_SOURCE_SCREEN_WIDTH, EXTRA_SOURCE_SCREEN_HEIGHT, BroadcastAuth.UID)
            for (key in extras.keySet()) {
                val value = extras.get(key)
                when (key) {
                    in strings -> require(value is String)
                    in ints -> require(value is Int)
                    EXTRA_MINI_MODE -> require(value is Boolean)
                    Intent.EXTRA_INTENT -> require(value is Intent)
                    Intent.EXTRA_COMPONENT_NAME -> require(value is ComponentName)
                    FreeformService.EXTRA_PENDING_INTENT -> require(value is PendingIntent)
                }
            }
        }
    }
}
