package com.example.messengerthreadblocker.api

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiDecisionClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GeminiDecisionClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GeminiDecisionClient(baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `blocks when same_thread probability is greater than 50 percent`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "answers": {
                        "same_thread": {
                          "confidence": 0.87,
                          "noul": 0.87,
                          "type": "noul"
                        }
                      },
                      "usage": {
                        "total_tokens": 120,
                        "total_input_tokens": 120,
                        "total_output_tokens": 0
                      }
                    }
                    """.trimIndent()
                )
        )

        val result = client.evaluateSameThread(
            apiKey = "test-api-key",
            groupName = "Weekend Trip",
            previousMessages = listOf("Alex: Meet at 8?", "Sam: Yes 8am works"),
            newMessage = "Jordan: See you at 8!"
        )

        assertTrue(result.isSuccess)
        val decision = result.getOrThrow()
        assertEquals(0.87, decision.probability, 0.0001)
        assertTrue(decision.isSameThread)

        val recorded = server.takeRequest()
        assertEquals("/v1beta/decisions", recorded.path)
        assertEquals("test-api-key", recorded.getHeader("x-goog-api-key"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"gemini-decision-1\""))
        assertTrue(body.contains("\"type\":\"noul\""))
        assertTrue(body.contains("Alex: Meet at 8?"))
        assertTrue(body.contains("Jordan: See you at 8!"))
    }

    @Test
    fun `allows when same_thread probability is 50 percent or less`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "answers": {
                        "same_thread": {
                          "confidence": 0.50,
                          "noul": 0.50,
                          "type": "noul"
                        }
                      }
                    }
                    """.trimIndent()
                )
        )

        val result = client.evaluateSameThread(
            apiKey = "test-api-key",
            groupName = "Weekend Trip",
            previousMessages = listOf("Alex: Meet at 8?"),
            newMessage = "Sam: Did you see the game last night?"
        )

        assertTrue(result.isSuccess)
        val decision = result.getOrThrow()
        assertEquals(0.50, decision.probability, 0.0001)
        assertFalse(decision.isSameThread)
    }
}
