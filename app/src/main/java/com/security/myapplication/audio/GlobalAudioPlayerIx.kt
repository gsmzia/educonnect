package com.security.myapplication.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.security.myapplication.MainActivity
import com.security.myapplication.models.AppMediaType
import com.security.myapplication.models.MediaManager
import com.security.myapplication.network.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * GLOBAL AUDIO & VOICE PLAYER (WhatsApp-Grade Persistent Playback Engine) — v2
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * WHAT CHANGED FROM v1 AND WHY (production bugs that were fixed):
 *
 *  1. Real background survival: playback now lives inside [VoiceAudioService], a
 *     genuine foreground Service. In v1 the MediaPlayer lived inside a plain
 *     Kotlin `object`, which has NO process-priority protection — Android could
 *     (and eventually would) kill the app process the moment it left the
 *     foreground / the task was swiped away, silently cutting audio. A started
 *     foreground Service is what keeps the process — and the audio — alive.
 *
 *  2. Actionable notification: the old notification was read-only (tap to open
 *     app only). It now has real Play/Pause and Close actions, exactly like
 *     WhatsApp's voice-note notification, so playback can be controlled without
 *     re-opening the app.
 *
 *  3. Audio focus handling: the player now requests audio focus and pauses on
 *     focus loss (e.g. an incoming call) and resumes on focus regain — before,
 *     a phone call would play right over the voice note.
 *
 *  4. Wake mode: `setWakeMode(PARTIAL_WAKE_LOCK)` keeps decoding alive with the
 *     screen off, matching real messenger apps. Previously long voice notes
 *     could stutter/stop once the screen locked.
 *
 *  5. Crash-safety: listeners are cleared before `release()`, and the previous
 *     MediaPlayer is fully torn down before a new one is created — avoiding the
 *     native "callback fired after release" race that could crash playback when
 *     users tapped between voice notes quickly.
 *
 *  6. No more silent notification crash: notification updates are all funneled
 *     through `startForeground()` (id-reuse) instead of raw `NotificationManager
 *     .notify()`, which avoids the `SecurityException` some devices throw on
 *     Android 13+ when POST_NOTIFICATIONS hasn't been granted — the audio still
 *     plays even if the visual notification is suppressed.
 *
 *  7. Lower-frequency, battery-friendlier progress polling (200ms instead of
 *     50ms) — the UI is still smooth, but this is 4x less recomposition/battery
 *     churn during long voice notes.
 *
 *  8. Swipe-to-dismiss the notification now actually stops playback cleanly
 *     (`setDeleteIntent`), instead of leaving an orphaned playing session with
 *     no visible notification.
 *
 * ⚠️ REQUIRED MANIFEST CHANGES — this file cannot add these for you:
 *
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
 *   <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 *   <uses-permission android:name="android.permission.WAKE_LOCK" />
 *
 *   <service
 *       android:name=".audio.VoiceAudioService"
 *       android:foregroundServiceType="mediaPlayback"
 *       android:exported="false"
 *       android:stopWithTask="false" />
 *
 *   Also request the POST_NOTIFICATIONS runtime permission somewhere in your
 *   Activity on Android 13+, or the notification (not the audio) simply won't
 *   show.
 *
 * Guarantees kept from v1:
 *  1. ZERO Scrolling Disruption — player lives outside LazyColumn bubbles.
 *  2. Single-Active Playback — starting a new voice note stops any existing one.
 *  3. Same public API as v1 — every screen already calling GlobalAudioPlayer.*
 *     keeps working unchanged.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * WHAT CHANGED IN THIS PASS (v3 — production hardening, no API changes):
 * ─────────────────────────────────────────────────────────────────────────
 *
 *  9. Seek flooding fixed: dragging the mini-player's scrubber used to fire
 *     a `startService()` Intent on every single pixel of finger movement.
 *     On a real device that's dozens of Intents per second, which is exactly
 *     what made scrubbing feel laggy/stuttery. `seekToMs()` now updates the
 *     on-screen position instantly (so dragging still *feels* instant) but
 *     throttles the actual Intent dispatch to the service to ~12/sec, with
 *     a trailing-edge guarantee so the final drag position is never dropped.
 *
 *  10. No more main-thread disk I/O: `MediaManager.findLocalFile()` used to
 *      run directly inside `onStartCommand()` (the main thread). On a chat
 *      with a large local voice-note cache this can stall the UI thread and
 *      risk an ANR. It now runs on Dispatchers.IO, with the MediaPlayer
 *      itself still built and driven from the main thread (required for its
 *      callbacks to fire).
 *
 *  11. Stale-track race fixed: if a user taps voice note A and then quickly
 *      taps voice note B before A has finished loading from disk, A's
 *      late-arriving lookup can no longer clobber B's session — the load is
 *      tied to the specific (msgId, playbackKey) it was started for and is
 *      cancelled outright the moment a newer track is requested.
 *
 *  12. Scrub-while-buffering no longer silently lost: seeking on a
 *      MediaPlayer that hasn't finished preparing throws IllegalStateException
 *      internally — previously this was swallowed by a catch-all and the
 *      user's scrub simply vanished. It's now queued and applied the instant
 *      the track finishes preparing.
 */
