package io.relimus.zflow.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * @author sunshine
 * @date 2021/1/31
 * 开启小窗数据库实体类
 *
 * [id] 是稳定自增主键，不承载业务含义；[sortNum] 只作排序用。
 * 需要调整顺序时使用 [DatabaseRepository.updateFreeFormSort]：它在单个
 * 事务里先校验快照未过期，再整体写入新顺序，避免主键与排序互相覆盖。
 */
@Entity
class FreeFormAppsEntity(
    // 稳定主键：迁移 7->8 后由原 sortNum 值继承而来，新行自增
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    // 排序序号（非主键，可自由更新）
    var sortNum: Int = 0,
    var packageName: String,
    //20210604新增用户标识
    var userId: Int = 0
) {
    override fun toString(): String {
        return "FreeFormAppsEntity(id=$id, sortNum=$sortNum, packageName='$packageName', userId=$userId)"
    }
}
