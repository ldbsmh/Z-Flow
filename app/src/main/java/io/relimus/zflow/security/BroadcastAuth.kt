package io.relimus.zflow.security

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import java.io.File
import java.security.SecureRandom

/** Only the provider/receiver in Z-Flow may access the master key. No world-readable prefs. */
object BroadcastAuth {
    const val AUTHORITY = "io.relimus.zflow.request.auth"
    const val METHOD = "issueCapability"
    const val TOKEN = "io.relimus.zflow.extra.token"
    const val UID = "io.relimus.zflow.extra.auth_uid"
    private const val APP = "io.relimus.zflow"
    private const val SYSTEM_UI = "com.android.systemui"

    // 密钥一次安装内不变；广播校验走主线程，读盘一次后进程内复用
    @Volatile
    private var cachedSecret: ByteArray? = null

    // systemui/home 的 uid 解析要跨两次 Binder；issue/verify 都在本进程，
    // 用同一份缓存保证两边判定一致。默认桌面可能在设置里被换，只缓存很短时间。
    private const val UID_CACHE_MS = 3_000L

    private class UidCache(val systemUiUid: Int?, val homeUid: Int?, val at: Long)

    @Volatile
    private var uidCache: UidCache? = null

    fun defaultHomePackage(context: Context): String? = runCatching {
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo ?: return@runCatching null
        // Android's resolver/chooser is not an actual selected HOME.
        home.packageName.takeUnless { it == "android" || !home.exported || !home.enabled }
    }.getOrNull()

    fun isDefaultHome(context: Context): Boolean =
        defaultHomePackage(context) == context.packageName

    fun isTrusted(context: Context, uid: Int): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = uidCache
        val resolved = if (cached != null && now - cached.at < UID_CACHE_MS) cached else {
            val fresh = UidCache(resolveSystemUiUid(context), resolveHomeUid(context), now).also { uidCache = it }
            fresh
        }
        return CapabilityPolicy.isTrustedUid(uid, context.applicationInfo.uid, resolved.systemUiUid, resolved.homeUid)
    }

    private fun resolveSystemUiUid(context: Context): Int? = runCatching {
        val info = context.packageManager.getApplicationInfo(SYSTEM_UI, 0)
        if (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
            info.uid else null
    }.getOrNull()

    private fun resolveHomeUid(context: Context): Int? =
        defaultHomePackage(context)?.let { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
        }

    /** Every fetch crosses Binder; extras never decide who may fetch a capability. */
    fun attach(context: Context, intent: Intent) {
        val result = context.contentResolver.call(Uri.parse("content://$AUTHORITY"), METHOD, null, null)
            ?: throw SecurityException("Z-Flow capability unavailable")
        val uid = result.getInt(UID, -1)
        val token = result.getString(TOKEN)
        if (uid != android.os.Process.myUid() || token == null || token.length != 64)
            throw SecurityException("Invalid Z-Flow capability")
        intent.putExtra(UID, uid).putExtra(TOKEN, token)
    }

    fun issue(context: Context, callingUid: Int): Bundle {
        if (!isTrusted(context, callingUid)) throw SecurityException("Caller is not a trusted hook host")
        return Bundle().apply {
            putInt(UID, callingUid)
            putString(TOKEN, CapabilityPolicy.token(secret(context), callingUid))
        }
    }

    fun verify(context: Context, intent: Intent): Boolean {
        val uid = intent.getIntExtra(UID, -1)
        if (!isTrusted(context, uid)) return false
        return CapabilityPolicy.matches(CapabilityPolicy.token(secret(context), uid), intent.getStringExtra(TOKEN))
    }

    @Synchronized
    private fun secret(context: Context): ByteArray {
        check(context.packageName == APP && android.os.Process.myUid() == context.applicationInfo.uid)
        cachedSecret?.let { return it }
        val file = AtomicFile(File(context.noBackupFilesDir, "broadcast-auth-v1.key"))
        if (file.baseFile.exists()) {
            return file.openRead().use { it.readBytes() }.also {
                check(it.size == 32)
                cachedSecret = it
            }
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (t: Throwable) {
            file.failWrite(output)
            throw t
        }
        cachedSecret = bytes
        return bytes
    }
}
