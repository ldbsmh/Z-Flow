package io.relimus.zflow.room

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * @author sunshine
 * @date 2021/1/31
 */
@Dao
interface FreeFormAppsDao {

    /** 重复 (packageName, userId) 视为无操作，不抛主键冲突。 */
    @Query("INSERT OR IGNORE INTO FreeFormAppsEntity(packageName, userId) VALUES(:packageName, :userId)")
    fun insert(packageName: String, userId: Int)

    @Query("DELETE FROM FreeFormAppsEntity WHERE packageName = :packageName and userId = :userId")
    fun delete(packageName: String, userId: Int)

    @Query("SELECT * FROM FreeFormAppsEntity")
    fun getAll() : LiveData<List<FreeFormAppsEntity>?>

    @Query("SELECT * FROM FreeFormAppsEntity")
    fun getAllByFlow() : Flow<List<FreeFormAppsEntity>?>

    @Query("SELECT sortNum, packageName, userId FROM FreeFormAppsEntity")
    fun getAllWithUser(): List<FreeFormAppsEntity>

    @Query("SELECT packageName FROM FreeFormAppsEntity")
    fun getAllName() : LiveData<List<String>?>

    @Query("SELECT packageName FROM FreeFormAppsEntity")
    fun getAllWithoutLiveData() : List<String>?

    @Query("SELECT COUNT(*) FROM FreeFormAppsEntity")
    fun getCount(): Int

    @Query("DELETE FROM FreeFormAppsEntity")
    fun deleteAll()

    @Delete
    fun deleteList(freeFormAppsEntityList: List<FreeFormAppsEntity>)

    @Update
    fun update(entity: FreeFormAppsEntity)

    @Update
    fun updateList(list: List<FreeFormAppsEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEntities(list: List<FreeFormAppsEntity>)

    /** 排序第一阶段：把指定行的主键挪到负数区，避免第二阶段互相撞主键。 */
    @Query("UPDATE FreeFormAppsEntity SET sortNum = -1 - sortNum WHERE sortNum >= 0")
    fun parkSortKeys()

    /** 排序第二阶段：把某一行的主键写成目标序号。 */
    @Query("UPDATE FreeFormAppsEntity SET sortNum = :sortNum WHERE packageName = :packageName AND userId = :userId")
    fun assignSortNum(packageName: String, userId: Int, sortNum: Int)

    @Query("SELECT sortNum FROM FreeFormAppsEntity")
    fun getAllSortNums(): List<Int>

    /** 批量写入用（需在事务中调用）。 */
    fun insertLists(list: List<FreeFormAppsEntity>) {
        list.forEach { insert(it.packageName, it.userId) }
    }
}
