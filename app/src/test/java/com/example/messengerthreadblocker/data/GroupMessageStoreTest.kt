package com.example.messengerthreadblocker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupMessageStoreTest {

    @Test
    fun `stores up to 10 messages per group and clears oldest when exceeded`() {
        val store = GroupMessageStore()
        val group = "Family Chat"

        // Add 10 messages
        for (i in 1..10) {
            val prev = store.recordAndGetPrevious(group, "msg-$i")
            assertEquals(i - 1, prev.size)
        }

        assertEquals(
            (1..10).map { "msg-$it" },
            store.getMessages(group)
        )

        // Add 11th message: previous should be msg-1..msg-10, and msg-1 should be cleared out
        val prevBefore11 = store.recordAndGetPrevious(group, "msg-11")
        assertEquals((1..10).map { "msg-$it" }, prevBefore11)

        assertEquals(
            (2..11).map { "msg-$it" },
            store.getMessages(group)
        )
    }

    @Test
    fun `maintains independent 10-element arrays per group in hash table`() {
        val store = GroupMessageStore()

        store.recordAndGetPrevious("Group A", "A1")
        store.recordAndGetPrevious("Group B", "B1")
        store.recordAndGetPrevious("Group A", "A2")

        assertEquals(listOf("A1", "A2"), store.getMessages("Group A"))
        assertEquals(listOf("B1"), store.getMessages("Group B"))
        assertEquals(2, store.groupCount())
    }
}