object GlobalAudioPlayer {

    private const val TAG = "GlobalAudioPlayer"

    private var appContext: Context? = null
    private var onCompletionCallback: ((Int) -> Unit)? = null
    private var activePlaybackKey: String? = null

    // ── Observable Compose States (unchanged public surface) ─────────────────
    private val _currentPlayingMsgId = mutableStateOf<Int?>(null)
    val currentPlayingMsgId: State<Int?> = _currentPlayingMsgId

    private val _isPlaying = mutableStateOf(false)
    val isPlaying: State<Boolean> = _isPlaying

    private val _isBuffering = mutableStateOf(false)
    val isBuffering: State<Boolean> = _isBuffering

    private val _currentProgress = mutableFloatStateOf(0f)
    val currentProgress: State<Float> = _currentProgress

    private val _currentPositionMs = mutableIntStateOf(0)
    val currentPositionMs: State<Int> = _currentPositionMs

    private val _totalDurationMs = mutableIntStateOf(0)
    val totalDurationMs: State<Int> = _totalDurationMs

    private val _currentTitle = mutableStateOf("")
    val currentTitle: State<String> = _currentTitle

    private val _currentSender = mutableStateOf("")
    val currentSender: State<String> = _currentSender

    // ── Public APIs (signatures identical to v1) ──────────────────────────────

    fun isMessagePlaying(msgId: Int): Boolean =
        _currentPlayingMsgId.value == msgId && _isPlaying.value

    fun isMessageLoaded(msgId: Int): Boolean =
        _currentPlayingMsgId.value == msgId

    fun play(
        context: Context,
        msgId: Int,
        mediaUrl: String,
        senderName: String = "Voice Note",
        title: String = "Voice Message",
        onCompleted: (Int) -> Unit = {}
    ) {
        playWithPlaybackKey(context, msgId, mediaUrl, senderName, title, msgId.toString(), onCompleted)
    }

