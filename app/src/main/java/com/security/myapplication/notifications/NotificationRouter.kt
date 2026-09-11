package com.security.myapplication.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.security.myapplication.MainActivity
import com.security.myapplication.models.Group
import com.security.myapplication.models.PersonalChatUser

/**
 * Data class representing a target navigation destination triggered by a notification tap.
 */
data class NotificationNavTarget(
    val type: String, // "personal" or "group"
    val currentUserId: Int,
    val otherUserId: Int = 0,
    val userName: String = "",
    val userRole: String = "Student",
    val userProfilePic: String? = null,
    val groupId: Int = 0,
    val groupName: String = "",
    val messageId: Int? = null
)

/**
 * Centralized Notification Router.
 * Generates navigation PendingIntents and manages routing state when app is launched or resumed.
 */
object NotificationRouter {

    const val EXTRA_OPEN_TYPE = "extra_notif_open_type" // "personal" or "group"
    const val EXTRA_CURRENT_USER_ID = "extra_notif_current_user_id"
    const val EXTRA_OTHER_USER_ID = "extra_notif_other_user_id"
    const val EXTRA_USER_NAME = "extra_notif_user_name"
    const val EXTRA_USER_ROLE = "extra_notif_user_role"
    const val EXTRA_USER_PIC = "extra_notif_user_pic"
    const val EXTRA_GROUP_ID = "extra_notif_group_id"
    const val EXTRA_GROUP_NAME = "extra_notif_group_name"
    const val EXTRA_MESSAGE_ID = "extra_notif_msg_id"

    // Observable Compose state for pending notification deep links
    private val _pendingNavTarget = mutableStateOf<NotificationNavTarget?>(null)
    val pendingNavTarget: State<NotificationNavTarget?> = _pendingNavTarget

    fun setPendingTarget(target: NotificationNavTarget?) {
        _pendingNavTarget.value = target
    }

    fun clearPendingTarget() {
        _pendingNavTarget.value = null
    }

    /**
     * Parses intent extras from a notification click and updates the pending navigation target.
     */
    fun handleIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val type = intent.getStringExtra(EXTRA_OPEN_TYPE) ?: return false
        val currentUserId = intent.getIntExtra(EXTRA_CURRENT_USER_ID, 0)
        val msgId = if (intent.hasExtra(EXTRA_MESSAGE_ID)) intent.getIntExtra(EXTRA_MESSAGE_ID, -1).takeIf { it != -1 } else null

        if (type.equals("personal", ignoreCase = true)) {
            val otherUserId = intent.getIntExtra(EXTRA_OTHER_USER_ID, 0)
            val name = intent.getStringExtra(EXTRA_USER_NAME) ?: "Chat"
            val role = intent.getStringExtra(EXTRA_USER_ROLE) ?: "Student"
            val pic = intent.getStringExtra(EXTRA_USER_PIC)
            if (otherUserId > 0) {
                _pendingNavTarget.value = NotificationNavTarget(
                    type = "personal",
                    currentUserId = currentUserId,
                    otherUserId = otherUserId,
                    userName = name,
                    userRole = role,
                    userProfilePic = pic,
                    messageId = msgId
                )
                return true
            }
        } else if (type.equals("group", ignoreCase = true)) {
            val groupId = intent.getIntExtra(EXTRA_GROUP_ID, 0)
            val groupName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: "Group"
            if (groupId > 0) {
                _pendingNavTarget.value = NotificationNavTarget(
                    type = "group",
                    currentUserId = currentUserId,
                    groupId = groupId,
                    groupName = groupName,
                    messageId = msgId
                )
                return true
            }
        }
        return false
    }

    /**
     * Creates a PendingIntent for opening a direct personal chat conversation.
     */
    fun createPersonalChatIntent(
        context: Context,
        currentUserId: Int,
        otherUserId: Int,
        otherUserName: String,
        otherUserRole: String = "Student",
        otherUserProfilePic: String? = null,
        messageId: Int? = null,
        requestCode: Int = 100_000_000 + (otherUserId % 400_000_000)
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TYPE, "personal")
            putExtra(EXTRA_CURRENT_USER_ID, currentUserId)
            putExtra(EXTRA_OTHER_USER_ID, otherUserId)
            putExtra(EXTRA_USER_NAME, otherUserName)
            putExtra(EXTRA_USER_ROLE, otherUserRole)
            putExtra(EXTRA_USER_PIC, otherUserProfilePic)
            if (messageId != null) putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    /**
     * Creates a PendingIntent for opening a group chat conversation.
     */
    fun createGroupChatIntent(
        context: Context,
        currentUserId: Int,
        groupId: Int,
        groupName: String,
        messageId: Int? = null,
        requestCode: Int = 600_000_000 + (groupId % 400_000_000)
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TYPE, "group")
            putExtra(EXTRA_CURRENT_USER_ID, currentUserId)
            putExtra(EXTRA_GROUP_ID, groupId)
            putExtra(EXTRA_GROUP_NAME, groupName)
            if (messageId != null) putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }
}
