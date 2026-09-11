package com.security.myapplication.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.security.myapplication.R
import com.security.myapplication.models.ChatRequest
import com.security.myapplication.models.PersonalMessageCreateRequest
import com.security.myapplication.network.ApiClient
import com.security.myapplication.network.AuthManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * NOTIFICATION REPLY RECEIVER (Zero Message Loss & Interactive Retry Engine)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Handles direct inline text replies, one-tap retries, and "Mark as read" actions from notifications.
 *
 * Reliability guarantees:
 *  - Automatic 3x retries with exponential backoff on temporary network blips.
 *  - Zero message loss: if sending fails, preserves the typed text and presents an
 *    interactive "🔄 Retry" notification action button.
 *  - Direct tap opens the exact chat deep-link.
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifReplyReceiver"

        const val ACTION_DIRECT_REPLY = "com.security.myapplication.notifications.ACTION_DIRECT_REPLY"
        const val ACTION_RETRY_REPLY  = "com.security.myapplication.notifications.ACTION_RETRY_REPLY"
        const val ACTION_MARK_AS_READ = "com.security.myapplication.notifications.ACTION_MARK_AS_READ"

        const val KEY_TEXT_REPLY = "key_notification_text_reply"

        const val EXTRA_IS_GROUP = "extra_reply_is_group"
        const val EXTRA_CURRENT_USER_ID = "extra_reply_current_user_id"
        const val EXTRA_TARGET_ID = "extra_reply_target_id" // otherUserId or groupId
        const val EXTRA_NOTIFICATION_ID = "extra_reply_notification_id"
        const val EXTRA_SENDER_NAME = "extra_reply_sender_name"
        const val EXTRA_REPLY_TEXT = "extra_reply_text"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
        val currentUserId = intent.getIntExtra(EXTRA_CURRENT_USER_ID, 0)
        val targetId = intent.getIntExtra(EXTRA_TARGET_ID, 0)
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val senderName = intent.getStringExtra(EXTRA_SENDER_NAME) ?: "Chat"

        if (currentUserId <= 0 || targetId <= 0) return

        ensureAuthToken(context)

        when (intent.action) {
            ACTION_MARK_AS_READ -> {
                try {
                    NotificationManagerCompat.from(context).cancel(notifId)
                    NotificationManagerCompat.from(context).cancel(
                        AppNotificationManager.getSummaryNotifId(isGroup, targetId)
                    )
                } catch (_: Exception) {}
                AppNotificationManager.clearConversationHistory(context, isGroup, targetId)

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (isGroup) {
                            ApiClient.apiService.markGroupChatRead(groupId = targetId, userId = currentUserId)
                        } else {
                            ApiClient.apiService.markPersonalChatRead(userId = currentUserId, otherUserId = targetId)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to mark as read: ${e.message}")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_DIRECT_REPLY -> {
                val results = RemoteInput.getResultsFromIntent(intent) ?: return
                val replyText = results.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim()
                if (replyText.isNullOrBlank()) return

                sendReplyWithRetry(context, isGroup, currentUserId, targetId, notifId, senderName, replyText)
            }

            ACTION_RETRY_REPLY -> {
                val replyText = intent.getStringExtra(EXTRA_REPLY_TEXT)?.trim()
                if (replyText.isNullOrBlank()) return

                sendReplyWithRetry(context, isGroup, currentUserId, targetId, notifId, senderName, replyText)
            }
        }
    }

    private fun sendReplyWithRetry(
        context: Context,
        isGroup: Boolean,
        currentUserId: Int,
        targetId: Int,
        notifId: Int,
        senderName: String,
        replyText: String
    ) {
        // Immediate visual progress in notification shade
        NotificationReplyWorker.showSendingNotification(context, notifId, senderName, replyText)

        val inputData = workDataOf(
            NotificationReplyWorker.KEY_IS_GROUP to isGroup,
            NotificationReplyWorker.KEY_CURRENT_USER_ID to currentUserId,
            NotificationReplyWorker.KEY_TARGET_ID to targetId,
            NotificationReplyWorker.KEY_NOTIFICATION_ID to notifId,
            NotificationReplyWorker.KEY_SENDER_NAME to senderName,
            NotificationReplyWorker.KEY_REPLY_TEXT to replyText,
            NotificationReplyWorker.KEY_IDEMPOTENCY_KEY to "notif_reply_${UUID.randomUUID()}"
        )

        val replyWorkRequest = OneTimeWorkRequestBuilder<NotificationReplyWorker>()
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("notification_reply")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "reply_${notifId}_${System.currentTimeMillis()}",
            ExistingWorkPolicy.REPLACE,
            replyWorkRequest
        )
    }

    private fun showSendingNotification(
        context: Context,
        notifId: Int,
        senderName: String,
        replyText: String
    ) {
        NotificationReplyWorker.showSendingNotification(context, notifId, senderName, replyText)
    }

    private fun showSentNotification(
        context: Context,
        notifId: Int,
        senderName: String,
        replyText: String
    ) {
        NotificationReplyWorker.showSentNotification(context, notifId, senderName, replyText)
    }

    private fun showFailedNotification(
        context: Context,
        notifId: Int,
        isGroup: Boolean,
        currentUserId: Int,
        targetId: Int,
        senderName: String,
        replyText: String
    ) {
        NotificationReplyWorker.showFailedNotification(
            context, notifId, isGroup, currentUserId, targetId, senderName, replyText
        )
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
