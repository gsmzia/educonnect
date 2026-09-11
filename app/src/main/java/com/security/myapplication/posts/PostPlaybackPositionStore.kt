package com.security.myapplication.posts

import android.content.Context
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight device-local resume state for post videos.
 *
 * A position belongs to one signed-in user and one post. It is intentionally
 * local: reading/resuming a video must not generate a network/database write
 * for every playback tick.
 */
object PostPlaybackPositionStore {
    data class Position(
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val completed: Boolean = false
    )

    private const val PREFS_NAME = "post_video_playback_positions_v1"
    private const val POSITION_SUFFIX = "_position"
    private const val DURATION_SUFFIX = "_duration"
    private const val COMPLETED_SUFFIX = "_completed"
    private const val WRITE_INTERVAL_MS = 5_000L
    private val lastWriteAt = ConcurrentHashMap<String, Long>()

    fun load(context: Context, userId: Int, postId: String): Position {
        if (userId <= 0 || postId.isBlank()) return Position()
        return try {
            val key = keyFor(userId, postId)
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            Position(
                positionMs = prefs.getInt(key + POSITION_SUFFIX, 0).coerceAtLeast(0),
                durationMs = prefs.getInt(key + DURATION_SUFFIX, 0).coerceAtLeast(0),
                completed = prefs.getBoolean(key + COMPLETED_SUFFIX, false)
            )
        } catch (_: Exception) {
            Position()
        }
    }

    /** Writes are throttled during playback; pause/dispose/completion force a final checkpoint. */
    fun save(
        context: Context,
        userId: Int,
        postId: String,
        positionMs: Int,
        durationMs: Int,
        completed: Boolean,
        force: Boolean = false
    ) {
        if (userId <= 0 || postId.isBlank()) return
        val key = keyFor(userId, postId)
        val now = System.currentTimeMillis()
        val previous = lastWriteAt[key] ?: 0L
        if (!force && now - previous < WRITE_INTERVAL_MS) return
        lastWriteAt[key] = now

        try {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(key + POSITION_SUFFIX, positionMs.coerceAtLeast(0))
                .putInt(key + DURATION_SUFFIX, durationMs.coerceAtLeast(0))
                .putBoolean(key + COMPLETED_SUFFIX, completed)
                .apply()
        } catch (_: Exception) {}
    }

    private fun keyFor(userId: Int, postId: String): String {
        val input = "$userId:$postId".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return "post_video_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
