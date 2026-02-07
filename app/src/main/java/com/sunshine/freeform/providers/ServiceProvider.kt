package com.sunshine.freeform.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.sunshine.freeform.service.FreeformManagerProxy

/**
 * ContentProvider that receives the FreeformManager binder from system_server.
 * Only accepts calls from the "android" package (system_server).
 */
class ServiceProvider : ContentProvider() {
    companion object {
        private const val TAG = "FreeformServiceProvider"
    }

    override fun onCreate(): Boolean = false

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // Only accept calls from android system package
        if (callingPackage != "android" || extras == null) {
            Log.w(TAG, "Rejected call from package: $callingPackage")
            return null
        }

        val binder = extras.getBinder("binder")
        if (binder == null) {
            Log.e(TAG, "Binder not found in extras")
            return null
        }

        FreeformManagerProxy.linkService(binder)
        Log.d(TAG, "FreeformManager binder linked successfully")
        return Bundle()
    }
}
