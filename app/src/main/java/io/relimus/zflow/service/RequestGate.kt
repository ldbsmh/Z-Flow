package io.relimus.zflow.service

/** Atomic admission and completion; rejected duplicates never acquire an in-flight slot. */
internal class RequestGate(private val maxRecent: Int = 256) {
    private val inFlight = mutableSetOf<String>()
    private val recent = LinkedHashMap<String, Long>()

    @Synchronized
    fun acquire(key: String, now: Long, windowMs: Long): Boolean {
        if (key in inFlight) return false
        val last = recent[key]
        if (last != null && now - last < windowMs) return false
        inFlight.add(key)
        recent.remove(key)
        recent[key] = now
        while (recent.size > maxRecent) recent.remove(recent.keys.first())
        return true
    }

    @Synchronized
    fun complete(key: String, retryable: Boolean = false) {
        inFlight.remove(key)
        if (retryable) recent.remove(key)
    }

    companion object {
        // Window manager coalesces by package and user, not by Activity or task ID.
        fun key(packageName: String, userId: Int): String = "$packageName|$userId"
    }
}
