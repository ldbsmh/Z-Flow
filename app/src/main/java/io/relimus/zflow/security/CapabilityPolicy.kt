package io.relimus.zflow.security

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Pure policy: package names / sender extras are never evidence of Binder identity. */
object CapabilityPolicy {
    fun isTrustedUid(uid: Int, appUid: Int, systemUiUid: Int?, homeUid: Int?): Boolean =
        uid >= 0 && (uid == 0 || uid == 1000 || uid == appUid ||
            (systemUiUid != null && uid == systemUiUid) || (homeUid != null && uid == homeUid))

    fun token(secret: ByteArray, uid: Int): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal("zflow.broadcast.v1:$uid".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun matches(expected: String, supplied: String?): Boolean {
        if (expected.length != 64 || supplied == null || supplied.length != 64) return false
        if (!supplied.all { it in '0'..'9' || it in 'a'..'f' }) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), supplied.toByteArray(Charsets.US_ASCII))
    }
}
