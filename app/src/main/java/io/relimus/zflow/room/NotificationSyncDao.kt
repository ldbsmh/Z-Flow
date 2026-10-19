package io.relimus.zflow.room

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Query

/**
 * 通知接管名单的同步读取接口。
 *
 * 与 [NotificationAppsDao] 分开是因为 Room 的 Flow / LiveData 返回值只能在
 * 带事务性协程环境里观察；SystemUI 等其它进程的 Binder 线程只需要一次同步查询。
 */
@Dao
interface NotificationSyncDao {

    @Query("SELECT DISTINCT packageName FROM NotificationAppsEntity")
    fun getAllPackageNames(): List<String>

    @Query("SELECT * FROM NotificationAppsEntity")
    fun getAllWithoutLiveData(): List<NotificationAppsEntity>
}
