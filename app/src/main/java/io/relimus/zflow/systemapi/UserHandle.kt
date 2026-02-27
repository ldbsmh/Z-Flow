package io.relimus.zflow.systemapi

import android.os.UserHandle
import io.relimus.zflow.utils.cast

/**
 * @author sunshine
 * @date 2021/6/5
 */
object UserHandle {

    /**
     * 通过uid获取userId
     */
    fun getUserId(userHandle: UserHandle, uid: Int): Int {
        return try {
            userHandle::class.java.getMethod("getUserId", Int::class.javaPrimitiveType).invoke(userHandle, uid).cast()
        } catch (_: Exception) {
            0
        }
    }

    fun getUserId(userHandle: UserHandle): Int {
        return try {
            val mHandleField = userHandle::class.java.getDeclaredField("mHandle")
            mHandleField.isAccessible = true
            mHandleField.get(userHandle).cast()
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }
}