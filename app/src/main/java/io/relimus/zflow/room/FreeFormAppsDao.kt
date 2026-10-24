package io.relimus.zflow.room

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * @author sunshine
 * @date 2021/1/31
 */
@Dao
interface FreeFormAppsDao {

    /** 重复 (packageName, userId) 视为无操作，不抛主键冲突。 */
    @Query("INSERT OR IGNORE INTO FreeFormAppsEntity(packageName, userId) SELECT :packageName, :userId WHERE NOT EXISTS (SELECT 1 FROM FreeFormAppsEntity WHERE packageName = :packageName AND userId = :userId)")
    fun insert(packageName: String, userId: Int)

    @Query("DELETE FROM FreeFormAppsEntity WHERE packageName = :packageName and userId = :userId")
    fun delete(packageName: String, userId: Int)

    @Query("SELECT * FROM FreeFormAppsEntity ORDER BY sortNum ASC")
    fun getAll() : LiveData<List<FreeFormAppsEntity>?>

    @Query("SELECT * FROM FreeFormAppsEntity ORDER BY sortNum ASC")
    fun getAllByFlow() : Flow<List<FreeFormAppsEntity>?>

    @Query("SELECT id, sortNum, packageName, userId FROM FreeFormAppsEntity ORDER BY sortNum ASC")
    fun getAllWithUser(): List<FreeFormAppsEntity>

    @Query("DELETE FROM FreeFormAppsEntity")
    fun deleteAll()

    @Delete
    fun deleteList(freeFormAppsEntityList: List<FreeFormAppsEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEntities(list: List<FreeFormAppsEntity>)

    /** 排序第一阶段：把指定行的主键挪到负数区，避免第二阶段互相撞主键。 */
    @Query("UPDATE FreeFormAppsEntity SET sortNum = -1 - sortNum WHERE sortNum >= 0")
    fun parkSortKeys()

    /** 排序第二阶段：把某一行的主键写成目标序号。 */
    @Query("UPDATE FreeFormAppsEntity SET sortNum = :sortNum WHERE packageName = :packageName AND userId = :userId")
    fun assignSortNum(packageName: String, userId: Int, sortNum: Int)
}
