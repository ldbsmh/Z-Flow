package io.relimus.zflow.room

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.core.content.edit
import androidx.room.withTransaction
import io.relimus.zflow.providers.BlacklistProvider
import io.relimus.zflow.providers.NotificationSettingsProvider
import io.relimus.zflow.room.MyDatabase.Companion.getDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 数据访问统一入口。
 *
 * 线程约定：
 * - 所有写方法都是 suspend，内部切到 Dispatchers.IO（Room 已移除
 *   allowMainThreadQueries），由调用方在 viewModelScope / lifecycleScope 中 launch，
 *   跟随其生命周期取消。
 * - 非 suspend 的同步方法（[isBlacklisted]、[getAllNotificationPackageNames] 等）
 *   只允许在 ContentProvider 的 Binder 线程或已经在后台线程的代码中调用，
 *   不得在 UI 主线程调用。
 * - 写操作异常不再静默吞掉：批量写入在一个事务里完成并返回 [WriteReport]；
 *   可预见的重复写入使用 INSERT OR IGNORE 语义，不再抛主键冲突。
 */
class DatabaseRepository(context: Context) {

    private val app: Context = context.applicationContext
    private val database = getDatabase(context)
    private val freeFormAppsDao: FreeFormAppsDao = database.freeFormAppsDao
    private val notificationAppsDao: NotificationAppsDao = database.notificationAppsDao
    private val notificationSyncDao: NotificationSyncDao = database.notificationSyncDao
    private val freeformBlacklistDao: FreeformBlacklistDao = database.freeformBlacklistDao

    /** 写入结果；[error] 为 null 表示成功。 */
    data class WriteReport(val error: Throwable? = null) {
        val isSuccess: Boolean get() = error == null
    }

    // ========== 悬浮窗应用列表 ==========

    suspend fun insertFreeForm(packageName: String, userId: Int) =
        runWrite("insertFreeForm") { freeFormAppsDao.insert(packageName, userId) }

    suspend fun deleteFreeForm(packageName: String, userId: Int) =
        runWrite("deleteFreeForm") { freeFormAppsDao.delete(packageName, userId) }

    /**
     * 整份写回拖拽后的顺序（浮窗排序页）。
     *
     * 不能逐条 `@Update` 改 sortNum：sortNum 是自增主键，交换两项时会先写出
     * 一个已存在的主键，Room 抛冲突后异常被吞掉，表现为“拖动结束顺序又弹回去”。
     *
     * 这里在一个事务里分两步：
     *  1. 所有行主键挪到负数区（负数不在自增分配范围内，不会撞车）；
     *  2. 按界面顺序写回 0..n-1。
     */
    suspend fun updateFreeFormSort(list: List<FreeFormAppsEntity>) =
        runWrite("updateFreeFormSort") {
            if (list.isEmpty()) return@runWrite
            database.withTransaction {
                freeFormAppsDao.parkSortKeys()
                list.forEachIndexed { index, entity ->
                    freeFormAppsDao.assignSortNum(entity.packageName, entity.userId, index)
                }
            }
        }

    /** 整体替换悬浮窗列表（事务内先清空再写入）。 */
    suspend fun replaceFreeFormList(list: List<FreeFormAppsEntity>) =
        runWrite("replaceFreeFormList") {
            database.withTransaction {
                freeFormAppsDao.deleteAll()
                freeFormAppsDao.insertLists(list)
            }
        }

    suspend fun deleteAllFreeForm() =
        runWrite("deleteAllFreeForm") { freeFormAppsDao.deleteAll() }

    suspend fun deleteMore(freeFormAppsEntityList: List<FreeFormAppsEntity>) =
        runWrite("deleteMore") {
            if (freeFormAppsEntityList.isNotEmpty()) {
                freeFormAppsDao.deleteList(freeFormAppsEntityList)
            }
        }

    fun getAllFreeFormName(): LiveData<List<String>?> = freeFormAppsDao.getAllName()

    fun getAllFreeForm(): LiveData<List<FreeFormAppsEntity>?> = freeFormAppsDao.getAll()

    fun getAllFreeFormAppsByFlow(): Flow<List<FreeFormAppsEntity>?> =
        freeFormAppsDao.getAllByFlow()

    /** 同步查询，必须在后台线程调用。 */
    fun getCount(): Int = freeFormAppsDao.getCount()

    /** 同步查询，必须在后台线程调用。 */
    fun getAllFreeFormWithoutLiveData(): List<String>? = freeFormAppsDao.getAllWithoutLiveData()

    /** 同步读取已选项（`packageName|userId`），必须在后台线程调用。 */
    fun getAllFreeFormKeys(): List<String> =
        runCatching { freeFormAppsDao.getAllWithUser() }
            .getOrDefault(emptyList())
            .map { "${it.packageName}|${it.userId}" }

    // ========== 通知接管应用列表 ==========

