package com.example.messengerthreadblocker.service

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.messengerthreadblocker.api.GeminiDecisionClient
import com.example.messengerthreadblocker.data.ApiKeyStore
import com.example.messengerthreadblocker.data.DecisionLogStore
import com.example.messengerthreadblocker.data.GroupMessageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Listens for incoming notifications, selects only notifications from the Messenger app,
 * stores each message in a 10-element array indexed by group name (clearing the oldest entry
 * when full), queries the Gemini Decision API to check if the message belongs to the same
 * thread of discussion (>50% probability), and blocks the notification if so.
 *
 * All other notifications—including all notifications from apps other than Messenger—are allowed.
 */
class MessengerNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MessengerThreadBlocker"
        val MESSENGER_PACKAGES = setOf(
            "com.facebook.orca",  // Facebook Messenger
            "com.facebook.mlite"  // Messenger Lite
        )
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var groupMessageStore: GroupMessageStore
    private lateinit var apiKeyStore: ApiKeyStore
    private val decisionClient = GeminiDecisionClient()

    override fun onCreate() {
        super.onCreate()
        groupMessageStore = GroupMessageStore(applicationContext)
        apiKeyStore = ApiKeyStore(applicationContext)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // 1. Only select notifications from Messenger; allow all other apps' notifications.
        if (sbn.packageName !in MESSENGER_PACKAGES) {
            return
        }

        val notification = sbn.notification ?: return

        // Ignore group summary / ongoing call / foreground service notifications.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0 ||
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        ) {
            return
        }

        val extracted = extractGroupAndMessage(notification) ?: return
        val (groupName, messageContent) = extracted

        // 2. Snapshot up to 10 previous saved messages for this group, and store the new message
        //    in the 10-element array (clearing out the oldest entry if 10 already exist).
        val previousMessages = groupMessageStore.recordAndGetPrevious(groupName, messageContent)

        // If there are no previous messages for this group yet, it cannot be part of an existing thread.
        if (previousMessages.isEmpty()) {
            DecisionLogStore.addEntry(
                groupName = groupName,
                messagePreview = messageContent,
                probability = null,
                blocked = false,
                detail = "Allowed (first message saved for group)"
            )
            return
        }

        val apiKey = apiKeyStore.getApiKey()
        if (apiKey == null) {
            DecisionLogStore.addEntry(
                groupName = groupName,
                messagePreview = messageContent,
                probability = null,
                blocked = false,
                detail = "Allowed (Gemini API key not configured)"
            )
            return
        }

        val notificationKey = sbn.key

        // 3. Call the Gemini Decision API asynchronously and block if probability > 50%.
        serviceScope.launch {
            val result = decisionClient.evaluateSameThread(
                apiKey = apiKey,
                groupName = groupName,
                previousMessages = previousMessages,
                newMessage = messageContent
            )

            result.fold(
                onSuccess = { decision ->
                    if (decision.isSameThread) {
                        // Probability > 50%: block the notification
                        cancelNotification(notificationKey)
                        Log.i(
                            TAG,
                            "Blocked Messenger notification in '$groupName' (same_thread=${decision.probability})"
                        )
                        DecisionLogStore.addEntry(
                            groupName = groupName,
                            messagePreview = messageContent,
                            probability = decision.probability,
                            blocked = true,
                            detail = "Blocked (same thread > 50%)"
                        )
                    } else {
                        Log.i(
                            TAG,
                            "Allowed Messenger notification in '$groupName' (same_thread=${decision.probability})"
                        )
                        DecisionLogStore.addEntry(
                            groupName = groupName,
                            messagePreview = messageContent,
                            probability = decision.probability,
                            blocked = false,
                            detail = "Allowed (new topic <= 50%)"
                        )
                    }
                },
                onFailure = { error ->
                    // Fail-open: allow the notification if the API call fails
                    Log.w(TAG, "Gemini Decision API failed; allowing notification", error)
                    DecisionLogStore.addEntry(
                        groupName = groupName,
                        messagePreview = messageContent,
                        probability = null,
                        blocked = false,
                        detail = "Allowed (API error: ${error.message})"
                    )
                }
            )
        }
    }

    internal fun extractGroupAndMessage(notification: Notification): Pair<String, String>? {
        val extras: Bundle = notification.extras ?: return null

        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val groupName = conversationTitle ?: title ?: return null

        // Prefer MessagingStyle messages if present (contains sender + text for group chats)
        val latestMessagingText = extractLatestMessagingStyleMessage(extras, groupName)

        val text = latestMessagingText
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        return Pair(groupName, text)
    }

    @Suppress("DEPRECATION")
    private fun extractLatestMessagingStyleMessage(extras: Bundle, groupName: String): String? {
        val parcelables = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        } else {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.mapNotNull { it as? Bundle }?.toTypedArray()
        } ?: return null

        if (parcelables.isEmpty()) return null

        val lastBundle = parcelables.last()
        val msgText = lastBundle.getCharSequence("text")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val sender = lastBundle.getCharSequence("sender")?.toString()?.trim()
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lastBundle.getParcelable<android.app.Person>("sender_person")?.name?.toString()?.trim()
            } else {
                null
            }

        return if (!sender.isNullOrEmpty() && sender != groupName) {
            "$sender: $msgText"
        } else {
            msgText
        }
    }
}
