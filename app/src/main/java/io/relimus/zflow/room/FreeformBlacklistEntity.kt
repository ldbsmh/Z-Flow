package io.relimus.zflow.room

import androidx.room.Entity

@Entity(primaryKeys = ["packageName", "userId"])
class FreeformBlacklistEntity(
    var packageName: String,
    var userId: Int = 0
) {
    override fun toString(): String {
        return "FreeformBlacklistEntity(packageName='$packageName', userId=$userId)"
    }
}