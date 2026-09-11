package com.security.myapplication.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.security.myapplication.R
import com.security.myapplication.models.AppMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * EDUCONNECT NOTIFICATION MANAGER — Clean Native Style
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Uses standard Android notification APIs only — no custom RemoteViews cards.
 * The system (Android OS) renders the notification container in its native style,
 * exactly like WhatsApp. We only supply content: title, body text, large icon
 * (sender avatar), inline Reply action, and sound.
 *
 * Message types and their notification text:
 *   Text        →  "Hello there!"
 *   Voice note  →  "🎤 Voice message (0:18)"
 *   Image       →  "📷 Photo" / "📷 Caption"
 *   Video       →  "🎥 Video" / "🎥 Caption"
 *   Audio file  →  "🎵 filename.mp3"
 *   Document    →  "📄 filename.pdf"
 */
object AppNotificationManager {

    // ── Notification Channels ─────────────────────────────────────────────────

    /** Incoming messages — high importance, custom sound, vibration */
    const val CHANNEL_MESSAGES = "educonnect_messages_v5"
    const val CHANNEL_MESSAGES_NAME = "EduConnect Messages"

    /** Active voice / audio playback controls (silent, ongoing) */
    const val CHANNEL_VOICE_PLAYBACK = "educonnect_voice_playback"
    const val CHANNEL_VOICE_PLAYBACK_NAME = "Voice & Audio Playback"

    /** File upload / download progress (silent) */
    const val CHANNEL_TRANSFERS = "sc_transfers"
    const val CHANNEL_TRANSFERS_NAME = "File Transfers"

    private const val NOTIF_ID_TRANSFER_BASE = 900000
    private const val PREFS_NOTIFICATION_HISTORY = "educonnect_notification_history"
    private const val MAX_CONVERSATION_MESSAGES = 6
    private const val MESSAGE_ALERT_COOLDOWN_MS = 1_500L
    private val lastAlertAtByConversation = ConcurrentHashMap<String, Long>()

    private data class ConversationMessage(
        val senderName: String,
        val body: String,
        val timestampMs: Long
    )

    /**
     * Keep every incoming message visible in the conversation notification, but
     * avoid a sound/vibration burst when several messages arrive together.
     */
    private fun shouldAlertForConversation(groupKey: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastAlertAtByConversation.put(groupKey, now)
        if (lastAlertAtByConversation.size > 2_000) {
            lastAlertAtByConversation.clear()
            lastAlertAtByConversation[groupKey] = now
        }
        return previous == null || now - previous >= MESSAGE_ALERT_COOLDOWN_MS
    }

    // ── Sound URI ─────────────────────────────────────────────────────────────

