package com.security.myapplication.notifications

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.security.myapplication.models.MessageType
import com.security.myapplication.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * EDUCONNECT FIREBASE CLOUD MESSAGING (FCM) SERVICE (Realtime 0-Polling Push)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Receives instant Google FCM push notifications with zero delay and 0% background battery drain.
 * Dispatches high-priority WhatsApp-style bundled notifications via [AppNotificationManager].
 */
class EduConnectFCMService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "EduConnectFCM"
        const val PREFS_APP = "app_prefs"
        const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_LAST_SYNC_ATTEMPT_MS = "last_fcm_sync_attempt_ms"
        private const val FCM_SYNC_RETRY_INTERVAL_MS = 60_000L

        /**
         * Get the saved FCM token from local storage.
         */
        fun getSavedToken(context: Context): String? {
            return context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
                .getString(KEY_FCM_TOKEN, null)
        }

        fun isTokenSyncedForUser(context: Context, userId: Int): Boolean {
            if (userId <= 0) return false
            val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            val token = prefs.getString(KEY_FCM_TOKEN, null)
            return !token.isNullOrBlank() &&
                token == prefs.getString("last_synced_fcm_token", null) &&
                userId == prefs.getInt("last_synced_fcm_user_id", -1)
        }

        /**
         * Retries a previously failed registration at a small, bounded rate.
         * The polling fallback calls this only while FCM is not usable, so a
         * temporary offline login can recover to real-time push automatically.
         */
        fun retryPendingTokenSync(context: Context, userId: Int) {
            val token = getSavedToken(context)
            if (userId <= 0 || token.isNullOrBlank() || isTokenSyncedForUser(context, userId)) return

            val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(KEY_LAST_SYNC_ATTEMPT_MS, 0L) < FCM_SYNC_RETRY_INTERVAL_MS) return
            syncTokenToServer(context, userId, token)
        }

        /** Removes this device from a signed-out account but retains the token for the next login. */
        fun unregisterTokenFromServer(context: Context, userId: Int) {
            val token = getSavedToken(context)
            if (userId <= 0 || token.isNullOrBlank()) return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ApiClient.apiService.removeFcmToken(userId, token)
                    Log.d(TAG, "FCM token removed for user $userId")
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to remove FCM token for user $userId: ${e.message}")
                }
            }
        }

        /**
         * Sync FCM token to backend.
         *
         * This is the CRITICAL function that makes FCM work end-to-end.
         * Without this call the backend never knows which FCM token belongs to
         * which user, so it can never send push notifications to this device.
         *
         * Retries up to 3 times with exponential backoff (1s → 2s → 4s) in
         * case of transient network errors at login time.
         */
        fun syncTokenToServer(context: Context, userId: Int, token: String, force: Boolean = false) {
            if (userId <= 0 || token.isBlank()) return

            val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            val lastSyncedToken = prefs.getString("last_synced_fcm_token", null)
            val lastSyncedUserId = prefs.getInt("last_synced_fcm_user_id", -1)

            // Skip redundant network call if already synced for this exact user & token
            if (!force && lastSyncedToken == token && lastSyncedUserId == userId) {
                Log.d(TAG, "FCM token already up to date on server for user $userId, skipping redundant sync")
                return
            }

            prefs.edit()
                .putLong(KEY_LAST_SYNC_ATTEMPT_MS, System.currentTimeMillis())
                .apply()

            CoroutineScope(Dispatchers.IO).launch {
                // Make sure AuthManager has a valid auth token before calling the API.
                // This is needed when onNewToken() fires before the app has fully initialised.
                if (com.security.myapplication.network.AuthManager.authToken.isNullOrBlank()) {
                    val savedAuth = prefs.getString("authToken", null)
                    if (!savedAuth.isNullOrBlank()) {
                        com.security.myapplication.network.AuthManager.setToken(savedAuth)
                    }
                }

                var attempt = 0
                var lastError: Exception? = null
                while (attempt < 3) {
                    try {
                        ApiClient.apiService.updateFcmToken(
                            userId,
                            com.security.myapplication.models.FcmTokenRequest(token)
                        )
                        Log.d(TAG, "FCM token synced for user $userId (attempt ${attempt + 1})")
                        // Cache successfully synced state to avoid repeat calls on app restarts
                        prefs.edit()
                            .putString("last_synced_fcm_token", token)
                            .putInt("last_synced_fcm_user_id", userId)
                            .apply()
                        AppMessageNotificationWatcher.refreshDeliveryMode(context, userId)
                        return@launch          // success — stop retrying
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(TAG, "FCM token sync attempt ${attempt + 1} failed: ${e.message}")
                        attempt++
                        if (attempt < 3) {
                            kotlinx.coroutines.delay(1000L * (1 shl (attempt - 1))) // 1s, 2s, 4s
                        }
                    }
                }
                Log.e(TAG, "FCM token sync permanently failed after 3 attempts: ${lastError?.message}")
            }
        }
    }

    /**
     * Called whenever Google assigns a new FCM device registration token.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Token assigned: $token")

        // Save token locally
        getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FCM_TOKEN, token)
            .apply()

        // Sync to backend if user is already logged in
        val userId = getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE).getInt("userId", -1)
        if (userId > 0) {
            syncTokenToServer(applicationContext, userId, token)
        }
    }

    /**
     * Called when a push notification payload arrives from Firebase.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM Message received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        if (data.isEmpty()) return

        val ctx = applicationContext
        AppNotificationManager.initializeChannels(ctx)

        val prefs = getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
        val currentUserId = prefs.getInt("userId", -1)
        if (currentUserId <= 0) {
            Log.d(TAG, "Ignored FCM message because no user session is active")
            return
        }

        // Do not consume/deduplicate a message Android was not allowed to show.
        // The safety sync will surface it after permission/channel is enabled.
        if (!AppNotificationManager.canPostMessageNotifications(ctx)) {
            Log.w(TAG, "Message notification permission or channel is disabled; retaining for catch-up")
            return
        }

        val senderId = data["sender_id"]?.toIntOrNull() ?: 0
        val senderName = data["sender_name"] ?: "New Message"
        val text = data["text"] ?: ""
        val messageId = data["message_id"]?.toIntOrNull() ?: (System.currentTimeMillis() % 1000000).toInt()
        val isGroup = data["is_group"]?.equals("true", ignoreCase = true) ?: false
        val groupId = data["group_id"]?.toIntOrNull() ?: 0
        val groupName = data["group_name"] ?: ""
        val role = data["role"] ?: "Student"
        val profilePic = data["profile_pic"]
        val msgTypeStr = data["message_type"] ?: "text"
        val durationSec = data["duration_sec"]?.toIntOrNull() ?: 0
        val timeStr = data["timestamp"] ?: "now"
        val mediaUrl = data["media_url"]

        val chatType = if (isGroup) "group" else "direct"
        val chatId = if (isGroup) groupId else senderId
        if (senderId <= 0 || chatId <= 0) {
            Log.w(TAG, "Ignored malformed FCM message: sender=$senderId chat=$chatId")
            return
        }

        if (senderId == currentUserId) {
            Log.d(TAG, "Suppressed FCM notification for self-sent message")
            return
        }

        // Check if message is already notified or suppressed
        if (currentUserId > 0 && NotificationDeduplicator.isAlreadyNotified(ctx, currentUserId, chatType, chatId, messageId)) {
            Log.d(TAG, "Suppressed duplicate FCM notification for message $messageId")
            return
        }

        // Suppress only when user is actively looking at this exact chat in foreground
        val isActiveChat = AppMessageNotificationWatcher.isAppInForeground &&
                (AppMessageNotificationWatcher.activeChatTypePublic?.equals(chatType, ignoreCase = true) == true &&
                 AppMessageNotificationWatcher.activeChatIdPublic == chatId)

        if (isActiveChat) {
            if (currentUserId > 0) {
                NotificationDeduplicator.markNotified(ctx, currentUserId, chatType, chatId, messageId)
            }
            Log.d(TAG, "Suppressed FCM notification because chat is actively open")
            return
        }

        val typeEnum = MessageType.from(msgTypeStr)

        when (typeEnum) {
            MessageType.VOICE -> AppNotificationManager.showIncomingVoiceNotification(
                context = ctx,
                currentUserId = currentUserId,
                senderId = if (isGroup) 0 else chatId,
                senderName = senderName,
                durationSeconds = durationSec,
                messageId = messageId,
                isGroup = isGroup,
                groupId = if (isGroup) chatId else 0,
                groupName = chatNameOrEmpty(isGroup, groupName),
                senderRole = role,
                senderProfilePic = profilePic,
                timestampStr = timeStr
            )
            MessageType.IMAGE -> AppNotificationManager.showImageNotification(
                context = ctx,
                currentUserId = currentUserId,
                senderId = if (isGroup) 0 else chatId,
                senderName = senderName,
                imageBitmap = null,
                caption = text.ifBlank { "Photo" },
                messageId = messageId,
                isGroup = isGroup,
                groupId = if (isGroup) chatId else 0,
                groupName = chatNameOrEmpty(isGroup, groupName),
                senderRole = role,
                senderProfilePic = profilePic,
                timestampStr = timeStr,
                mediaUrl = mediaUrl
            )
            MessageType.VIDEO -> AppNotificationManager.showVideoNotification(
                context = ctx,
                currentUserId = currentUserId,
                senderId = if (isGroup) 0 else chatId,
                senderName = senderName,
                videoThumbnail = null,
                caption = text.ifBlank { "Video" },
                messageId = messageId,
                isGroup = isGroup,
                groupId = if (isGroup) chatId else 0,
                groupName = chatNameOrEmpty(isGroup, groupName),
                senderRole = role,
                senderProfilePic = profilePic,
                timestampStr = timeStr,
                mediaUrl = mediaUrl
            )
            MessageType.AUDIO -> {
                val audioName = text.substringAfterLast("/").takeIf { it.isNotBlank() } ?: "Audio"
                AppNotificationManager.showFileNotification(
                    context = ctx,
                    currentUserId = currentUserId,
                    senderId = if (isGroup) 0 else chatId,
                    senderName = senderName,
                    fileName = "\uD83C\uDFB5 $audioName",
                    fileSizeStr = null,
                    messageId = messageId,
                    isGroup = isGroup,
                    groupId = if (isGroup) chatId else 0,
                    groupName = chatNameOrEmpty(isGroup, groupName),
                    senderRole = role,
                    senderProfilePic = profilePic,
                    timestampStr = timeStr
                )
            }
            MessageType.FILE -> AppNotificationManager.showFileNotification(
                context = ctx,
                currentUserId = currentUserId,
                senderId = if (isGroup) 0 else chatId,
                senderName = senderName,
                fileName = text.ifBlank { "Attachment" },
                fileSizeStr = null,
                messageId = messageId,
                isGroup = isGroup,
                groupId = if (isGroup) chatId else 0,
                groupName = chatNameOrEmpty(isGroup, groupName),
                senderRole = role,
                senderProfilePic = profilePic,
                timestampStr = timeStr
            )
            else -> AppNotificationManager.showTextMessageNotification(
                context = ctx,
                currentUserId = currentUserId,
                senderId = if (isGroup) 0 else chatId,
                senderName = senderName,
                messageText = text,
                messageId = messageId,
                isGroup = isGroup,
                groupId = if (isGroup) chatId else 0,
                groupName = chatNameOrEmpty(isGroup, groupName),
                senderRole = role,
                senderProfilePic = profilePic,
                timestampStr = timeStr
            )
        }

        // Root fix: mark this message as notified in unified NotificationDeduplicator
        // so that even if background polling service runs, it will NEVER double-notify it!
        if (currentUserId > 0) {
            NotificationDeduplicator.markNotified(ctx, currentUserId, chatType, chatId, messageId)
        }
    }

    override fun onDeletedMessages() {
        super.onDeletedMessages()
        // FCM calls this if its pending-message buffer overflowed while the
        // device was offline. Trigger server-backed reconciliation immediately.
        val userId = getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE).getInt("userId", -1)
        if (userId > 0) {
            try {
                NotificationSyncService.start(applicationContext, userId)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start deleted-message catch-up: ${e.message}")
            }
        }
    }

    private fun chatNameOrEmpty(isGroup: Boolean, groupName: String): String {
        return if (isGroup) groupName else ""
    }
}
