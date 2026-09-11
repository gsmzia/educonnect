package com.security.myapplication.screens

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.security.myapplication.models.ChatMessage
import com.security.myapplication.models.PersonalMessage
import com.security.myapplication.transfer.TransferManager
import com.security.myapplication.transfer.TransferStateHolder
import com.security.myapplication.transfer.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// -----------------------------------------------------------------------------
// Complete UI State Machine for Universal Attachment Uploads.
// -----------------------------------------------------------------------------
enum class UploadStatus {
    IDLE, QUEUED, UPLOADING, PAUSED, CANCELLED, FAILED, RETRYING, COMPLETED
}

/**
 * Universal Media Upload System & Persistent State Manager (WhatsApp-Style Resumable).
 * Provides full backward compatibility with all existing bubble composables and screens.
 */
object UploadStateManager {

    /**
     * Persistent record of one pending/in-progress upload.
     */
    data class UploadEntry(
        val tempId: Int,
        val tempFilePath: String,
        val senderId: Int,
        val originalName: String = "",
        val mimeType: String = "",
        val msgType: String = "file",
        val receiverId: Int = 0,
        val groupId: Int = 0,
        val caption: String = "",
        val bytesSent: Long = 0,
        val totalBytes: Long = 0,
        val status: String = "IDLE",
        val isCancelled: Boolean = false,
        val chatType: String = "personal",
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val retryCount: Int = 0
    )

