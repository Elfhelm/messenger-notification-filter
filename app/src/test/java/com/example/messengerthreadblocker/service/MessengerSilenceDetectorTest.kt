package com.example.messengerthreadblocker.service

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Test

class MessengerSilenceDetectorTest {

    @Test
    fun `detects silent when channel importance is LOW or MIN`() {
        assertEquals(
            MessengerSilenceDetector.SilenceState.SILENT,
            MessengerSilenceDetector.evaluateChannelSilence(
                rankingImportance = NotificationManager.IMPORTANCE_LOW,
                channelImportance = NotificationManager.IMPORTANCE_LOW,
                hasSound = true,
                shouldVibrate = true,
                lastAudiblyAlertedMillis = -1L,
                nowMillis = 10_000L
            )
        )

        assertEquals(
            MessengerSilenceDetector.SilenceState.SILENT,
            MessengerSilenceDetector.evaluateChannelSilence(
                rankingImportance = NotificationManager.IMPORTANCE_MIN,
                channelImportance = NotificationManager.IMPORTANCE_MIN,
                hasSound = false,
                shouldVibrate = false,
                lastAudiblyAlertedMillis = -1L,
                nowMillis = 10_000L
            )
        )
    }

    @Test
    fun `detects silent when channel is DEFAULT but sound and vibration are both disabled`() {
        assertEquals(
            MessengerSilenceDetector.SilenceState.SILENT,
            MessengerSilenceDetector.evaluateChannelSilence(
                rankingImportance = NotificationManager.IMPORTANCE_DEFAULT,
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
                hasSound = false,
                shouldVibrate = false,
                lastAudiblyAlertedMillis = -1L,
                nowMillis = 10_000L
            )
        )
    }

    @Test
    fun `detects noisy when channel is HIGH or DEFAULT with sound or vibration enabled`() {
        assertEquals(
            MessengerSilenceDetector.SilenceState.NOISY,
            MessengerSilenceDetector.evaluateChannelSilence(
                rankingImportance = NotificationManager.IMPORTANCE_HIGH,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                hasSound = true,
                shouldVibrate = true,
                lastAudiblyAlertedMillis = -1L,
                nowMillis = 10_000L
            )
        )

        // Vibration only (no sound) still buzzes!
        assertEquals(
            MessengerSilenceDetector.SilenceState.NOISY,
            MessengerSilenceDetector.evaluateChannelSilence(
                rankingImportance = NotificationManager.IMPORTANCE_DEFAULT,
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
                hasSound = false,
                shouldVibrate = true,
                lastAudiblyAlertedMillis = -1L,
                nowMillis = 10_000L
            )
        )

        // Sound only (no vibration) still plays sound!
        assertEquals(
            MessengerSilenceDetector.SilenceState.NOISY,
            MessengerSilenceDetector.evaluateChannelSilence(
                rankingImportance = NotificationManager.IMPORTANCE_DEFAULT,
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
                hasSound = true,
                shouldVibrate = false,
                lastAudiblyAlertedMillis = -1L,
                nowMillis = 10_000L
            )
        )
    }

    @Test
    fun `detects noisy when notification audibly alerted within recent window`() {
        assertEquals(
            MessengerSilenceDetector.SilenceState.NOISY,
            MessengerSilenceDetector.evaluateChannelSilence(
                rankingImportance = NotificationManager.IMPORTANCE_LOW,
                channelImportance = NotificationManager.IMPORTANCE_LOW,
                hasSound = false,
                shouldVibrate = false,
                lastAudiblyAlertedMillis = 9_500L,
                nowMillis = 10_000L
            )
        )
    }

    @Test
    fun `ignores stale lastAudiblyAlertedMillis older than recent window`() {
        assertEquals(
            MessengerSilenceDetector.SilenceState.SILENT,
            MessengerSilenceDetector.evaluateChannelSilence(
                rankingImportance = NotificationManager.IMPORTANCE_LOW,
                channelImportance = NotificationManager.IMPORTANCE_LOW,
                hasSound = false,
                shouldVibrate = false,
                lastAudiblyAlertedMillis = 1_000L,
                nowMillis = 10_000L
            )
        )
    }

    @Test
    fun `falls back to ranking importance when channel is unavailable`() {
        assertEquals(
            MessengerSilenceDetector.SilenceState.SILENT,
            MessengerSilenceDetector.evaluateChannelSilence(
                rankingImportance = NotificationManager.IMPORTANCE_LOW,
                channelImportance = null,
                hasSound = false,
                shouldVibrate = false,
                lastAudiblyAlertedMillis = -1L,
                nowMillis = 10_000L
            )
        )

        assertEquals(
            MessengerSilenceDetector.SilenceState.NOISY,
            MessengerSilenceDetector.evaluateChannelSilence(
                rankingImportance = NotificationManager.IMPORTANCE_HIGH,
                channelImportance = null,
                hasSound = false,
                shouldVibrate = false,
                lastAudiblyAlertedMillis = -1L,
                nowMillis = 10_000L
            )
        )

        assertEquals(
            MessengerSilenceDetector.SilenceState.UNKNOWN,
            MessengerSilenceDetector.evaluateChannelSilence(
                rankingImportance = NotificationManager.IMPORTANCE_UNSPECIFIED,
                channelImportance = null,
                hasSound = false,
                shouldVibrate = false,
                lastAudiblyAlertedMillis = -1L,
                nowMillis = 10_000L
            )
        )
    }
}