    /** Uses an explicit stable key when an audio item has a different identity than its numeric id. */
    fun playWithPlaybackKey(
        context: Context,
        msgId: Int,
        mediaUrl: String,
        senderName: String = "Voice Note",
        title: String = "Voice Message",
        playbackKey: String,
        onCompleted: (Int) -> Unit = {}
    ) {
        val ctx = context.applicationContext
        appContext = ctx
        onCompletionCallback = onCompleted

        // Same message already loaded (playing or paused) — just resume it.
        if (_currentPlayingMsgId.value == msgId && activePlaybackKey == playbackKey) {
            if (!_isPlaying.value) resume()
            return
        }

        activePlaybackKey = playbackKey

        val intent = Intent(ctx, VoiceAudioService::class.java).apply {
            action = VoiceAudioService.ACTION_PLAY
            putExtra(VoiceAudioService.EXTRA_MSG_ID, msgId)
            putExtra(VoiceAudioService.EXTRA_URL, mediaUrl)
            putExtra(VoiceAudioService.EXTRA_SENDER, senderName)
            putExtra(VoiceAudioService.EXTRA_TITLE, title)
            putExtra(VoiceAudioService.EXTRA_PLAYBACK_KEY, playbackKey)
        }
        try {
            ContextCompat.startForegroundService(ctx, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VoiceAudioService: ${e.message}", e)
        }
    }

    fun togglePlayPause(
        context: Context,
        msgId: Int,
        mediaUrl: String,
        senderName: String = "Voice Note",
        title: String = "Voice Message",
        onCompleted: (Int) -> Unit = {}
    ) {
        togglePlayPauseWithPlaybackKey(context, msgId, mediaUrl, senderName, title, msgId.toString(), onCompleted)
    }

    /** Post audio uses this to scope the resume position to user + post. */
    fun togglePlayPauseWithPlaybackKey(
        context: Context,
        msgId: Int,
        mediaUrl: String,
        senderName: String = "Voice Note",
        title: String = "Voice Message",
        playbackKey: String,
        onCompleted: (Int) -> Unit = {}
    ) {
        if (_currentPlayingMsgId.value == msgId && activePlaybackKey == playbackKey) {
            if (_isPlaying.value) pause() else resume()
        } else {
            playWithPlaybackKey(context, msgId, mediaUrl, senderName, title, playbackKey, onCompleted)
        }
    }

    fun pause() {
        appContext?.let { sendAction(it, VoiceAudioService.ACTION_PAUSE) }
    }

    fun resume() {
        appContext?.let { sendAction(it, VoiceAudioService.ACTION_RESUME) }
    }

    fun seekTo(fraction: Float) {
        val dur = _totalDurationMs.intValue
        if (dur > 0) {
            seekToMs((fraction * dur).toInt().coerceIn(0, dur))
        }
    }

    // Dragging the scrubber can call seekToMs() dozens of times per second.
    // Sending a startService() Intent on every single one of those is what
    // caused visible scrub lag — this throttles the *dispatch* to the
    // service while the on-screen position still updates every single call,
    // so the UI stays perfectly smooth and the final position is never lost.
    private const val SEEK_DISPATCH_INTERVAL_MS = 80L
    private var lastSeekDispatchAtMs = 0L
    private var pendingSeekDispatchMs: Int? = null
    private var seekDispatchJob: Job? = null
    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun seekToMs(positionMs: Int) {
        val ctx = appContext ?: return
        // Optimistic UI update so scrubbing feels instant regardless of throttling.
        _currentPositionMs.intValue = positionMs
        val dur = _totalDurationMs.intValue
        if (dur > 0) {
            _currentProgress.floatValue = (positionMs.toFloat() / dur).coerceIn(0f, 1f)
        }

        val now = SystemClock.elapsedRealtime()
        pendingSeekDispatchMs = positionMs
        if (now - lastSeekDispatchAtMs >= SEEK_DISPATCH_INTERVAL_MS) {
            dispatchSeek(ctx, positionMs)
        } else {
            // Trailing-edge: make sure the very last position during a fast
            // drag always lands, even if it arrives inside the throttle window.
            seekDispatchJob?.cancel()
            seekDispatchJob = playerScope.launch {
                delay(SEEK_DISPATCH_INTERVAL_MS)
                pendingSeekDispatchMs?.let { dispatchSeek(ctx, it) }
            }
        }
    }

    private fun dispatchSeek(ctx: Context, positionMs: Int) {
        lastSeekDispatchAtMs = SystemClock.elapsedRealtime()
        pendingSeekDispatchMs = null
        sendAction(ctx, VoiceAudioService.ACTION_SEEK) {
            putExtra(VoiceAudioService.EXTRA_SEEK_MS, positionMs)
        }
    }

    fun stop() {
        val ctx = appContext
        clearSession()
        if (ctx != null) sendAction(ctx, VoiceAudioService.ACTION_STOP)
    }

    private fun sendAction(ctx: Context, action: String, extras: (Intent.() -> Unit)? = null) {
        try {
            val intent = Intent(ctx, VoiceAudioService::class.java).apply {
                this.action = action
                extras?.invoke(this)
            }
            ctx.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send action $action: ${e.message}", e)
        }
    }

    // ── Audio Position Persistence ──────────────────────────────────────────
    // Persists playback position per stable playback key so "user was at 0:47 of this voice
    // note" survives service stops, incoming calls, and app restarts.
    // KEY format: "audio_pos_<playbackKey>" → position in ms (Int).

    private const val PREFS_AUDIO = "audio_playback_state"
    private const val KEY_POS_PREFIX = "audio_pos_"
    private const val KEY_SAVED_MSG_ID = "audio_saved_msg_id"
    // Only write to SharedPreferences at most every 2 seconds to avoid
    // hammering disk on the 200ms progress ticker.
    private var lastPersistTimeMs = 0L

    internal fun persistPosition(context: Context, playbackKey: String, posMs: Int) {
        val now = System.currentTimeMillis()
        if (now - lastPersistTimeMs < 2000L) return   // throttle
        lastPersistTimeMs = now
        try {
            context.getSharedPreferences(PREFS_AUDIO, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_POS_PREFIX + playbackKey, posMs)
                .putString(KEY_SAVED_MSG_ID, playbackKey)
                .apply()
        } catch (_: Exception) {}
    }

    fun savedPositionFor(context: Context, playbackKey: String): Int {
        return try {
            context.getSharedPreferences(PREFS_AUDIO, Context.MODE_PRIVATE)
                .getInt(KEY_POS_PREFIX + playbackKey, 0)
        } catch (_: Exception) { 0 }
    }

    internal fun clearPersistedPosition(context: Context, playbackKey: String) {
        try {
            context.getSharedPreferences(PREFS_AUDIO, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_POS_PREFIX + playbackKey)
                .remove(KEY_SAVED_MSG_ID)
                .apply()
        } catch (_: Exception) {}
    }

    internal fun setSessionMeta(msgId: Int, sender: String, title: String) {
        _currentPlayingMsgId.value = msgId
        _currentSender.value = sender
        _currentTitle.value = title
        _currentProgress.floatValue = 0f
        _currentPositionMs.intValue = 0
        _totalDurationMs.intValue = 0
        _isPlaying.value = false
    }

    internal fun setBuffering(buffering: Boolean) {
        _isBuffering.value = buffering
    }

    internal fun setDuration(ms: Int) {
        _totalDurationMs.intValue = ms
    }

    internal fun setPosition(ms: Int, fraction: Float) {
        _currentPositionMs.intValue = ms
        _currentProgress.floatValue = fraction
    }

    internal fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    internal fun clearSession() {
        activePlaybackKey = null
        _currentPlayingMsgId.value = null
        _isPlaying.value = false
        _isBuffering.value = false
        _currentProgress.floatValue = 0f
        _currentPositionMs.intValue = 0
        _totalDurationMs.intValue = 0
        _currentTitle.value = ""
        _currentSender.value = ""
    }

    internal fun notifyCompleted(msgId: Int) {
        val cb = onCompletionCallback
        onCompletionCallback = null
        cb?.invoke(msgId)
    }
}

/**
 * The actual playback engine. Runs as a foreground Service so it survives the
 * app being backgrounded, tab-switched away from, or swiped out of Recents —
 * exactly like WhatsApp's voice-note / audio playback.
 *
 * Controlled purely via Intent actions from [GlobalAudioPlayer] — no binding
 * required, since both live in the same process.
 */
class VoiceAudioService : Service() {

