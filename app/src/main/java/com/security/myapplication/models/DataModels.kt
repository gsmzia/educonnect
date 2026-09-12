package com.security.myapplication.models

data class User(
    val id: Int,
    val email: String,
    val username: String? = null,
    val name: String = "",
    val role: String = "student",
    val degree: String? = null,
    val major: String? = null,
    val academic_year: String? = null,
    val profile_pic: String? = null,
    val university_id: Int? = null,
    val is_founder: Boolean? = false,
    val semester: Int? = null,
    val active_session_mode: String? = null
)

data class Group(
    val id: Int,
    val name: String,
    val degree: String = "",
    val major: String = "",
    val academic_year: String = "",
    val subject_id: Int = 0,
    val teacher_id: Int? = null,
    val profile_pic: String? = null,
    val university_id: Int? = null,
    val academic_subject_id: Int? = null,
    val academic_semester_id: Int? = null
)

data class Assignment(
    val title: String,
    val description: String,
    val file_url: String?
)

// ── Canonical Message Type Enum ─────────────────────────────────────────────
enum class MessageType(val wire: String) {
    TEXT("text"),
    IMAGE("image"),
    VOICE("voice"),
    VIDEO("video"),
    FILE("file"),
    AUDIO("audio"),
    UNKNOWN("");

