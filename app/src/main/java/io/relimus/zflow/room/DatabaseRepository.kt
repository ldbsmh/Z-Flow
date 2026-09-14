package io.relimus.zflow.room

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import io.relimus.zflow.room.MyDatabase.Companion.getDatabase
import kotlinx.coroutines.flow.Flow

class DatabaseRepository(context: Context) {

    private val freeFormAppsDao: FreeFormAppsDao
    private val notificationAppsDao: NotificationAppsDao
    private val freeformBlacklistDao: FreeformBlacklistDao

    fun insertFreeForm(packageName: String, userId: Int) {
        try {
            freeFormAppsDao.insert(packageName, userId)
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "insertFreeForm failed: pkg=$packageName userId=$userId", e)
        }
    }

    fun deleteFreeForm(packageName: String, userId: Int) {
        try {
            freeFormAppsDao.delete(packageName, userId)
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "deleteFreeForm failed: pkg=$packageName userId=$userId", e)
        }
    }

    fun getAllFreeFormName(): LiveData<List<String>?> {
        return freeFormAppsDao.getAllName()
    }

    fun getAllFreeForm(): LiveData<List<FreeFormAppsEntity>?> {
        return freeFormAppsDao.getAll()
    }

    fun getAllFreeFormAppsByFlow(): Flow<List<FreeFormAppsEntity>?> {
        return freeFormAppsDao.getAllByFlow()
    }

    fun getCount(): Int {
        return freeFormAppsDao.getCount()
    }

    fun update(entity: FreeFormAppsEntity) {
        try {
            freeFormAppsDao.update(entity)
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "updateFreeForm failed: entity=$entity", e)
        }
    }

    fun getAllFreeFormWithoutLiveData(): List<String>? {
        return freeFormAppsDao.getAllWithoutLiveData()
    }

    fun deleteAllFreeForm() {
        try {
            freeFormAppsDao.deleteAll()
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "deleteAllFreeForm failed", e)
        }
    }

    fun deleteMore(freeFormAppsEntityList: List<FreeFormAppsEntity>) {
        try {
            freeFormAppsDao.deleteList(freeFormAppsEntityList)
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "deleteMore freeForm failed", e)
        }
    }

    fun insertNotification(packageName: String, userId: Int) {
        try {
            notificationAppsDao.insert(packageName, userId)
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "insertNotification failed: pkg=$packageName userId=$userId", e)
        }
    }

    fun deleteNotification(packageName: String, userId: Int) {
        try {
            notificationAppsDao.delete(packageName, userId)
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "deleteNotification failed: pkg=$packageName userId=$userId", e)
        }
    }

    fun getAllNotification(): LiveData<List<NotificationAppsEntity>?> {
        return notificationAppsDao.getAll()
    }

    fun getAllNotificationByFlow(): Flow<List<NotificationAppsEntity>?> {
        return notificationAppsDao.getAllByFlow()
    }

    /**
     * 同步获取所有被勾选“接管通知”的应用包名。
     * 供 ContentProvider（SystemUI 等其它进程）查询使用。
     */
    fun getAllNotificationPackageNames(): List<String>? {
        return notificationAppsDao.getAllPackageNames()
    }

    fun deleteAllNotification() {
        try {
            notificationAppsDao.deleteAll()
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "deleteAllNotification failed", e)
        }
    }

    fun insertBlacklist(packageName: String, userId: Int) {
        try {
            freeformBlacklistDao.insert(packageName, userId)
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "insertBlacklist failed: pkg=$packageName userId=$userId", e)
        }
    }

    fun deleteBlacklist(packageName: String, userId: Int) {
        try {
            freeformBlacklistDao.delete(packageName, userId)
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "deleteBlacklist failed: pkg=$packageName userId=$userId", e)
        }
    }

    fun getAllBlacklist(): LiveData<List<FreeformBlacklistEntity>?> {
        return freeformBlacklistDao.getAll()
    }

    fun getAllBlacklistByFlow(): Flow<List<FreeformBlacklistEntity>?> {
        return freeformBlacklistDao.getAllByFlow()
    }

    fun deleteAllBlacklist() {
        try {
            freeformBlacklistDao.deleteAll()
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "deleteAllBlacklist failed", e)
        }
    }

    fun isBlacklisted(packageName: String, userId: Int): Boolean {
        return try {
            freeformBlacklistDao.count(packageName, userId) > 0
        } catch (e: Exception) {
            Log.e("DatabaseRepository", "isBlacklisted failed: pkg=$packageName userId=$userId", e)
            false
        }
    }

    init {
        val database = getDatabase(context)
        freeFormAppsDao = database.freeFormAppsDao
        notificationAppsDao = database.notificationAppsDao
        freeformBlacklistDao = database.freeformBlacklistDao
    }
}