    fun getNotificationSoundUri(context: Context): Uri =
        Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.educonnect_notification}")

    /** True only when Android and the incoming-message channel can display alerts. */
    fun canPostMessageNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel(CHANNEL_MESSAGES)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    // ── Notification ID helpers ───────────────────────────────────────────────

    fun getMessageNotifId(isGroup: Boolean, conversationId: Int, messageId: Int? = null): Int {
        val baseOffset = if (isGroup) 600_000_000 else 100_000_000
        // One notification per conversation prevents a burst of messages from
        // flooding the shade; MessagingStyle below carries the recent history.
        return baseOffset + (conversationId % 400_000_000)
    }

    fun getConversationGroupKey(isGroup: Boolean, conversationId: Int): String =
        if (isGroup) "edu_chat_group_$conversationId" else "edu_chat_direct_$conversationId"

    fun getSummaryNotifId(isGroup: Boolean, conversationId: Int): Int {
        val prefix = if (isGroup) 700000 else 800000
        return prefix + (conversationId % 90000)
    }

    private fun conversationHistoryKey(groupKey: String) = "history_$groupKey"

    private fun getConversationHistory(context: Context, groupKey: String): List<ConversationMessage> {
        return try {
            val raw = context.getSharedPreferences(PREFS_NOTIFICATION_HISTORY, Context.MODE_PRIVATE)
                .getString(conversationHistoryKey(groupKey), "[]") ?: "[]"
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.optJSONObject(index) ?: continue
                    add(
                        ConversationMessage(
                            senderName = item.optString("sender", "Chat"),
                            body = item.optString("body", ""),
                            timestampMs = item.optLong("time", System.currentTimeMillis())
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun appendConversationMessage(
        context: Context,
        groupKey: String,
        senderName: String,
        body: String,
        timestampMs: Long
    ): List<ConversationMessage> {
        val history = getConversationHistory(context, groupKey).toMutableList()
        history.add(ConversationMessage(senderName, body, timestampMs))
        val kept = history.takeLast(MAX_CONVERSATION_MESSAGES)
        try {
            val json = JSONArray()
            kept.forEach { message ->
                json.put(
                    JSONObject()
                        .put("sender", message.senderName)
                        .put("body", message.body)
                        .put("time", message.timestampMs)
                )
            }
            context.getSharedPreferences(PREFS_NOTIFICATION_HISTORY, Context.MODE_PRIVATE).edit()
                .putString(conversationHistoryKey(groupKey), json.toString())
                .apply()
        } catch (_: Exception) {}
        return kept
    }

    fun clearConversationHistory(context: Context, isGroup: Boolean, conversationId: Int) {
        val groupKey = getConversationGroupKey(isGroup, conversationId)
        try {
            context.getSharedPreferences(PREFS_NOTIFICATION_HISTORY, Context.MODE_PRIVATE).edit()
                .remove(conversationHistoryKey(groupKey))
                .apply()
        } catch (_: Exception) {}
    }

    /** Removes all notification-only message previews when the account changes. */
    fun clearAllConversationHistory(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NOTIFICATION_HISTORY, Context.MODE_PRIVATE).edit()
                .clear()
                .apply()
        } catch (_: Exception) {}
    }

    // ── Group summary (stacking, WhatsApp style) ──────────────────────────────

    fun updateGroupSummaryNotification(
        context: Context,
        currentUserId: Int,
        isGroup: Boolean,
        conversationId: Int,
        conversationTitle: String,
        senderRole: String = "Student",
        senderProfilePic: String? = null
    ) {
        val groupKey = getConversationGroupKey(isGroup, conversationId)
        val summaryId = getSummaryNotifId(isGroup, conversationId)

        val contentIntent = if (isGroup) {
            NotificationRouter.createGroupChatIntent(
                context, currentUserId, conversationId, conversationTitle, null, summaryId
            )
        } else {
            NotificationRouter.createPersonalChatIntent(
                context, currentUserId, conversationId, conversationTitle,
                senderRole, senderProfilePic, null, summaryId
            )
        }

        val messageCount = getConversationHistory(context, groupKey).size
        val summaryBuilder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif_small)
            .setColor(0xFF8B6BFF.toInt())
            .setContentTitle(conversationTitle)
            .setContentText(if (messageCount == 1) "1 new message" else "$messageCount new messages")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        notifySafely(context, summaryId, summaryBuilder.build())
    }

    /**
     * Parses a raw ISO timestamp string and returns a static relative-time label.
     * NOTE: This is NOT used for the notification timestamp display anymore.
     * Live auto-updating relative time ("now" → "2m ago" → "1h ago") is handled
     * by .setWhen(epochMs) + .setShowWhen(true) on the Notification — the Android OS
     * updates it automatically, exactly like WhatsApp. This function is kept as a
     * utility for other UI elements that need a one-time formatted string.
     */
    fun formatRelativeTime(rawTimestamp: String?): String {
        if (rawTimestamp.isNullOrBlank()) return "now"
        if (rawTimestamp.equals("now", ignoreCase = true) ||
            rawTimestamp.equals("just now", ignoreCase = true)) return "now"

        val epochMs = tryParseTimestamp(rawTimestamp) ?: return "now"
        val diffMs = (System.currentTimeMillis() - epochMs).coerceAtLeast(0)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(diffMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        val hours   = TimeUnit.MILLISECONDS.toHours(diffMs)
        val days    = TimeUnit.MILLISECONDS.toDays(diffMs)

        return when {
            seconds < 60  -> "now"
            minutes == 1L -> "1m ago"
            minutes < 60  -> "${minutes}m ago"
            hours == 1L   -> "1h ago"
            hours < 24    -> "${hours}h ago"
            days == 1L    -> "Yesterday"
            days < 7      -> "${days}d ago"
            else -> {
                val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
                SimpleDateFormat("MMM d", Locale.getDefault()).format(cal.time)
            }
        }
    }

    /** Clock time (e.g. "10:45 AM") in device local timezone. */
    fun formatClockTime(rawTimestamp: String?): String {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
        if (rawTimestamp.isNullOrBlank()) return sdf.format(Date())
        val epochMs = tryParseTimestamp(rawTimestamp)
        return if (epochMs != null) sdf.format(Date(epochMs)) else sdf.format(Date())
    }

    private fun tryParseTimestamp(raw: String): Long? {
        val cleanRaw = raw.trim()
        cleanRaw.toLongOrNull()?.let { return it }

        val utcFormats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",    "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",         "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss.SSSSSS",        "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (fmt in utcFormats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
                sdf.parse(cleanRaw)?.let { return it.time }
            } catch (_: Exception) {}
        }
        for (fmt in listOf("hh:mm a", "HH:mm")) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.getDefault()).apply { isLenient = false }
                val date = sdf.parse(cleanRaw)
                if (date != null) {
                    val cal = Calendar.getInstance()
                    val timeCal = Calendar.getInstance().apply { time = date }
                    cal.set(Calendar.HOUR_OF_DAY, timeCal.get(Calendar.HOUR_OF_DAY))
                    cal.set(Calendar.MINUTE, timeCal.get(Calendar.MINUTE))
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
            } catch (_: Exception) {}
        }
        return null
    }

    // ── Sender Avatar (large icon — circular, no app logo) ───────────────────

    /**
     * Returns a circular sender avatar bitmap for use as the notification large icon.
     * - Decodes and center-crops the sender's actual profile picture if available.
     * - Otherwise generates a colored circle with the sender's first initial.
     * Never uses or falls back to the application logo.
     */
    fun getSenderAvatarBitmap(
        context: Context,
        senderName: String,
        profilePicBase64: String?,
        sizeDp: Int = 48
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt().coerceAtLeast(48)

        if (!profilePicBase64.isNullOrBlank()) {
            try {
                val cleanB64 = profilePicBase64.substringAfter("base64,")
                val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (rawBitmap != null) {
                    val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(output)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    val radius = sizePx / 2f
                    canvas.drawCircle(radius, radius, radius, paint)
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    val srcRect = if (rawBitmap.width >= rawBitmap.height) {
                        val offset = (rawBitmap.width - rawBitmap.height) / 2
                        Rect(offset, 0, offset + rawBitmap.height, rawBitmap.height)
                    } else {
                        val offset = (rawBitmap.height - rawBitmap.width) / 2
                        Rect(0, offset, rawBitmap.width, offset + rawBitmap.width)
                    }
                    canvas.drawBitmap(rawBitmap, srcRect, Rect(0, 0, sizePx, sizePx), paint)
                    return output
                }
            } catch (_: Exception) {}
        }

        // Initials avatar
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5B3FCC.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
        val letter = senderName.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "U"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = sizePx * 0.46f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val yOffset = (canvas.height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(letter, canvas.width / 2f, yOffset, textPaint)
        return output
    }

    // ── Channel Initialization ────────────────────────────────────────────────

    fun initializeChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val soundUri = getNotificationSoundUri(context)
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // Delete legacy channels
            try {
                nm.deleteNotificationChannel("educonnect_messages")
                nm.deleteNotificationChannel("educonnect_messages_v1")
                nm.deleteNotificationChannel("educonnect_messages_v2")
                nm.deleteNotificationChannel("educonnect_messages_v3")
                nm.deleteNotificationChannel("educonnect_messages_v4")
            } catch (_: Exception) {}

            // 1. Messages channel — high importance, custom EduConnect sound
            val msgChannel = NotificationChannel(
                CHANNEL_MESSAGES, CHANNEL_MESSAGES_NAME, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming personal and group chat messages"
                enableLights(true)
                lightColor = 0xFF8B6BFF.toInt()
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 100, 250)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(soundUri, audioAttrs)
            }

            // 2. Voice playback — low importance, no sound
            val playbackChannel = NotificationChannel(
                CHANNEL_VOICE_PLAYBACK, CHANNEL_VOICE_PLAYBACK_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active voice message and audio playback controls"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // 3. Transfers — low importance, no sound
            val transferChannel = NotificationChannel(
                CHANNEL_TRANSFERS, CHANNEL_TRANSFERS_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "File upload and download progress"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            nm.createNotificationChannel(msgChannel)
            nm.createNotificationChannel(playbackChannel)
            nm.createNotificationChannel(transferChannel)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SHARED BUILDER HELPER — constructs a clean native notification
    // No RemoteViews, no custom card backgrounds, no duplicate logos.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a clean, WhatsApp-style message notification using only standard APIs.
     * The Android system renders the outer container natively; we only provide content.
     *
     * [timestampMs] — epoch millis of the actual message arrival time.
     *   • setWhen(timestampMs) + setShowWhen(true) let the OS display and AUTO-UPDATE
     *     the relative timestamp ("now" → "2m ago" → "1h ago") for free, exactly like
     *     WhatsApp does — no manual timer or periodic refresh needed.
     */
    private fun buildMessageNotification(
        context: Context,
        channel: String,
        title: String,
        contentText: String,
        contentIntent: PendingIntent,
        largeIcon: Bitmap?,
        groupKey: String,
        replyAction: NotificationCompat.Action?,
        markAsReadAction: NotificationCompat.Action?,
        bigPicture: Bitmap? = null,
        bigText: String? = null,
        timestampMs: Long = System.currentTimeMillis()
    ): Notification {
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notif_small)
            .setColor(0xFF8B6BFF.toInt())
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setGroup(groupKey)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // Custom sound (used on API < 26; on API 26+ the channel controls sound)
            .setSound(getNotificationSoundUri(context))
            // Explicit vibrate pattern — do NOT use DEFAULT_VIBRATE/DEFAULT_SOUND
            // to avoid triggering the system default sound on top of our custom sound
            .setVibrate(longArrayOf(0, 250, 100, 250))
            .setLights(0xFF8B6BFF.toInt(), 500, 500)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // The id stays stable per conversation, but an incoming message is
            // a new event and must still alert when this notification updates.
            .setOnlyAlertOnce(false)
            // ── Live auto-updating timestamp (WhatsApp style) ──────────────────
            // Android OS reads setWhen() and continuously updates the displayed
            // relative time string without any code on our side.
            .setWhen(timestampMs)
            .setShowWhen(true)

        if (!shouldAlertForConversation(groupKey)) {
            builder.setSilent(true)
        }

        if (largeIcon != null) builder.setLargeIcon(largeIcon)
        if (replyAction != null) builder.addAction(replyAction)
        if (markAsReadAction != null) builder.addAction(markAsReadAction)

        val isGroupConversation = groupKey.startsWith("edu_chat_group_")
        val senderName = if (isGroupConversation) contentText.substringBefore(": ", title) else title
        val messageBody = if (isGroupConversation) contentText.substringAfter(": ", contentText) else contentText
        val history = appendConversationMessage(context, groupKey, senderName, messageBody, timestampMs)

        when {
            bigPicture != null -> builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bigPicture)
                    .setBigContentTitle(title)
                    .setSummaryText(contentText)
            )
            else -> {
                val currentUser = Person.Builder().setName("You").build()
                val style = NotificationCompat.MessagingStyle(currentUser)
                    .setConversationTitle(if (isGroupConversation) title else null)
                    .setGroupConversation(isGroupConversation)
                history.forEach { message ->
                    style.addMessage(
                        NotificationCompat.MessagingStyle.Message(
                            message.body,
                            message.timestampMs,
                            Person.Builder().setName(message.senderName).build()
                        )
                    )
                }
                builder.setStyle(style)
            }
        }

        return builder.build()
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Action builders (Reply + Mark as read)
    // ─────────────────────────────────────────────────────────────────────────

    private fun createReplyAction(
        context: Context,
        isGroup: Boolean,
        currentUserId: Int,
        targetId: Int,
        notifId: Int,
        senderName: String
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT_REPLY)
            .setLabel("Reply…")
            .build()
        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_DIRECT_REPLY
            putExtra(NotificationReplyReceiver.EXTRA_IS_GROUP, isGroup)
            putExtra(NotificationReplyReceiver.EXTRA_CURRENT_USER_ID, currentUserId)
            putExtra(NotificationReplyReceiver.EXTRA_TARGET_ID, targetId)
            putExtra(NotificationReplyReceiver.EXTRA_NOTIFICATION_ID, notifId)
            putExtra(NotificationReplyReceiver.EXTRA_SENDER_NAME, senderName)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val replyPendingIntent = PendingIntent.getBroadcast(context, notifId + 1000, replyIntent, flags)
        return NotificationCompat.Action.Builder(0, "Reply", replyPendingIntent)
            .addRemoteInput(remoteInput).build()
    }

    private fun createMarkAsReadAction(
        context: Context,
        isGroup: Boolean,
        currentUserId: Int,
        targetId: Int,
        notifId: Int
    ): NotificationCompat.Action {
        val readIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_MARK_AS_READ
            putExtra(NotificationReplyReceiver.EXTRA_IS_GROUP, isGroup)
            putExtra(NotificationReplyReceiver.EXTRA_CURRENT_USER_ID, currentUserId)
            putExtra(NotificationReplyReceiver.EXTRA_TARGET_ID, targetId)
            putExtra(NotificationReplyReceiver.EXTRA_NOTIFICATION_ID, notifId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val readPendingIntent = PendingIntent.getBroadcast(context, notifId + 3000, readIntent, flags)
        return NotificationCompat.Action.Builder(0, "Mark as read", readPendingIntent).build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. TEXT MESSAGE
    // ─────────────────────────────────────────────────────────────────────────

    fun showTextMessageNotification(
        context: Context,
        currentUserId: Int,
        senderId: Int,
        senderName: String,
        messageText: String,
        messageId: Int,
        isGroup: Boolean = false,
        groupId: Int = 0,
        groupName: String = "",
        senderRole: String = "Student",
        senderProfilePic: String? = null,
        timestampStr: String = "now"
    ) {
        initializeChannels(context)

        val targetId = if (isGroup) groupId else senderId
        val notifId = getMessageNotifId(isGroup, targetId, messageId)
        val title = if (isGroup) groupName else senderName
        val contentText = if (isGroup) "$senderName: $messageText" else messageText

        val contentIntent = if (isGroup) {
            NotificationRouter.createGroupChatIntent(context, currentUserId, groupId, groupName, messageId, notifId)
        } else {
            NotificationRouter.createPersonalChatIntent(context, currentUserId, senderId, senderName, senderRole, senderProfilePic, messageId, notifId)
        }

        val largeIcon = getSenderAvatarBitmap(context, senderName, senderProfilePic)
        val replyAction = createReplyAction(context, isGroup, currentUserId, targetId, notifId, senderName)
        val markAsReadAction = createMarkAsReadAction(context, isGroup, currentUserId, targetId, notifId)
        val groupKey = getConversationGroupKey(isGroup, targetId)
        val timestampMs = tryParseTimestamp(timestampStr) ?: System.currentTimeMillis()

        val notif = buildMessageNotification(
            context = context,
            channel = CHANNEL_MESSAGES,
            title = title,
            contentText = contentText,
            contentIntent = contentIntent,
            largeIcon = largeIcon,
            groupKey = groupKey,
            replyAction = replyAction,
            markAsReadAction = markAsReadAction,
            bigText = contentText,
            timestampMs = timestampMs
        )
        notifySafely(context, notifId, notif)

        updateGroupSummaryNotification(
            context, currentUserId, isGroup, targetId, title, senderRole, senderProfilePic
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. INCOMING VOICE NOTE (alert-style, no playback controls)
    // ─────────────────────────────────────────────────────────────────────────

    fun showIncomingVoiceNotification(
        context: Context,
        currentUserId: Int,
        senderId: Int,
        senderName: String,
        durationSeconds: Int,
        messageId: Int,
        isGroup: Boolean = false,
        groupId: Int = 0,
        groupName: String = "",
        senderRole: String = "Student",
        senderProfilePic: String? = null,
        timestampStr: String = "now"
    ) {
        initializeChannels(context)

        val targetId = if (isGroup) groupId else senderId
        val notifId = getMessageNotifId(isGroup, targetId, messageId)
        val title = if (isGroup) groupName else senderName

        val durMin = durationSeconds.coerceAtLeast(0) / 60
        val durSec = durationSeconds.coerceAtLeast(0) % 60
        val durationStr = if (durationSeconds > 0) " (%02d:%02d)".format(durMin, durSec) else ""
        val contentText = if (isGroup) "$senderName: 🎤 Voice message$durationStr"
                          else "🎤 Voice message$durationStr"

        val contentIntent = if (isGroup) {
            NotificationRouter.createGroupChatIntent(context, currentUserId, groupId, groupName, messageId, notifId)
        } else {
            NotificationRouter.createPersonalChatIntent(context, currentUserId, senderId, senderName, senderRole, senderProfilePic, messageId, notifId)
        }

        val largeIcon = getSenderAvatarBitmap(context, senderName, senderProfilePic)
        val replyAction = createReplyAction(context, isGroup, currentUserId, targetId, notifId, senderName)
        val markAsReadAction = createMarkAsReadAction(context, isGroup, currentUserId, targetId, notifId)
        val groupKey = getConversationGroupKey(isGroup, targetId)
        val timestampMs = tryParseTimestamp(timestampStr) ?: System.currentTimeMillis()

        val notif = buildMessageNotification(
            context = context,
            channel = CHANNEL_MESSAGES,
            title = title,
            contentText = contentText,
            contentIntent = contentIntent,
            largeIcon = largeIcon,
            groupKey = groupKey,
            replyAction = replyAction,
            markAsReadAction = markAsReadAction,
            timestampMs = timestampMs
        )
        notifySafely(context, notifId, notif)

        updateGroupSummaryNotification(
            context, currentUserId, isGroup, targetId, title, senderRole, senderProfilePic
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. ACTIVE VOICE / AUDIO PLAYBACK (shown during GlobalAudioPlayer playback)
    //    NOTE: The actual playback notification is built inside VoiceAudioService
    //    using native MediaStyle. This entry point delegates to showIncomingVoiceNotification
    //    when playback has not started yet.
    // ─────────────────────────────────────────────────────────────────────────

    fun showVoiceMessageNotification(
        context: Context,
        currentUserId: Int,
        senderId: Int,
        senderName: String,
        mediaUrl: String,
        durationSeconds: Int,
        messageId: Int,
        currentPosSeconds: Int = 0,
        isPlaying: Boolean = false,
        isGroup: Boolean = false,
        groupId: Int = 0,
        groupName: String = "",
        senderRole: String = "Student",
        senderProfilePic: String? = null,
        timestampStr: String = "now"
    ) {
        // Delegate to incoming voice alert if playback hasn't started
        showIncomingVoiceNotification(
            context, currentUserId, senderId, senderName, durationSeconds, messageId,
            isGroup, groupId, groupName, senderRole, senderProfilePic, timestampStr
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. IMAGE / PHOTO
    // ─────────────────────────────────────────────────────────────────────────

    fun showImageNotification(
        context: Context,
        currentUserId: Int,
        senderId: Int,
        senderName: String,
        imageBitmap: Bitmap?,
        caption: String?,
        messageId: Int,
        isGroup: Boolean = false,
        groupId: Int = 0,
        groupName: String = "",
        senderRole: String = "Student",
        senderProfilePic: String? = null,
        timestampStr: String = "now",
        mediaUrl: String? = null
    ) {
        initializeChannels(context)

        val targetId = if (isGroup) groupId else senderId
        val notifId = getMessageNotifId(isGroup, targetId, messageId)
        val title = if (isGroup) groupName else senderName
        val bodyText = if (!caption.isNullOrBlank()) caption else "Photo"
        val contentText = if (isGroup) "$senderName: 📷 $bodyText" else "📷 $bodyText"

        val contentIntent = if (isGroup) {
            NotificationRouter.createGroupChatIntent(context, currentUserId, groupId, groupName, messageId, notifId)
        } else {
            NotificationRouter.createPersonalChatIntent(context, currentUserId, senderId, senderName, senderRole, senderProfilePic, messageId, notifId)
        }

        val largeIcon = getSenderAvatarBitmap(context, senderName, senderProfilePic)
        val replyAction = createReplyAction(context, isGroup, currentUserId, targetId, notifId, senderName)
        val markAsReadAction = createMarkAsReadAction(context, isGroup, currentUserId, targetId, notifId)
        val groupKey = getConversationGroupKey(isGroup, targetId)
        val timestampMs = tryParseTimestamp(timestampStr) ?: System.currentTimeMillis()

        // Resolve thumbnail bitmap: use provided bitmap, or fetch/decode from mediaUrl (WhatsApp style)
        val resolvedBitmap = imageBitmap ?: if (!mediaUrl.isNullOrBlank()) {
            com.security.myapplication.models.MediaManager.loadNotificationThumbnail(context, messageId, mediaUrl, isVideo = false)
        } else null

        val notif = buildMessageNotification(
            context = context,
            channel = CHANNEL_MESSAGES,
            title = title,
            contentText = contentText,
            contentIntent = contentIntent,
            largeIcon = largeIcon,
            groupKey = groupKey,
            replyAction = replyAction,
            markAsReadAction = markAsReadAction,
            bigPicture = resolvedBitmap,
            timestampMs = timestampMs
        )
        notifySafely(context, notifId, notif)

        updateGroupSummaryNotification(
            context, currentUserId, isGroup, targetId, title, senderRole, senderProfilePic
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. VIDEO
    // ─────────────────────────────────────────────────────────────────────────

    fun showVideoNotification(
        context: Context,
        currentUserId: Int,
        senderId: Int,
        senderName: String,
        videoThumbnail: Bitmap?,
        caption: String?,
        messageId: Int,
        isGroup: Boolean = false,
        groupId: Int = 0,
        groupName: String = "",
        senderRole: String = "Student",
        senderProfilePic: String? = null,
        timestampStr: String = "now",
        mediaUrl: String? = null
    ) {
        initializeChannels(context)

        val targetId = if (isGroup) groupId else senderId
        val notifId = getMessageNotifId(isGroup, targetId, messageId)
        val title = if (isGroup) groupName else senderName
        val bodyText = if (!caption.isNullOrBlank()) caption else "Video"
        val contentText = if (isGroup) "$senderName: 🎥 $bodyText" else "🎥 $bodyText"

        val contentIntent = if (isGroup) {
            NotificationRouter.createGroupChatIntent(context, currentUserId, groupId, groupName, messageId, notifId)
        } else {
            NotificationRouter.createPersonalChatIntent(context, currentUserId, senderId, senderName, senderRole, senderProfilePic, messageId, notifId)
        }

        val largeIcon = getSenderAvatarBitmap(context, senderName, senderProfilePic)
        val replyAction = createReplyAction(context, isGroup, currentUserId, targetId, notifId, senderName)
        val markAsReadAction = createMarkAsReadAction(context, isGroup, currentUserId, targetId, notifId)
        val groupKey = getConversationGroupKey(isGroup, targetId)
        val timestampMs = tryParseTimestamp(timestampStr) ?: System.currentTimeMillis()

        // Resolve thumbnail bitmap: use provided bitmap, or extract from video mediaUrl (WhatsApp style)
        val resolvedBitmap = videoThumbnail ?: if (!mediaUrl.isNullOrBlank()) {
            com.security.myapplication.models.MediaManager.loadNotificationThumbnail(context, messageId, mediaUrl, isVideo = true)
        } else null

        val notif = buildMessageNotification(
            context = context,
            channel = CHANNEL_MESSAGES,
            title = title,
            contentText = contentText,
            contentIntent = contentIntent,
            largeIcon = largeIcon,
            groupKey = groupKey,
            replyAction = replyAction,
            markAsReadAction = markAsReadAction,
            bigPicture = resolvedBitmap,
            timestampMs = timestampMs
        )
        notifySafely(context, notifId, notif)

        updateGroupSummaryNotification(
            context, currentUserId, isGroup, targetId, title, senderRole, senderProfilePic
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. FILE / DOCUMENT / AUDIO FILE
    // ─────────────────────────────────────────────────────────────────────────

    fun showFileNotification(
        context: Context,
        currentUserId: Int,
        senderId: Int,
        senderName: String,
        fileName: String,
        fileSizeStr: String? = null,
        messageId: Int,
        isGroup: Boolean = false,
        groupId: Int = 0,
        groupName: String = "",
        senderRole: String = "Student",
        senderProfilePic: String? = null,
        timestampStr: String = "now"
    ) {
        initializeChannels(context)

        val targetId = if (isGroup) groupId else senderId
        val notifId = getMessageNotifId(isGroup, targetId, messageId)
        val title = if (isGroup) groupName else senderName

        val ext = fileName.substringAfterLast(".", "").lowercase()
        val isAudio = ext in listOf("mp3", "wav", "m4a", "aac", "ogg", "opus", "flac", "wma", "amr", "3gp") ||
                      fileName.startsWith("🎵")
        val iconPrefix = if (isAudio) "🎵 " else "📄 "
        val cleanName = fileName.removePrefix("🎵 ").removePrefix("📄 ")
        val bodyText = if (!fileSizeStr.isNullOrBlank()) "$cleanName ($fileSizeStr)" else cleanName
        val contentText = if (isGroup) "$senderName: $iconPrefix$bodyText" else "$iconPrefix$bodyText"

        val contentIntent = if (isGroup) {
            NotificationRouter.createGroupChatIntent(context, currentUserId, groupId, groupName, messageId, notifId)
        } else {
            NotificationRouter.createPersonalChatIntent(context, currentUserId, senderId, senderName, senderRole, senderProfilePic, messageId, notifId)
        }

        val largeIcon = getSenderAvatarBitmap(context, senderName, senderProfilePic)
        val replyAction = createReplyAction(context, isGroup, currentUserId, targetId, notifId, senderName)
        val markAsReadAction = createMarkAsReadAction(context, isGroup, currentUserId, targetId, notifId)
        val groupKey = getConversationGroupKey(isGroup, targetId)
        val timestampMs = tryParseTimestamp(timestampStr) ?: System.currentTimeMillis()

        val notif = buildMessageNotification(
            context = context,
            channel = CHANNEL_MESSAGES,
            title = title,
            contentText = contentText,
            contentIntent = contentIntent,
            largeIcon = largeIcon,
            groupKey = groupKey,
            replyAction = replyAction,
            markAsReadAction = markAsReadAction,
            timestampMs = timestampMs
        )
        notifySafely(context, notifId, notif)

        updateGroupSummaryNotification(
            context, currentUserId, isGroup, targetId, title, senderRole, senderProfilePic
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. UNIFIED TRANSFER PROGRESS / COMPLETION (upload & download)
    //    These are already clean native notifications — no changes needed.
    // ─────────────────────────────────────────────────────────────────────────

    fun buildUnifiedTransferProgressNotification(
        context: Context,
        notifId: Int,
        title: String,
        fileName: String,
        isUpload: Boolean,
        percent: Int
    ): Notification {
        initializeChannels(context)
        val cleanDisplayName = when {
            fileName.startsWith("voice_") || fileName.startsWith("rec_") -> "Voice message"
            fileName.startsWith("temp_") || fileName.startsWith("upload_") ||
                    fileName.startsWith("download_") ||
                    fileName.all { it.isDigit() || it == '-' || it == '_' } ->
                if (isUpload) "File" else "Media"
            else -> fileName.ifBlank { if (isUpload) "File" else "Media" }
        }
        val body = if (percent >= 0) "$percent% • $cleanDisplayName" else "Starting… • $cleanDisplayName"
        val tapIntent = NotificationRouter.createPersonalChatIntent(context, 0, 0, "Transfers", requestCode = notifId)

        return NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(if (isUpload) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setColor(0xFF8B6BFF.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setContentIntent(tapIntent)
            .build()
    }

    fun buildUnifiedTransferCompletedNotification(
        context: Context,
        notifId: Int,
        title: String,
        fileName: String,
        isUpload: Boolean
    ): Notification {
        initializeChannels(context)
        val cleanDisplayName = when {
            fileName.startsWith("voice_") || fileName.startsWith("rec_") -> "Voice message"
            fileName.startsWith("temp_") || fileName.startsWith("upload_") ||
                    fileName.startsWith("download_") ||
                    fileName.all { it.isDigit() || it == '-' || it == '_' } ->
                if (isUpload) "File" else "Media"
            else -> fileName.ifBlank { if (isUpload) "File" else "Media" }
        }
        val tapIntent = NotificationRouter.createPersonalChatIntent(context, 0, 0, "Transfers", requestCode = notifId + 1)
        return NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(if (isUpload) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_sys_download_done)
            .setColor(0xFF8B6BFF.toInt())
            .setContentTitle(title)
            .setContentText(cleanDisplayName)
            .setAutoCancel(true)
            .setTimeoutAfter(4000)
            .setContentIntent(tapIntent)
            .build()
    }

    fun showMediaDownloadProgressNotification(context: Context, msgId: Int, fileName: String, mediaType: AppMediaType, percent: Int) {
        val typeLabel = when (mediaType) {
            AppMediaType.IMAGE    -> "Photo"
            AppMediaType.VIDEO    -> "Video"
            AppMediaType.AUDIO    -> "Audio"
            AppMediaType.DOCUMENT -> "Document"
        }
        val notifId = NOTIF_ID_TRANSFER_BASE + (msgId % 90000)
        notifySafely(context, notifId, buildUnifiedTransferProgressNotification(context, notifId, "Downloading $typeLabel", fileName, false, percent))
    }

    fun showMediaDownloadCompleteNotification(context: Context, msgId: Int, fileName: String, mediaType: AppMediaType) {
        val notifId = NOTIF_ID_TRANSFER_BASE + (msgId % 90000)
        cancelNotification(context, notifId)
        val typeLabel = when (mediaType) {
            AppMediaType.IMAGE    -> "Photo"
            AppMediaType.VIDEO    -> "Video"
            AppMediaType.AUDIO    -> "Audio"
            AppMediaType.DOCUMENT -> "Document"
        }
        notifySafely(context, notifId + 500, buildUnifiedTransferCompletedNotification(context, notifId + 500, "$typeLabel saved", fileName, false))
    }

    fun cancelMediaDownloadNotification(context: Context, msgId: Int) {
        cancelNotification(context, NOTIF_ID_TRANSFER_BASE + (msgId % 90000))
    }

    fun showMediaUploadProgressNotification(context: Context, tempId: Int, fileName: String, mediaType: String, percent: Int) {
        val typeLabel = when (mediaType.lowercase()) {
            "image", "photo"       -> "Photo"
            "video"                -> "Video"
            "voice", "voicenote"   -> "Voice Note"
            "audio"                -> "Audio"
            else                   -> "Document"
        }
        val notifId = NOTIF_ID_TRANSFER_BASE + (kotlin.math.abs(tempId) % 90000)
        notifySafely(context, notifId, buildUnifiedTransferProgressNotification(context, notifId, "Sending $typeLabel", fileName, true, percent))
    }

    fun cancelMediaUploadNotification(context: Context, tempId: Int) {
        cancelNotification(context, NOTIF_ID_TRANSFER_BASE + (kotlin.math.abs(tempId) % 90000))
    }

    fun showTransferProgressNotification(context: Context, transferId: String, fileName: String, direction: String, percent: Int): Notification {
        val notifId = (transferId.hashCode() and 0x7FFFFFFF) % 90000 + NOTIF_ID_TRANSFER_BASE
        val isUpload = direction.equals("Uploading", ignoreCase = true)
        val title = if (isUpload) "Uploading $fileName" else "Downloading $fileName"
        val notif = buildUnifiedTransferProgressNotification(context, notifId, title, fileName, isUpload, percent)
        notifySafely(context, notifId, notif)
        return notif
    }

    fun showTransferCompletedNotification(context: Context, transferId: String, fileName: String, direction: String) {
        val notifId = (transferId.hashCode() and 0x7FFFFFFF) % 90000 + NOTIF_ID_TRANSFER_BASE
        cancelNotification(context, notifId)
        val isUpload = direction.equals("Uploading", ignoreCase = true)
        val title = if (isUpload) "Upload complete" else "Download complete"
        notifySafely(context, notifId + 500, buildUnifiedTransferCompletedNotification(context, notifId + 500, title, fileName, isUpload))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cancel helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun cancelNotification(context: Context, notifId: Int) {
        try { NotificationManagerCompat.from(context).cancel(notifId) } catch (_: Exception) {}
    }

    fun cancelTransferNotification(context: Context, transferId: String) {
        cancelNotification(context, (transferId.hashCode() and 0x7FFFFFFF) % 90000 + NOTIF_ID_TRANSFER_BASE)
    }

    fun cancelAll(context: Context) {
        try { NotificationManagerCompat.from(context).cancelAll() } catch (_: Exception) {}
    }

    private fun notifySafely(context: Context, notifId: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not yet granted — silently suppressed
        } catch (_: Exception) {}
    }
}
