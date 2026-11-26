package com.example.messengerthreadblocker.data

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MessengerSilenceStoreTest {

    @Before
    fun setUp() {
        MessengerSilenceStore.resetForTesting()
    }

    @Test
    fun `tracks transitions from unknown to noisy to pending recheck to verified silent`() {
        assertEquals(MessengerSilenceStore.Status.UNKNOWN, MessengerSilenceStore.snapshot.value.status)

        MessengerSilenceStore.recordObserved(
            context = null,
            isSilent = false,
            channelName = "Chats & calls",
            channelId = "orca_messaging_channel",
            nowMillis = 1_000L
        )
        var snap = MessengerSilenceStore.snapshot.value
        assertEquals(MessengerSilenceStore.Status.DETECTED_NOISY, snap.status)
        assertEquals("Chats & calls", snap.channelName)
        assertEquals("orca_messaging_channel", snap.channelId)

        // User opens Messenger notification settings
        MessengerSilenceStore.markSettingsOpened(context = null, nowMillis = 2_000L)
        snap = MessengerSilenceStore.snapshot.value
        assertEquals(MessengerSilenceStore.Status.PENDING_RECHECK, snap.status)
        assertEquals("Chats & calls", snap.channelName)

        // Next Messenger message arrives silently
        MessengerSilenceStore.recordObserved(
            context = null,
            isSilent = true,
            channelName = "Chats & calls",
            channelId = "orca_messaging_channel",
            nowMillis = 3_000L
        )
        snap = MessengerSilenceStore.snapshot.value
        assertEquals(MessengerSilenceStore.Status.VERIFIED_SILENT, snap.status)

        // Re-opening settings after already verified silent keeps VERIFIED_SILENT
        MessengerSilenceStore.markSettingsOpened(context = null, nowMillis = 4_000L)
        assertEquals(MessengerSilenceStore.Status.VERIFIED_SILENT, MessengerSilenceStore.snapshot.value.status)
    }
}
