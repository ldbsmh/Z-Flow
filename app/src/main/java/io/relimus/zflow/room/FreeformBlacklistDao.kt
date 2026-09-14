package io.relimus.zflow.room

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FreeformBlacklistDao {

    @Query("INSERT INTO FreeformBlacklistEntity(packageName, userId) VALUES(:packageName, :userId)")
    fun insert(packageName: String, userId: Int)

    @Query("DELETE FROM FreeformBlacklistEntity WHERE packageName = :packageName AND userId = :userId")
    fun delete(packageName: String, userId: Int)

    @Query("SELECT * FROM FreeformBlacklistEntity")
    fun getAll(): LiveData<List<FreeformBlacklistEntity>?>

    @Query("SELECT * FROM FreeformBlacklistEntity")
    fun getAllByFlow(): Flow<List<FreeformBlacklistEntity>?>

    @Query("SELECT * FROM FreeformBlacklistEntity")
    fun getAllWithoutLiveData(): List<FreeformBlacklistEntity>

    @Query("SELECT * FROM FreeformBlacklistEntity WHERE packageName = :packageName AND userId = :userId LIMIT 1")
    fun findOne(packageName: String, userId: Int): FreeformBlacklistEntity?

    @Query("SELECT COUNT(*) FROM FreeformBlacklistEntity WHERE packageName = :packageName AND userId = :userId")
    fun count(packageName: String, userId: Int): Int

    @Query("DELETE FROM FreeformBlacklistEntity")
    fun deleteAll()

    @Delete
    fun deleteList(list: List<FreeformBlacklistEntity>)
}