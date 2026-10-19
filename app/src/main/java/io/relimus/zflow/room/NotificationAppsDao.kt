package io.relimus.zflow.room

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * @author sunshine
 * @date 2021/1/31
 */
@Dao
interface NotificationAppsDao {

    /** 重复 (packageName, userId) 视为无操作，不抛主键冲突。 */
    @Query("INSERT OR IGNORE INTO NotificationAppsEntity(packageName, userId) VALUES(:packageName, :userId)")
    fun insert(packageName: String, userId: Int)

    @Query("DELETE FROM NotificationAppsEntity WHERE packageName = :packageName and userId = :userId")
    fun delete(packageName: String, userId: Int)

    @Query("SELECT * FROM NotificationAppsEntity")
    fun getAll() : LiveData<List<NotificationAppsEntity>?>

    @Query("SELECT * FROM NotificationAppsEntity")
    fun getAllByFlow() : Flow<List<NotificationAppsEntity>?>

    @Query("SELECT COUNT(*) FROM NotificationAppsEntity WHERE packageName = :packageName AND userId = :userId")
    fun count(packageName: String, userId: Int): Int

    @Query("DELETE FROM NotificationAppsEntity")
    fun deleteAll()

    /** 批量写入用（需在事务中调用）。 */
    fun insertLists(list: List<NotificationAppsEntity>) {
        list.forEach { insert(it.packageName, it.userId) }
    }
}
