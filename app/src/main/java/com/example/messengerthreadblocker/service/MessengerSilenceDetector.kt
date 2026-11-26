package com.example.messengerthreadblocker.service

import android.app.NotificationManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Determines whether an incoming Messenger notification was delivered silently by Android
 * or whether Messenger's own notification channel is still configured to buzz / play sound.
 *
 * On Android, NotificationListenerService.onNotificationPosted runs AFTER the OS has already
 * triggered sound and vibration for the posting app's channel. Therefore, to prevent blocked
 * Messenger notifications from buzzing, Messenger's chat channel must be set to Silent, and
 * this app re-alerts with sound/vibration only for ALLOWED notifications.
 */
object MessengerSilenceDetector {

    internal const val RECENT_ALERT_WINDOW_MS = 5_000L

    enum class SilenceState {
        /** Messenger's notification arrived without sound or vibration. */
        SILENT,
        /** Messenger's notification channel is still configured to buzz and/or play sound. */
        NOISY,
        /** Unable to determine channel settings from the ranking map. */
        UNKNOWN
    }

    data class DetectionResult(
        val state: SilenceState,
        val channelId: String?,
        val channelName: String?
    )

    fun inspect(
        sbn: StatusBarNotification,
        rankingMap: NotificationListenerService.RankingMap?,
        nowMillis: Long = System.currentTimeMillis()
    ): DetectionResult {
        val fallbackChannelId = sbn.notification?.channelId
        if (rankingMap == null) {
            return DetectionResult(
                state = SilenceState.UNKNOWN,
                channelId = fallbackChannelId,
                channelName = null
            )
        }

        val ranking = NotificationListenerService.Ranking()
        val hasRanking = rankingMap.getRanking(sbn.key, ranking)
        if (!hasRanking) {
            return DetectionResult(
                state = SilenceState.UNKNOWN,
                channelId = fallbackChannelId,
                channelName = null
            )
        }

        val channel = ranking.channel
        val channelId = channel?.id ?: fallbackChannelId
        val channelName = channel?.name?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val lastAudiblyAlertedMillis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ranking.lastAudiblyAlertedMillis
        } else {
            -1L
        }

        val state = evaluateChannelSilence(
            rankingImportance = ranking.importance,
            channelImportance = channel?.importance,
            hasSound = channel?.sound != null,
            shouldVibrate = channel?.shouldVibrate() ?: false,
            lastAudiblyAlertedMillis = lastAudiblyAlertedMillis,
            nowMillis = nowMillis
        )

        return DetectionResult(
            state = state,
            channelId = channelId,
            channelName = channelName
        )
    }

    /**
     * Pure evaluation logic separated from Android framework types for unit testing.
     */
    internal fun evaluateChannelSilence(
        rankingImportance: Int?,
        channelImportance: Int?,
        hasSound: Boolean,
        shouldVibrate: Boolean,
        lastAudiblyAlertedMillis: Long,
        nowMillis: Long
    ): SilenceState {
        // On API 29+, if Android literally just audibly/haptically alerted for this notification,
        // we know for certain it buzzed or played sound.
        if (lastAudiblyAlertedMillis > 0L) {
            val elapsed = nowMillis - lastAudiblyAlertedMillis
            if (elapsed in 0L..RECENT_ALERT_WINDOW_MS) {
                return SilenceState.NOISY
            }
        }

        // Prefer inspecting the actual NotificationChannel configuration when available,
        // since this accurately detects noisy channels even when the phone is temporarily in DND.
        if (channelImportance != null) {
            if (channelImportance <= NotificationManager.IMPORTANCE_LOW) {
                return SilenceState.SILENT
            }
            // Some OEMs (e.g., Samsung One UI) keep importance at DEFAULT/HIGH while letting the
            // user turn both Sound = None and Vibrate = Off.
            if (!hasSound && !shouldVibrate) {
                return SilenceState.SILENT
            }
            return SilenceState.NOISY
        }

        if (rankingImportance != null && rankingImportance != NotificationManager.IMPORTANCE_UNSPECIFIED) {
            return if (rankingImportance <= NotificationManager.IMPORTANCE_LOW) {
                SilenceState.SILENT
            } else {
                SilenceState.NOISY
            }
        }

        return SilenceState.UNKNOWN
    }
}
