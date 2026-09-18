package io.relimus.zflow.xposed.hook.utils

import android.app.Application
import android.os.Process
import android.os.SystemClock
import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicLong

/**
 * 增强日志工具，支持进程、线程、traceId，Debug级别不写XposedBridge。
 */
object XLog {

    private const val DEFAULT_TAG = "Z-Flow"
    private val sequence = AtomicLong(0)

    fun newTraceId(prefix: String = "FF"): String {
        return "$prefix-${SystemClock.uptimeMillis()}-${sequence.incrementAndGet()}"
    }

    fun d(
        msg: Any? = null,
        e: Throwable? = null,
        tag: String = DEFAULT_TAG
    ) = log(Log.DEBUG, "D", msg, e, tag)

    fun i(
        msg: Any? = null,
        e: Throwable? = null,
        tag: String = DEFAULT_TAG
    ) = log(Log.INFO, "I", msg, e, tag)

    fun w(
        msg: Any? = null,
        e: Throwable? = null,
        tag: String = DEFAULT_TAG
    ) = log(Log.WARN, "W", msg, e, tag)

    fun e(
        msg: Any? = null,
        e: Throwable? = null,
        tag: String = DEFAULT_TAG
    ) = log(Log.ERROR, "E", msg, e, tag)

    /** 强制写入 LSPosed 日志，用于跨进程诊断链路。 */
    fun ls(msg: Any?, tag: String = "ZFlowDiag", e: Throwable? = null) {
        runCatching {
            val text = msg?.toString().orEmpty()
            XposedBridge.log("[$tag] $text")
            e?.let { XposedBridge.log(it) }
        }
    }

    private fun log(
        priority: Int,
        level: String,
        msg: Any?,
        throwable: Throwable?,
        tag: String
    ) {
        val processName = runCatching {
            Application.getProcessName()
        }.getOrDefault("unknown")

        val message = buildString {
            append("process=")
            append(processName)
            append(" pid=")
            append(Process.myPid())
            append(" thread=")
            append(Thread.currentThread().name)
            append(" | ")
            append(msg?.toString().orEmpty())
        }

        Log.println(
            priority,
            tag,
            if (throwable == null) {
                message
            } else {
                "$message\n${Log.getStackTraceString(throwable)}"
            }
        )

        // 只有 Error 级别写入 Xposed（LSPosed）日志，避免刷屏
        if (level == "E") {
            runCatching {
                XposedBridge.log(
                    "[$tag][$level] $message"
                )
                throwable?.let {
                    XposedBridge.log(it)
                }
            }
        }
    }
}