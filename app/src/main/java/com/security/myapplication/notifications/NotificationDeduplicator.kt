package com.security.myapplication.notifications

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedHashSet

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * NOTIFICATION DEDUPLICATOR (Unified Seen & Notified Registry)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Guarantees that FCM push and background polling never show duplicate notifications.
 * Whichever engine processes a message first marks it here. The second engine checks
 * [isAlreadyNotified] and immediately drops the redundant event.
 */
object NotificationDeduplicator {

    private const val TAG = "NotifDeduplicator"
    private const val PREFS_NAME = "educonnect_notif_prefs"
    private const val KEY_PREFIX_SEEN = "seen_msg_"
    private const val KEY_NOTIFIED_IDS = "notified_message_ids_"
    private const val MAX_TRACKED_IDS = 1000

    // Thread-safe set for recently notified message keys: "${userId}_${chatType}_${chatId}_${msgId}"
    // Insertion order is retained so the oldest exact IDs are trimmed first.
    // Access is guarded by this object's monitor.
    private val notifiedIdsSet = LinkedHashSet<String>()

    // Tracks high-water marks for the polling fallback only. It must never be
    // used as notification deduplication because FCM can arrive out of order.
    private val maxSeenIdMap = ConcurrentHashMap<String, Int>()

    @Volatile
    private var initializedUserId: Int = -1

    /**
     * Initializes cache from SharedPreferences for the given user. Safe to call repeatedly.
     */
    fun init(context: Context, userId: Int) {
        if (userId <= 0) return
        if (initializedUserId == userId) return

        synchronized(this) {
            if (initializedUserId == userId) return
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val userPrefix = "${KEY_PREFIX_SEEN}${userId}_"
                maxSeenIdMap.clear()
                notifiedIdsSet.clear()
                for ((k, v) in prefs.all) {
                    if (k.startsWith(userPrefix)) {
                        val rawKey = k.removePrefix(userPrefix)
                        val intVal = (v as? Int) ?: continue
                        val existing = maxSeenIdMap[rawKey] ?: 0
                        if (intVal > existing) {
                            maxSeenIdMap[rawKey] = intVal
                        }
                    }
                }

                // Load saved recent notified IDs
                val savedSet = prefs.getStringSet("${KEY_NOTIFIED_IDS}${userId}", null)
                if (savedSet != null) {
                    notifiedIdsSet.addAll(savedSet)
                }
                initializedUserId = userId
                Log.d(TAG, "Initialized deduplicator for user $userId with ${maxSeenIdMap.size} conversations")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to init NotificationDeduplicator: ${e.message}")
            }
        }
    }

    /**
     * Returns true if this message has already been notified, or if it belongs to an actively
     * open chat screen where notifications should be silently suppressed.
     */
    fun isAlreadyNotified(
        context: Context,
        userId: Int,
        chatType: String,
        chatId: Int,
        messageId: Int
    ): Boolean {
        if (userId <= 0 || messageId <= 0) return false
        init(context, userId)

        val normType = chatType.trim().lowercase()
        val convKey = "${normType}_$chatId"
        val fullMsgKey = "${userId}_${convKey}_$messageId"

        // 1. Direct message ID hit in recently notified set
        if (synchronized(this) { notifiedIdsSet.contains(fullMsgKey) }) {
            return true
        }

        // 2. Active chat suppression: if user is actively viewing this chat in foreground,
        // mark it as notified so it never pops a delayed notification later.
        val isActiveChat = AppMessageNotificationWatcher.isAppInForeground &&
                (AppMessageNotificationWatcher.activeChatTypePublic?.equals(normType, ignoreCase = true) == true) &&
                (AppMessageNotificationWatcher.activeChatIdPublic == chatId)
        if (isActiveChat) {
            markNotified(context, userId, normType, chatId, messageId)
            return true
        }

        return false
    }

    /** Atomically claims an exact message ID across FCM and fallback polling. */
    fun claimIfNew(
        context: Context,
        userId: Int,
        chatType: String,
        chatId: Int,
        messageId: Int
    ): Boolean {
        if (userId <= 0 || messageId <= 0) return false
        init(context, userId)
        val normType = chatType.trim().lowercase()
        val fullMsgKey = "${userId}_${normType}_${chatId}_$messageId"

        synchronized(this) {
            if (!notifiedIdsSet.add(fullMsgKey)) return false
            trimOldestLocked()
            persistExactIdsLocked(context, userId)
            return true
        }
    }

    /** Releases a claim when Android could not actually post the notification. */
    fun releaseClaim(
        context: Context,
        userId: Int,
        chatType: String,
        chatId: Int,
        messageId: Int
    ) {
        if (userId <= 0 || messageId <= 0) return
        val normType = chatType.trim().lowercase()
        val fullMsgKey = "${userId}_${normType}_${chatId}_$messageId"
        synchronized(this) {
            if (notifiedIdsSet.remove(fullMsgKey)) {
                persistExactIdsLocked(context, userId)
            }
        }
    }

    /** Marks an intentionally suppressed/already-consumed message. */
    fun markNotified(
        context: Context,
        userId: Int,
        chatType: String,
        chatId: Int,
        messageId: Int
    ) {
        claimIfNew(context, userId, chatType, chatId, messageId)
    }

    private fun trimOldestLocked() {
        while (notifiedIdsSet.size > MAX_TRACKED_IDS) {
            val iterator = notifiedIdsSet.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun persistExactIdsLocked(context: Context, userId: Int) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val recentForUser = notifiedIdsSet
                .filter { it.startsWith("${userId}_") }
                .takeLast(500)
                .toSet()
            prefs.edit().putStringSet("${KEY_NOTIFIED_IDS}${userId}", recentForUser).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist exact notification IDs: ${e.message}")
        }
    }

    /**
     * Gets highest seen message ID for a conversation.
     */
    fun getMaxSeenId(userId: Int, chatType: String, chatId: Int): Int {
        val normType = chatType.trim().lowercase()
        val convKey = "${normType}_$chatId"
        return maxSeenIdMap[convKey] ?: 0
    }

    /**
     * Wipes deduplication cache for user on logout.
     */
    fun clearForUser(context: Context, userId: Int) {
        try {
            notifiedIdsSet.clear()
            maxSeenIdMap.clear()
            initializedUserId = -1
            if (userId > 0) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                val userPrefix = "${KEY_PREFIX_SEEN}${userId}_"
                for (k in prefs.all.keys) {
                    if (k.startsWith(userPrefix)) {
                        editor.remove(k)
                    }
                }
                editor.remove("${KEY_NOTIFIED_IDS}${userId}")
                editor.apply()
                Log.d(TAG, "Cleared deduplicator state for user $userId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear deduplicator for user $userId: ${e.message}")
        }
    }
}
