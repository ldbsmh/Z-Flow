package io.relimus.zflow.ui.choose_apps

import android.app.Application
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.relimus.zflow.providers.LandscapeAppsProvider
import io.relimus.zflow.providers.NotificationSettingsProvider
import io.relimus.zflow.room.DatabaseRepository
import io.relimus.zflow.room.FreeFormAppsEntity
import io.relimus.zflow.room.FreeformBlacklistEntity
import io.relimus.zflow.room.NotificationAppsEntity
import io.relimus.zflow.systemapi.UserHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * 应用选择页 ViewModel。
 *
 * - 数据库读写全部走 viewModelScope + Dispatchers.IO，不阻塞主线程；
 * - 应用列表的加载与缓存归 ViewModel 所有：旋转屏幕 / Activity 重建直接复用，
 *   不会重复查询，也不会回调到已经销毁的界面；
 * - “全选”是一次事务 + 一次变更通知，而不是逐条插入 + 逐条广播。
 */
class ChooseAppsViewModel(application: Application) : AndroidViewModel(application) {

    sealed class LoadState {
        object Loading : LoadState()
        data class Success(
            val allApps: List<LauncherActivityInfo>,
            /** 已选项，格式 `packageName|userId`；横屏类型 userId 为 -1。 */
            val selected: List<String>
        ) : LoadState()
        data class Failed(val message: String?) : LoadState()
    }

    private val repository = DatabaseRepository(application)

    private val _state = MutableLiveData<LoadState>(LoadState.Loading)
    val state: LiveData<LoadState> = _state

    var type = TYPE_FLOATING

    private var allAppsCache: List<LauncherActivityInfo>? = null
    private var loadJob: Job? = null

    // ========== 已选列表（悬浮窗 / 通知 / 黑名单走 LiveData）==========

    fun getAllApps(): LiveData<List<FreeFormAppsEntity>?> = repository.getAllFreeForm()

    fun getAllNotificationApps(): LiveData<List<NotificationAppsEntity>?> =
        repository.getAllNotification()

    fun getAllBlacklistApps(): LiveData<List<FreeformBlacklistEntity>?> =
        repository.getAllBlacklist()

    /** 横屏名单由 Provider 托管（SharedPreferences），同步读取开销很小。 */
    fun getAllLandscapeApps(): List<String> = landscapeCall(
        LandscapeAppsProvider.METHOD_GET_ALL,
        null
    )?.getStringArrayList(LandscapeAppsProvider.EXTRA_PACKAGE_LIST) ?: emptyList()

    // ========== 应用列表加载 ==========

    fun loadApps(userManager: UserManager, forceRefresh: Boolean) {
        if (loadJob?.isActive == true) return      // 去重：同一时刻只跑一个加载任务

        val cached = allAppsCache
        if (!forceRefresh && cached != null && _state.value is LoadState.Success) return

        _state.value = LoadState.Loading
        loadJob = viewModelScope.launch {
            _state.value = runCatching {
                val apps = cached ?: withContext(Dispatchers.IO) { queryAllApps(userManager) }
                    .also { allAppsCache = it }
                val selected = withContext(Dispatchers.IO) { querySelected() }
                LoadState.Success(apps, selected)
            }.getOrElse { LoadState.Failed(it.message) }
        }
    }

    private fun queryAllApps(userManager: UserManager): List<LauncherActivityInfo> {
        val ctx = getApplication<Application>()
        val launcherApps = ctx.getSystemService(LauncherApps::class.java)
        val pm = ctx.packageManager
        val list = ArrayList<LauncherActivityInfo>()
        userManager.userProfiles.forEach { user ->
            runCatching { launcherApps.getActivityList(null, user) }
                .getOrDefault(emptyList())
                .forEach { list.add(it) }
        }
        list.sortWith { o1, o2 ->
            Collator.getInstance().compare(
                o1.applicationInfo.loadLabel(pm),
                o2.applicationInfo.loadLabel(pm)
            )
        }
        return list
    }

    /**
     * 首屏渲染用的已选包名。
     * 悬浮窗 / 通知 / 黑名单类型同时观察 LiveData 做增量刷新，这里的首屏值只用于
     * 在 LiveData 首次回调之前就正确显示勾选状态。
     * 注意：Room 的 Flow 只能从 “冷启动” 状态收集（不能在 Activity 重建后从
     * STARTED 状态再次订阅），因此这里用只读、无事务的同步查询兜底。
     */
    private suspend fun querySelected(): List<String> = when (type) {
        TYPE_LANDSCAPE -> getAllLandscapeApps().map { "$it|$LANDSCAPE_USER" }
        TYPE_NOTIFICATION -> repository.getAllNotificationKeys()
        TYPE_BLACKLIST -> repository.getAllBlacklistKeys()
        else -> repository.getAllFreeFormKeys()
    }

