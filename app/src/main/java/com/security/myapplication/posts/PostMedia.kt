package com.security.myapplication.posts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayCircleFilled
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.security.myapplication.audio.GlobalAudioPlayer
import com.security.myapplication.models.AppMediaType
import com.security.myapplication.models.MediaManager
import com.security.myapplication.network.ApiClient
import com.security.myapplication.screens.MediaBitmapCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.view.Surface
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Global coordinator for video playback across the post feed:
 * guarantees that only ONE video is playing at any given time.
 * If user taps play on a new video, the previous one automatically pauses.
 */
object GlobalVideoPlayerManager {
    private val _activeVideoKey = MutableStateFlow<String?>(null)
    val activeVideoKey: StateFlow<String?> = _activeVideoKey.asStateFlow()

    fun requestPlay(videoKey: String) {
        _activeVideoKey.value = videoKey
    }

    fun notifyPaused(videoKey: String) {
        if (_activeVideoKey.value == videoKey) {
            _activeVideoKey.value = null
        }
    }
}

/**
 * Universal RAM-safe Media renderer for Posts.
 */
fun formatMediaDuration(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val mins = totalSec / 60
    val secs = totalSec % 60
    return String.format("%02d:%02d", mins, secs)
}

/**
 * Universal RAM-safe Media renderer for Posts.
 */
@Composable
fun PostMediaContent(
    media: PostMedia,
    postId: String,
    currentUserId: Int,
    isUploading: Boolean = false,
    uploadProgress: Float = 0f,
    uploadError: String? = null,
    isVisibleOnScreen: Boolean = true,
    isAutoPlayVisible: Boolean = false,
    onRemoveMedia: (() -> Unit)? = null,
    onRetryUpload: (() -> Unit)? = null,
    onVotePoll: (optionId: String) -> Unit = {},
    onDoubleTapLike: (() -> Unit)? = null
) {
    when (media.type) {
        PostMediaType.IMAGE -> PostImageMedia(
            media = media,
            isUploading = isUploading,
            uploadProgress = uploadProgress,
            uploadError = uploadError,
            onRemoveMedia = onRemoveMedia,
            onRetryUpload = onRetryUpload,
            onDoubleTapLike = onDoubleTapLike
        )
        PostMediaType.VIDEO -> PostVideoMedia(
            media = media,
            postId = postId,
            currentUserId = currentUserId,
            isUploading = isUploading,
            uploadProgress = uploadProgress,
            uploadError = uploadError,
            isVisibleOnScreen = isVisibleOnScreen,
            isAutoPlayVisible = isAutoPlayVisible,
            onRemoveMedia = onRemoveMedia,
            onRetryUpload = onRetryUpload
        )
        PostMediaType.AUDIO, PostMediaType.VOICE -> PostAudioMedia(media, postId, currentUserId)
        PostMediaType.DOCUMENT -> PostFileMedia(media)
        PostMediaType.POLL -> media.pollData?.let { PostPollMedia(it, onVotePoll) }
        PostMediaType.NONE -> {}
    }
}

fun resolveMediaUrl(url: String): String {
    return if (url.startsWith("/uploads/")) {
        ApiClient.BASE_URL.removeSuffix("/") + url
    } else url
}

/**
 * Cloudinary returns the original MP4 URL.  Its HLS profile uses the same
 * public id, so no database/API contract needs to change.  Non-Cloudinary,
 * local, and legacy URLs retain normal progressive playback.
 */
fun cloudinaryAdaptiveHlsUrl(originalUrl: String): String? {
    if (!originalUrl.startsWith("https://res.cloudinary.com/")) return null
    val marker = "/video/upload/"
    val markerIndex = originalUrl.indexOf(marker)
    if (markerIndex < 0) return null
    val queryIndex = originalUrl.indexOf('?', markerIndex + marker.length)
    val pathEnd = if (queryIndex >= 0) queryIndex else originalUrl.length
    val publicIdPath = originalUrl.substring(markerIndex + marker.length, pathEnd)
    val extensionIndex = publicIdPath.lastIndexOf('.')
    if (extensionIndex <= publicIdPath.lastIndexOf('/')) return null
    return buildString {
        append(originalUrl.substring(0, markerIndex + marker.length))
        append("sp_auto:maxres_720p/")
        append(publicIdPath.substring(0, extensionIndex))
        append(".m3u8")
        if (queryIndex >= 0) append(originalUrl.substring(queryIndex))
    }
}

// Feed-image files are separate from transfer/media cache entries so eviction
// never removes a user's downloaded chat attachment.  200MB is enough for a
// responsive feed while staying predictable on low-storage phones.
private const val MAX_POST_IMAGE_CACHE_BYTES = 200L * 1024L * 1024L
private const val POST_IMAGE_CACHE_DIR = "post_image_cache"

private fun postImageCacheFile(context: Context, key: String): File {
    val dir = File(context.cacheDir, POST_IMAGE_CACHE_DIR).apply { if (!exists()) mkdirs() }
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return File(dir, "$digest.img")
}

