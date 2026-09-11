package com.security.myapplication.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.security.myapplication.MainActivity
import com.security.myapplication.R
import com.security.myapplication.models.HomeFeedItem
import com.security.myapplication.models.MessageType
import com.security.myapplication.network.ApiClient
import com.security.myapplication.network.AuthManager
import kotlinx.coroutines.*

/**
 * Sticky foreground data-sync service that keeps the notification polling loop
 * alive when the app is minimized or killed. Shows a silent invisible persistent
 * notification (no sound, no vibration, no badge) - identical to WhatsApp's approach.
 */
class NotificationSyncService : Service() {

    companion object {
        private const val TAG            = "NotifSyncService"
        private const val CHANNEL_SYNC   = "educonnect_sync_silent_v2"
        private const val NOTIF_ID_SYNC  = 7
        const val EXTRA_USER_ID          = "user_id"

        fun start(context: Context, userId: Int) {
            if (userId <= 0) return
            val intent = Intent(context, NotificationSyncService::class.java)
                .putExtra(EXTRA_USER_ID, userId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NotificationSyncService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var userId: Int = 0
    private var isScreenOn = true
    private var lastOnlinePingTime = 0L

    private val seenMessageIdMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val PREFS_NOTIF = "educonnect_notif_prefs"
    private val KEY_PREFIX  = "seen_msg_"

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    // Instant sync when user turns on phone screen
                    restartPollLoop()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureSyncChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID_SYNC, buildSilentNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID_SYNC, buildSilentNotification())
        }

        try {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenReceiver, filter)
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newUserId = intent?.getIntExtra(EXTRA_USER_ID, 0) ?: 0
        if (newUserId > 0 && newUserId != userId) {
            userId = newUserId
            loadSeenIds()
            restartPollLoop()
        } else if (newUserId > 0 && pollJob?.isActive != true) {
            loadSeenIds()
            restartPollLoop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        pollJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun restartPollLoop() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            Log.d(TAG, "Adaptive sync loop started for user $userId")
            // If persisted seen-IDs are already loaded from a prior run, skip the
            // seed-only first pass so new messages get notified immediately.
            var seeded = seenMessageIdMap.isNotEmpty()

            while (isActive) {
                val loopStartTime = System.currentTimeMillis()
                try {
                    ensureToken()
                    // When registration failed during an offline login, keep
                    // polling as a safe fallback and periodically restore FCM
                    // once connectivity/backend service returns.
                    EduConnectFCMService.retryPendingTokenSync(applicationContext, userId)

                    // Throttle online ping: only every 20 seconds and only if screen is on or in foreground
                    if (isScreenOn || AppMessageNotificationWatcher.isAppInForeground) {
                        if (loopStartTime - lastOnlinePingTime >= 20_000L) {
                            lastOnlinePingTime = loopStartTime
                            try { ApiClient.apiService.pingOnline(userId) } catch (_: Exception) {}
                        }
                    }

                    val feed = ApiClient.apiService.getHomeFeed(userId)

                    if (!seeded && seenMessageIdMap.isEmpty()) {
                        seedInitial(feed)
                        seeded = true
                    } else {
                        seeded = true
                        processForNotifications(feed)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Adaptive poll error: ${e.message}")
                }

                // FCM remains the instant path. When it is registered, this is a
                // low-frequency reliability safety net; otherwise it becomes the
                // faster primary fallback for devices without working FCM.
                val isForeground = AppMessageNotificationWatcher.isAppInForeground
                val fcmReady = EduConnectFCMService.isTokenSyncedForUser(applicationContext, userId)
                val pollDelayMs = when {
                    fcmReady && isForeground -> 12_000L
                    fcmReady && isScreenOn -> 20_000L
                    fcmReady -> 45_000L
                    isForeground -> 2_000L
                    isScreenOn -> 6_000L
                    else -> 25_000L
                }

                delay(pollDelayMs)
            }
        }
    }

    private fun seedInitial(items: List<HomeFeedItem>) {
        NotificationDeduplicator.init(applicationContext, userId)
        for (item in items) {
            val key   = "${item.type}_${item.id}"
            val msgId = resolveMessageId(item)
            if (msgId != 0) {
                seenMessageIdMap[key] = msgId
                saveSeenId(key, msgId)
                NotificationDeduplicator.markNotified(applicationContext, userId, item.type, item.id, msgId)
            }
        }
        Log.d(TAG, "Seeded ${seenMessageIdMap.size} conversations")
    }

    private suspend fun processForNotifications(items: List<HomeFeedItem>) {
        val ctx = applicationContext
        AppNotificationManager.initializeChannels(ctx)
        NotificationDeduplicator.init(ctx, userId)

        for (item in items) {
            val key     = "${item.type}_${item.id}"
            val msgText = item.last_message ?: continue
            if (msgText.isBlank()) continue
            if (isSelfSent(item)) continue

            val latestMsgId = resolveMessageId(item)
            val dedupMax    = NotificationDeduplicator.getMaxSeenId(userId, item.type, item.id)
            val lastSeen    = maxOf(seenMessageIdMap[key] ?: 0, dedupMax)
            if (latestMsgId <= lastSeen && lastSeen > 0) {
                seenMessageIdMap[key] = lastSeen
                continue
            }

            // Suppress only when user is actively viewing this exact chat in foreground
            val isActiveChat = AppMessageNotificationWatcher.isAppInForeground &&
                    (AppMessageNotificationWatcher.activeChatTypePublic?.equals(item.type, ignoreCase = true) == true &&
                     AppMessageNotificationWatcher.activeChatIdPublic   == item.id)
            if (isActiveChat) {
                seenMessageIdMap[key] = latestMsgId
                saveSeenId(key, latestMsgId)
                NotificationDeduplicator.markNotified(ctx, userId, item.type, item.id, latestMsgId)
                continue
            }

            val isGroup = item.type.equals("group", ignoreCase = true)
            var processedAll = false

            // If we have a previous lastSeen, fetch the complete list of messages in this chat
            // to notify EVERY fast/intermediate message that arrived in quick succession!
            if (lastSeen > 0) {
                try {
                    if (isGroup) {
                        val groupMessages = ApiClient.apiService.getMessages(item.id)
                        val unnotified = groupMessages.filter { msg ->
                            msg.id > lastSeen && msg.sender_id != userId && !msg.is_deleted &&
                            !NotificationDeduplicator.isAlreadyNotified(ctx, userId, "group", item.id, msg.id)
                        }.sortedBy { it.id }

                        if (unnotified.isNotEmpty()) {
                            for (msg in unnotified) {
                                val senderName = msg.sender_name.ifBlank { item.name }
                                val timeStr = msg.timestamp ?: item.timestamp_raw ?: item.timestamp ?: "now"
                                dispatchNotification(
                                    ctx = ctx,
                                    type = msg.typeEnum,
                                    isGroup = true,
                                    chatId = item.id,
                                    chatName = item.name,
                                    senderName = senderName,
                                    text = msg.text,
                                    msgId = msg.id,
                                    role = item.role ?: "Student",
                                    profilePic = item.profile_pic,
                                    durationSec = msg.duration_sec,
                                    timeStr = timeStr,
                                    mediaUrl = msg.media_url
                                )
                            }
                            val maxId = maxOf(latestMsgId, unnotified.last().id)
                            seenMessageIdMap[key] = maxId
                            saveSeenId(key, maxId)
                            processedAll = true
                        }
                    } else {
                        val personalMessages = ApiClient.apiService.getPersonalMessages(userId, item.id)
                        val unnotified = personalMessages.filter { msg ->
                            msg.id > lastSeen && msg.sender_id != userId && !msg.is_deleted &&
                            !NotificationDeduplicator.isAlreadyNotified(ctx, userId, "direct", item.id, msg.id)
                        }.sortedBy { it.id }

                        if (unnotified.isNotEmpty()) {
                            for (msg in unnotified) {
                                val senderName = item.last_sender?.ifBlank { item.name } ?: item.name
                                val timeStr = msg.timestamp ?: item.timestamp_raw ?: item.timestamp ?: "now"
                                dispatchNotification(
                                    ctx = ctx,
                                    type = msg.typeEnum,
                                    isGroup = false,
                                    chatId = item.id,
                                    chatName = "",
                                    senderName = senderName,
                                    text = msg.text,
                                    msgId = msg.id,
                                    role = item.role ?: "Student",
                                    profilePic = item.profile_pic,
                                    durationSec = msg.duration_sec,
                                    timeStr = timeStr,
                                    mediaUrl = msg.media_url
                                )
                            }
                            val maxId = maxOf(latestMsgId, unnotified.last().id)
                            seenMessageIdMap[key] = maxId
                            saveSeenId(key, maxId)
                            processedAll = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fast messages detail fetch failed: ${e.message}, falling back to feed item")
                }
            }

            // Fallback: Notify the single latest item from home feed if full list wasn't fetched
            if (!processedAll) {
                seenMessageIdMap[key] = latestMsgId
                saveSeenId(key, latestMsgId)

                if (isSelfSent(item)) continue
                if (NotificationDeduplicator.isAlreadyNotified(ctx, userId, item.type, item.id, latestMsgId)) continue

                val senderName = item.last_sender?.ifBlank { item.name } ?: item.name
                val timeStr    = item.timestamp_raw ?: item.timestamp ?: "now"
                dispatchNotification(
                    ctx = ctx,
                    type = item.typeEnum,
                    isGroup = isGroup,
                    chatId = item.id,
                    chatName = if (isGroup) item.name else "",
                    senderName = senderName,
                    text = msgText,
                    msgId = latestMsgId,
                    role = item.role ?: "Student",
                    profilePic = item.profile_pic,
                    durationSec = item.last_message_duration_sec,
                    timeStr = timeStr
                )
            }
        }
    }

    private fun isSelfSent(item: HomeFeedItem): Boolean {
        if (item.last_sender_id != null && item.last_sender_id == userId) return true
        val sender = item.last_sender?.trim() ?: return false
        if (sender.equals("You", ignoreCase = true) ||
            sender.equals("Me", ignoreCase = true) ||
            sender.equals("Aap", ignoreCase = true) ||
            sender.equals("Self", ignoreCase = true)) {
            return true
        }
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val myName = prefs.getString("name", null)?.trim()
        val myUsername = prefs.getString("username", null)?.trim()
        if (!myName.isNullOrBlank() && sender.equals(myName, ignoreCase = true)) return true
        if (!myUsername.isNullOrBlank() && sender.equals(myUsername, ignoreCase = true)) return true
        return false
    }

    private fun dispatchNotification(
        ctx: Context,
        type: MessageType,
        isGroup: Boolean,
        chatId: Int,
        chatName: String,
        senderName: String,
        text: String,
        msgId: Int,
        role: String,
        profilePic: String?,
        durationSec: Int,
        timeStr: String,
        mediaUrl: String? = null
    ) {
        val chatType = if (isGroup) "group" else "direct"
        if (NotificationDeduplicator.isAlreadyNotified(ctx, userId, chatType, chatId, msgId)) {
            Log.d(TAG, "Suppressed duplicate polling notification for msg $msgId in $chatType $chatId")
            return
        }
        NotificationDeduplicator.markNotified(ctx, userId, chatType, chatId, msgId)
        when (type) {
            MessageType.VOICE -> AppNotificationManager.showIncomingVoiceNotification(
                ctx, userId,
                if (isGroup) 0 else chatId, senderName,
                if (durationSec > 0) durationSec else 0,
                msgId, isGroup,
                if (isGroup) chatId else 0, chatName,
                role, profilePic, timeStr
            )
            MessageType.IMAGE -> AppNotificationManager.showImageNotification(
                ctx, userId,
                if (isGroup) 0 else chatId, senderName, null,
                text.ifBlank { "Photo" }, msgId, isGroup,
                if (isGroup) chatId else 0, chatName,
                role, profilePic, timeStr,
                mediaUrl = mediaUrl
            )
            MessageType.VIDEO -> AppNotificationManager.showVideoNotification(
                ctx, userId,
                if (isGroup) 0 else chatId, senderName, null,
                text.ifBlank { "Video" }, msgId, isGroup,
                if (isGroup) chatId else 0, chatName,
                role, profilePic, timeStr,
                mediaUrl = mediaUrl
            )
            MessageType.AUDIO -> {
                val audioName = text.substringAfterLast("/").takeIf { it.isNotBlank() } ?: "Audio"
                AppNotificationManager.showFileNotification(
                    ctx, userId,
                    if (isGroup) 0 else chatId, senderName,
                    "\uD83C\uDFB5 $audioName", null, msgId, isGroup,
                    if (isGroup) chatId else 0, chatName,
                    role, profilePic, timeStr
                )
            }
            MessageType.FILE -> AppNotificationManager.showFileNotification(
                ctx, userId,
                if (isGroup) 0 else chatId, senderName,
                text.ifBlank { "Attachment" }, null, msgId, isGroup,
                if (isGroup) chatId else 0, chatName,
                role, profilePic, timeStr
            )
            else -> AppNotificationManager.showTextMessageNotification(
                ctx, userId,
                if (isGroup) 0 else chatId, senderName, text, msgId, isGroup,
                if (isGroup) chatId else 0, chatName,
                role, profilePic, timeStr
            )
        }
    }

    private fun resolveMessageId(item: HomeFeedItem): Int {
        return if (item.last_message_id != null && item.last_message_id > 0) {
            item.last_message_id
        } else {
            val raw = "${item.type}_${item.id}_${item.last_message}_${item.timestamp_raw ?: item.timestamp}"
            (raw.hashCode() and 0x7FFFFFFF)
        }
    }

    private fun ensureToken() {
        if (AuthManager.authToken.isNullOrBlank()) {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("authToken", null)
            if (!token.isNullOrBlank()) AuthManager.setToken(token)
        }
    }

    private fun loadSeenIds() {
        val prefs = getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE)
        seenMessageIdMap.clear()
        val userPrefix = "${KEY_PREFIX}${userId}_"
        for ((k, v) in prefs.all) {
            if (k.startsWith(userPrefix)) {
                val rawKey = k.removePrefix(userPrefix)
                val intVal = (v as? Int) ?: continue
                seenMessageIdMap[rawKey] = intVal
            }
        }
    }

    private fun saveSeenId(key: String, msgId: Int) {
        val userPrefix = "${KEY_PREFIX}${userId}_"
        getSharedPreferences(PREFS_NOTIF, Context.MODE_PRIVATE)
            .edit().putInt(userPrefix + key, msgId).apply()
    }

    private fun ensureSyncChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            try { nm.deleteNotificationChannel("educonnect_sync_service") } catch (_: Exception) {}
            if (nm.getNotificationChannel(CHANNEL_SYNC) == null) {
                val ch = NotificationChannel(CHANNEL_SYNC, "EduConnect Sync",
                    NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Silent background message synchronization"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildSilentNotification(): Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        val tapPending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(null)
            .setContentText(null)
            .setContentIntent(tapPending)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
