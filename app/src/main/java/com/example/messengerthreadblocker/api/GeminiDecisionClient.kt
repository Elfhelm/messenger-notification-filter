package com.example.messengerthreadblocker.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the Gemini Decisions API (POST /v1beta/decisions, model: gemini-decision-1).
 * Evaluates whether an incoming Messenger message is part of the same thread of discussion
 * as up to 10 previous saved messages in the same group, returning a calibrated probability.
 */
class GeminiDecisionClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val MODEL_ID = "gemini-decision-1"
        const val QUESTION_KEY = "same_thread"
        const val BLOCK_THRESHOLD = 0.50
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    data class ThreadDecisionResult(
        val probability: Double,
        val confidence: Double,
        val isSameThread: Boolean
    )

    sealed class VerificationResult {
        data object Valid : VerificationResult()
        data class Invalid(val reason: String) : VerificationResult()
    }

    /**
     * Calls the Gemini Decision API with [previousMessages] (up to 10) and [newMessage]
     * for [groupName] and asks if [newMessage] is part of the same thread of discussion.
     */
    suspend fun evaluateSameThread(
        apiKey: String,
        groupName: String,
        previousMessages: List<String>,
        newMessage: String
    ): Result<ThreadDecisionResult> = withContext(Dispatchers.IO) {
        try {
            val requestBodyJson = buildRequestJson(groupName, previousMessages, newMessage)
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1beta/decisions")
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errorMsg = parseErrorMessage(bodyString) ?: "HTTP ${response.code}"
                    return@withContext Result.failure(IOException("Gemini Decision API error: $errorMsg"))
                }
                val parsed = parseDecisionResponse(bodyString)
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Lightweight verification call to confirm the user's Gemini API key works with
     * the Gemini Decisions API (including free-tier keys).
     */
    suspend fun verifyApiKey(apiKey: String): VerificationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext VerificationResult.Invalid("API key is empty.")
        }
        val result = evaluateSameThread(
            apiKey = apiKey.trim(),
            groupName = "Verification Test",
            previousMessages = listOf("Alice: Are we still meeting at 6pm?"),
            newMessage = "Bob: Yes, 6pm works for me!"
        )
        result.fold(
            onSuccess = { VerificationResult.Valid },
            onFailure = { VerificationResult.Invalid(it.message ?: "Verification failed") }
        )
    }

    internal fun buildRequestJson(
        groupName: String,
        previousMessages: List<String>,
        newMessage: String
    ): JSONObject {
        val inputBuilder = StringBuilder()
        inputBuilder.append("Group: ").append(groupName).append("\n\n")
        inputBuilder.append("Previous messages (oldest to newest, up to 10):\n")
        if (previousMessages.isEmpty()) {
            inputBuilder.append("(none)\n")
        } else {
            previousMessages.forEachIndexed { index, msg ->
                inputBuilder.append(index + 1).append(". ").append(msg).append("\n")
            }
        }
        inputBuilder.append("\nNew incoming message:\n").append(newMessage)

        val criteria = JSONObject().apply {
            put(
                "true_description",
                "The new message continues, replies to, or discusses the same topic/thread as the previous messages."
            )
            put(
                "false_description",
                "The new message starts a new, unrelated topic or there are no relevant previous messages."
            )
        }

        val question = JSONObject().apply {
            put("type", "noul")
            put(
                "instructions",
                "Is the new incoming message part of the same thread of discussion as the previous saved messages in this group?"
            )
            put("criteria", criteria)
        }

        val questions = JSONObject().apply {
            put(QUESTION_KEY, question)
        }

        return JSONObject().apply {
            put("model", MODEL_ID)
            put("input", inputBuilder.toString())
            put("questions", questions)
        }
    }

    internal fun parseDecisionResponse(responseBody: String): ThreadDecisionResult {
        val root = JSONObject(responseBody)
        val answers = root.getJSONObject("answers")
        val answer = answers.getJSONObject(QUESTION_KEY)
        val probability = answer.getDouble("noul")
        val confidence = answer.optDouble("confidence", maxOf(probability, 1.0 - probability))
        return ThreadDecisionResult(
            probability = probability,
            confidence = confidence,
            isSameThread = probability > BLOCK_THRESHOLD
        )
    }

    private fun parseErrorMessage(body: String): String? {
        return try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
