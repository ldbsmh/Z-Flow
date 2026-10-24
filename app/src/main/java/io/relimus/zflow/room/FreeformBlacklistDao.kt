package io.relimus.zflow.room

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FreeformBlacklistDao {

    @Query("INSERT OR IGNORE INTO FreeformBlacklistEntity(packageName, userId) VALUES(:packageName, :userId)")
    fun insert(packageName: String, userId: Int)

    @Query("DELETE FROM FreeformBlacklistEntity WHERE packageName = :packageName AND userId = :userId")
    fun delete(packageName: String, userId: Int)

    @Query("SELECT * FROM FreeformBlacklistEntity")
    fun getAll(): LiveData<List<FreeformBlacklistEntity>?>

    @Query("SELECT * FROM FreeformBlacklistEntity")
    fun getAllByFlow(): Flow<List<FreeformBlacklistEntity>?>

    @Query("SELECT packageName, userId FROM FreeformBlacklistEntity")
    fun getAllWithUser(): List<FreeformBlacklistEntity>

    @Query("SELECT COUNT(*) FROM FreeformBlacklistEntity WHERE packageName = :packageName AND userId = :userId")
    fun count(packageName: String, userId: Int): Int

    @Query("DELETE FROM FreeformBlacklistEntity")
    fun deleteAll()

    /** 批量写入用（需在事务中调用）。 */
    fun insertLists(list: List<FreeformBlacklistEntity>) {
        list.forEach { insert(it.packageName, it.userId) }
    }
}
