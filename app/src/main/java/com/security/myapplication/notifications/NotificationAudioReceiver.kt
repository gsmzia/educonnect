package com.security.myapplication.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.security.myapplication.audio.GlobalAudioPlayer

/**
 * BroadcastReceiver for handling voice note play, pause, resume, and stop actions directly from notifications.
 */
class NotificationAudioReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PLAY_VOICE = "com.security.myapplication.notifications.ACTION_PLAY_VOICE"
        const val ACTION_PAUSE_VOICE = "com.security.myapplication.notifications.ACTION_PAUSE_VOICE"
        const val ACTION_STOP_VOICE = "com.security.myapplication.notifications.ACTION_STOP_VOICE"

        const val EXTRA_MSG_ID = "extra_audio_msg_id"
        const val EXTRA_MEDIA_URL = "extra_audio_media_url"
        const val EXTRA_SENDER_NAME = "extra_audio_sender_name"
        const val EXTRA_TITLE = "extra_audio_title"
        const val EXTRA_NOTIF_ID = "extra_audio_notif_id"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            ACTION_PLAY_VOICE -> {
                val msgId = intent.getIntExtra(EXTRA_MSG_ID, -1)
                val url = intent.getStringExtra(EXTRA_MEDIA_URL) ?: return
                val sender = intent.getStringExtra(EXTRA_SENDER_NAME) ?: "Voice Note"
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Voice Message"
                if (msgId != -1) {
                    GlobalAudioPlayer.play(context, msgId, url, sender, title)
                }
            }
            ACTION_PAUSE_VOICE -> {
                GlobalAudioPlayer.pause()
            }
            ACTION_STOP_VOICE -> {
                GlobalAudioPlayer.stop()
            }
        }
    }
}
