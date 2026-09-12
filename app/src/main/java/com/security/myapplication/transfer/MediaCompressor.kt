@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.security.myapplication.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.OptIn as AndroidOptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resumeWith

/**
 * Creates a smaller upload copy without ever replacing the user's source file.
 *
 * Transformer uses the device media codec pipeline where available and moves
 * work off the UI thread.  A result is accepted only when it is non-empty and
 * smaller than its source; unsupported/OEM-problematic formats simply retain
 * the original file, so an optimization cannot turn into a failed attachment.
 */
@AndroidOptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
object MediaCompressor {
    private const val MIN_VIDEO_BYTES = 5L * 1024L * 1024L
    private const val MIN_JPEG_BYTES = 1L * 1024L * 1024L
    private const val MAX_IMAGE_EDGE = 1920

    suspend fun compressIfUseful(context: Context, source: File, mediaType: String): File {
        if (!source.exists() || source.length() <= 0L) return source
        return when (mediaType.lowercase()) {
            "video" -> if (source.length() >= MIN_VIDEO_BYTES) compressVideo(context, source) else source
            "image" -> if (source.length() >= MIN_JPEG_BYTES) compressJpeg(context, source) else source
            else -> source // Documents, audio, voice notes: preserve original fidelity.
        }
    }

    private suspend fun compressJpeg(context: Context, source: File): File {
        val extension = source.extension.lowercase()
        // JPEG photos compress predictably.  Do not flatten transparent PNGs,
        // animated GIFs, or WebP into a lossy JPEG unexpectedly.
        if (extension !in setOf("jpg", "jpeg")) return source
        return withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext source
            var sample = 1
            // Decode near the target size, not at 4K then scale down.  This
            // keeps the temporary bitmap around 8–16 MB instead of risking a
            // large allocation on low-memory phones.
            while (bounds.outWidth / sample > MAX_IMAGE_EDGE ||
                bounds.outHeight / sample > MAX_IMAGE_EDGE) sample *= 2
            val bitmap = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return@withContext source
            try {
                val largestEdge = maxOf(bitmap.width, bitmap.height)
                val scaled = if (largestEdge > MAX_IMAGE_EDGE) {
                    val scale = MAX_IMAGE_EDGE.toFloat() / largestEdge
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else bitmap
                val output = File.createTempFile("compressed_img_", ".jpg", context.cacheDir)
                FileOutputStream(output).use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                if (scaled !== bitmap) scaled.recycle()
                if (output.length() in 1L until source.length()) output else {
                    output.delete()
                    source
                }
            } catch (_: Exception) {
                source
            } finally {
                bitmap.recycle()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun compressVideo(context: Context, source: File): File {
        val output = File.createTempFile("compressed_vid_", ".mp4", context.cacheDir)
        val result = withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<File?> { continuation ->
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (!continuation.isCompleted) continuation.resumeWith(Result.success(output))
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (!continuation.isCompleted) continuation.resumeWith(Result.success(null))
                    }
                }
                try {
                    val edited = EditedMediaItem.Builder(MediaItem.fromUri(source.toURI().toString()))
                        // Presentation forces a transcode instead of a no-op
                        // remux, while preserving aspect ratio at 720p.
                        .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(720))))
                        .setFrameRate(30)
                        .build()
                    val transformer = Transformer.Builder(context.applicationContext)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(listener)
                        .build()
                    continuation.invokeOnCancellation {
                        transformer.cancel()
                        output.delete()
                    }
                    transformer.start(edited, output.absolutePath)
                } catch (_: Exception) {
                    if (!continuation.isCompleted) continuation.resumeWith(Result.success(null))
                }
            }
        }
        return if (result != null && result.exists() && result.length() in 1L until source.length()) {
            result
        } else {
            output.delete()
            source
        }
    }
}
