package io.relimus.zflow.ui.choose_apps

import android.app.Application
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.room.DatabaseRepository
import io.relimus.zflow.room.FreeFormAppsEntity
import io.relimus.zflow.room.FreeformBlacklistEntity
import io.relimus.zflow.room.NotificationAppsEntity
import io.relimus.zflow.systemapi.UserHandle

class ChooseAppsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DatabaseRepository(application)
    private val sp = application.getSharedPreferences(ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)

    var type = 1

    fun getAllApps(): LiveData<List<FreeFormAppsEntity>?> {
        return repository.getAllFreeForm()
    }

    fun getAllNotificationApps(): LiveData<List<NotificationAppsEntity>?> {
        return repository.getAllNotification()
    }

    fun getAllBlacklistApps(): LiveData<List<FreeformBlacklistEntity>?> {
        return repository.getAllBlacklist()
    }

    fun insertApps(packageName: String, userId: Int) {
        when (type) {
            2 -> {
                repository.insertNotification(packageName, userId)
                notifyNotificationAppsChanged()
            }
            3 -> {
                repository.insertBlacklist(packageName, userId)
            }
            4 -> addLandscapeApp(packageName)
            else -> repository.insertFreeForm(packageName, userId)
        }
    }

    fun deleteApps(packageName: String, userId: Int) {
        when (type) {
            2 -> {
                repository.deleteNotification(packageName, userId)
                notifyNotificationAppsChanged()
            }
            3 -> {
                repository.deleteBlacklist(packageName, userId)
            }
            4 -> removeLandscapeApp(packageName)
            else -> {
                repository.deleteFreeForm(packageName, userId)
            }
        }
    }

    fun deleteAll() {
        when (type) {
            2 -> {
                repository.deleteAllNotification()
                notifyNotificationAppsChanged()
            }
            3 -> {
                repository.deleteAllBlacklist()
            }
            4 -> clearAllLandscapeApps()
            1 -> {
                repository.deleteAllFreeForm()
            }
        }
    }

    fun insertAllApps(allAppsList: ArrayList<LauncherActivityInfo>, userManager: UserManager) {
        deleteAll()
        allAppsList.forEach {
            val userId = UserHandle.getUserId(it.user, it.applicationInfo.uid)
            when (type) {
                2 -> {
                    repository.insertNotification(it.applicationInfo.packageName, userId)
                    notifyNotificationAppsChanged()
                }
                3 -> {
                    repository.insertBlacklist(it.applicationInfo.packageName, userId)
                }
                4 -> addLandscapeApp(it.applicationInfo.packageName)
                else -> repository.insertFreeForm(it.applicationInfo.packageName, userId)
            }
        }
    }

    private fun notifyNotificationAppsChanged() {
        putBoolean("notify_freeform_changed", !getBoolean("notify_freeform_changed", false))
    }

    private fun putBoolean(key: String, newValue: Boolean) {
        sp.edit { putBoolean(key, newValue) }
    }

    private fun getBoolean(key: String, default: Boolean): Boolean {
        return sp.getBoolean(key, default)
    }

    // ========== 横屏应用管理（SharedPreferences + ContentProvider）==========

    private val landscapeUri = Uri.parse("content://io.relimus.zflow.landscape.provider")

    fun getAllLandscapeApps(): List<String> {
        return try {
            getApplication<Application>().contentResolver.call(
                landscapeUri, "get_all", null, null
            )?.getStringArrayList("package_list") ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun addLandscapeApp(packageName: String) {
        getApplication<Application>().contentResolver.call(
            landscapeUri, "add", null,
            Bundle().apply { putString("package_name", packageName) }
        )
    }

    private fun removeLandscapeApp(packageName: String) {
        getApplication<Application>().contentResolver.call(
            landscapeUri, "remove", null,
            Bundle().apply { putString("package_name", packageName) }
        )
    }

    private fun clearAllLandscapeApps() {
        getApplication<Application>().contentResolver.call(
            landscapeUri, "clear_all", null, null
        )
    }
}