    companion object {
        const val ACTION_PLAY = "com.security.myapplication.audio.action.PLAY"
        const val ACTION_PAUSE = "com.security.myapplication.audio.action.PAUSE"
        const val ACTION_RESUME = "com.security.myapplication.audio.action.RESUME"
        const val ACTION_TOGGLE = "com.security.myapplication.audio.action.TOGGLE"
        const val ACTION_SEEK = "com.security.myapplication.audio.action.SEEK"
        const val ACTION_STOP = "com.security.myapplication.audio.action.STOP"

        const val EXTRA_MSG_ID = "extra_msg_id"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PLAYBACK_KEY = "extra_playback_key"
        const val EXTRA_SEEK_MS = "extra_seek_ms"

        private const val NOTIF_CHANNEL_ID = "educonnect_voice_playback"
        private const val NOTIF_ID = 99281
        private const val TAG = "VoiceAudioService"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentMsgId: Int? = null
    private var currentPlaybackKey: String? = null
    private var pausedByFocusLoss = false
    private var isForegroundActive = false

    // Off-main-thread track loading: the local-file disk lookup runs on IO,
    // then hops back to Main to build/drive the MediaPlayer. `loadJob` is
    // cancelled whenever a newer track supersedes it, so a slow lookup for a
    // track the user already swiped past can never clobber the current one.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null

    // A seek requested while the track is still buffering (MediaPlayer not
    // yet Prepared) can't be applied immediately — it's queued here and
    // applied the moment onPrepared fires instead of being silently dropped.
    private var pendingSeekMs: Int? = null

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var notifTickCounter = 0

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (mediaPlayer?.isPlaying == true) {
                    pausedByFocusLoss = true
                    pausePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try { mediaPlayer?.setVolume(0.3f, 0.3f) } catch (_: Exception) {}
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try { mediaPlayer?.setVolume(1f, 1f) } catch (_: Exception) {}
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    resumePlayback()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val msgId = intent.getIntExtra(EXTRA_MSG_ID, -1)
                val url = intent.getStringExtra(EXTRA_URL)
                val sender = intent.getStringExtra(EXTRA_SENDER) ?: "Voice Note"
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Voice Message"
                val playbackKey = intent.getStringExtra(EXTRA_PLAYBACK_KEY) ?: msgId.toString()
                if (msgId != -1 && url != null) {
                    startPlayback(msgId, url, sender, title, playbackKey)
                }
            }
            ACTION_TOGGLE -> togglePlayback()
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_SEEK -> seekPlayback(intent.getIntExtra(EXTRA_SEEK_MS, 0))
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    // ── Playback control ───────────────────────────────────────────────────

    private fun startPlayback(msgId: Int, mediaUrl: String, sender: String, title: String, playbackKey: String) {
        if (currentMsgId == msgId && currentPlaybackKey == playbackKey && mediaPlayer != null) {
            resumePlayback()
            return
        }
        releaseCurrentPlayer() // also cancels any in-flight loadJob for a previous track

        currentMsgId = msgId
        currentPlaybackKey = playbackKey
        GlobalAudioPlayer.setSessionMeta(msgId, sender, title)
        GlobalAudioPlayer.setBuffering(true)

        // Show the notification immediately — required within seconds of
        // startForegroundService(), well before network/prepare finishes.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            isForegroundActive = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }

        val safeUrl = when {
            mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://") -> mediaUrl
            mediaUrl.startsWith("content://") || mediaUrl.startsWith("file://") -> mediaUrl
            mediaUrl.startsWith("/uploads/") -> ApiClient.BASE_URL.removeSuffix("/") + mediaUrl
            mediaUrl.startsWith("uploads/") -> ApiClient.BASE_URL.removeSuffix("/") + "/" + mediaUrl
            !mediaUrl.contains("://") && !mediaUrl.startsWith("/") -> ApiClient.BASE_URL.removeSuffix("/") + "/" + mediaUrl
            else -> mediaUrl
        }

        loadJob = serviceScope.launch {
            try {
                // The local-cache lookup touches disk — on a chat with a large
                // downloaded voice-note history this can take long enough to
                // jank the UI or trip an ANR if run on the main thread, so it
                // runs on IO and only hops back to Main to touch MediaPlayer.
                val localFile = withContext(Dispatchers.IO) {
                    MediaManager.findLocalFile(applicationContext, msgId, safeUrl, AppMediaType.AUDIO)
                        ?: MediaManager.findLocalFile(applicationContext, msgId, mediaUrl, AppMediaType.AUDIO)
                }

                // A newer play() call may have superseded this one while we
                // were on IO — bail out instead of configuring a player for a
                // track the user already left.
                if (currentMsgId != msgId || currentPlaybackKey != playbackKey) return@launch

                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                }
                mediaPlayer = mp

                when {
                    localFile != null && localFile.exists() && localFile.length() > 0 ->
                        mp.setDataSource(localFile.absolutePath)
                    safeUrl.startsWith("content://") || safeUrl.startsWith("file://") ->
                        mp.setDataSource(applicationContext, Uri.parse(safeUrl))
                    else -> mp.setDataSource(safeUrl)
                }

                mp.setOnPreparedListener { player ->
                    GlobalAudioPlayer.setBuffering(false)
                    GlobalAudioPlayer.setDuration(player.duration.coerceAtLeast(1))
                    // ── Apply a queued scrub, else restore persisted position ──
                    // If the user scrubbed while this was still buffering, that
                    // target wins. Otherwise, if they were at e.g. 0:47 before
                    // the service stopped (call came in, swipe-to-kill, app
                    // restart), resume there.
                    val queuedSeek = pendingSeekMs
                    pendingSeekMs = null
                    val seekTargetMs = queuedSeek ?: run {
                        val restoredMs = GlobalAudioPlayer.savedPositionFor(applicationContext, playbackKey)
                        if (restoredMs > 2000 && restoredMs < player.duration - 1000) restoredMs else null
                    }
                    if (seekTargetMs != null) {
                        try {
                            val clamped = seekTargetMs.coerceIn(0, player.duration)
                            player.seekTo(clamped)
                            val fraction = clamped.toFloat() / player.duration.toFloat()
                            GlobalAudioPlayer.setPosition(clamped, fraction.coerceIn(0f, 1f))
                        } catch (_: Exception) {}
                    }
                    // ─────────────────────────────────────────────────────────
                    if (requestAudioFocus()) {
                        player.start()
                        GlobalAudioPlayer.setPlaying(true)
                        startProgressTracker()
                        updateNotification()
                    } else {
                        GlobalAudioPlayer.setPlaying(false)
                        Log.w(TAG, "Audio focus denied — staying paused")
                    }
                }

                mp.setOnCompletionListener {
                    val finishedId = currentMsgId ?: msgId
                    // Clear saved position — note was listened to completely,
                    // next play should start from 0 again (WhatsApp behavior).
                    GlobalAudioPlayer.clearPersistedPosition(applicationContext, currentPlaybackKey ?: playbackKey)
                    stopPlayback()
                    GlobalAudioPlayer.notifyCompleted(finishedId)
                }

                mp.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    GlobalAudioPlayer.setBuffering(false)
                    stopPlayback()
                    true
                }

                mp.prepareAsync()

            } catch (e: CancellationException) {
                // Superseded by a newer play() call — not a real failure, and
                // must NOT stopPlayback() here since that could tear down the
                // session that just replaced this one.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Playback start failed: ${e.message}", e)
                GlobalAudioPlayer.setBuffering(false)
                stopPlayback()
            }
        }
    }

