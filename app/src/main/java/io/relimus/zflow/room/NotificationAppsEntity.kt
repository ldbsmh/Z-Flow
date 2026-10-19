package io.relimus.zflow.room

import androidx.room.Entity

/**
 * @author sunshine
 * @date 2021/1/31
 * 开启小窗数据库实体类
 *
 * 主键为 (packageName, userId)：同一包名在不同 Android 用户 / 工作资料下
 * 可以分别保存，与 [FreeformBlacklistEntity] 保持一致。
 */
@Entity(primaryKeys = ["packageName", "userId"])
class NotificationAppsEntity(
    var packageName: String,
    //20210604新增用户标识
    var userId: Int = 0
) {
    override fun toString(): String {
        return "NotificationAppsEntity(packageName='$packageName', userId=$userId)"
    }
}
