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
import com.example.messengerthreadblocker.data.MessengerSilenceStore
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
 * To prevent blocked notifications from buzzing/sounding before the async Gemini decision
 * returns, Messenger's own notification channel should be set to Silent. When Messenger is
 * silent:
 * - BLOCKED notifications are cancelled silently (zero buzz, zero sound).
 * - ALLOWED notifications are re-posted on this app's high-importance channel (full sound,
 *   vibration, heads-up, tap-to-open Messenger chat, and reply actions) and Messenger's
 *   silent original is cancelled to prevent duplicates.
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

    internal data class ExtractedMessage(
        val groupName: String,
        val messageContent: String,
        val messageTimestamp: Long
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var groupMessageStore: GroupMessageStore
    private lateinit var apiKeyStore: ApiKeyStore
    private val decisionClient = GeminiDecisionClient()
    private val deduplicator = NotificationDeduplicator()

    override fun onCreate() {
        super.onCreate()
        groupMessageStore = GroupMessageStore(applicationContext)
        apiKeyStore = ApiKeyStore(applicationContext)
        MessengerSilenceStore.ensureLoaded(applicationContext)
        AllowedNotificationPoster.ensureChannel(applicationContext)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Inspect any currently active Messenger notifications to refresh silence detection early.
        runCatching {
            val rankingMap = currentRanking ?: return@runCatching
            val active = activeNotifications ?: return@runCatching
            val messengerSbn = active.firstOrNull { sbn ->
                sbn.packageName in MESSENGER_PACKAGES &&
                    !isIgnoredMessengerNotification(sbn.notification)
            } ?: return@runCatching

            val detection = MessengerSilenceDetector.inspect(messengerSbn, rankingMap)
            recordSilenceObservation(detection)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return

        // 1. Only select notifications from Messenger; allow all other apps' notifications.
        if (sbn.packageName !in MESSENGER_PACKAGES) {
            return
        }

        val notification = sbn.notification ?: return
        if (isIgnoredMessengerNotification(notification)) {
            return
        }

        val silenceResult = MessengerSilenceDetector.inspect(sbn, rankingMap ?: currentRanking)
        recordSilenceObservation(silenceResult)

        val extracted = extractMessageDetails(notification) ?: return
        val (groupName, messageContent, messageTimestamp) = extracted
        val notificationKey = sbn.key

        // Deduplicate rapid in-place updates from Messenger (e.g. avatar loading) for the same message.
        val fingerprint = NotificationDeduplicator.buildFingerprint(
            notificationKey = notificationKey,
            groupName = groupName,
            messageContent = messageContent,
            messageTimestamp = messageTimestamp
        )
        val existingState = deduplicator.tryBegin(fingerprint)
        if (existingState != null) {
            if (existingState == NotificationDeduplicator.State.BLOCKED ||
                existingState == NotificationDeduplicator.State.ALLOWED_REPOSTED
            ) {
                cancelNotification(notificationKey)
            }
            return
        }

        // 2. Snapshot up to 10 previous saved messages for this group, and store the new message
        //    in the 10-element array (clearing out the oldest entry if 10 already exist).
        val previousMessages = groupMessageStore.recordAndGetPrevious(groupName, messageContent)

        // If there are no previous messages for this group yet, it cannot be part of an existing thread.
        if (previousMessages.isEmpty()) {
            handleAllowedNotification(
                sbn = sbn,
                fingerprint = fingerprint,
                groupName = groupName,
                messageContent = messageContent,
                probability = null,
                baseDetail = "Allowed (first message saved for group)",
                silenceResult = silenceResult
            )
            return
        }

        val apiKey = apiKeyStore.getApiKey()
        if (apiKey == null) {
            handleAllowedNotification(
                sbn = sbn,
                fingerprint = fingerprint,
                groupName = groupName,
                messageContent = messageContent,
                probability = null,
                baseDetail = "Allowed (Gemini API key not configured)",
                silenceResult = silenceResult
            )
            return
        }

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
                        handleBlockedNotification(
                            notificationKey = notificationKey,
                            fingerprint = fingerprint,
                            groupName = groupName,
                            messageContent = messageContent,
                            probability = decision.probability,
                            silenceResult = silenceResult
                        )
                    } else {
                        handleAllowedNotification(
                            sbn = sbn,
                            fingerprint = fingerprint,
                            groupName = groupName,
                            messageContent = messageContent,
                            probability = decision.probability,
                            baseDetail = "Allowed (new topic <= 50%)",
                            silenceResult = silenceResult
                        )
                    }
                },
                onFailure = { error ->
                    // Fail-open: allow the notification if the API call fails
                    Log.w(TAG, "Gemini Decision API failed; allowing notification", error)
                    handleAllowedNotification(
                        sbn = sbn,
                        fingerprint = fingerprint,
                        groupName = groupName,
                        messageContent = messageContent,
                        probability = null,
                        baseDetail = "Allowed (API error: ${error.message})",
                        silenceResult = silenceResult
                    )
                }
            )
        }
    }

    private fun isIgnoredMessengerNotification(notification: Notification?): Boolean {
        if (notification == null) return true
        return (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0 ||
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
    }

    private fun recordSilenceObservation(result: MessengerSilenceDetector.DetectionResult) {
        when (result.state) {
            MessengerSilenceDetector.SilenceState.SILENT -> {
                MessengerSilenceStore.recordObserved(
                    context = applicationContext,
                    isSilent = true,
                    channelName = result.channelName,
                    channelId = result.channelId
                )
            }
            MessengerSilenceDetector.SilenceState.NOISY -> {
                MessengerSilenceStore.recordObserved(
                    context = applicationContext,
                    isSilent = false,
                    channelName = result.channelName,
                    channelId = result.channelId
                )
            }
            MessengerSilenceDetector.SilenceState.UNKNOWN -> Unit
        }
    }

    private fun shouldRepostAlert(silenceResult: MessengerSilenceDetector.DetectionResult): Boolean {
        return when (silenceResult.state) {
            MessengerSilenceDetector.SilenceState.SILENT -> true
            MessengerSilenceDetector.SilenceState.NOISY -> false
            MessengerSilenceDetector.SilenceState.UNKNOWN -> {
                MessengerSilenceStore.snapshot.value.status == MessengerSilenceStore.Status.VERIFIED_SILENT
            }
        }
    }

    private fun handleBlockedNotification(
        notificationKey: String,
        fingerprint: String,
        groupName: String,
        messageContent: String,
        probability: Double,
        silenceResult: MessengerSilenceDetector.DetectionResult
    ) {
        cancelNotification(notificationKey)
        deduplicator.markCompleted(fingerprint, NotificationDeduplicator.State.BLOCKED)

        val detail = if (silenceResult.state == MessengerSilenceDetector.SilenceState.NOISY) {
            "Blocked (same thread > 50% — silence Messenger in Step 4 to stop buzz)"
        } else {
            "Blocked silently (same thread > 50%)"
        }

        Log.i(
            TAG,
            "Blocked Messenger notification in '$groupName' (same_thread=$probability, messengerState=${silenceResult.state})"
        )
        DecisionLogStore.addEntry(
            groupName = groupName,
            messagePreview = messageContent,
            probability = probability,
            blocked = true,
            detail = detail
        )
    }

    private fun handleAllowedNotification(
        sbn: StatusBarNotification,
        fingerprint: String,
        groupName: String,
        messageContent: String,
        probability: Double?,
        baseDetail: String,
        silenceResult: MessengerSilenceDetector.DetectionResult
    ) {
        val detail: String
        if (shouldRepostAlert(silenceResult)) {
            val posted = AllowedNotificationPoster.postAllowedNotification(
                context = applicationContext,
                sbn = sbn,
                groupName = groupName,
                messageContent = messageContent
            )
            if (posted) {
                cancelNotification(sbn.key)
                deduplicator.markCompleted(fingerprint, NotificationDeduplicator.State.ALLOWED_REPOSTED)
                detail = "$baseDetail — alerted"
            } else {
                deduplicator.markCompleted(fingerprint, NotificationDeduplicator.State.ALLOWED_NATIVE)
                detail = "$baseDetail (enable Filtered Alerts in Step 3 to hear buzz/sound)"
            }
        } else {
            deduplicator.markCompleted(fingerprint, NotificationDeduplicator.State.ALLOWED_NATIVE)
            detail = if (silenceResult.state == MessengerSilenceDetector.SilenceState.NOISY) {
                "$baseDetail (silence Messenger in Step 4 so blocked messages don't buzz)"
            } else {
                baseDetail
            }
        }

        Log.i(
            TAG,
            "Allowed Messenger notification in '$groupName' (same_thread=$probability, messengerState=${silenceResult.state})"
        )
        DecisionLogStore.addEntry(
            groupName = groupName,
            messagePreview = messageContent,
            probability = probability,
            blocked = false,
            detail = detail
        )
    }

    internal fun extractGroupAndMessage(notification: Notification): Pair<String, String>? {
        val extracted = extractMessageDetails(notification) ?: return null
        return Pair(extracted.groupName, extracted.messageContent)
    }

    internal fun extractMessageDetails(notification: Notification): ExtractedMessage? {
        val extras: Bundle = notification.extras ?: return null

        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val groupName = conversationTitle ?: title ?: return null

        // Prefer MessagingStyle messages if present (contains sender + text + timestamp for group chats)
        val latestMessaging = extractLatestMessagingStyleMessage(extras, groupName)
        if (latestMessaging != null) {
            return ExtractedMessage(
                groupName = groupName,
                messageContent = latestMessaging.first,
                messageTimestamp = latestMessaging.second.takeIf { it > 0L } ?: notification.`when`
            )
        }

        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        return ExtractedMessage(
            groupName = groupName,
            messageContent = text,
            messageTimestamp = notification.`when`
        )
    }

    @Suppress("DEPRECATION")
    private fun extractLatestMessagingStyleMessage(extras: Bundle, groupName: String): Pair<String, Long>? {
        val parcelables = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        } else {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.mapNotNull { it as? Bundle }?.toTypedArray()
        } ?: return null

        if (parcelables.isEmpty()) return null

        val lastBundle = parcelables.last()
        val msgText = lastBundle.getCharSequence("text")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val msgTime = lastBundle.getLong("time", 0L)
        val sender = lastBundle.getCharSequence("sender")?.toString()?.trim()
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lastBundle.getParcelable<android.app.Person>("sender_person")?.name?.toString()?.trim()
            } else {
                null
            }

        val formatted = if (!sender.isNullOrEmpty() && sender != groupName) {
            "$sender: $msgText"
        } else {
            msgText
        }
        return Pair(formatted, msgTime)
    }
}