    suspend fun insertNotification(packageName: String, userId: Int) =
        runWrite("insertNotification") { notificationAppsDao.insert(packageName, userId) }

    suspend fun deleteNotification(packageName: String, userId: Int) =
        runWrite("deleteNotification") { notificationAppsDao.delete(packageName, userId) }

    /** 整体替换通知名单（事务内先清空再写入，只产生一次通知）。 */
    suspend fun replaceNotificationList(list: List<NotificationAppsEntity>) =
        runWrite("replaceNotificationList") {
            database.withTransaction {
                notificationAppsDao.deleteAll()
                notificationAppsDao.insertLists(list)
            }
        }

    suspend fun deleteAllNotification() =
        runWrite("deleteAllNotification") { notificationAppsDao.deleteAll() }

    fun getAllNotification(): LiveData<List<NotificationAppsEntity>?> =
        notificationAppsDao.getAll()

    fun getAllNotificationByFlow(): Flow<List<NotificationAppsEntity>?> =
        notificationAppsDao.getAllByFlow()

    /**
     * 同步读取被勾选“接管通知”的应用包名。
     * 供 ContentProvider（SystemUI 等其它进程）的 Binder 线程查询使用。
     */
    fun getAllNotificationPackageNames(): List<String> =
        runCatching { notificationSyncDao.getAllPackageNames() }
            .onFailure { Log.e(TAG, "getAllNotificationPackageNames failed", it) }
            .getOrDefault(emptyList())

    /** 同步读取通知名单已选项（`packageName|userId`）。 */
    fun getAllNotificationKeys(): List<String> =
        getAllNotificationPackageNames().map { "$it|0" }

    // ========== 黑名单 ==========

    suspend fun insertBlacklist(packageName: String, userId: Int) =
        runWrite("insertBlacklist") {
            freeformBlacklistDao.insert(packageName, userId)
        }.also { if (it.isSuccess) bumpVersion(BlacklistProvider.KEY_BLACKLIST_VERSION) }

    suspend fun deleteBlacklist(packageName: String, userId: Int) =
        runWrite("deleteBlacklist") {
            freeformBlacklistDao.delete(packageName, userId)
        }.also { if (it.isSuccess) bumpVersion(BlacklistProvider.KEY_BLACKLIST_VERSION) }

    /** 整体替换黑名单（事务内先清空再写入）。 */
    suspend fun replaceBlacklist(list: List<FreeformBlacklistEntity>) =
        runWrite("replaceBlacklist") {
            database.withTransaction {
                freeformBlacklistDao.deleteAll()
                freeformBlacklistDao.insertLists(list)
            }
        }.also { if (it.isSuccess) bumpVersion(BlacklistProvider.KEY_BLACKLIST_VERSION) }

    suspend fun deleteAllBlacklist() =
        runWrite("deleteAllBlacklist") { freeformBlacklistDao.deleteAll() }
            .also { if (it.isSuccess) bumpVersion(BlacklistProvider.KEY_BLACKLIST_VERSION) }

    fun getAllBlacklist(): LiveData<List<FreeformBlacklistEntity>?> =
        freeformBlacklistDao.getAll()

    fun getAllBlacklistByFlow(): Flow<List<FreeformBlacklistEntity>?> =
        freeformBlacklistDao.getAllByFlow()

    /** 同步读取黑名单包名（去重），必须在后台线程调用。 */
    fun getAllBlacklistKeys(): List<String> =
        runCatching { freeformBlacklistDao.getAllWithUser() }
            .getOrDefault(emptyList())
            .map { "${it.packageName}|${it.userId}" }

    /** 同步查询，必须在后台线程或 Binder 线程调用。 */
    fun isBlacklisted(packageName: String, userId: Int): Boolean {
        return try {
            freeformBlacklistDao.count(packageName, userId) > 0
        } catch (e: Exception) {
            Log.e(TAG, "isBlacklisted failed: pkg=$packageName userId=$userId", e)
            false
        }
    }

    // ========== 名单变更通知（供其它进程的缓存失效）==========

    /**
     * 推进名单版本号。
     * SystemUI / launcher 等进程读取名单时会带版本号做缓存判断，
     * 版本号变化后它们的本地缓存立即失效，不必等 TTL 自然过期。
     * @param key [BlacklistProvider.KEY_BLACKLIST_VERSION] /
     *            [NotificationSettingsProvider.KEY_APP_LIST_VERSION]
     */
    fun bumpVersion(key: String) {
        val sp = app.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        sp.edit { putLong(key, sp.getLong(key, 0L) + 1) }
    }

    private suspend fun runWrite(tag: String, block: suspend () -> Unit): WriteReport {
        return withContext(Dispatchers.IO) {
            try {
                block()
                WriteReport()
            } catch (e: Exception) {
                Log.e(TAG, "$tag failed", e)
                WriteReport(e)
            }
        }
    }

    companion object {
        private const val TAG = "DatabaseRepository"
    }
}
