package com.example.messengerthreadblocker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thread-safe hash table indexed by Messenger group name, where each entry is a
 * fixed 10-element array storing the most recent messages for that group.
 *
 * When a new message arrives for a group whose 10-element array is already full,
 * the oldest entry is cleared out to make room for the new message.
 */
class GroupMessageStore(context: Context? = null) {

    companion object {
        const val MAX_MESSAGES_PER_GROUP = 10
        private const val PREFS_NAME = "group_message_store_prefs"
        private const val KEY_HISTORY_JSON = "group_history_json"
    }

    /**
     * Fixed 10-element array wrapper that maintains chronological order
     * and clears out the oldest entry when full.
     */
    class TenElementMessageArray {
        internal val messages: Array<String?> = arrayOfNulls(MAX_MESSAGES_PER_GROUP)
        private var count: Int = 0

        /**
         * Stores [message] in the 10-element array, clearing out the oldest
         * entry (index 0) if the array already contains 10 elements.
         */
        fun add(message: String) {
            if (count < MAX_MESSAGES_PER_GROUP) {
                messages[count] = message
                count++
            } else {
                // Clear out the oldest entry at index 0 by shifting elements left
                messages[0] = null
                for (i in 1 until MAX_MESSAGES_PER_GROUP) {
                    messages[i - 1] = messages[i]
                }
                messages[MAX_MESSAGES_PER_GROUP - 1] = message
            }
        }

        /**
         * Returns all currently stored messages in chronological order (oldest first).
         */
        fun toList(): List<String> {
            val result = ArrayList<String>(count)
            for (i in 0 until count) {
                messages[i]?.let { result.add(it) }
            }
            return result
        }

        fun size(): Int = count
    }

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Hash table indexed by the name of the group -> 10-element array of messages
    private val groupTable: HashMap<String, TenElementMessageArray> = HashMap()

    init {
        loadFromPrefs()
    }

    /**
     * Atomically snapshots up to 10 previous saved messages for [groupName] and then
     * stores [newMessage] in the 10-element array for [groupName], clearing out the
     * oldest entry if 10 entries already exist.
     *
     * @return The list of previous messages (oldest to newest) before [newMessage] was added.
     */
    @Synchronized
    fun recordAndGetPrevious(groupName: String, newMessage: String): List<String> {
        val entry = groupTable.getOrPut(groupName) { TenElementMessageArray() }
        val previous = entry.toList()
        entry.add(newMessage)
        saveToPrefs()
        return previous
    }

    /**
     * Returns the current saved messages (up to 10) for [groupName].
     */
    @Synchronized
    fun getMessages(groupName: String): List<String> {
        return groupTable[groupName]?.toList() ?: emptyList()
    }

    /**
     * Returns the number of groups currently tracked in the hash table.
     */
    @Synchronized
    fun groupCount(): Int = groupTable.size

    /**
     * Returns a snapshot of all group names and their saved message counts.
     */
    @Synchronized
    fun getGroupSummaries(): Map<String, Int> {
        return groupTable.mapValues { it.value.size() }
    }

    @Synchronized
    fun clearAll() {
        groupTable.clear()
        prefs?.edit()?.remove(KEY_HISTORY_JSON)?.apply()
    }

    private fun saveToPrefs() {
        val prefs = prefs ?: return
        val root = JSONObject()
        for ((group, array) in groupTable) {
            val jsonArray = JSONArray()
            for (msg in array.toList()) {
                jsonArray.put(msg)
            }
            root.put(group, jsonArray)
        }
        prefs.edit().putString(KEY_HISTORY_JSON, root.toString()).apply()
    }

    private fun loadFromPrefs() {
        val raw = prefs?.getString(KEY_HISTORY_JSON, null) ?: return
        try {
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val group = keys.next()
                val jsonArray = root.optJSONArray(group) ?: continue
                val entry = TenElementMessageArray()
                for (i in 0 until jsonArray.length()) {
                    val msg = jsonArray.optString(i, "")
                    if (msg.isNotEmpty()) {
                        entry.add(msg)
                    }
                }
                if (entry.size() > 0) {
                    groupTable[group] = entry
                }
            }
        } catch (_: Exception) {
            // Ignore corrupted persisted cache
        }
    }
}
