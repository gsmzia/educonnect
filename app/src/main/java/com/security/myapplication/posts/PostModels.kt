package com.security.myapplication.posts

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Supported Media types in Posts / Announcements.
 */
enum class PostMediaType {
    NONE,
    IMAGE,
    VIDEO,
    AUDIO,
    VOICE,
    DOCUMENT,
    POLL
}

/**
 * Poll Option representation.
 */
data class PollOption(
    val id: String,
    val text: String,
    val voteCount: Int = 0,
    val voterUserIds: List<Int> = emptyList()
)

/**
 * Poll container.
 */
data class PollData(
    val question: String,
    val options: List<PollOption> = emptyList(),
    val totalVotes: Int = 0,
    val userVotedOptionId: String? = null
)

/**
 * Post Media Attachment model.
 */
data class PostMedia(
    val type: PostMediaType = PostMediaType.NONE,
    val mediaUrl: String? = null,
    val localUri: String? = null,
    val fileName: String? = null,
    val fileSizeText: String? = null,
    val mimeType: String? = null,
    val durationSeconds: Int = 0,
    val pollData: PollData? = null
)

/**
 * Comment model on a Post.
 */
data class PostComment(
    val id: String,
    val postId: String,
    val parentCommentId: String? = null,
    val authorId: Int,
    val authorName: String,
    val authorRole: String = "Student",
    val authorProfilePic: String? = null,
    val text: String,
    val timestamp: String,
    val formattedTime: String = "Just now",
    val likesCount: Int = 0,
    val likedByMe: Boolean = false,
    val likedUserIds: List<Int> = emptyList(),
    val repliesCount: Int = 0,
    val replies: List<PostComment> = emptyList()
)

/**
 * Main Post / Announcement model.
 */
data class Post(
    val id: String,
    val authorId: Int,
    val authorName: String,
    val authorRole: String = "Student", // "Teacher", "Student", "Admin"
    val authorUsername: String? = null,
    val authorProfilePic: String? = null,
    val subtitleInfo: String = "BS Computer Science – Year 1", // e.g. department, degree or username
    val text: String = "",
    val media: PostMedia? = null,
    val timestamp: String,
    val formattedTime: String = "Just now",
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
    val likedUserIds: List<Int> = emptyList(),
    val commentCount: Int = 0,
    val comments: List<PostComment> = emptyList(),
    val savedByMe: Boolean = false,
    val savedUserIds: List<Int> = emptyList(),
    val reactionEmojis: List<String> = listOf("👍", "❤️", "😮"),
    val targetAudience: String = "ALL", // "ACADEMIC" | "SIMPLE" | "ALL"
    val isPosting: Boolean = false,
    val isFailed: Boolean = false
)

/** One keyset-paginated page returned by GET /posts. */
data class PostFeedResponse(
    val posts: List<Post> = emptyList(),
    val nextCursorTimestamp: String? = null,
    val nextCursorId: Int? = null
)

/** One keyset-paginated page returned by GET /posts/{post_id}/comments. */
data class PostCommentPageResponse(
    val comments: List<PostComment> = emptyList(),
    val nextCursorTimestamp: String? = null,
    val nextCursorId: Int? = null,
    val totalCount: Int = 0
)

/**
 * Live relative time calculation helper.
 * Parses raw ISO string or epoch millis and returns live relative format:
 * - < 60s  -> "Just now"
 * - 1..59m -> "Xm ago"
 * - 1..23h -> "Xh ago"
 * - 1 day  -> "Yesterday"
 * - 2..6d  -> "Xd ago"
 * - >= 7d  -> "MMM d" (e.g. Sep 4)
 */
fun getRelativeTimeText(rawTimestamp: String?): String {
    if (rawTimestamp.isNullOrBlank()) return "Just now"
    if (rawTimestamp.equals("now", ignoreCase = true) ||
        rawTimestamp.equals("just now", ignoreCase = true)) {
        return "Just now"
    }

    val cleanRaw = rawTimestamp.trim()

    // 1. Check if raw string is numeric epoch milliseconds
    var epochMs = cleanRaw.toLongOrNull()

    // 2. Parse ISO formats
    if (epochMs == null) {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss.SSSSSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
                val date = sdf.parse(cleanRaw)
                if (date != null) {
                    epochMs = date.time
                    break
                }
            } catch (_: Exception) {}
        }
    }

    if (epochMs == null) {
        return cleanRaw
    }

    val now = System.currentTimeMillis()
    val diffMs = (now - epochMs).coerceAtLeast(0)

    val seconds = TimeUnit.MILLISECONDS.toSeconds(diffMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
    val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
    val days = TimeUnit.MILLISECONDS.toDays(diffMs)

    return when {
        seconds < 60 -> "Just now"
        minutes == 1L -> "1m ago"
        minutes < 60 -> "${minutes}m ago"
        hours == 1L -> "1h ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "Yesterday"
        days < 7 -> "${days}d ago"
        else -> {
            val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
            SimpleDateFormat("MMM d", Locale.getDefault()).format(cal.time)
        }
    }
}

/**
 * Returns an ever-incrementing tick value that increments every [intervalMs] milliseconds.
 * Any composable that reads this value will be recomposed on each tick, making
 * getRelativeTimeText() labels update live while the screen is visible.
 *
 * Default interval: 60 000 ms (1 minute) — sufficient for "Xm/Xh/Xd ago" labels,
 * costs essentially zero CPU/battery (one coroutine delay loop per screen).
 */
@Composable
fun rememberTimeTick(intervalMs: Long = 60_000L): Int {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            tick++
        }
    }
    return tick
}

/**
 * Isolated timestamp Text composable:
 * Confines 60-second time-tick recomposition strictly to this Text node,
 * preventing the entire parent card (avatars, active video/audio, poll bars) from recomposing.
 */
@Composable
fun RelativeTimeText(
    timestamp: Any?,
    color: Color = Color(0xFF7A76A3),
    fontSize: TextUnit = 11.5.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier
) {
    val tick = rememberTimeTick()
    val str = when (timestamp) {
        null -> null
        is Long -> timestamp.toString()
        is Number -> timestamp.toLong().toString()
        is String -> timestamp
        else -> timestamp.toString()
    }
    Text(
        text = getRelativeTimeText(str),
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        modifier = modifier
    )
}

/**
 * Shared, off-thread base64 avatar decoder for the Posts module.
 *
 * - Checks in-memory MediaBitmapCache first for instant O(1) retrieval.
 * - Decodes on Dispatchers.Default (never blocks the main thread).
 * - Caches decoded Bitmap in MediaBitmapCache so lazy list scrolling never re-decodes.
 * - Returns null if the string is blank/null so callers fall back to the initial letter circle.
 */
@Composable
fun rememberPostAvatarBitmap(base64: String?): State<ImageBitmap?> {
    return produceState<ImageBitmap?>(initialValue = null, key1 = base64) {
        if (base64.isNullOrBlank()) {
            value = null
            return@produceState
        }
        val cacheKey = "avatar_" + base64.hashCode()
        val cached = com.security.myapplication.screens.MediaBitmapCache.get(cacheKey)
        if (cached != null) {
            value = cached.asImageBitmap()
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    com.security.myapplication.screens.MediaBitmapCache.put(cacheKey, bmp)
                    bmp.asImageBitmap()
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
