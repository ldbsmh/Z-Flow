package io.relimus.zflow.xposed.services

/** Remove only this instance, never an equal object or one sharing its display ID. */
internal fun <T : Any> MutableList<T>.removeWindowIdentity(instance: T): Boolean {
    val index = indexOfFirst { it === instance }
    if (index < 0) return false
    removeAt(index)
    return true
}
