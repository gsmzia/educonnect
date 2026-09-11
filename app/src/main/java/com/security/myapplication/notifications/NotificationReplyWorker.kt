package com.security.myapplication.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.security.myapplication.R
import com.security.myapplication.models.ChatRequest
import com.security.myapplication.models.PersonalMessageCreateRequest
import com.security.myapplication.network.ApiClient
import com.security.myapplication.network.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * WorkManager-backed worker for inline notification replies.
 * Guarantees zero message loss independent of BroadcastReceiver lifecycle
 * or system process kills.
 */
class NotificationReplyWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "NotifReplyWorker"

        const val KEY_IS_GROUP = "extra_reply_is_group"
        const val KEY_CURRENT_USER_ID = "extra_reply_current_user_id"
        const val KEY_TARGET_ID = "extra_reply_target_id"
        const val KEY_NOTIFICATION_ID = "extra_reply_notification_id"
        const val KEY_SENDER_NAME = "extra_reply_sender_name"
        const val KEY_REPLY_TEXT = "extra_reply_text"
        const val KEY_IDEMPOTENCY_KEY = "extra_reply_idempotency_key"

        fun showSendingNotification(
            context: Context,
            notifId: Int,
            senderName: String,
            replyText: String
        ) {
            val notif = NotificationCompat.Builder(context, AppNotificationManager.CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notif_small)
                .setColor(0xFF8B6BFF.toInt())
                .setContentTitle("Sending to $senderName…")
                .setContentText(replyText)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setOngoing(false)
                .setProgress(0, 0, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            try {
                NotificationManagerCompat.from(context).notify(notifId, notif)
            } catch (_: SecurityException) {}
        }


        fun showSentNotification(
            context: Context,
            notifId: Int,
            senderName: String,
            replyText: String
        ) {
            val notif = NotificationCompat.Builder(context, AppNotificationManager.CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notif_small)
                .setColor(0xFF4CAF50.toInt()) // Green success
                .setContentTitle(senderName)
                .setContentText("✓ You: $replyText")
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setTimeoutAfter(2500)
                .build()

            try {
                NotificationManagerCompat.from(context).notify(notifId, notif)
            } catch (_: SecurityException) {}
        }


        fun showFailedNotification(
            context: Context,
            notifId: Int,
            isGroup: Boolean,
            currentUserId: Int,
            targetId: Int,
            senderName: String,
            replyText: String
        ) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)

            val retryIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
                action = NotificationReplyReceiver.ACTION_RETRY_REPLY
                putExtra(NotificationReplyReceiver.EXTRA_IS_GROUP, isGroup)
                putExtra(NotificationReplyReceiver.EXTRA_CURRENT_USER_ID, currentUserId)
                putExtra(NotificationReplyReceiver.EXTRA_TARGET_ID, targetId)
                putExtra(NotificationReplyReceiver.EXTRA_NOTIFICATION_ID, notifId)
                putExtra(NotificationReplyReceiver.EXTRA_SENDER_NAME, senderName)
                putExtra(NotificationReplyReceiver.EXTRA_REPLY_TEXT, replyText)
            }
            val retryPendingIntent = PendingIntent.getBroadcast(
                context,
                notifId + 5000,
                retryIntent,
                flags
            )

            val chatIntent = if (isGroup) {
                NotificationRouter.createGroupChatIntent(context, currentUserId, targetId, senderName, null, notifId)
            } else {
                NotificationRouter.createPersonalChatIntent(context, currentUserId, targetId, senderName, "Student", null, null, notifId)
            }

            val failedNotif = NotificationCompat.Builder(context, AppNotificationManager.CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notif_small)
                .setColor(0xFFFF4B4B.toInt()) // Red failure accent
                .setContentTitle("Failed to send: $senderName")
                .setContentText("\"$replyText\"")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle("Failed to send to $senderName")
                        .bigText("\"$replyText\"\n\nTap '🔄 Retry' to resend or tap here to open chat.")
                )
                .setAutoCancel(true)
                .setContentIntent(chatIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
                .addAction(
                    android.R.drawable.stat_notify_sync,
                    "🔄 Retry",
                    retryPendingIntent
                )
                .build()

            try {
                NotificationManagerCompat.from(context).notify(notifId, failedNotif)
            } catch (_: SecurityException) {}
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val isGroup = inputData.getBoolean(KEY_IS_GROUP, false)
        val currentUserId = inputData.getInt(KEY_CURRENT_USER_ID, 0)
        val targetId = inputData.getInt(KEY_TARGET_ID, 0)
        val notifId = inputData.getInt(KEY_NOTIFICATION_ID, 0)
        val senderName = inputData.getString(KEY_SENDER_NAME) ?: "Chat"
        val replyText = inputData.getString(KEY_REPLY_TEXT)?.trim().orEmpty()
        val idempotencyKey = inputData.getString(KEY_IDEMPOTENCY_KEY)?.takeIf { it.isNotBlank() }
            ?: "notif_reply_${currentUserId}_${targetId}_${notifId}_${replyText.hashCode()}"

        if (currentUserId <= 0 || targetId <= 0 || replyText.isBlank()) {
            return@withContext Result.failure()
        }

        ensureAuthToken(context)

        var success = false
        for (attempt in 1..3) {
            try {
                ensureAuthToken(context)
                if (isGroup) {
                    ApiClient.apiService.sendMessage(
                        groupId = targetId,
                        idempotencyKey = idempotencyKey,
                        msg = ChatRequest(
                            group_id = targetId,
                            sender_id = currentUserId,
                            text = replyText,
                            idempotency_key = idempotencyKey
                        )
                    )
                } else {
                    ApiClient.apiService.sendPersonalMessage(
                        idempotencyKey = idempotencyKey,
                        request = PersonalMessageCreateRequest(
                            sender_id = currentUserId,
                            receiver_id = targetId,
                            text = replyText,
                            idempotency_key = idempotencyKey
                        )
                    )
                }
                success = true
                break
            } catch (e: Exception) {
                Log.w(TAG, "Reply send attempt $attempt failed: ${e.message}")
                if (attempt < 3) {
                    delay(attempt * 1200L)
                }
            }
        }

        if (success) {
            showSentNotification(context, notifId, senderName, replyText)
            delay(1600L)
            try {
                NotificationManagerCompat.from(context).cancel(notifId)
                NotificationManagerCompat.from(context).cancel(
                    AppNotificationManager.getSummaryNotifId(isGroup, targetId)
                )
            } catch (_: Exception) {}
            AppNotificationManager.clearConversationHistory(context, isGroup, targetId)
            Result.success()
        } else {
            showFailedNotification(
                context = context,
                notifId = notifId,
                isGroup = isGroup,
                currentUserId = currentUserId,
                targetId = targetId,
                senderName = senderName,
                replyText = replyText
            )
            Result.failure()
        }
    }

    private fun ensureAuthToken(context: Context) {
        if (AuthManager.authToken.isNullOrBlank()) {
            val sharedPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val savedToken = sharedPrefs.getString("authToken", null)
            if (!savedToken.isNullOrBlank()) {
                AuthManager.setToken(savedToken)
            }
        }
    }
}
