package io.relimus.zflow.security

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Exported deliberately: authorization uses the kernel-supplied Binder UID, not a permission. */
class RequestAuthProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != BroadcastAuth.METHOD) throw SecurityException("Unsupported operation")
        val callerUid = Binder.getCallingUid() // capture before any identity clearing / asynchronous work
        return BroadcastAuth.issue(requireNotNull(context), callerUid)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw SecurityException("Unsupported operation")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Unsupported operation")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("Unsupported operation")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("Unsupported operation")
}
