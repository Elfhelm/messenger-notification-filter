package com.example.messengerthreadblocker.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DecisionLogEntry(
    val timestamp: String,
    val groupName: String,
    val messagePreview: String,
    val probabilityPercent: Int?,
    val blocked: Boolean,
    val detail: String
)

/**
 * In-memory log of recent Messenger notification decisions for display in MainActivity.
 */
object DecisionLogStore {
    private const val MAX_ENTRIES = 30
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _entries = MutableStateFlow<List<DecisionLogEntry>>(emptyList())
    val entries: StateFlow<List<DecisionLogEntry>> = _entries.asStateFlow()

    @Synchronized
    fun addEntry(
        groupName: String,
        messagePreview: String,
        probability: Double?,
        blocked: Boolean,
        detail: String
    ) {
        val percent = probability?.let { (it * 100.0).toInt().coerceIn(0, 100) }
        val entry = DecisionLogEntry(
            timestamp = timeFormat.format(Date()),
            groupName = groupName,
            messagePreview = messagePreview.take(100),
            probabilityPercent = percent,
            blocked = blocked,
            detail = detail
        )
        val updated = listOf(entry) + _entries.value.take(MAX_ENTRIES - 1)
        _entries.value = updated
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }
}
