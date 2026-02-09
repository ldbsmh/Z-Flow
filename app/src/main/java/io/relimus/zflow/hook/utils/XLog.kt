package io.relimus.zflow.hook.utils

import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.io.Serializable

/**
 * 仿照 YukiHookAPI
 * 采用类似 YLog 的用法，支持 Msg、Throwable 和自定义 Tag
 */
object XLog {

    private const val DEFAULT_TAG = "Z-Flow"

    fun d(msg: Any? = null, e: Throwable? = null, tag: String = DEFAULT_TAG) =
        log("D", msg, e, tag)

    fun i(msg: Any? = null, e: Throwable? = null, tag: String = DEFAULT_TAG) =
        log("I", msg, e, tag)

    fun w(msg: Any? = null, e: Throwable? = null, tag: String = DEFAULT_TAG) =
        log("W", msg, e, tag)

    fun e(msg: Any? = null, e: Throwable? = null, tag: String = DEFAULT_TAG) =
        log("E", msg, e, tag)

    /**
     * 内部统一打印逻辑
     */
    private fun log(priority: String, msg: Any?, throwable: Throwable?, tag: String) {
        val data = LogData(priority, tag, msg?.toString() ?: "", throwable)

        // 打印到 Logcat
        when (priority) {
            "D" -> Log.d(tag, data.msg, throwable)
            "I" -> Log.i(tag, data.msg, throwable)
            "W" -> Log.w(tag, data.msg, throwable)
            "E" -> Log.e(tag, data.msg, throwable)
        }

        // 打印到 Xposed 管理器
        XposedBridge.log(data.toString())
        throwable?.let { XposedBridge.log(it) }
    }

    /**
     * 日志数据结构体，负责格式化输出内容
     */
    data class LogData(
        val priority: String,
        val tag: String,
        val msg: String,
        val throwable: Throwable? = null
    ) : Serializable {
        override fun toString(): String = "[$tag][$priority] $msg"
    }
}
