package com.example.messengerthreadblocker.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationDeduplicatorTest {

    @Test
    fun `deduplicates identical notification updates while in flight and after completion`() {
        val deduplicator = NotificationDeduplicator()
        val fp = NotificationDeduplicator.buildFingerprint(
            notificationKey = "0|com.facebook.orca|100|null|10123",
            groupName = "Family Chat",
            messageContent = "Alice: Dinner at 7?",
            messageTimestamp = 1_700_000_000_000L
        )

        // First arrival: newly marked IN_FLIGHT
        assertNull(deduplicator.tryBegin(fp))
        // Second arrival while Gemini call is running: returns IN_FLIGHT
        assertEquals(NotificationDeduplicator.State.IN_FLIGHT, deduplicator.tryBegin(fp))

        // After decision completes as BLOCKED, further duplicate updates return BLOCKED
        deduplicator.markCompleted(fp, NotificationDeduplicator.State.BLOCKED)
        assertEquals(NotificationDeduplicator.State.BLOCKED, deduplicator.tryBegin(fp))
    }

    @Test
    fun `allows same text when timestamp differs`() {
        val deduplicator = NotificationDeduplicator()
        val fp1 = NotificationDeduplicator.buildFingerprint(
            notificationKey = "0|com.facebook.orca|100|null|10123",
            groupName = "Family Chat",
            messageContent = "Bob: ok",
            messageTimestamp = 1_700_000_000_000L
        )
        val fp2 = NotificationDeduplicator.buildFingerprint(
            notificationKey = "0|com.facebook.orca|100|null|10123",
            groupName = "Family Chat",
            messageContent = "Bob: ok",
            messageTimestamp = 1_700_000_005_000L
        )

        assertNull(deduplicator.tryBegin(fp1))
        deduplicator.markCompleted(fp1, NotificationDeduplicator.State.ALLOWED_REPOSTED)

        // Distinct message sent 5 seconds later should not be deduplicated
        assertNull(deduplicator.tryBegin(fp2))
    }

    @Test
    fun `evicts oldest entries when capacity is exceeded`() {
        val deduplicator = NotificationDeduplicator(maxEntries = 2)
        assertNull(deduplicator.tryBegin("fp-1"))
        assertNull(deduplicator.tryBegin("fp-2"))
        assertNull(deduplicator.tryBegin("fp-3"))

        // fp-1 was evicted, fp-2 and fp-3 remain
        assertNull(deduplicator.getState("fp-1"))
        assertEquals(NotificationDeduplicator.State.IN_FLIGHT, deduplicator.getState("fp-2"))
        assertEquals(NotificationDeduplicator.State.IN_FLIGHT, deduplicator.getState("fp-3"))
    }
}
