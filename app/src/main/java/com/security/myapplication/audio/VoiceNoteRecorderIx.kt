package com.security.myapplication.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * One recorder profile for every chat type.
 *
 * The preferred AAC-LC profile gives clear speech without relying on the
 * device's often very low default bitrate.  Older/OEM-customized devices can
 * reject an explicit recorder configuration, so each fallback is created as a
 * fresh recorder and only used when the prior one cannot prepare or start.
 *
 * v2 changes (production hardening — start(context, filePrefix) keeps working
 * exactly as before, every fix below is additive or purely internal):
 *
 *  1. RECORD_AUDIO is checked up front. Previously a missing permission made
 *     every single fallback profile fail with the same unhelpful error,
 *     which then surfaced as a generic "No compatible voice recorder
 *     profile" — masking the real, fixable cause. Now it fails fast with a
 *     clear SecurityException the caller can catch and show a "grant mic
 *     permission" prompt for.
 *
 *  2. Recordings are stored under the app's persistent files directory
 *     instead of cacheDir. cacheDir can be wiped by the OS under storage
 *     pressure at any moment, including right after the user finishes
 *     recording but before the voice note has been sent — that's silent
 *     data loss. filesDir is not eligible for that kind of surprise eviction.
 *
 *  3. A low-storage guard throws a clear IOException instead of letting
 *     prepare()/start() fail deep inside MediaRecorder's native layer with a
 *     cryptic RuntimeException.
 *
 *  4. File.createTempFile() requires a prefix of at least 3 characters and
 *     throws IllegalArgumentException otherwise. A short/blank filePrefix
 *     used to blow up identically on every profile and get buried in the
 *     same generic error; it's now padded defensively.
 *
 *  5. An optional onError callback (default no-op, so existing call sites
 *     don't need to change) is wired to MediaRecorder's error listener, so a
 *     runtime failure mid-recording (mic seized by another app, media
 *     server died, etc.) is surfaced instead of silently corrupting the
 *     output.
 *
 *  6. New stop()/cancel() helpers. The single most common MediaRecorder crash
 *     in production voice-note features is calling stop() on a recording
 *     that lasted under ~1 second — it throws RuntimeException("stop
 *     failed") and leaves a corrupt file behind. stop() now catches that
 *     specific case, deletes the corrupt file, and returns null instead of
 *     crashing the app.
 *
 *  7. New startAsync() suspend wrapper. prepare()/start() are blocking calls;
 *     start() itself is unchanged (still synchronous, so no call site needs
 *     to change), but callers that can use coroutines now have an easy way
 *     to keep this off the caller's current thread.
 */
object VoiceNoteRecorder {
    private const val TAG = "VoiceNoteRecorder"

    // Below this much free space, don't even attempt to record — a partial,
    // truncated voice note is worse than a clear "storage full" error.
    private const val MIN_FREE_BYTES = 1_000_000L // 1 MB

    data class ActiveRecording(
        val recorder: MediaRecorder,
        val file: File
    )

    private data class Profile(
        val source: Int,
        val sampleRate: Int?,
        val bitrate: Int?,
        val channels: Int?,
        val label: String
    )

    private val profiles = listOf(
        // Voice recognition source is optimized for spoken-word capture on
        // Android devices; AAC-LC 96 kbps avoids muffled OEM defaults.
        Profile(MediaRecorder.AudioSource.VOICE_RECOGNITION, 44_100, 96_000, 1, "voice-96k"),
        // Still clear, but accepted by more constrained/older encoders.
        Profile(MediaRecorder.AudioSource.MIC, 44_100, 64_000, 1, "mic-64k"),
        // Last-resort baseline: preserve the ability to send a voice note even
        // on a broken vendor recorder implementation.
        Profile(MediaRecorder.AudioSource.MIC, null, null, null, "baseline")
    )

    /**
     * Starts a mono M4A/AAC recording or throws after every compatible profile fails.
     *
     * Throws [SecurityException] if RECORD_AUDIO isn't granted, [IOException] if
     * storage is critically low, or [IllegalStateException] (wrapping the last
     * underlying error) if no recorder profile could be started.
     *
     * [onError] is optional and fires if the recorder hits a runtime error
     * *after* recording has already started (e.g. the microphone gets taken
     * by another app mid-call) — existing callers that don't pass it are
     * unaffected.
     */
    fun start(
        context: Context,
        filePrefix: String,
        onError: (Throwable) -> Unit = {}
    ): ActiveRecording {
        ensureRecordPermission(context)
        val dir = recordingsDir(context)
        ensureStorageAvailable(dir)

        val safePrefix = safeTempPrefix(filePrefix)
        var lastError: Exception? = null

        for (profile in profiles) {
            val file = try {
                File.createTempFile(safePrefix, ".m4a", dir)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Could not create output file for ${profile.label}: ${e.message}")
                continue
            }
            val recorder = newRecorder(context)
            try {
                recorder.apply {
                    setAudioSource(profile.source)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    // Speech does not benefit from stereo; the baseline avoids
                    // this explicit setting for maximum legacy compatibility.
                    profile.channels?.let(::setAudioChannels)
                    profile.sampleRate?.let(::setAudioSamplingRate)
                    profile.bitrate?.let(::setAudioEncodingBitRate)
                    setOutputFile(file.absolutePath)
                    setOnErrorListener { _, what, extra ->
                        val msg = "Recorder error mid-session (what=$what extra=$extra)"
                        Log.e(TAG, msg)
                        onError(IllegalStateException(msg))
                    }
                    prepare()
                    start()
                }
                Log.d(TAG, "Voice recording started with ${profile.label}")
                return ActiveRecording(recorder, file)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Voice profile ${profile.label} unavailable: ${e.message}")
                try { recorder.reset() } catch (_: Exception) {}
                try { recorder.release() } catch (_: Exception) {}
                try { file.delete() } catch (_: Exception) {}
            }
        }
        throw IllegalStateException("No compatible voice recorder profile", lastError)
    }

    /**
     * Same as [start], but does the (blocking) profile/prepare/start work on
     * [Dispatchers.IO] so it never stalls the caller's current thread —
     * handy for callers that already run in a coroutine and want to keep
     * the tap-to-record button responsive.
     */
    suspend fun startAsync(
        context: Context,
        filePrefix: String,
        onError: (Throwable) -> Unit = {}
    ): ActiveRecording = withContext(Dispatchers.IO) {
        start(context, filePrefix, onError)
    }

    /**
     * Safely stops an active recording and returns the finished file, or
     * null if the recording couldn't be finalized (in which case the
     * partial/corrupt file is deleted so it can't be accidentally sent).
     *
     * Specifically guards against the well-known MediaRecorder quirk where
     * stop() throws RuntimeException if called within roughly the first
     * second of recording, before anything meaningful was written.
     */
    fun stop(activeRecording: ActiveRecording): File? {
        val (recorder, file) = activeRecording
        return try {
            recorder.stop()
            recorder.release()
            file
        } catch (e: RuntimeException) {
            Log.w(TAG, "stop() failed, likely a too-short recording: ${e.message}")
            try { recorder.release() } catch (_: Exception) {}
            try { file.delete() } catch (_: Exception) {}
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error stopping recorder: ${e.message}", e)
            try { recorder.release() } catch (_: Exception) {}
            try { file.delete() } catch (_: Exception) {}
            null
        }
    }

    /** Discards an in-progress recording (e.g. user swiped to cancel) without keeping the file. */
    fun cancel(activeRecording: ActiveRecording) {
        val (recorder, file) = activeRecording
        try { recorder.stop() } catch (_: Exception) {}
        try { recorder.release() } catch (_: Exception) {}
        try { file.delete() } catch (_: Exception) {}
    }

    private fun ensureRecordPermission(context: Context) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw SecurityException("RECORD_AUDIO permission not granted — request it before calling start().")
        }
    }

    private fun ensureStorageAvailable(dir: File) {
        val free = dir.usableSpace
        // usableSpace returns 0 if it genuinely can't tell — treat that as
        // "unknown" rather than "full" so it doesn't block recording outright.
        if (free in 1 until MIN_FREE_BYTES) {
            throw IOException("Not enough storage space to start a recording")
        }
    }

    private fun safeTempPrefix(prefix: String): String {
        val cleaned = prefix.ifBlank { "voice" }
        return if (cleaned.length < 3) cleaned.padEnd(3, '_') else cleaned
    }

    // Persistent, app-private storage for not-yet-sent recordings. Unlike
    // cacheDir, the OS won't silently reclaim this under storage pressure.
    private fun recordingsDir(context: Context): File =
        File(context.filesDir, "voice_notes").apply { if (!exists()) mkdirs() }

    @Suppress("DEPRECATION")
    private fun newRecorder(context: Context): MediaRecorder =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
}