    val UploadEntry.progressFraction: Float
        get() = if (totalBytes > 0) (bytesSent.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val UploadEntry.progressPct: Int
        get() = (progressFraction * 100).toInt()

    private const val PREFS_NAME = "sc_upload_state"
    private const val KEY_UPLOADS = "pending_uploads"

    val backgroundUploadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val activeUploadJobs = ConcurrentHashMap<Int, Job>()
    val cancelFlags = ConcurrentHashMap<Int, Boolean>()

    // Compose reads this synchronously in message bubbles.  SharedPreferences
    // JSON parsing belongs to the IO scope, never to a UI-frame lookup.
    private val entryCache = ConcurrentHashMap<Int, UploadEntry>()

    private val prefsMutex = Mutex()
    private val lastPersistAt = ConcurrentHashMap<Int, Long>()
    private const val PERSIST_THROTTLE_MS = 500L

    // Notification throttle — reuses PERSIST_THROTTLE_MS so we never spam binder IPC
    private val lastNotifAt  = ConcurrentHashMap<Int, Long>()
    private val lastNotifPct = ConcurrentHashMap<Int, Int>()

    val mediaProgressMap: SnapshotStateMap<Int, Float> = mutableStateMapOf()
    val mediaBytesMap: SnapshotStateMap<Int, Pair<Long, Long>> = mutableStateMapOf()
    val mediaStatusMap: SnapshotStateMap<Int, UploadStatus> = mutableStateMapOf()

    private val idCounter = java.util.concurrent.atomic.AtomicInteger(
        (System.currentTimeMillis() % 500_000).toInt() + 1000
    )

    fun generateUniqueTempId(): Int = -idCounter.incrementAndGet()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(ctx: Context, entry: UploadEntry) {
        entryCache[entry.tempId] = entry
        backgroundUploadScope.launch {
            prefsMutex.withLock {
                val all = loadAll(ctx).toMutableMap()
                all[entry.tempId] = entry
                persist(ctx, all)
            }
        }
    }

    fun remove(ctx: Context, tempId: Int) {
        entryCache.remove(tempId)
        cancelFlags.remove(tempId)
        activeUploadJobs.remove(tempId)
        mediaProgressMap.remove(tempId)
        mediaBytesMap.remove(tempId)
        mediaStatusMap.remove(tempId)
        com.security.myapplication.notifications.AppNotificationManager.cancelMediaUploadNotification(ctx, tempId)
        backgroundUploadScope.launch {
            prefsMutex.withLock {
                val all = loadAll(ctx).toMutableMap()
                all.remove(tempId)
                persist(ctx, all)
            }
        }
    }

    fun updateProgress(ctx: Context, tempId: Int, bytesSent: Long, totalBytes: Long = 0) {
        val resolvedTotal = if (totalBytes > 0) totalBytes else (mediaBytesMap[tempId]?.second ?: 0L)
        val prog = if (resolvedTotal > 0) bytesSent.toFloat() / resolvedTotal else null

        backgroundUploadScope.launch(Dispatchers.Main) {
            mediaBytesMap[tempId] = Pair(bytesSent, resolvedTotal)
            if (prog != null) {
                mediaProgressMap[tempId] = prog
                val pct = (prog * 100).toInt().coerceIn(1, 99)

                // Throttle: fire notification only when ≥500ms elapsed OR pct actually changed
                val nowNotif = System.currentTimeMillis()
                val prevNotifAt  = lastNotifAt[tempId]  ?: 0L
                val prevNotifPct = lastNotifPct[tempId] ?: -1
                if (nowNotif - prevNotifAt >= PERSIST_THROTTLE_MS || pct != prevNotifPct) {
                    lastNotifAt[tempId]  = nowNotif
                    lastNotifPct[tempId] = pct
                    val entry = get(ctx, tempId)
                    val fName = entry?.originalName ?: "Media"
                    val mType = entry?.msgType ?: "file"
                    com.security.myapplication.notifications.AppNotificationManager.showMediaUploadProgressNotification(
                        ctx, tempId, fName, mType, pct
                    )
                }
            }
            mediaStatusMap[tempId] = UploadStatus.UPLOADING
        }

        val now = System.currentTimeMillis()
        val last = lastPersistAt[tempId] ?: 0L
        if (now - last < PERSIST_THROTTLE_MS) return
        lastPersistAt[tempId] = now

        backgroundUploadScope.launch {
            prefsMutex.withLock {
                val all = loadAll(ctx).toMutableMap()
                val existing = all[tempId] ?: return@withLock
                val finalTotal = if (totalBytes > 0) totalBytes else existing.totalBytes
                all[tempId] = existing.copy(
                    bytesSent = bytesSent,
                    totalBytes = finalTotal,
                    status = "UPLOADING",
                    isCancelled = false,
                    updatedAt = System.currentTimeMillis()
                )
                persist(ctx, all)
            }
        }
    }

    fun updateStatus(ctx: Context, tempId: Int, status: UploadStatus) {
        backgroundUploadScope.launch(Dispatchers.Main) {
            mediaStatusMap[tempId] = status
        }
        backgroundUploadScope.launch {
            prefsMutex.withLock {
                val all = loadAll(ctx).toMutableMap()
                val existing = all[tempId] ?: return@withLock
                all[tempId] = existing.copy(
                    status = status.name,
                    isCancelled = (status == UploadStatus.CANCELLED),
                    updatedAt = System.currentTimeMillis()
                )
                persist(ctx, all)
            }
        }
    }

    fun setCancelled(ctx: Context, tempId: Int) {
        cancelFlags[tempId] = true
        activeUploadJobs[tempId]?.cancel()
        activeUploadJobs.remove(tempId)
        com.security.myapplication.notifications.AppNotificationManager.cancelMediaUploadNotification(ctx, tempId)
        // FIX Bug#2: snapshot current bytesSent from live map BEFORE clearing
        val snapshotBytes = mediaBytesMap[tempId]
        backgroundUploadScope.launch(Dispatchers.Main) {
            mediaStatusMap[tempId] = UploadStatus.CANCELLED
        }
        backgroundUploadScope.launch {
            prefsMutex.withLock {
                val all = loadAll(ctx).toMutableMap()
                val existing = all[tempId] ?: return@withLock
                all[tempId] = existing.copy(
                    isCancelled = true,
                    status = "CANCELLED",
                    // Preserve progress so resume can continue from correct offset
                    bytesSent  = snapshotBytes?.first ?: existing.bytesSent,
                    totalBytes = snapshotBytes?.second?.takeIf { it > 0L } ?: existing.totalBytes,
                    updatedAt  = System.currentTimeMillis()
                )
                persist(ctx, all)
            }
        }
    }

    fun setFailed(ctx: Context, tempId: Int) {
        activeUploadJobs.remove(tempId)
        com.security.myapplication.notifications.AppNotificationManager.cancelMediaUploadNotification(ctx, tempId)
        // FIX Bug#2: snapshot current bytesSent from live map BEFORE clearing
        val snapshotBytes = mediaBytesMap[tempId]
        backgroundUploadScope.launch(Dispatchers.Main) {
            mediaStatusMap[tempId] = UploadStatus.FAILED
        }
        backgroundUploadScope.launch {
            prefsMutex.withLock {
                val all = loadAll(ctx).toMutableMap()
                val existing = all[tempId] ?: return@withLock
                all[tempId] = existing.copy(
                    isCancelled = false,
                    status = "FAILED",
                    retryCount = existing.retryCount + 1,
                    // Preserve progress so retry can attempt resume from correct offset
                    bytesSent  = snapshotBytes?.first ?: existing.bytesSent,
                    totalBytes = snapshotBytes?.second?.takeIf { it > 0L } ?: existing.totalBytes,
                    updatedAt  = System.currentTimeMillis()
                )
                persist(ctx, all)
            }
        }
    }

    fun markCompleted(ctx: Context, tempId: Int) {
        activeUploadJobs.remove(tempId)
        cancelFlags.remove(tempId)
        lastPersistAt.remove(tempId)
        lastNotifAt.remove(tempId)
        lastNotifPct.remove(tempId)
        // Direct WhatsApp-style dismissal: As soon as send completes, remove the upload progress notification immediately!
        com.security.myapplication.notifications.AppNotificationManager.cancelMediaUploadNotification(ctx, tempId)
        backgroundUploadScope.launch(Dispatchers.Main) {
            mediaProgressMap[tempId] = 1f
            mediaStatusMap[tempId] = UploadStatus.COMPLETED
        }
        backgroundUploadScope.launch {
            kotlinx.coroutines.delay(500)
            prefsMutex.withLock {
                val all = loadAll(ctx).toMutableMap()
                all.remove(tempId)
                persist(ctx, all)
            }
            withContext(Dispatchers.Main) {
                mediaBytesMap.remove(tempId)
                mediaProgressMap.remove(tempId)
                mediaStatusMap.remove(tempId)
            }
        }
    }

    fun get(ctx: Context, tempId: Int): UploadEntry? = entryCache[tempId]

    fun restoreSessionState(ctx: Context, autoResume: Boolean = false) {
        backgroundUploadScope.launch {
            val all = loadAll(ctx)
            all.values.forEach { entry ->
                val file = File(entry.tempFilePath)
                if (!file.exists() || file.length() == 0L) {
                    remove(ctx, entry.tempId)
                    return@forEach
                }
                val prog = if (entry.totalBytes > 0) (entry.bytesSent.toFloat() / entry.totalBytes).coerceIn(0f, 1f) else 0f
                withContext(Dispatchers.Main) {
                    mediaProgressMap[entry.tempId] = prog
                    mediaBytesMap[entry.tempId] = Pair(entry.bytesSent, entry.totalBytes)
                    // FIX restoreSessionState: distinguish actual user-cancel vs app-kill interrupt
                    // FAILED = explicit error (retryCount > 0 means it actually tried and failed)
                    // PAUSED = app was killed mid-upload (file still intact, no error flagged)
                    when {
                        entry.isCancelled -> {
                            mediaStatusMap[entry.tempId] = UploadStatus.CANCELLED
                        }
                        entry.status == "FAILED" -> {
                            // Actual upload failure — show retry
                            mediaStatusMap[entry.tempId] = UploadStatus.FAILED
                        }
                        activeUploadJobs.containsKey(entry.tempId) -> {
                            mediaStatusMap[entry.tempId] = UploadStatus.UPLOADING
                        }
                        else -> {
                            // App-kill interrupted — show as paused (not failed) so user can resume
                            mediaStatusMap[entry.tempId] = UploadStatus.PAUSED
                        }
                    }
                }
            }
        }
    }

    fun loadAll(ctx: Context): Map<Int, UploadEntry> {
        return try {
            val json = prefs(ctx).getString(KEY_UPLOADS, null) ?: run {
                entryCache.clear()
                return emptyMap()
            }
            val arr = JSONArray(json)
            val result = mutableMapOf<Int, UploadEntry>()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    val e = UploadEntry(
                        tempId           = o.optInt("tempId", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: continue,
                        tempFilePath     = o.optString("tempFilePath"),
                        senderId         = o.optInt("senderId"),
                        originalName     = o.optString("originalName"),
                        mimeType         = o.optString("mimeType"),
                        msgType          = o.optString("msgType"),
                        receiverId       = o.optInt("receiverId"),
                        groupId          = o.optInt("groupId"),
                        caption          = o.optString("caption", ""),
                        bytesSent        = o.optLong("bytesSent"),
                        totalBytes       = o.optLong("totalBytes"),
                        status           = o.optString("status", "IDLE"),
                        isCancelled      = o.optBoolean("isCancelled"),
                        chatType         = o.optString("chatType", "personal"),
                        createdAt        = o.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt        = o.optLong("updatedAt", System.currentTimeMillis()),
                        retryCount       = o.optInt("retryCount", 0)
                    )
                    if (File(e.tempFilePath).exists()) {
                        result[e.tempId] = e
                    }
                } catch (_: Exception) {}
            }
            entryCache.clear()
            entryCache.putAll(result)
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persist(ctx: Context, map: Map<Int, UploadEntry>) {
        try {
            val arr = JSONArray()
            map.values.forEach { e ->
                val o = JSONObject().apply {
                    put("tempId",       e.tempId)
                    put("tempFilePath", e.tempFilePath)
                    put("originalName", e.originalName)
                    put("mimeType",     e.mimeType)
                    put("msgType",      e.msgType)
                    put("senderId",     e.senderId)
                    put("receiverId",   e.receiverId)
                    put("groupId",      e.groupId)
                    put("caption",      e.caption)
                    put("bytesSent",    e.bytesSent)
                    put("totalBytes",   e.totalBytes)
                    put("status",       e.status)
                    put("isCancelled",  e.isCancelled)
                    put("chatType",     e.chatType)
                    put("createdAt",    e.createdAt)
                    put("updatedAt",    e.updatedAt)
                    put("retryCount",   e.retryCount)
                }
                arr.put(o)
            }
            prefs(ctx).edit().putString(KEY_UPLOADS, arr.toString()).apply()
            // Keep UI access O(1); callers reach persist only from the IO scope.
            entryCache.clear()
            entryCache.putAll(map)
        } catch (_: Exception) {}
    }
}

// -----------------------------------------------------------------------------
// UniversalUploader - backward-compatible delegation to TransferManager.
// -----------------------------------------------------------------------------
object UniversalUploader {

    fun cancel(context: Context, tempId: Int) {
        UploadStateManager.setCancelled(context, tempId)
    }

    fun uploadGroup(
        context: Context,
        groupId: Int,
        userId: Int,
        uri: Uri? = null,
        file: File? = null,
        originalName: String = "",
        mimeType: String = "",
        msgType: String = "file",
        caption: String = "",
        tempId: Int = UploadStateManager.generateUniqueTempId(),
        existingTempFile: File? = null,
        onFailure: () -> Unit = {},
        onSuccess: (ChatMessage) -> Unit
    ) {
        val resolvedFile = existingTempFile ?: file
        TransferManager.enqueueUpload(
            context = context,
            chatType = "group",
            senderId = userId,
            peerId = groupId,
            mediaType = msgType,
            uri = uri,
            file = resolvedFile,
            fileName = originalName,
            mimeType = mimeType,
            caption = caption,
            onGroupSuccess = onSuccess
        )
    }

    fun uploadPersonal(
        context: Context,
        senderId: Int,
        receiverId: Int,
        uri: Uri? = null,
        file: File? = null,
        originalName: String = "",
        mimeType: String = "",
        msgType: String = "file",
        caption: String = "",
        tempId: Int = UploadStateManager.generateUniqueTempId(),
        existingTempFile: File? = null,
        onFailure: () -> Unit = {},
        onSuccess: (PersonalMessage) -> Unit
    ) {
        val resolvedFile = existingTempFile ?: file
        TransferManager.enqueueUpload(
            context = context,
            chatType = "personal",
            senderId = senderId,
            peerId = receiverId,
            mediaType = msgType,
            uri = uri,
            file = resolvedFile,
            fileName = originalName,
            mimeType = mimeType,
            caption = caption,
            onSuccess = onSuccess
        )
    }

    fun resume(
        context: Context,
        tempId: Int,
        onPersonalSuccess: ((PersonalMessage) -> Unit)? = null,
        onGroupSuccess: ((ChatMessage) -> Unit)? = null,
        onFailure: () -> Unit = {}
    ) {
        val entry = UploadStateManager.get(context, tempId) ?: run { onFailure(); return }
        val savedFile = File(entry.tempFilePath)
        if (!savedFile.exists() || savedFile.length() == 0L) {
            UploadStateManager.remove(context, tempId)
            onFailure()
            return
        }

        if (entry.chatType == "group") {
            TransferManager.enqueueUpload(
                context        = context,
                chatType       = "group",
                senderId       = entry.senderId,
                peerId         = entry.groupId,
                mediaType      = entry.msgType,
                file           = savedFile,
                fileName       = entry.originalName,
                mimeType       = entry.mimeType,
                caption        = entry.caption,
                onGroupSuccess = onGroupSuccess
            )
        } else {
            TransferManager.enqueueUpload(
                context        = context,
                chatType       = "personal",
                senderId       = entry.senderId,
                peerId         = entry.receiverId,
                mediaType      = entry.msgType,
                file           = savedFile,
                fileName       = entry.originalName,
                mimeType       = entry.mimeType,
                caption        = entry.caption,
                onSuccess      = onPersonalSuccess
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Media type enum & MediaManager - unified with com.security.myapplication.models.MediaManager
// -----------------------------------------------------------------------------
typealias AppMediaType = com.security.myapplication.models.AppMediaType
typealias MediaManager = com.security.myapplication.models.MediaManager

// -----------------------------------------------------------------------------
// In-memory LRU bitmap cache (thumbnails)
// -----------------------------------------------------------------------------
object MediaBitmapCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    // Keep thumbnails bounded on low-RAM phones. A percentage-only cache can
    // consume tens of MB on Infinix-class devices and trigger GC/LMK pressure.
    private val cacheSize = minOf(maxMemory / 8, 16 * 1024)
    private val lruCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }
    fun get(key: String): Bitmap? {
        val bmp = lruCache.get(key)
        if (bmp != null && bmp.isRecycled) {
            lruCache.remove(key)
            return null
        }
        return bmp
    }
    fun put(key: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            lruCache.put(key, bitmap)
        }
    }
    fun remove(key: String) { lruCache.remove(key) }
    fun clear() { lruCache.evictAll() }
    fun trim(maxSizeKb: Int) { lruCache.trimToSize(maxSizeKb) }
}

// -----------------------------------------------------------------------------
// UniversalUploadProgressBadge
// -----------------------------------------------------------------------------

@Composable
fun UniversalUploadProgressBadge(
    tempId: Int = 0,
    transferId: String = "",
    isUploading: Boolean = false,
    isCancelledOrFailed: Boolean = false,
    status: UploadStatus? = null,
    progress: Float = 0.05f,
    size: Dp = 36.dp,
    tint: Color = Color.White,
    backgroundColor: Color = Color(0xFF8B6BFF),
    onCancel: () -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentProgress: Float
    val effectiveStatus: TransferStatus

    if (transferId.isNotBlank()) {
        currentProgress = TransferStateHolder.progressFor(transferId)
        effectiveStatus = TransferStateHolder.statusFor(transferId)
    } else {
        currentProgress = UploadStateManager.mediaProgressMap[tempId] ?: progress
        val legacyStatus = status ?: UploadStateManager.mediaStatusMap[tempId]
        effectiveStatus = when (legacyStatus) {
            UploadStatus.QUEUED                              -> TransferStatus.QUEUED
            UploadStatus.UPLOADING, UploadStatus.RETRYING   -> TransferStatus.UPLOADING
            UploadStatus.PAUSED, UploadStatus.CANCELLED     -> TransferStatus.PAUSED
            UploadStatus.FAILED                             -> TransferStatus.FAILED
            UploadStatus.COMPLETED                          -> TransferStatus.COMPLETED
            else -> when {
                isUploading        -> TransferStatus.UPLOADING
                isCancelledOrFailed -> TransferStatus.PAUSED
                else               -> TransferStatus.QUEUED
            }
        }
    }

    val isActive = effectiveStatus == TransferStatus.UPLOADING
        || effectiveStatus == TransferStatus.DOWNLOADING
        || effectiveStatus == TransferStatus.RESUMING
        || effectiveStatus == TransferStatus.QUEUED

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable { if (isActive) onCancel() else onRetry() },
        contentAlignment = Alignment.Center
    ) {
        when {
            // FIX Badge: unified active branch handles QUEUED + UPLOADING/DOWNLOADING/RESUMING
            // QUEUED shows indeterminate spinner; active with known progress shows determinate ring
            isActive -> {
                Box(contentAlignment = Alignment.Center) {
                    if (effectiveStatus == TransferStatus.QUEUED) {
                        // Indeterminate — size unknown yet
                        CircularProgressIndicator(
                            modifier = Modifier.size(size - 4.dp),
                            color = tint,
                            trackColor = tint.copy(alpha = 0.25f),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        // Determinate — show real progress
                        CircularProgressIndicator(
                            progress = { currentProgress },
                            color = tint,
                            trackColor = tint.copy(alpha = 0.25f),
                            modifier = Modifier.size(size - 4.dp),
                            strokeWidth = 2.5.dp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel Transfer",
                        tint = tint,
                        modifier = Modifier.size(size / 2.5f)
                    )
                }
            }
            effectiveStatus == TransferStatus.PAUSED
            || effectiveStatus == TransferStatus.FAILED -> {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = if (effectiveStatus == TransferStatus.PAUSED) "Resume" else "Retry",
                    tint = tint,
                    modifier = Modifier.size(size / 1.8f)
                )
            }
        }
    }
}
