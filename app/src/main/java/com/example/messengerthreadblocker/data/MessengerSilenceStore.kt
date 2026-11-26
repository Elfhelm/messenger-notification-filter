package com.example.messengerthreadblocker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether Messenger's own notification channel has been silenced by the user,
 * based on live observations from MessengerNotificationListenerService.
 *
 * Persisted via SharedPreferences and exposed as a process-wide StateFlow so MainActivity
 * immediately reflects status changes observed by the listener service.
 */
object MessengerSilenceStore {

    private const val PREFS_NAME = "messenger_silence_prefs"
    private const val KEY_STATUS = "silence_status"
    private const val KEY_CHANNEL_NAME = "channel_name"
    private const val KEY_CHANNEL_ID = "channel_id"
    private const val KEY_UPDATED_AT = "updated_at"

    enum class Status {
        /** No Messenger notification observed yet. */
        UNKNOWN,
        /** User opened Messenger notification settings; waiting for next message to confirm. */
        PENDING_RECHECK,
        /** Latest observed Messenger notification arrived silently (no buzz/sound). */
        VERIFIED_SILENT,
        /** Latest observed Messenger notification arrived on a buzzing/sounding channel. */
        DETECTED_NOISY
    }

    data class Snapshot(
        val status: Status = Status.UNKNOWN,
        val channelName: String? = null,
        val channelId: String? = null,
        val updatedAtMillis: Long = 0L
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context?) {
        if (loaded || context == null) return
        val prefs = getPrefs(context)
        val statusName = prefs.getString(KEY_STATUS, Status.UNKNOWN.name)
        val parsedStatus = runCatching { Status.valueOf(statusName ?: Status.UNKNOWN.name) }
            .getOrDefault(Status.UNKNOWN)
        _snapshot.value = Snapshot(
            status = parsedStatus,
            channelName = prefs.getString(KEY_CHANNEL_NAME, null),
            channelId = prefs.getString(KEY_CHANNEL_ID, null),
            updatedAtMillis = prefs.getLong(KEY_UPDATED_AT, 0L)
        )
        loaded = true
    }

    @Synchronized
    fun recordObserved(
        context: Context?,
        isSilent: Boolean,
        channelName: String?,
        channelId: String?,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        ensureLoaded(context)
        val previous = _snapshot.value
        val updated = Snapshot(
            status = if (isSilent) Status.VERIFIED_SILENT else Status.DETECTED_NOISY,
            channelName = channelName ?: previous.channelName,
            channelId = channelId ?: previous.channelId,
            updatedAtMillis = nowMillis
        )
        _snapshot.value = updated
        save(context, updated)
    }

    @Synchronized
    fun markSettingsOpened(
        context: Context?,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        ensureLoaded(context)
        val current = _snapshot.value
        if (current.status == Status.DETECTED_NOISY || current.status == Status.UNKNOWN) {
            val updated = current.copy(
                status = Status.PENDING_RECHECK,
                updatedAtMillis = nowMillis
            )
            _snapshot.value = updated
            save(context, updated)
        }
    }

    @Synchronized
    internal fun resetForTesting() {
        loaded = false
        _snapshot.value = Snapshot()
    }

    private fun save(context: Context?, snapshot: Snapshot) {
        if (context == null) return
        getPrefs(context).edit()
            .putString(KEY_STATUS, snapshot.status.name)
            .putString(KEY_CHANNEL_NAME, snapshot.channelName)
            .putString(KEY_CHANNEL_ID, snapshot.channelId)
            .putLong(KEY_UPDATED_AT, snapshot.updatedAtMillis)
            .apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