    companion object {
        fun from(v: String?): MessageType =
            entries.find { it.wire.equals(v?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

data class ChatMessage(
    val id: Int,
    val group_id: Int,
    val sender_id: Int,
    val sender_name: String,
    val text: String,
    val timestamp: String?,
    val message_type: String = "text",
    val media_url: String? = null,
    val is_media_expired: Boolean = false,
    val sender_profile_pic: String? = null,
    val is_read: Boolean = false,
    val is_delivered: Boolean = false,
    val is_deleted: Boolean = false,
    val deleted_by: Int? = null,
    val is_pinned: Boolean = false,
    val pinned_at: String? = null,
    val idempotency_key: String? = null,
    val duration_sec: Int = 0
) {
    val typeEnum: MessageType get() = MessageType.from(message_type)
}

data class Attendance(
    val id: Int,
    val student_id: Int,
    val group_id: Int,
    val date: String,
    val status: String
)

// ── Request Models ──────────────────────────────────────────────────────────────

data class OTPRequest(val email: String)
data class SignupRequest(
    val email: String,
    val username: String,
    val name: String,
    val password: String,
    val role: String,
    val otp_code: String? = null
)
data class OnlineStatusResponse(
    val is_online: Boolean = false,
    val online: Boolean = false,
    val last_seen: String? = null,
    val user_id: Int = 0
) {
    val isUserOnline: Boolean get() = is_online || online
}
data class LoginRequest(val email: String, val password: String)
data class ChatRequest(val group_id: Int, val sender_id: Int, val text: String, val idempotency_key: String? = null)
data class AttendanceRequest(val student_id: Int, val group_id: Int, val date: String, val status: String)

data class MessageResponse(val message: String)

data class GroupCreateRequest(
    val name: String,
    val creator_id: Int,
    val member_ids: List<Int>,
    val profile_pic: String? = null
)

/** Request body for registering/updating a device FCM push token. */
data class FcmTokenRequest(
    val token: String,
    val platform: String = "android"
)
data class LoginResponse(
    val message: String,
    val user: User,
    val university_name: String? = null,
    val active_session_mode: String? = null,
    val access_token: String? = null,
    val token_type: String? = null
)
data class SessionModeRequest(val mode: String)
data class SessionModeResponse(
    val message: String,
    val active_session_mode: String
)
data class GroupResponse(val message: String, val group: Group)
data class ForgotPasswordRequest(val email: String)
data class ForgotPasswordReset(val email: String, val otp_code: String, val new_password: String)

// ── Program Ownership & Personal Chat ─────────────────────────────────────────

data class PersonalChatUser(
    val id: Int,
    val name: String,
    val username: String?,
    val role: String,
    val profile_pic: String? = null,
    val autoMessage: String? = null
)

data class PersonalMessage(
    val id: Int,
    val sender_id: Int,
    val receiver_id: Int,
    val text: String,
    val timestamp: String?,
    val message_type: String = "text",
    val media_url: String? = null,
    val is_media_expired: Boolean = false,
    val is_read: Boolean = false,
    val is_delivered: Boolean = false,
    val is_deleted: Boolean = false,
    val deleted_by: Int? = null,
    val is_pinned: Boolean = false,
    val pinned_at: String? = null,
    val idempotency_key: String? = null,
    val duration_sec: Int = 0
) {
    val typeEnum: MessageType get() = MessageType.from(message_type)
}

data class DeleteEveryoneRequest(
    val user_id: Int
)

/** Server acknowledgement for a persisted media-upload chunk. */
data class ResumableUploadChunkResponse(
    val received_bytes: Long,
    val total_bytes: Long,
    val completed: Boolean,
    val personal_message: PersonalMessage? = null,
    val group_message: ChatMessage? = null,
    val media_url: String? = null
)

data class PersonalMessageCreateRequest(
    val sender_id: Int,
    val receiver_id: Int,
    val text: String,
    val idempotency_key: String? = null
)

data class ForwardPersonalRequest(
    val sender_id: Int,
    val receiver_id: Int,
    val media_url: String,
    val message_type: String,
    val text: String = "",
    val idempotency_key: String? = null
)

data class ForwardGroupRequest(
    val sender_id: Int,
    val media_url: String,
    val message_type: String,
    val text: String = "",
    val idempotency_key: String? = null
)

data class UserProfileUpdateRequest(
    val name: String? = null,
    val profile_pic: String? = null
)
data class ApprovalRespondRequest(
    val user_id: Int,
    val status: String
)

data class LeaveGroupRequest(
    val user_id: Int
)

data class RemoveGroupMemberRequest(
    val admin_id: Int,
    val user_id: Int
)

data class PersonalBlockStatus(
    val is_blocked: Boolean = false,
    val is_blocked_by_me: Boolean = false,
    val is_blocked_by_other: Boolean = false
)

data class AddGroupMembersRequest(
    val user_ids: List<Int>
)

data class GroupMembershipResponse(
    val is_member: Boolean = true,
    val is_left: Boolean = false,
    val left_at: String? = null
)

data class GroupDetailsResponse(
    val id: Int,
    val name: String,
    val degree: String,
    val major: String,
    val academic_year: String,
    val teacher_id: Int?,
    val profile_pic: String?
)

data class GroupProfileUpdateRequest(
    val profile_pic: String
)

data class GroupProfileUpdateResponse(
    val message: String,
    val profile_pic: String?
)

data class HomeFeedItem(
    val type: String = "direct",          // "group" or "direct"
    val id: Int = 0,
    val name: String = "",
    val degree: String? = null,
    val major: String? = null,
    val academic_year: String? = null,
    val last_message: String? = null,
    val last_sender: String? = null,
    val timestamp: String? = null,
    val timestamp_raw: String? = null,
    val unread_count: Int = 0,
    val profile_pic: String? = null,   // only for direct chats
    val last_message_is_read: Boolean = false,
    val last_message_is_delivered: Boolean = false,
    val subject_id: Int? = null,
    val teacher_id: Int? = null,
    val username: String? = null,
    val last_message_id: Int? = null,
    val last_sender_id: Int? = null,
    val last_message_type: String? = null,
    val last_message_duration_sec: Int = -1,
    val role: String? = null,
    val is_left: Boolean = false
) {
    val typeEnum: MessageType get() = MessageType.from(last_message_type)

    // ✅ BUG #10 FIX: Fast lightweight structural equality check for Compose recomposition.
    // Compares profile_pic length/hash rather than whole 50KB-100KB Base64 strings.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HomeFeedItem) return false
        return id == other.id &&
                type == other.type &&
                last_message_id == other.last_message_id &&
                name == other.name &&
                last_message == other.last_message &&
                unread_count == other.unread_count &&
                last_message_is_read == other.last_message_is_read &&
                last_message_is_delivered == other.last_message_is_delivered &&
                timestamp_raw == other.timestamp_raw &&
                role == other.role &&
                is_left == other.is_left &&
                subject_id == other.subject_id &&
                teacher_id == other.teacher_id &&
                username == other.username &&
                profile_pic?.length == other.profile_pic?.length
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + type.hashCode()
        result = 31 * result + (last_message_id ?: 0)
        result = 31 * result + name.hashCode()
        result = 31 * result + last_message.hashCode()
        result = 31 * result + unread_count
        result = 31 * result + is_left.hashCode()
        result = 31 * result + (profile_pic?.length ?: 0)
        return result
    }
}


// ── Global Message Memory Cache (Instant 0ms Chat Load + Bounded Memory) ────────
object MessageCache {
    private const val MAX_CACHED_CONVERSATIONS = 50
    private const val MAX_MESSAGES_PER_CONV = 200

    private val personalCache = java.util.concurrent.ConcurrentHashMap<String, List<PersonalMessage>>()
    private val groupCache = java.util.concurrent.ConcurrentHashMap<Int, List<ChatMessage>>()

    fun getPersonal(u1: Int, u2: Int): List<PersonalMessage> {
        val key = if (u1 < u2) "${u1}_${u2}" else "${u2}_${u1}"
        return personalCache[key] ?: emptyList()
    }

    fun savePersonal(u1: Int, u2: Int, msgs: List<PersonalMessage>) {
        if (msgs.isNotEmpty()) {
            // ✅ BUG #1 FIX: Evict oldest entries when conversation cache limit is reached
            if (personalCache.size >= MAX_CACHED_CONVERSATIONS) {
                personalCache.keys().toList().take(10).forEach { personalCache.remove(it) }
            }
            val key = if (u1 < u2) "${u1}_${u2}" else "${u2}_${u1}"
            // ✅ BUG #1 FIX: Cap message history per conversation to prevent OOM
            personalCache[key] = msgs.takeLast(MAX_MESSAGES_PER_CONV)
        }
    }

    fun clearPersonal(u1: Int, u2: Int) {
        val key = if (u1 < u2) "${u1}_${u2}" else "${u2}_${u1}"
        personalCache.remove(key)
    }

    fun getGroup(gId: Int): List<ChatMessage> {
        return groupCache[gId] ?: emptyList()
    }

    fun saveGroup(gId: Int, msgs: List<ChatMessage>) {
        if (msgs.isNotEmpty()) {
            if (groupCache.size >= MAX_CACHED_CONVERSATIONS) {
                groupCache.keys().toList().take(10).forEach { groupCache.remove(it) }
            }
            groupCache[gId] = msgs.takeLast(MAX_MESSAGES_PER_CONV)
        }
    }

    fun clearGroup(gId: Int) {
        groupCache.remove(gId)
    }

    /**
     * ✅ BUG #1 PRIVACY FIX: Wipe all in-memory chats on logout so next user on shared device doesn't see old history.
     */
    fun clearAll() {
        personalCache.clear()
        groupCache.clear()
    }
}

// ── Global Read Status Manager (Persists across screen navigation + Bounded + ID-aware) ───────
object ReadStatusManager {
    private const val MAX_READ_ENTRIES = 200
    // ✅ BUG #8 FIX: Store lightweight Int IDs / hashes (saves memory, no duplicate long text strings)
    private val readChats = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // ✅ BUG #2 FIX: Support both unique message ID and content fallback for exact unread tracking
    fun markAsRead(type: String, id: Int, lastMessageId: Int) {
        if (readChats.size >= MAX_READ_ENTRIES) {
            readChats.keys().toList().take(40).forEach { readChats.remove(it) }
        }
        readChats["${type}_$id"] = lastMessageId
    }

    fun markAsRead(type: String, id: Int, lastMessage: String?) {
        val pseudoId = lastMessage?.hashCode() ?: 0
        markAsRead(type, id, pseudoId)
    }

    fun isRead(type: String, id: Int, currentLastMessageId: Int): Boolean {
        if (currentLastMessageId <= 0) return true
        val lastRead = readChats["${type}_$id"] ?: return false
        return lastRead == currentLastMessageId
    }

    fun isRead(type: String, id: Int, currentLastMessage: String?): Boolean {
        val lastReadMsg = readChats["${type}_$id"] ?: return false
        return lastReadMsg == (currentLastMessage?.hashCode() ?: 0)
    }

    fun clearRead(type: String, id: Int) {
        readChats.remove("${type}_$id")
    }

    fun clearAll() {
        readChats.clear()
    }
}

// ── Worldwide Dynamic Local Time Formatter (Zero Allocations on Scroll) ─────────────────

private val fallbackFormatters = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
    "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    "yyyy-MM-dd'T'HH:mm:ss.SSS",
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd",
    "HH:mm:ss",
    "HH:mm"
).map { pattern ->
    ThreadLocal.withInitial {
        java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
}

private val outputTimeFormatter = ThreadLocal.withInitial {
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).apply {
        timeZone = java.util.TimeZone.getDefault()
    }
}

fun formatUniversalLocalTime(rawTimestamp: String?): String {
    if (rawTimestamp.isNullOrEmpty()) {
        return outputTimeFormatter.get()?.format(java.util.Date()) ?: ""
    }

    // 1. Modern java.time ISO-8601 parser (zero allocations on API 26+)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        try {
            val normalized = if (rawTimestamp.endsWith("Z") || rawTimestamp.contains("+")) rawTimestamp else "${rawTimestamp}Z"
            val instant = java.time.Instant.parse(normalized)
            val localZoned = instant.atZone(java.time.ZoneId.systemDefault())
            return java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault()).format(localZoned)
        } catch (_: Exception) {}
    }

    // 2. ThreadLocal reusable SimpleDateFormat parsers (0 allocations during scroll)
    for (tl in fallbackFormatters) {
        try {
            val sdf = tl.get() ?: continue
            val parsed = sdf.parse(rawTimestamp)
            if (parsed != null) {
                return outputTimeFormatter.get()?.format(parsed) ?: ""
            }
        } catch (_: Exception) {}
    }

    // ✅ BUG #3 FIX: Log warning so format discrepancies can be detected in production logs
    android.util.Log.w("TimeFormat", "Unparseable timestamp: $rawTimestamp")
    return outputTimeFormatter.get()?.format(java.util.Date()) ?: ""
}

