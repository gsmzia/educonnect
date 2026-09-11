package com.security.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.security.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (com.security.myapplication.notifications.NotificationRouter.handleIntent(intent)) {
                clearOpenedConversationNotification(intent)
            }
        } catch (_: Exception) {}

        // Request notification permission on Android 13+ (API 33+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0D0A22)
                ) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try {
            if (com.security.myapplication.notifications.NotificationRouter.handleIntent(intent)) {
                clearOpenedConversationNotification(intent)
            }
        } catch (_: Exception) {}
    }

    /** Clears only the conversation the user chose to open; other chats stay unread. */
    private fun clearOpenedConversationNotification(intent: android.content.Intent) {
        val openType = intent.getStringExtra(
            com.security.myapplication.notifications.NotificationRouter.EXTRA_OPEN_TYPE
        ) ?: return
        val isGroup = openType.equals("group", ignoreCase = true)
        val conversationId = if (isGroup) {
            intent.getIntExtra(
                com.security.myapplication.notifications.NotificationRouter.EXTRA_GROUP_ID,
                0
            )
        } else {
            intent.getIntExtra(
                com.security.myapplication.notifications.NotificationRouter.EXTRA_OTHER_USER_ID,
                0
            )
        }
        if (conversationId <= 0) return

        val notifications = com.security.myapplication.notifications.AppNotificationManager
        notifications.cancelNotification(
            this,
            notifications.getMessageNotifId(isGroup, conversationId)
        )
        notifications.cancelNotification(
            this,
            notifications.getSummaryNotifId(isGroup, conversationId)
        )
        notifications.clearConversationHistory(this, isGroup, conversationId)
    }
}