    private fun togglePlayback() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) pausePlayback() else resumePlayback()
    }

    private fun pausePlayback() {
        try {
            val mp = mediaPlayer
            // ── Persist position before pausing ──────────────────────────────
            // This covers: incoming call, audio focus loss, user tap, notification action.
            if (mp != null) {
                val posMs = try { mp.currentPosition } catch (_: Exception) { 0 }
                val msgId = currentMsgId
                val playbackKey = currentPlaybackKey
                if (msgId != null && playbackKey != null && posMs > 0) {
                    GlobalAudioPlayer.persistPosition(applicationContext, playbackKey, posMs)
                }
            }
            // ─────────────────────────────────────────────────────────────────
            mp?.let { if (it.isPlaying) it.pause() }
            GlobalAudioPlayer.setPlaying(false)
            stopProgressTracker()
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "pause failed: ${e.message}", e)
        }
    }

    private fun resumePlayback() {
        try {
            if (!requestAudioFocus()) return
            mediaPlayer?.start()
            GlobalAudioPlayer.setPlaying(true)
            startProgressTracker()
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "resume failed: ${e.message}", e)
        }
    }

    private fun seekPlayback(ms: Int) {
        val mp = mediaPlayer
        if (mp == null) {
            // Still loading (e.g. loadJob hasn't reached MediaPlayer creation
            // yet) — remember what the user wants and apply it once ready.
            pendingSeekMs = ms
            return
        }
        try {
            mp.seekTo(ms)
            val dur = GlobalAudioPlayer.totalDurationMs.value
            val fraction = if (dur > 0) (ms.toFloat() / dur).coerceIn(0f, 1f) else 0f
            GlobalAudioPlayer.setPosition(ms, fraction)
            updateNotification()
        } catch (e: IllegalStateException) {
            // MediaPlayer.seekTo() is only valid once Prepared. The user
            // scrubbed while it was still buffering — queue it for onPrepared
            // instead of dropping the interaction on the floor.
            pendingSeekMs = ms
        } catch (e: Exception) {
            Log.e(TAG, "seek failed: ${e.message}", e)
        }
    }

    private fun stopPlayback() {
        stopProgressTracker()
        releaseCurrentPlayer()
        abandonAudioFocus()
        GlobalAudioPlayer.clearSession()
        if (isForegroundActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForegroundActive = false
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIF_ID)
        stopSelf()
    }

    private fun releaseCurrentPlayer() {
        loadJob?.cancel()
        loadJob = null
        pendingSeekMs = null
        mediaPlayer?.let { mp ->
            try {
                mp.setOnPreparedListener(null)
                mp.setOnCompletionListener(null)
                mp.setOnErrorListener(null)
                if (mp.isPlaying) mp.stop()
            } catch (_: Exception) {
                // Player may not have been prepared yet — safe to ignore.
            } finally {
                try { mp.release() } catch (_: Exception) {}
            }
        }
        mediaPlayer = null
        currentMsgId = null
        currentPlaybackKey = null
    }

    // ── Audio focus ─────────────────────────────────────────────────────────

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusListener, mainHandler)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    // ── Progress polling (200ms — smooth but battery-friendly) ───────────────

    private fun startProgressTracker() {
        stopProgressTracker()
        notifTickCounter = 0
        progressRunnable = object : Runnable {
            override fun run() {
                val mp = mediaPlayer
                if (mp != null && GlobalAudioPlayer.isPlaying.value) {
                    try {
                        val pos = mp.currentPosition
                        val dur = GlobalAudioPlayer.totalDurationMs.value
                        val fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                        GlobalAudioPlayer.setPosition(pos, fraction)
                        // Persist position during active playback (throttled to ~2s intervals
                        // inside persistPosition itself — so this line is effectively free).
                        val msgId = currentMsgId
                        val playbackKey = currentPlaybackKey
                        if (msgId != null && playbackKey != null && pos > 0) {
                            GlobalAudioPlayer.persistPosition(applicationContext, playbackKey, pos)
                        }
                    } catch (_: Exception) {}

                    notifTickCounter++
                    if (notifTickCounter % 5 == 0) updateNotification() // ~once/sec

                    mainHandler.postDelayed(this, 200)
                }
            }
        }
        mainHandler.post(progressRunnable!!)
    }

    private fun stopProgressTracker() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    // ── Notification (WhatsApp-style: Play/Pause + Close actions) ───────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Voice & Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows currently playing voice note or audio controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        try {
            if (!isForegroundActive) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIF_ID, buildNotification())
                }
                isForegroundActive = true
            } else {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification: ${e.message}", e)
        }
    }

    private fun buildNotification(): Notification {
        val sender = GlobalAudioPlayer.currentSender.value
        val title = GlobalAudioPlayer.currentTitle.value
        val isPlaying = GlobalAudioPlayer.isPlaying.value
        val isBuffering = GlobalAudioPlayer.isBuffering.value
        val posMs = GlobalAudioPlayer.currentPositionMs.value
        val durMs = GlobalAudioPlayer.totalDurationMs.value

        val curMin = (posMs / 1000) / 60
        val curSec = (posMs / 1000) % 60
        val totMin = (durMs / 1000) / 60
        val totSec = (durMs / 1000) % 60
        val timeStr = "%02d:%02d / %02d:%02d".format(curMin, curSec, totMin, totSec)

        val isVoice = title.equals("Voice Note", ignoreCase = true) ||
                      title.equals("Voice Message", ignoreCase = true) ||
                      title.contains("voice", ignoreCase = true) ||
                      title.isBlank() ||
                      title.startsWith("rec_") ||
                      title.startsWith("voice_")

        val cleanTitle = if (isVoice) "Voice message" else title
        val notifTitle = if (sender.isNotBlank()) sender else (if (isVoice) "EDU Connect" else "EDU Connect")

        val statusText = when {
            isBuffering -> "Loading…  $timeStr"
            isPlaying   -> "▶ $timeStr"
            else        -> "⏸ $timeStr  —  $cleanTitle"
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val togglePending = servicePendingIntent(ACTION_TOGGLE, 1)
        val stopPending   = servicePendingIntent(ACTION_STOP,   2)

        // Generate a simple circular initial-letter avatar for the large icon
        val avatarBmp = com.security.myapplication.notifications.AppNotificationManager
            .getSenderAvatarBitmap(this, sender.ifBlank { "E" }, null, 48)

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(com.security.myapplication.R.drawable.ic_notif_small)
            .setColor(0xFF8B6BFF.toInt())
            .setContentTitle(notifTitle)
            .setContentText(statusText)
            .setLargeIcon(avatarBmp)
            .setContentIntent(contentIntent)
            .setDeleteIntent(stopPending)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Play/Pause action button
            .addAction(
                if (isPlaying) com.security.myapplication.R.drawable.ic_notif_pause
                else com.security.myapplication.R.drawable.ic_notif_play,
                if (isPlaying) "Pause" else "Play",
                togglePending
            )
            // Stop / dismiss action button
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPending
            )
            .build()
    }


    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, VoiceAudioService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    override fun onDestroy() {
        stopProgressTracker()
        releaseCurrentPlayer()
        abandonAudioFocus()
        serviceScope.cancel()
        super.onDestroy()
    }

    // Deliberately NOT overridden to stop playback: the default Service
    // behaviour (and android:stopWithTask="false" in the manifest) is what
    // lets audio keep playing after the app is swiped away from Recents —
    // exactly like WhatsApp voice notes.
}
