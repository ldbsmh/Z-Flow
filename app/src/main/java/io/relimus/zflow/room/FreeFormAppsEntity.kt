package io.relimus.zflow.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * @author sunshine
 * @date 2021/1/31
 * 开启小窗数据库实体类
 *
 * [sortNum] 是自增主键，只作排序用，不承载业务含义。
 * 需要调整顺序时不要直接改它（主键不可变，且交换会撞主键），
 * 而是用 [DatabaseRepository.updateFreeFormSort]：它在单个事务里
 * 先挪到负数、再写回正数，把整份顺序一次性落库。
 */
@Entity
class FreeFormAppsEntity(
    //用于排序
    @PrimaryKey(autoGenerate = true)
    val sortNum: Int,
    var packageName: String,
    //20210604新增用户标识
    var userId: Int = 0
) {
    override fun toString(): String {
        return "FreeFormAppsEntity(sortNum=$sortNum, packageName='$packageName', userId=$userId)"
    }
}