/** Must be called on Dispatchers.IO. Evicts least-recently-used feed images only. */
private fun trimPostImageCache(cacheDir: File, protectedFile: File) {
    val files = cacheDir.listFiles { file -> file.isFile && file.name.endsWith(".img") }
        ?.sortedBy { it.lastModified() }
        ?: return
    var totalBytes = files.sumOf { it.length() }
    for (file in files) {
        if (totalBytes <= MAX_POST_IMAGE_CACHE_BYTES) break
        if (file == protectedFile) continue
        val length = file.length()
        if (file.delete()) totalBytes -= length
    }
}

/**
 * Image Media Renderer (Downsampled, cached, tap-for-fullscreen, double-tap-to-like, centered upload overlay).
 */
@Composable
fun PostImageMedia(
    media: PostMedia,
    isUploading: Boolean = false,
    uploadProgress: Float = 0f,
    uploadError: String? = null,
    onRemoveMedia: (() -> Unit)? = null,
    onRetryUpload: (() -> Unit)? = null,
    onDoubleTapLike: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val rawKey = media.localUri ?: media.mediaUrl ?: ""
    val dataKey = resolveMediaUrl(rawKey)
    val initialCached = remember(dataKey) { if (dataKey.isNotBlank()) MediaBitmapCache.get(dataKey) else null }
    var bitmap by remember(dataKey) { mutableStateOf(initialCached) }
    var isLoading by remember(dataKey) { mutableStateOf(initialCached == null && dataKey.isNotBlank()) }
    var isLoadError by remember(dataKey) { mutableStateOf(false) }
    var reloadTrigger by remember(dataKey) { mutableIntStateOf(0) }
    var showFullscreen by remember { mutableStateOf(false) }

    var heartAnimTrigger by remember { mutableStateOf(0L) }
    var heartVisible by remember { mutableStateOf(false) }
    val heartScale = remember { Animatable(0f) }
    val heartAlpha = remember { Animatable(0f) }

    LaunchedEffect(heartAnimTrigger) {
        if (heartAnimTrigger > 0L) {
            heartVisible = true
            heartScale.snapTo(0.2f)
            heartAlpha.snapTo(1f)
            launch {
                heartScale.animateTo(
                    targetValue = 1.3f,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                )
                heartScale.animateTo(
                    targetValue = 1.0f,
                    animationSpec = tween(durationMillis = 140)
                )
            }
            delay(500)
            heartAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 250)
            )
            heartVisible = false
        }
    }

    LaunchedEffect(dataKey, reloadTrigger) {
        if (dataKey.isBlank()) {
            isLoading = false
            isLoadError = false
            return@LaunchedEffect
        }

        if (bitmap != null && reloadTrigger == 0) {
            isLoading = false
            isLoadError = false
            return@LaunchedEffect
        }

        val cached = MediaBitmapCache.get(dataKey)
        if (cached != null && reloadTrigger == 0) {
            bitmap = cached
            isLoading = false
            isLoadError = false
            return@LaunchedEffect
        }

        isLoading = true
        isLoadError = false

        withContext(Dispatchers.IO) {
            var loadedBmp: Bitmap? = null
            var downloadFile: File? = null
            try {
                if (dataKey.startsWith("content://") || dataKey.startsWith("file://")) {
                    val uri = Uri.parse(dataKey)
                    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, boundsOpts)
                    }
                    if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
                        var sampleSize = 1
                        val halfH = boundsOpts.outHeight / 2
                        val halfW = boundsOpts.outWidth / 2
                        while ((halfH / sampleSize) >= 800 && (halfW / sampleSize) >= 1080) {
                            sampleSize *= 2
                        }
                        val decodeOpts = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        loadedBmp = context.contentResolver.openInputStream(uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream, null, decodeOpts)
                        }
                    }
                } else if (dataKey.startsWith("http")) {
                    val cacheFile = postImageCacheFile(context, dataKey)
                    val partialFile = File(cacheFile.parentFile, "${cacheFile.name}.part")
                    downloadFile = cacheFile
                    if (reloadTrigger > 0 && cacheFile.exists()) {
                        try { cacheFile.delete() } catch (_: Exception) {}
                    }
                    if (!cacheFile.exists() || cacheFile.length() == 0L) {
                        try { partialFile.delete() } catch (_: Exception) {}
                        if (cacheFile.exists() && cacheFile.length() == 0L) {
                            try { cacheFile.delete() } catch (_: Exception) {}
                        }
                        val url = URL(dataKey)
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.doInput = true
                        try {
                            conn.connect()
                            if (conn.responseCode !in 200..299) {
                                throw java.io.IOException("Post image request failed: HTTP ${conn.responseCode}")
                            }
                            conn.inputStream.use { input ->
                                partialFile.outputStream().use { fos ->
                                    input.copyTo(fos)
                                }
                            }
                        } finally {
                            conn.disconnect()
                        }
                        if (!partialFile.renameTo(cacheFile)) {
                            throw java.io.IOException("Could not finalize cached post image")
                        }
                    }
                    if (cacheFile.exists() && cacheFile.length() > 0L) {
                        // File timestamps are the LRU access order. This runs on IO
                        // and does not affect frame rendering on the main thread.
                        cacheFile.setLastModified(System.currentTimeMillis())
                        trimPostImageCache(cacheFile.parentFile ?: context.cacheDir, cacheFile)
                        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        FileInputStream(cacheFile).use { fis ->
                            BitmapFactory.decodeStream(fis, null, boundsOpts)
                        }
                        var sampleSize = 1
                        val halfH = boundsOpts.outHeight / 2
                        val halfW = boundsOpts.outWidth / 2
                        while ((halfH / sampleSize) >= 800 && (halfW / sampleSize) >= 1080) {
                            sampleSize *= 2
                        }
                        val decodeOpts = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        loadedBmp = FileInputStream(cacheFile).use { fis ->
                            BitmapFactory.decodeStream(fis, null, decodeOpts)
                        }
                    }
                }
            } catch (_: Exception) {
                try {
                    downloadFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
                    downloadFile?.parentFile?.let { parent ->
                        File(parent, "${downloadFile?.name}.part").delete()
                    }
                } catch (_: Exception) {}
            }

            loadedBmp?.let { bmp ->
                MediaBitmapCache.put(dataKey, bmp)
            }
            withContext(Dispatchers.Main) {
                bitmap = loadedBmp
                isLoading = false
                isLoadError = (loadedBmp == null && dataKey.isNotBlank())
            }
        }
    }

    // Determine aspect ratio dynamically
    val imageAspectRatio = remember(bitmap) {
        bitmap?.let { bmp ->
            if (bmp.width > 0 && bmp.height > 0) {
                (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.56f, 2.4f)
            } else 1.777f
        } ?: 1.777f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .aspectRatio(imageAspectRatio)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF16132D))
            .pointerInput(dataKey, bitmap, isUploading) {
                if (!isUploading) {
                    detectTapGestures(
                        onTap = {
                            if (bitmap != null) showFullscreen = true
                        },
                        onDoubleTap = {
                            if (bitmap != null) {
                                onDoubleTapLike?.invoke()
                                heartAnimTrigger = System.currentTimeMillis()
                            }
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Post Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else if (isLoading) {
            CircularProgressIndicator(
                color = Color(0xFF8B6BFF),
                modifier = Modifier.size(32.dp),
                strokeWidth = 2.5.dp
            )
        } else if (isLoadError) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { reloadTrigger++ }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Photo,
                    contentDescription = "Failed to load image",
                    tint = Color(0xFF8B6BFF).copy(alpha = 0.7f),
                    modifier = Modifier.size(34.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Couldn't load image",
                    color = Color(0xFFDCDAF0),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF8B6BFF).copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        tint = Color(0xFF8B6BFF),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Tap to retry",
                        color = Color(0xFF8B6BFF),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Live Centered Upload Indicator Overlay
        if (isUploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (uploadProgress > 0f) {
                        CircularProgressIndicator(
                            progress = { uploadProgress.coerceIn(0f, 1f) },
                            color = Color(0xFF8B6BFF),
                            trackColor = Color(0xFF352B66),
                            modifier = Modifier.size(46.dp),
                            strokeWidth = 3.5.dp
                        )
                        Text(
                            text = "${(uploadProgress * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        CircularProgressIndicator(
                            color = Color(0xFF8B6BFF),
                            modifier = Modifier.size(46.dp),
                            strokeWidth = 3.5.dp
                        )
                    }
                }
            }
        } else if (uploadError != null) {
            // Error banner at bottom center
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFB71C1C).copy(alpha = 0.9f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "⚠️ Upload failed",
                    color = Color.White,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // Retry button (only shown on error, bottom-left)
            if (onRetryUpload != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF8B6BFF).copy(alpha = 0.92f))
                        .clickable { onRetryUpload() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "↺ Retry",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Remove (×) button — only removes/discards, never retries
        if (onRemoveMedia != null) {
            IconButton(
                onClick = onRemoveMedia,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Instagram-style double-tap floating heart overlay
        if (heartVisible) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Liked",
                tint = Color.White.copy(alpha = (heartAlpha.value * 0.95f).coerceIn(0f, 1f)),
                modifier = Modifier
                    .size(90.dp)
                    .graphicsLayer {
                        scaleX = heartScale.value
                        scaleY = heartScale.value
                        alpha = heartAlpha.value
                        shadowElevation = 8.dp.toPx()
                    }
            )
        }
    }

    // Fullscreen Image Dialog
    if (showFullscreen && bitmap != null) {
        Dialog(
            onDismissRequest = { showFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
            ) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Fullscreen Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = { showFullscreen = false },
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(16.dp)
                        .align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

/**
 * Modern Facebook/Instagram-Style Video Media Renderer.
 * - Shows selected video thumbnail filling the preview area cleanly.
 * - During upload: Centered circular progress overlay, Play button is hidden/disabled.
 * - After upload (100%): Play enabled, adaptive HLS playback via Media3/ExoPlayer.
 * - Smooth range seeking, Play/Pause/Resume, minimal buffering spinner, and clean timeline controls.
 */
@Composable
fun PostVideoMedia(
    media: PostMedia,
    postId: String,
    currentUserId: Int,
    isUploading: Boolean = false,
    uploadProgress: Float = 0f,
    uploadError: String? = null,
    isVisibleOnScreen: Boolean = true,
    isAutoPlayVisible: Boolean = false,
    onRemoveMedia: (() -> Unit)? = null,
    onRetryUpload: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val rawKey = media.localUri ?: media.mediaUrl ?: ""
    val dataKey = resolveMediaUrl(rawKey)
    // The compose screen uses a temporary "preview" post id; never let one
    // draft attachment inherit another draft's playback checkpoint.
    val playbackPostId = if (postId == "preview") "" else postId
    val savedPlayback = remember(currentUserId, playbackPostId) {
        PostPlaybackPositionStore.load(context, currentUserId, playbackPostId)
    }

    val initialCachedThumb = remember(dataKey) {
        if (dataKey.isNotBlank()) MediaBitmapCache.get("vid_thumb_$dataKey") else null
    }
    var thumbBitmap by remember(dataKey) { mutableStateOf(initialCachedThumb) }
    var videoAspectRatio by remember(dataKey) {
        mutableFloatStateOf(
            initialCachedThumb?.let { bmp ->
                if (bmp.width > 0 && bmp.height > 0) {
                    (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.56f, 2.4f)
                } else null
            } ?: (16f / 9f)
        )
    }

    // Player state
    var player by remember(dataKey, currentUserId, postId) { mutableStateOf<ExoPlayer?>(null) }
    var hasStarted by remember(dataKey, currentUserId, postId) { mutableStateOf(false) }
    var isPlaying by remember(dataKey, currentUserId, postId) { mutableStateOf(false) }
    var isBuffering by remember(dataKey, currentUserId, postId) { mutableStateOf(false) }
    var isError by remember(dataKey, currentUserId, postId) { mutableStateOf(false) }
    var currentPosMs by remember(dataKey, currentUserId, postId) { mutableIntStateOf(savedPlayback.positionMs) }
    var totalDurMs by remember(dataKey, currentUserId, postId) { mutableIntStateOf(savedPlayback.durationMs.coerceAtLeast(1)) }
    var isUserSeeking by remember(dataKey, currentUserId, postId) { mutableStateOf(false) }
    var seekTargetFraction by remember(dataKey, currentUserId, postId) { mutableFloatStateOf(0f) }
    var showControls by remember(dataKey, currentUserId, postId) { mutableStateOf(false) }
    // Feed autoplay always begins silently; the user can explicitly unmute.
    var isMuted by remember(dataKey, currentUserId, postId) { mutableStateOf(true) }
    var isCompleted by remember(dataKey, currentUserId, postId) { mutableStateOf(savedPlayback.completed) }
    var restorePositionMs by remember(dataKey, currentUserId, postId) { mutableIntStateOf(savedPlayback.positionMs) }
    var shouldRestorePosition by remember(dataKey, currentUserId, postId) { mutableStateOf(!savedPlayback.completed && savedPlayback.positionMs > 0) }

    fun savePlaybackPosition(force: Boolean = false) {
        val livePosition = try { (player?.currentPosition ?: currentPosMs.toLong()).toInt() } catch (_: Exception) { currentPosMs }
        val liveDuration = try { (player?.duration?.takeIf { it > 0 } ?: totalDurMs.toLong()).toInt() } catch (_: Exception) { totalDurMs }
        if (livePosition > 0 || isCompleted) {
            PostPlaybackPositionStore.save(
                context = context,
                userId = currentUserId,
                postId = playbackPostId,
                positionMs = livePosition,
                durationMs = liveDuration,
                completed = isCompleted,
                force = force
            )
        }
    }

    fun releaseVideoPlayer() {
        val activePlayer = player
        player = null
        try { activePlayer?.stop() } catch (_: Exception) {}
        try { activePlayer?.release() } catch (_: Exception) {}
    }

    // Observe global coordinator: auto-pause if another video plays in feed
    val activeVideoKey by GlobalVideoPlayerManager.activeVideoKey.collectAsState()
    LaunchedEffect(activeVideoKey) {
        if (activeVideoKey != null && activeVideoKey != dataKey && isPlaying) {
            player?.let { mp ->
                try {
                    if (mp.isPlaying) {
                        savePlaybackPosition(force = true)
                        mp.pause()
                        isPlaying = false
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // Auto-pause video when scrolled out of view (FB/Instagram feed smoothness)
    LaunchedEffect(isVisibleOnScreen) {
        if (!isVisibleOnScreen && isPlaying) {
            player?.let { mp ->
                try {
                    if (mp.isPlaying) {
                        savePlaybackPosition(force = true)
                        mp.pause()
                        isPlaying = false
                        GlobalVideoPlayerManager.notifyPaused(dataKey)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // Instagram/Facebook-style feed behavior: only start a not-yet-started
    // video when at least half of its card is visible.  The separate 30%
    // visibility input above remains responsible for pausing it on scroll-out.
    LaunchedEffect(isAutoPlayVisible, hasStarted, isUploading, dataKey) {
        if (isAutoPlayVisible && !hasStarted && !isCompleted && !isUploading && dataKey.isNotBlank()) {
            isMuted = true
            isBuffering = true
            GlobalVideoPlayerManager.requestPlay(dataKey)
            hasStarted = true
        }
    }

    // Quick tap animation for play/pause pulse
    var animPulseIcon by remember { mutableStateOf<ImageVector?>(null) }
    val pulseScale = remember { Animatable(0.7f) }
    val pulseAlpha = remember { Animatable(0f) }

    fun triggerPulse(icon: ImageVector) {
        animPulseIcon = icon
        scope.launch {
            pulseScale.snapTo(0.6f)
            pulseAlpha.snapTo(1f)
            pulseScale.animateTo(1.2f, tween(180, easing = FastOutSlowInEasing))
            pulseAlpha.animateTo(0f, tween(180))
            animPulseIcon = null
        }
    }

    var hideJob by remember { mutableStateOf<Job?>(null) }
    fun scheduleHideControls() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(2600)
            if (isPlaying && !isUserSeeking) {
                showControls = false
            }
        }
    }

    // Extract thumbnail and aspect ratio
    LaunchedEffect(dataKey) {
        if (dataKey.isBlank()) return@LaunchedEffect
        val cacheKey = "vid_thumb_$dataKey"
        val cached = thumbBitmap ?: MediaBitmapCache.get(cacheKey)
        if (cached != null) {
            thumbBitmap = cached
            if (cached.width > 0 && cached.height > 0) {
                val cachedRatio = cached.width.toFloat() / cached.height.toFloat()
                if (cachedRatio > 0f) {
                    videoAspectRatio = cachedRatio.coerceIn(0.56f, 2.4f)
                }
            }
        }

        withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                if (dataKey.startsWith("content://")) {
                    retriever.setDataSource(context, Uri.parse(dataKey))
                } else if (dataKey.startsWith("file://")) {
                    val path = dataKey.removePrefix("file://").let { if (!it.startsWith("/")) "/$it" else it }
                    val f = File(path)
                    if (f.exists()) retriever.setDataSource(f.absolutePath)
                    else retriever.setDataSource(context, Uri.parse(dataKey))
                } else {
                    retriever.setDataSource(dataKey, HashMap())
                }

                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 16f
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 9f
                val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val ratio = if (rot == 90 || rot == 270) h / w else w / h

                val frame = if (cached == null) {
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } else null

                retriever.release()

                withContext(Dispatchers.Main) {
                    if (ratio > 0f) {
                        videoAspectRatio = ratio.coerceIn(0.56f, 2.4f)
                    }
                    if (frame != null) {
                        MediaBitmapCache.put(cacheKey, frame)
                        thumbBitmap = frame
                    }
                }
            } catch (_: Exception) {
                if (cached == null) {
                    val frame = MediaManager.extractVideoThumbnail(context, dataKey, maxDim = 640)
                    if (frame != null) {
                        MediaBitmapCache.put(cacheKey, frame)
                        withContext(Dispatchers.Main) {
                            thumbBitmap = frame
                            if (frame.width > 0 && frame.height > 0) {
                                val frameRatio = frame.width.toFloat() / frame.height.toFloat()
                                if (frameRatio > 0f) {
                                    videoAspectRatio = frameRatio.coerceIn(0.56f, 2.4f)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Progress poller
    LaunchedEffect(player, hasStarted) {
        while (isActive) {
            val mp = player
            if (mp != null && hasStarted && !isUserSeeking) {
                try {
                    if (mp.isPlaying) {
                        currentPosMs = mp.currentPosition.toInt()
                        val dur = mp.duration
                        if (dur > 0) totalDurMs = dur.toInt()
                        savePlaybackPosition()
                    }
                } catch (_: Exception) {}
            }
            delay(100)
        }
    }

    // Cleanup on dispose
    DisposableEffect(dataKey, currentUserId, postId) {
        onDispose {
            hideJob?.cancel()
            GlobalVideoPlayerManager.notifyPaused(dataKey)
            savePlaybackPosition(force = true)
            releaseVideoPlayer()
        }
    }

    fun togglePlayPause() {
        if (isUploading) return
        if (!hasStarted) {
            if (isCompleted) {
                isCompleted = false
                currentPosMs = 0
                restorePositionMs = 0
                shouldRestorePosition = false
                PostPlaybackPositionStore.save(context, currentUserId, playbackPostId, 0, totalDurMs, completed = false, force = true)
            }
            GlobalVideoPlayerManager.requestPlay(dataKey)
            hasStarted = true
            showControls = true
            scheduleHideControls()
            return
        }
        val mp = player ?: return
        try {
            if (isCompleted) {
                mp.seekTo(0L)
                mp.play()
                isPlaying = true
                isCompleted = false
                restorePositionMs = 0
                shouldRestorePosition = false
                PostPlaybackPositionStore.save(context, currentUserId, playbackPostId, 0, totalDurMs, completed = false, force = true)
                GlobalVideoPlayerManager.requestPlay(dataKey)
                triggerPulse(Icons.Filled.PlayArrow)
            } else if (mp.isPlaying) {
                savePlaybackPosition(force = true)
                mp.pause()
                isPlaying = false
                GlobalVideoPlayerManager.notifyPaused(dataKey)
                triggerPulse(Icons.Filled.Pause)
            } else {
                mp.play()
                isPlaying = true
                GlobalVideoPlayerManager.requestPlay(dataKey)
                triggerPulse(Icons.Filled.PlayArrow)
            }
        } catch (_: Exception) {}
        showControls = true
        scheduleHideControls()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .aspectRatio(videoAspectRatio)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF16132D))
            .clickable {
                if (!isUploading) {
                    if (!hasStarted) {
                        togglePlayPause()
                    } else {
                        showControls = !showControls
                        if (showControls && isPlaying) scheduleHideControls()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // 1. Thumbnail Background
        if (!hasStarted || isBuffering || player == null) {
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap!!.asImageBitmap(),
                    contentDescription = "Video Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF14112E), Color(0xFF221A47))))
                )
            }
        }

        // 2. Video Surface.  Media3 handles HLS quality switching and keeps
        // network/buffering work off the Compose/UI thread.
        if (hasStarted) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var attachedSurface: Surface? = null

                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                try {
                                    attachedSurface?.release()
                                    val surface = Surface(st)
                                    attachedSurface = surface
                                    val mp = ExoPlayer.Builder(ctx).build()
                                    val hlsUrl = cloudinaryAdaptiveHlsUrl(dataKey)
                                    var usingHls = hlsUrl != null
                                    var hlsFallbackUsed = false

                                    fun setSource(url: String, isHls: Boolean) {
                                        val item = MediaItem.Builder().setUri(url).apply {
                                            if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8)
                                        }.build()
                                        mp.setMediaItem(item)
                                        mp.prepare()
                                    }

                                    mp.addListener(object : Player.Listener {
                                        override fun onPlaybackStateChanged(playbackState: Int) {
                                            isBuffering = playbackState == Player.STATE_BUFFERING
                                            when (playbackState) {
                                                Player.STATE_READY -> {
                                                    totalDurMs = mp.duration.takeIf { it > 0 }?.toInt() ?: 1
                                                    val maxResumePosition = (totalDurMs - 1_000).coerceAtLeast(0)
                                                    if (shouldRestorePosition && restorePositionMs in 2_001..maxResumePosition) {
                                                        mp.seekTo(restorePositionMs.toLong())
                                                        currentPosMs = restorePositionMs
                                                    } else if (isCompleted) {
                                                        currentPosMs = totalDurMs
                                                    }
                                                    mp.volume = if (isMuted) 0f else 1f
                                                    if (GlobalVideoPlayerManager.activeVideoKey.value == dataKey && !isCompleted) {
                                                        mp.play()
                                                    }
                                                    isError = false
                                                }
                                                Player.STATE_ENDED -> {
                                                    isPlaying = false
                                                    isCompleted = true
                                                    currentPosMs = totalDurMs
                                                    savePlaybackPosition(force = true)
                                                    showControls = true
                                                    GlobalVideoPlayerManager.notifyPaused(dataKey)
                                                }
                                            }
                                        }

                                        override fun onIsPlayingChanged(playing: Boolean) {
                                            isPlaying = playing
                                        }

                                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                                            if (videoSize.width > 0 && videoSize.height > 0) {
                                                val ratio = videoSize.width.toFloat() / videoSize.height.toFloat()
                                                videoAspectRatio = ratio.coerceIn(0.56f, 2.4f)
                                            }
                                        }

                                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                            // A just-uploaded Cloudinary HLS rendition can still be
                                            // processing.  Fall back once to the original MP4 rather
                                            // than presenting a playback error to the viewer.
                                            if (usingHls && !hlsFallbackUsed) {
                                                hlsFallbackUsed = true
                                                usingHls = false
                                                setSource(dataKey, isHls = false)
                                            } else {
                                                isError = true
                                                isBuffering = false
                                                isPlaying = false
                                                GlobalVideoPlayerManager.notifyPaused(dataKey)
                                            }
                                        }
                                    })
                                    mp.setVideoSurface(surface)
                                    isBuffering = true
                                    setSource(hlsUrl ?: dataKey, isHls = usingHls)
                                    player = mp
                                } catch (e: Exception) {
                                    isError = true
                                    isBuffering = false
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                savePlaybackPosition(force = true)
                                try { player?.clearVideoSurface() } catch (_: Exception) {}
                                releaseVideoPlayer()
                                try { attachedSurface?.release() } catch (_: Exception) {}
                                attachedSurface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3. Live Centered Upload Indicator (Play button hidden and disabled while uploading)
        if (isUploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (uploadProgress > 0f) {
                        CircularProgressIndicator(
                            progress = { uploadProgress.coerceIn(0f, 1f) },
                            color = Color(0xFF8B6BFF),
                            trackColor = Color(0xFF352B66),
                            modifier = Modifier.size(46.dp),
                            strokeWidth = 3.5.dp
                        )
                        Text(
                            text = "${(uploadProgress * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        CircularProgressIndicator(
                            color = Color(0xFF8B6BFF),
                            modifier = Modifier.size(46.dp),
                            strokeWidth = 3.5.dp
                        )
                    }
                }
            }
        }

        // 4. Initial Ready-to-Play Button (Clean Instagram/Facebook style)
        if (!isUploading && !hasStarted) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                            imageVector = if (isCompleted) Icons.Filled.Replay else Icons.Filled.PlayArrow,
                            contentDescription = if (isCompleted) "Replay Video" else "Play Video",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // 5. Buffering Spinner
        if (hasStarted && isBuffering && !isError) {
            CircularProgressIndicator(
                color = Color(0xFF8B6BFF),
                modifier = Modifier.size(44.dp),
                strokeWidth = 3.5.dp
            )
        }

        // 6. Play/Pause Pulse Icon on Tap
        if (animPulseIcon != null) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f * pulseAlpha.value)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = animPulseIcon!!,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = pulseAlpha.value),
                    modifier = Modifier
                        .size(34.dp)
                        .scale(pulseScale.value)
                )
            }
        }

        // 7. Minimal Bottom Scrubber & Controls Overlay
        AnimatedVisibility(
            visible = hasStarted && showControls,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                val currentFrac = if (totalDurMs > 0) {
                    if (isUserSeeking) seekTargetFraction
                    else (currentPosMs.toFloat() / totalDurMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Slider(
                    value = currentFrac,
                    onValueChange = { frac ->
                        isUserSeeking = true
                        seekTargetFraction = frac
                        scheduleHideControls()
                    },
                    onValueChangeFinished = {
                        player?.let { mp ->
                            val targetMs = (seekTargetFraction * totalDurMs).toInt().coerceIn(0, totalDurMs)
                            try {
                                mp.seekTo(targetMs.toLong())
                                currentPosMs = targetMs
                                restorePositionMs = targetMs
                                shouldRestorePosition = targetMs > 0
                                if (isCompleted) {
                                    isCompleted = false
                                    mp.play()
                                    isPlaying = true
                                }
                            } catch (_: Exception) {}
                        }
                        isUserSeeking = false
                        scheduleHideControls()
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFF8B6BFF),
                        inactiveTrackColor = Color.White.copy(alpha = 0.28f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { togglePlayPause() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                isCompleted -> Icons.Filled.Replay
                                isPlaying -> Icons.Filled.Pause
                                else -> Icons.Filled.PlayArrow
                            },
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.width(6.dp))

                    val displayMs = if (isUserSeeking) (seekTargetFraction * totalDurMs).toInt() else currentPosMs
                    Text(
                        text = "${formatMediaDuration(displayMs)} / ${formatMediaDuration(totalDurMs)}",
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(Modifier.weight(1f))

                    // Mute / Unmute Button
                    IconButton(
                        onClick = {
                            isMuted = !isMuted
                            player?.let { mp ->
                                mp.volume = if (isMuted) 0f else 1f
                            }
                            scheduleHideControls()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = "Mute",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }

        // 8. Upload Error Banner + Retry Button
        if (uploadError != null) {
            // Error banner at bottom center
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFB71C1C).copy(alpha = 0.9f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "⚠️ Upload failed",
                    color = Color.White,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // Retry button (bottom-left, only shown on error)
            if (onRetryUpload != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF8B6BFF).copy(alpha = 0.92f))
                        .clickable { onRetryUpload() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "↺ Retry",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 9. Remove (×) Button — only removes/discards, never retries
        if (onRemoveMedia != null) {
            IconButton(
                onClick = onRemoveMedia,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Audio / Voice Note Media Renderer.
 */
@Composable
fun PostAudioMedia(media: PostMedia, postId: String, currentUserId: Int) {
    val context = LocalContext.current
    val rawUrl = media.localUri ?: media.mediaUrl ?: ""
    val fullUrl = resolveMediaUrl(rawUrl)
    val audioId = remember(rawUrl) { rawUrl.hashCode() }
    val playbackKey = remember(currentUserId, postId) { "post_audio_${currentUserId}_${postId}" }

    val isPlayingThis = GlobalAudioPlayer.isMessagePlaying(audioId)
    val isLoadedThis = GlobalAudioPlayer.isMessageLoaded(audioId)
    val progress = if (isLoadedThis) GlobalAudioPlayer.currentProgress.value else 0f
    val currentPosMs = if (isLoadedThis) GlobalAudioPlayer.currentPositionMs.value else 0
    val totalDurationMs = if (isLoadedThis && GlobalAudioPlayer.totalDurationMs.value > 0) {
        GlobalAudioPlayer.totalDurationMs.value
    } else {
        (media.durationSeconds * 1000).coerceAtLeast(0)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF181438))
            .border(1.dp, Color(0xFF2E245E), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0xFF8B6BFF))
                .clickable {
                    if (fullUrl.isNotBlank()) {
                        GlobalAudioPlayer.togglePlayPauseWithPlaybackKey(
                            context = context,
                            msgId = audioId,
                            mediaUrl = fullUrl,
                            senderName = "Audio Post",
                            title = media.fileName ?: "Audio Recording",
                            playbackKey = playbackKey
                        )
                    } else {
                        Toast.makeText(context, "Audio source not available", Toast.LENGTH_SHORT).show()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlayingThis) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Audio Control",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = media.fileName ?: "Audio Recording",
                color = Color.White,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Color(0xFF8B6BFF),
                trackColor = Color(0xFF2C2454)
            )
        }

        Spacer(Modifier.width(12.dp))

        val timeText = if (isLoadedThis && currentPosMs > 0) {
            String.format("%d:%02d", (currentPosMs / 1000) / 60, (currentPosMs / 1000) % 60)
        } else if (totalDurationMs > 0) {
            String.format("%d:%02d", (totalDurationMs / 1000) / 60, (totalDurationMs / 1000) % 60)
        } else {
            "Audio"
        }

        Text(
            text = timeText,
            color = Color(0xFF8B88B2),
            fontSize = 11.5.sp
        )
    }
}

/**
 * File / Document / ZIP Media Renderer.
 */
@Composable
fun PostFileMedia(media: PostMedia) {
    val context = LocalContext.current
    val fileName = media.fileName ?: "Document.pdf"
    val ext = fileName.substringAfterLast(".", "").uppercase()
    val rawUrl = media.localUri ?: media.mediaUrl ?: ""
    val fullUrl = resolveMediaUrl(rawUrl)

    val badgeColor = when (ext) {
        "PDF" -> Color(0xFFE53935)
        "DOC", "DOCX" -> Color(0xFF1E88E5)
        "XLS", "XLSX" -> Color(0xFF43A047)
        "PPT", "PPTX" -> Color(0xFFFF7043)
        "ZIP", "RAR", "7Z" -> Color(0xFFFB8C00)
        else -> Color(0xFF8B6BFF)
    }

    fun openOrDownload() {
        if (rawUrl.isBlank()) {
            Toast.makeText(context, "File link not available", Toast.LENGTH_SHORT).show()
            return
        }

        if (rawUrl.startsWith("content://") || rawUrl.startsWith("file://")) {
            try {
                val uri = Uri.parse(rawUrl)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "No app found to open $ext file", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Use the same resumable, tenant-aware engine as chat attachments.
            // DownloadManager's old Downloads/EduConnect destination had no
            // Simple User / university separation and bypassed resume state.
            val transferMessageId = ("post-file|$fullUrl|$fileName").hashCode()
            MediaManager.downloadMediaWithProgress(
                context = context,
                msgId = transferMessageId,
                urlStr = fullUrl,
                defaultFileName = fileName,
                type = AppMediaType.DOCUMENT,
                section = "post",
                onComplete = {
                    Toast.makeText(context, "Saved to your EDUConnect Posts folder", Toast.LENGTH_SHORT).show()
                },
                onError = { error ->
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF181438))
            .border(1.dp, Color(0xFF2E245E), RoundedCornerShape(12.dp))
            .clickable { openOrDownload() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(badgeColor.copy(alpha = 0.2f))
                .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = ext.take(4),
                color = badgeColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                color = Color.White,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = media.fileSizeText ?: "$ext Document",
                color = Color(0xFF8B88B2),
                fontSize = 11.5.sp
            )
        }

        Spacer(Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFF261E50))
                .clickable { openOrDownload() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Download File",
                tint = Color(0xFF8B6BFF),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Interactive Poll Media Renderer.
 */
@Composable
fun PostPollMedia(
    poll: PollData,
    onVoteOption: (optionId: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF161234))
            .border(1.dp, Color(0xFF2C2258), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        // Poll Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Poll,
                contentDescription = null,
                tint = Color(0xFF8B6BFF),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = poll.question,
                color = Color.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        val totalVotes = poll.totalVotes.coerceAtLeast(1)

        poll.options.forEach { option ->
            val isSelected = poll.userVotedOptionId == option.id
            val percentage = (option.voteCount.toFloat() / totalVotes)
            val animatedProgress by animateFloatAsState(
                targetValue = percentage,
                animationSpec = tween(500),
                label = "poll_bar"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1F1A44))
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) Color(0xFF8B6BFF) else Color(0xFF332962),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onVoteOption(option.id) }
            ) {
                // Filled progress bar behind text
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (isSelected) Modifier.background(
                                Brush.horizontalGradient(listOf(Color(0xFF8B6BFF).copy(alpha = 0.5f), Color(0xFF5B3FCC).copy(alpha = 0.6f)))
                            ) else Modifier.background(Color(0xFF2C245A).copy(alpha = 0.45f))
                        )
                )

                // Option Row Content
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = null,
                        tint = if (isSelected) Color(0xFF8B6BFF) else Color(0xFF6B6699),
                        modifier = Modifier.size(18.dp)
                    )

                    Spacer(Modifier.width(10.dp))

                    Text(
                        text = option.text,
                        color = if (isSelected) Color.White else Color(0xFFD6D4EB),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = "${(percentage * 100).toInt()}%",
                        color = if (isSelected) Color(0xFF8B6BFF) else Color(0xFF8B88B2),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "${poll.totalVotes} total votes",
            color = Color(0xFF7A76A3),
            fontSize = 11.5.sp,
            modifier = Modifier.align(Alignment.End)
        )
    }
}
