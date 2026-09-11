package com.security.myapplication.posts

import android.content.Context

/**
 * Small, per-user local drafts which intentionally live outside app_prefs.
 * Authentication cleanup may clear app_prefs after a 401/403, but a user's
 * unfinished post/comment must be available after they sign in again.
 */
object SessionDraftStore {
    private const val PREFS = "session_recovery_drafts"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun postKey(userId: Int) = "post_$userId"
    private fun commentKey(userId: Int, postId: String) = "comment_${userId}_$postId"

    fun getPost(context: Context, userId: Int): String =
        prefs(context).getString(postKey(userId), "").orEmpty()

    fun savePost(context: Context, userId: Int, text: String) {
        prefs(context).edit().apply {
            if (text.isBlank()) remove(postKey(userId)) else putString(postKey(userId), text)
        }.apply()
    }

    fun clearPost(context: Context, userId: Int) {
        prefs(context).edit().remove(postKey(userId)).apply()
    }

    fun getComment(context: Context, userId: Int, postId: String): String =
        prefs(context).getString(commentKey(userId, postId), "").orEmpty()

    fun saveComment(context: Context, userId: Int, postId: String, text: String) {
        prefs(context).edit().apply {
            if (text.isBlank()) remove(commentKey(userId, postId)) else putString(commentKey(userId, postId), text)
        }.apply()
    }

    fun clearComment(context: Context, userId: Int, postId: String) {
        prefs(context).edit().remove(commentKey(userId, postId)).apply()
    }
}
