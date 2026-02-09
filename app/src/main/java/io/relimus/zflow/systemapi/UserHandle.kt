package io.relimus.zflow.systemapi

import android.os.UserHandle

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
            userHandle::class.java.getMethod("getUserId", Int::class.javaPrimitiveType).invoke(userHandle, uid) as Int
        } catch (_: Exception) {
            0
        }
    }

    fun getUserId(userHandle: UserHandle): Int {
        return try {
            val mHandleField = userHandle::class.java.getDeclaredField("mHandle")
            mHandleField.isAccessible = true
            mHandleField.get(userHandle) as Int
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }
}