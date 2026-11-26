package com.example.messengerthreadblocker.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.messengerthreadblocker.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Posts alerting (sound + vibration + heads-up) notifications on behalf of ALLOWED Messenger
 * messages when Messenger's native notification channel has been silenced.
 *
 * Preserves Messenger's original tap action (contentIntent), large icon (avatar), and safe
 * notification actions (such as inline Reply / Mark as Read) so the user experience remains
 * seamless while blocked messages produce zero buzz or sound.
 */
object AllowedNotificationPoster {

    const val ALLOWED_CHANNEL_ID = "allowed_messenger_messages"
    private const val ALLOWED_CHANNEL_NAME = "Allowed Messenger Messages"
    private const val TEST_NOTIFICATION_ID = 1999
    private const val MAX_LARGE_ICON_PX = 256

    private val nextNotificationId = AtomicInteger(2000)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ALLOWED_CHANNEL_ID) != null) {
            return
        }

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            ALLOWED_CHANNEL_ID,
            ALLOWED_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Sound and vibration alerts for Messenger notifications allowed by the thread filter."
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 250L, 150L, 250L)
            setSound(soundUri, audioAttributes)
            enableLights(true)
        }

        manager.createNotificationChannel(channel)
    }

    fun hasPostNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun canPostNotifications(context: Context): Boolean {
        return hasPostNotificationPermission(context) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Posts a high-importance alerting notification for an ALLOWED Messenger message.
     *
     * @return true if the notification was posted, false if permission is missing or posting failed.
     */
    @SuppressLint("MissingPermission")
    fun postAllowedNotification(
        context: Context,
        sbn: StatusBarNotification,
        groupName: String,
        messageContent: String
    ): Boolean {
        ensureChannel(context)
        if (!canPostNotifications(context)) {
            return false
        }

        val original = sbn.notification
        val notificationId = nextNotificationId.incrementAndGet()

        val builder = NotificationCompat.Builder(context, ALLOWED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_chat)
            .setContentTitle(groupName)
            .setContentText(messageContent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageContent))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setShowWhen(true)
            .setWhen(original.`when`.takeIf { it > 0L } ?: System.currentTimeMillis())

        val contentIntent = original.contentIntent ?: createFallbackLaunchIntent(context, sbn.packageName, notificationId)
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }

        extractLargeIconBitmap(context, original)?.let { bitmap ->
            builder.setLargeIcon(bitmap)
        }

        copySafeActions(original, builder)

        return try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Posts a sample alert on the Allowed Messenger Messages channel so the user can verify
     * sound and vibration settings from MainActivity.
     */
    @SuppressLint("MissingPermission")
    fun postTestAlert(context: Context): Boolean {
        ensureChannel(context)
        if (!canPostNotifications(context)) {
            return false
        }

        val builder = NotificationCompat.Builder(context, ALLOWED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_chat)
            .setContentTitle("Filtered Alert Test")
            .setContentText("Allowed Messenger messages will buzz and sound like this.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)

        return try {
            NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, builder.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun createFallbackLaunchIntent(
        context: Context,
        packageName: String,
        requestCode: Int
    ): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun extractLargeIconBitmap(context: Context, notification: Notification): Bitmap? {
        return runCatching {
            val icon = notification.getLargeIcon() ?: return@runCatching null
            val drawable = icon.loadDrawable(context) ?: return@runCatching null
            if (drawable is BitmapDrawable) {
                val bmp = drawable.bitmap
                if (bmp != null && !bmp.isRecycled) {
                    return@runCatching scaleDownIfNeeded(bmp)
                }
            }
            val width = drawable.intrinsicWidth.coerceIn(1, MAX_LARGE_ICON_PX)
            val height = drawable.intrinsicHeight.coerceIn(1, MAX_LARGE_ICON_PX)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }.getOrNull()
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= MAX_LARGE_ICON_PX) return bitmap
        val scale = MAX_LARGE_ICON_PX.toFloat() / maxDim.toFloat()
        val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    private fun copySafeActions(original: Notification, builder: NotificationCompat.Builder) {
        val actions = original.actions ?: return
        for (action in actions) {
            runCatching {
                val title = action.title ?: return@runCatching
                val actionIntent = action.actionIntent ?: return@runCatching
                // Use icon 0 to avoid cross-package resource ID lookup crashes.
                val compatActionBuilder = NotificationCompat.Action.Builder(0, title, actionIntent)
                    .setAllowGeneratedReplies(action.allowGeneratedReplies)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    compatActionBuilder.setSemanticAction(action.semanticAction)
                }

                action.remoteInputs?.forEach { remoteInput ->
                    val riBuilder = RemoteInput.Builder(remoteInput.resultKey)
                        .setAllowFreeFormInput(remoteInput.allowFreeFormInput)
                    remoteInput.label?.let { riBuilder.setLabel(it) }
                    remoteInput.choices?.let { riBuilder.setChoices(it) }
                    remoteInput.extras?.let { riBuilder.addExtras(it) }
                    compatActionBuilder.addRemoteInput(riBuilder.build())
                }

                builder.addAction(compatActionBuilder.build())
            }
        }
    }
}
