package com.example.messengerthreadblocker.service

/**
 * Deduplicates rapid in-place notification updates from Messenger (for example, when Messenger
 * posts a message and 100ms later updates the exact same notification to attach an avatar).
 *
 * Without deduplication, the second callback would see the message already stored in
 * GroupMessageStore, compare it against itself, and falsely block an allowed notification
 * (or re-alert twice).
 */
class NotificationDeduplicator(
    private val maxEntries: Int = 100
) {

    enum class State {
        IN_FLIGHT,
        BLOCKED,
        ALLOWED_REPOSTED,
        ALLOWED_NATIVE
    }

    private val entries = LinkedHashMap<String, State>(maxEntries, 0.75f, true)

    @Synchronized
    fun getState(fingerprint: String): State? = entries[fingerprint]

    /**
     * Marks [fingerprint] as IN_FLIGHT if not already tracked.
     *
     * @return null if newly marked IN_FLIGHT (caller should process), or the existing [State]
     * if this notification update was already seen.
     */
    @Synchronized
    fun tryBegin(fingerprint: String): State? {
        val existing = entries[fingerprint]
        if (existing != null) {
            return existing
        }
        putBounded(fingerprint, State.IN_FLIGHT)
        return null
    }

    @Synchronized
    fun markCompleted(fingerprint: String, finalState: State) {
        putBounded(fingerprint, finalState)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun putBounded(key: String, value: State) {
        entries[key] = value
        while (entries.size > maxEntries) {
            val oldestKey = entries.entries.iterator().next().key
            entries.remove(oldestKey)
        }
    }

    companion object {
        fun buildFingerprint(
            notificationKey: String,
            groupName: String,
            messageContent: String,
            messageTimestamp: Long
        ): String {
            return "$notificationKey|$groupName|$messageContent|$messageTimestamp"
        }
    }
}