/**
 * Zero-allocation timestamp parser for deterministic message sorting in LazyColumns.
 * Uses Instant.parse on API 26+ and ThreadLocal SimpleDateFormat fallbacks on older Android.
 */
fun parseUniversalTimestampMillis(rawTimestamp: String?): Long {
    if (rawTimestamp.isNullOrBlank()) return 0L
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        try {
            val normalized = if (rawTimestamp.endsWith("Z") || rawTimestamp.contains("+")) rawTimestamp else "${rawTimestamp}Z"
            return java.time.Instant.parse(normalized).toEpochMilli()
        } catch (_: Exception) {}
    }
    for (tl in fallbackFormatters) {
        try {
            val sdf = tl.get() ?: continue
            val parsed = sdf.parse(rawTimestamp)
            if (parsed != null) return parsed.time
        } catch (_: Exception) {}
    }
    return 0L
}


// ── NOTE: MediaBitmapCache lives in screens/UploadStateManager.kt (com.security.myapplication.screens).
// A duplicate was previously defined here; it was dead code because PersonalChatScreen.kt
// (in the screens package) always resolved to the screens version. Removed to prevent confusion.

/**
 * Universal logout cleanup helper — wipes all memory caches on session end,
 * resets authentication token, stops background notification watchers,
 * and purges user-specific posts cache.
 */