    /**
     * 单条勾选切换（列表点击）。
     * 与 [insertApps]/[deleteApps] 的区别：调用方已经知道目标状态，
     * 这里只负责落库，不再回读。
     */
    fun toggleApp(packageName: String, userId: Int, checked: Boolean) {
        if (checked) insertApps(packageName, userId) else deleteApps(packageName, userId)
    }

    // ========== 写操作（全部在后台线程）==========

    fun insertApps(packageName: String, userId: Int) {
        viewModelScope.launch {
            when (type) {
                TYPE_NOTIFICATION -> {
                    repository.insertNotification(packageName, userId)
                    notifyNotificationAppsChanged()
                }
                TYPE_BLACKLIST -> repository.insertBlacklist(packageName, userId)
                TYPE_LANDSCAPE -> addLandscapeApp(packageName)
                else -> repository.insertFreeForm(packageName, userId)
            }
        }
    }

    fun deleteApps(packageName: String, userId: Int) {
        viewModelScope.launch {
            when (type) {
                TYPE_NOTIFICATION -> {
                    repository.deleteNotification(packageName, userId)
                    notifyNotificationAppsChanged()
                }
                TYPE_BLACKLIST -> repository.deleteBlacklist(packageName, userId)
                TYPE_LANDSCAPE -> removeLandscapeApp(packageName)
                else -> repository.deleteFreeForm(packageName, userId)
            }
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            when (type) {
                TYPE_NOTIFICATION -> {
                    repository.deleteAllNotification()
                    notifyNotificationAppsChanged()
                }
                TYPE_BLACKLIST -> repository.deleteAllBlacklist()
                TYPE_LANDSCAPE -> landscapeCall(LandscapeAppsProvider.METHOD_CLEAR_ALL, null)
                TYPE_FLOATING -> repository.deleteAllFreeForm()
            }
        }
    }

    /**
     * 全选：先构造目标实体列表，再在一个事务内整体替换，
     * 结束之后只发一次“通知名单已变化”信号。
     */
    fun insertAllApps(allApps: List<LauncherActivityInfo>) {
        if (allApps.isEmpty()) return
        viewModelScope.launch {
            when (type) {
                TYPE_NOTIFICATION -> {
                    repository.replaceNotificationList(allApps.map { NotificationAppsEntity(it.applicationInfo.packageName, userIdOf(it)) })
                    notifyNotificationAppsChanged()
                }
                TYPE_BLACKLIST -> {
                    repository.replaceBlacklist(allApps.map { FreeformBlacklistEntity(it.applicationInfo.packageName, userIdOf(it)) })
                }
                TYPE_LANDSCAPE -> withContext(Dispatchers.IO) {
                    landscapeCall(LandscapeAppsProvider.METHOD_CLEAR_ALL, null)
                    // Provider 写入是独立的 SharedPreferences.commit，逐条写入即可
                    allApps.forEach { addLandscapeApp(it.applicationInfo.packageName) }
                }
                else -> {
                    repository.replaceFreeFormList(
                        allApps.mapIndexed { index, info ->
                            FreeFormAppsEntity(index, info.applicationInfo.packageName, userIdOf(info))
                        }
                    )
                }
            }
        }
    }

    private fun userIdOf(info: LauncherActivityInfo): Int =
        UserHandle.getUserId(info.user, info.applicationInfo.uid)

    /** 通知名单变化：推进版本号，让 SystemUI 进程的下一次查询直接失效缓存。 */
    private fun notifyNotificationAppsChanged() {
        repository.bumpVersion(NotificationSettingsProvider.KEY_APP_LIST_VERSION)
    }

    // ========== 横屏应用管理（Provider 内部是 SharedPreferences）==========

    private val landscapeUri = Uri.parse("content://${LandscapeAppsProvider.AUTHORITY}")

    private fun landscapeCall(method: String, extras: Bundle?): Bundle? = runCatching {
        getApplication<Application>().contentResolver.call(landscapeUri, method, null, extras)
    }.getOrNull()

    private fun addLandscapeApp(packageName: String) {
        landscapeCall(
            LandscapeAppsProvider.METHOD_ADD,
            Bundle().apply { putString(LandscapeAppsProvider.EXTRA_PACKAGE_NAME, packageName) }
        )
    }

    private fun removeLandscapeApp(packageName: String) {
        landscapeCall(
            LandscapeAppsProvider.METHOD_REMOVE,
            Bundle().apply { putString(LandscapeAppsProvider.EXTRA_PACKAGE_NAME, packageName) }
        )
    }

    companion object {
        /** 横屏名单不分用户，统一用 -1 作为键的一部分。 */
        const val LANDSCAPE_USER = -1

        const val TYPE_FLOATING = 1
        const val TYPE_NOTIFICATION = 2
        const val TYPE_BLACKLIST = 3
        const val TYPE_LANDSCAPE = 4
    }
}