fun clearUserSessionData(context: android.content.Context? = null, userId: Int? = null) {
    // Detach this physical device before clearing the session so an old account
    // cannot continue receiving data pushes on a shared phone.
    if (context != null && userId != null && userId > 0) {
        com.security.myapplication.notifications.EduConnectFCMService
            .unregisterTokenFromServer(context, userId)
    }
    MessageCache.clearAll()
    ReadStatusManager.clearAll()
    if (context != null) {
        com.security.myapplication.notifications.AppNotificationManager
            .clearAllConversationHistory(context)
        com.security.myapplication.notifications.AppNotificationManager.cancelAll(context)
    }
    com.security.myapplication.notifications.NotificationRouter.clearPendingTarget()
    // Clear the canonical MediaBitmapCache (defined in screens.UploadStateManager)
    com.security.myapplication.screens.MediaBitmapCache.clear()
    com.security.myapplication.network.AuthManager.clear()
    if (context != null) {
        if (userId != null && userId > 0) {
            com.security.myapplication.posts.PostRepository.clearForUser(context, userId)
            com.security.myapplication.offline.ChatOfflineCache.clearForUser(context, userId)
            com.security.myapplication.notifications.NotificationDeduplicator.clearForUser(context, userId)
        }
        com.security.myapplication.notifications.AppMessageNotificationWatcher.stop()
    }
}

// ── Posts / Announcements ───────────────────────────────────────────────────
data class CreatePostNetworkRequest(
    val author_id: Int,
    val text: String = "",
    val media_type: String? = null,
    val media_url: String? = null,
    val media_filename: String? = null,
    val poll_question: String? = null,
    val poll_options: List<String>? = null,
    val target_audience: String? = null
)

data class LikeToggleRequest(val user_id: Int, val liked: Boolean? = null)
data class LikeToggleResponse(val message: String, val liked: Boolean, val likeCount: Int)

data class SaveToggleRequest(val user_id: Int, val saved: Boolean? = null)
data class SaveToggleResponse(val message: String, val saved: Boolean)

data class CommentCreateRequest(
    val author_id: Int,
    val text: String,
    val parent_id: Int? = null
)
data class PollVoteRequest(val user_id: Int, val option_id: String)
data class MediaUploadResponse(val media_url: String, val fileName: String, val message: String)

