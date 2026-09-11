package com.security.myapplication.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.work.*
import com.security.myapplication.models.ChatMessage
import com.security.myapplication.models.PersonalMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * UNIVERSAL TRANSFER MANAGER
 * Single entry point for ALL file transfers (uploads + downloads).
 *
 * Replaces UploadStateManager + UniversalUploader for ALL attachment types:
 * • Images         • Videos         • Voice Notes     • Audio files
 * • PDFs           • Word / Excel   • ZIP / RAR / 7Z  • APKs / any file
 *
 * Architecture:
 *   Room DB (source of truth) ← TransferWorker writes ← WorkManager schedules
 *   TransferStateHolder (Compose state) ← TransferWorker pushes progress →
 *   UI Bubble composables read from TransferStateHolder
 *
 * Key guarantees:
 *   ✓ Never creates duplicate server messages
 *   ✓ Never restarts a COMPLETED transfer
 *   ✓ Persists bytes to Room on every progress tick — survives app kill
 *   ✓ Runs in foreground service — survives screen switch and background
 *   ✓ Single notification per transfer, updated in-place
 *   ✓ Pause is purely cooperative (no forced Job cancellation race)
 *   ✓ Resume correctly handles both PAUSED and FAILED transfers
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object TransferManager {

    private const val TAG = "TransferManager"
    const val MAX_VIDEO_UPLOAD_BYTES = 100L * 1024L * 1024L
    const val VIDEO_UPLOAD_LIMIT_MESSAGE =
        "Videos over 100 MB are coming soon. Please choose a video up to 100 MB."

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Callbacks registered by ChatTab / PersonalChatScreen — keyed by transferId
    private val personalUploadCallbacks = ConcurrentHashMap<String, (PersonalMessage) -> Unit>()
    private val groupUploadCallbacks    = ConcurrentHashMap<String, (ChatMessage) -> Unit>()
    private val postMediaUploadCallbacks = ConcurrentHashMap<String, (String) -> Unit>()
    private val downloadCallbacks       = ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<(File) -> Unit>>()
    private val downloadProgressCallbacks = ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<(Float) -> Unit>>()
    // Non-blocking lookup for Compose bubbles.  This replaces the old
    // runBlocking Room query in MediaManager.findLocalFile().
    private val activeUploadFiles = ConcurrentHashMap<String, String>()

    fun getActiveUploadFile(transferId: String): File? =
        activeUploadFiles[transferId]?.let(::File)?.takeIf { it.exists() && it.length() > 0L }

    // ─────────────────────────────────────────────────────────────────────────
    // UPLOAD — one function for every attachment type
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enqueue any upload.
     *
     * @param chatType      "personal" | "group" | "post"
     * @param senderId      Current user ID
     * @param peerId        Receiver user ID (personal) or Group ID (group)
     * @param mediaType     "image" | "video" | "audio" | "voice" | "file"
     * @param uri           Content URI from picker (optional)
     * @param file          Existing File object (optional, used for recordings)
     * @param fileName      Desired file name (auto-detected if empty)
     * @param mimeType      MIME type (auto-detected from extension if empty)
     * @param caption       Caption text (optional)
     * @param onTempId      Called immediately with a stable transferId for UI use
     * @param onSuccess     Called when server confirms receipt — for Personal chat
     * @param onGroupSuccess Called when server confirms receipt — for Group chat
     *
     * @return transferId (UUID string) — use this as the stable key in all UI state maps
     */
    fun enqueueUpload(
        context: Context,
        chatType: String,
        senderId: Int,
        peerId: Int,
        mediaType: String,
        uri: Uri? = null,
        file: File? = null,
        fileName: String = "",
        mimeType: String = "",
        caption: String = "",
        durationSec: Int = 0,
        customTransferId: String? = null,
        onTempId: (transferId: String) -> Unit = {},
        onSuccess: ((PersonalMessage) -> Unit)? = null,
        onGroupSuccess: ((ChatMessage) -> Unit)? = null,
        onPostMediaSuccess: ((mediaUrl: String) -> Unit)? = null
    ): String {
        val transferId = customTransferId ?: UUID.randomUUID().toString()

        // Register callbacks before worker starts
        if (onSuccess != null)      personalUploadCallbacks[transferId] = onSuccess
        if (onGroupSuccess != null) groupUploadCallbacks[transferId]    = onGroupSuccess
        if (onPostMediaSuccess != null) postMediaUploadCallbacks[transferId] = onPostMediaSuccess

        // Immediately notify caller so they can show a temp bubble
        onTempId(transferId)
        TransferStateHolder.onStatusChange(transferId, TransferStatus.QUEUED)

        scope.launch {
            try {
                val dao = TransferDatabase.getInstance(context).transferDao()

                // Resolve file name and MIME
                val resolvedName = when {
                    fileName.isNotBlank() -> fileName
                    file != null -> file.name
                    uri != null -> {
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToFirst() && nameIdx >= 0) cursor.getString(nameIdx) else null
                        } ?: uri.lastPathSegment ?: "file.bin"
                    }
                    else -> "attachment.bin"
                }
                val ext = resolvedName.substringAfterLast(".", "").lowercase()
                val resolvedMime = when {
                    mimeType.isNotBlank() -> mimeType
                    uri != null && context.contentResolver.getType(uri) != null ->
                        context.contentResolver.getType(uri)!!
                    else -> mimeTypeFromExt(ext, mediaType)
                }

                // Do this before creating the private cache copy.  The backend
                // repeats the same rule, so stale/modified clients cannot bypass
                // it, while supported clients avoid needless disk I/O first.
                val sourceSize = sourceSizeBytes(context, uri, file)
                if (mediaType.equals("video", ignoreCase = true) && sourceSize > MAX_VIDEO_UPLOAD_BYTES) {
                    Log.i(TAG, "[$transferId] Rejected unsupported ${sourceSize}-byte video before queueing")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, VIDEO_UPLOAD_LIMIT_MESSAGE, Toast.LENGTH_LONG).show()
                    }
                    TransferStateHolder.onFailed(transferId)
                    return@launch
                }

                // Copy to stable cache file so it survives picker lifecycle
                val tempFile = prepareTempFile(context, uri, file, resolvedName, ext, mediaType)
                if (tempFile == null) {
                    Log.e(TAG, "[$transferId] Could not prepare temp file")
                    TransferStateHolder.onFailed(transferId)
                    return@launch
                }

                // Compression is best-effort and runs through Media3/Bitmap on
                // background codec workers.  It returns the original on any
                // device/format failure and only accepts a genuinely smaller
                // output, so it can never lower reliability or enlarge uploads.
                val uploadFile = MediaCompressor.compressIfUseful(context, tempFile, mediaType)
                val uploadName = when {
                    mediaType.equals("video", ignoreCase = true) && uploadFile !== tempFile ->
                        resolvedName.substringBeforeLast('.', resolvedName) + ".mp4"
                    mediaType.equals("image", ignoreCase = true) && uploadFile !== tempFile ->
                        resolvedName.substringBeforeLast('.', resolvedName) + ".jpg"
                    else -> resolvedName
                }
                val uploadMime = when {
                    mediaType.equals("video", ignoreCase = true) && uploadFile !== tempFile -> "video/mp4"
                    mediaType.equals("image", ignoreCase = true) && uploadFile !== tempFile -> "image/jpeg"
                    else -> resolvedMime
                }
                // Do not delete tempFile here. In some entry points it is the
                // caller's original recording/file rather than a manager-owned
                // cache copy. Keeping it is safer than risking user-media loss;
                // normal cache cleanup can reclaim app-owned temporary files.

                val totalBytes = uploadFile.length()
                activeUploadFiles[transferId] = uploadFile.absolutePath

                // Write initial record to Room
                val record = TransferRecord(
                    transferId       = transferId,
                    chatType         = chatType,
                    senderId         = senderId,
                    peerId           = peerId,
                    direction        = "upload",
                    mediaType        = mediaType,
                    localPath        = uploadFile.absolutePath,
                    remoteUrl        = "",
                    fileName         = uploadName,
                    mimeType         = uploadMime,
                    totalBytes       = totalBytes,
                    transferredBytes = 0,
                    status           = TransferStatus.QUEUED.name,
                    serverMsgId      = 0,
                    caption          = caption,
                    createdAt        = System.currentTimeMillis(),
                    updatedAt        = System.currentTimeMillis(),
                    durationSec      = durationSec
                )
                dao.upsert(record)

                // Schedule WorkManager job as expedited foreground task
                scheduleWorker(context, transferId)

            } catch (e: Exception) {
                Log.e(TAG, "[$transferId] Failed to enqueue upload: ${e.message}", e)
                TransferStateHolder.onFailed(transferId)
            }
        }

        return transferId
    }

    /** Size lookup only; it never opens or copies a media payload into memory. */
    fun sourceSizeBytes(context: Context, uri: Uri? = null, file: File? = null): Long {
        file?.let { return it.length().takeIf { size -> size >= 0L } ?: -1L }
        uri ?: return -1L
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it >= 0L }
            } ?: context.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Enqueue a text message upload into the persistent queue.
     * Guarantees unsent text messages never disappear even across app restarts or offline state.
     */
    fun enqueueTextUpload(
        context: Context,
        chatType: String,
        senderId: Int,
        peerId: Int,
        text: String,
        customTransferId: String? = null,
        onTempId: (transferId: String) -> Unit = {},
        onSuccess: ((PersonalMessage) -> Unit)? = null,
        onGroupSuccess: ((ChatMessage) -> Unit)? = null
    ): String {
        val transferId = customTransferId ?: UUID.randomUUID().toString()

        if (onSuccess != null)      personalUploadCallbacks[transferId] = onSuccess
        if (onGroupSuccess != null) groupUploadCallbacks[transferId]    = onGroupSuccess

        onTempId(transferId)
        TransferStateHolder.onStatusChange(transferId, TransferStatus.QUEUED)

        scope.launch {
            try {
                val dao = TransferDatabase.getInstance(context).transferDao()
                val totalBytes = text.toByteArray(Charsets.UTF_8).size.toLong()

                val record = TransferRecord(
                    transferId       = transferId,
                    chatType         = chatType,
                    senderId         = senderId,
                    peerId           = peerId,
                    direction        = "upload",
                    mediaType        = "text",
                    localPath        = "",
                    remoteUrl        = "",
                    fileName         = "",
                    mimeType         = "text/plain",
                    totalBytes       = totalBytes,
                    transferredBytes = 0,
                    status           = TransferStatus.QUEUED.name,
                    serverMsgId      = 0,
                    caption          = text,
                    createdAt        = System.currentTimeMillis(),
                    updatedAt        = System.currentTimeMillis(),
                    durationSec      = 0
                )
                dao.upsert(record)
                scheduleWorker(context, transferId)
            } catch (e: Exception) {
                Log.e(TAG, "[$transferId] Failed to enqueue text upload: ${e.message}", e)
                TransferStateHolder.onFailed(transferId)
            }
        }

        return transferId
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DOWNLOAD — for receivers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enqueue a download for a received media message.
     *
     * @param transferId    Stable ID — use "$msgId-download" so it's deterministic
     * @param url           Remote URL (/uploads/... or full http://...)
     * @param fileName      Target file name
     * @param mediaType     "image" | "video" | "audio" | "voice" | "file"
     * @param onComplete    Called on the Main thread when file is ready
     * @param onError       Called on the Main thread on failure
     */
    fun enqueueDownload(
        context: Context,
        transferId: String,
        url: String,
        fileName: String,
        mediaType: String,
        section: String = "chat",
        userId: Int = 0,
        onProgress: (Float) -> Unit = {},
        onComplete: (File) -> Unit = {},
        onError: () -> Unit = {}
    ) {
        // Resolve the owner once, before background work begins.  A later
        // logout/login must not route this transfer into a different user's
        // tenant media folder when it resumes.
        val ownerUserId = if (userId > 0) userId else {
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getInt("userId", 0)
        }
        val normalizedSection = if (section.equals("post", ignoreCase = true)) "post" else "chat"

        // Register callback immediately so repeated calls while downloading are all notified
        downloadCallbacks.getOrPut(transferId) { java.util.concurrent.CopyOnWriteArrayList() }.add(onComplete)
        downloadProgressCallbacks.getOrPut(transferId) { java.util.concurrent.CopyOnWriteArrayList() }.add(onProgress)

        scope.launch {
            val dao = TransferDatabase.getInstance(context).transferDao()

            // A transfer id is stable for one message, but this Room database
            // survives logout. Never reuse another account/section's record:
            // doing so could return a Student file while a Simple User is active.
            var existing = dao.get(transferId)
            if (existing != null && existing.direction == "download" &&
                (existing.senderId != ownerUserId || existing.chatType != normalizedSection)
            ) {
                dao.deleteById(transferId)
                existing = null
            }

            // Guard: already completed?
            if (existing?.status == TransferStatus.COMPLETED.name) {
                val f = File(existing.localPath)
                if (f.exists() && f.length() > 0) {
                    withContext(Dispatchers.Main) { onDownloadComplete(transferId, f) }
                    return@launch
                }
            }

            // Guard: already downloading? Worker is active and will invoke onDownloadComplete for all registered listeners!
            if (existing?.statusEnum?.isActive == true) return@launch

            val targetDir = getDownloadDir(context, mediaType, normalizedSection, ownerUserId)
            targetDir.mkdirs()
            val targetFile = File(targetDir, fileName)
            val partFile = File(targetDir, "$fileName.part")

            // Guard: file already fully downloaded and finalized on disk?
            if (targetFile.exists() && targetFile.length() > 0 && !partFile.exists()) {
                dao.markCompleted(transferId, url, 0, System.currentTimeMillis())
                withContext(Dispatchers.Main) {
                    TransferStateHolder.onCompleted(transferId)
                    onDownloadComplete(transferId, targetFile)
                }
                return@launch
            }

            val resumeFrom = if (partFile.exists()) partFile.length() else 0L
            val record = existing?.copy(
                transferredBytes = resumeFrom,
                status = TransferStatus.QUEUED.name,
                updatedAt = System.currentTimeMillis()
            ) ?: TransferRecord(
                transferId       = transferId,
                chatType         = normalizedSection,
                senderId         = ownerUserId,
                peerId           = 0,
                direction        = "download",
                mediaType        = mediaType,
                localPath        = targetFile.absolutePath,
                remoteUrl        = url,
                fileName         = fileName,
                mimeType         = mimeTypeFromExt(fileName.substringAfterLast(".", ""), mediaType),
                totalBytes       = 0,
                transferredBytes = resumeFrom,
                status           = TransferStatus.QUEUED.name,
                serverMsgId      = 0,
                caption          = "",
                createdAt        = System.currentTimeMillis(),
                updatedAt        = System.currentTimeMillis()
            )

            dao.upsert(record)
            TransferStateHolder.onStatusChange(transferId, TransferStatus.QUEUED)
            scheduleWorker(context, transferId)
        }
    }

    /**
     * Persist a download record in Room DB WITHOUT scheduling a WorkManager job.
     *
     * Used by MediaManager.downloadMediaWithProgress() which handles the actual HTTP byte-loop itself
     * (for fine-grained UI progress). This call just ensures the transfer survives app-kill:
     * if the app is killed, restoreOnAppStart() will find this record as PAUSED/DOWNLOADING
     * and TransferAutoResumeWatcher will call enqueueDownload() to properly re-queue it with a Worker.
     */
    fun registerDownloadRecord(
        context: Context,
        transferId: String,
        url: String,
        fileName: String,
        mediaType: String,
        section: String = "chat",
        userId: Int = 0
    ) {
        scope.launch {
            try {
                val dao = TransferDatabase.getInstance(context).transferDao()
                var existing = dao.get(transferId)
                val ownerUserId = if (userId > 0) userId else {
                    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getInt("userId", 0)
                }
                val normalizedSection = if (section.equals("post", ignoreCase = true)) "post" else "chat"
                if (existing != null && existing.direction == "download" &&
                    (existing.senderId != ownerUserId || existing.chatType != normalizedSection)
                ) {
                    dao.deleteById(transferId)
                    existing = null
                }
                // Do not overwrite a same-owner completed record.
                if (existing?.status == TransferStatus.COMPLETED.name) return@launch
                val targetDir = getDownloadDir(context, mediaType, normalizedSection, ownerUserId)
                targetDir.mkdirs()
                val targetFile = File(targetDir, fileName)
                val resumeFrom = if (targetFile.exists()) targetFile.length() else 0L

                val record = existing?.copy(
                    status    = TransferStatus.DOWNLOADING.name,
                    updatedAt = System.currentTimeMillis()
                ) ?: TransferRecord(
                    transferId       = transferId,
                    chatType         = normalizedSection,
                    senderId         = ownerUserId,
                    peerId           = 0,
                    direction        = "download",
                    mediaType        = mediaType,
                    localPath        = targetFile.absolutePath,
                    remoteUrl        = url,
                    fileName         = fileName,
                    mimeType         = mimeTypeFromExt(fileName.substringAfterLast(".", ""), mediaType),
                    totalBytes       = 0,
                    transferredBytes = resumeFrom,
                    status           = TransferStatus.DOWNLOADING.name,
                    serverMsgId      = 0,
                    caption          = "",
                    createdAt        = System.currentTimeMillis(),
                    updatedAt        = System.currentTimeMillis()
                )
                dao.upsert(record)
                // No scheduleWorker() here — MediaManager drives the actual download
            } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PAUSE / RESUME / CANCEL
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cancel/Pause a running transfer immediately and stop WorkManager task.
     */
    fun cancel(context: Context, transferId: String) {
        TransferWorker.flagCancel(transferId)
        try {
            WorkManager.getInstance(context).cancelUniqueWork(transferId)
        } catch (_: Exception) {}
        TransferStateHolder.onStatusChange(transferId, TransferStatus.PAUSED)
        scope.launch {
            try {
                val dao = TransferDatabase.getInstance(context).transferDao()
                dao.markPaused(transferId)
            } catch (_: Exception) {}
        }
        Log.d(TAG, "[$transferId] Transfer cancelled/paused by user")
    }

    /**
     * Pause a running transfer. Bytes saved in DB by the worker itself. Call resume() to continue.
     */
    fun pause(context: Context, transferId: String) {
        cancel(context, transferId)
    }

    /**
     * Resume from where it paused/failed. Reads transferredBytes from Room DB.
     * Handles BOTH:
     *  - PAUSED  → resumed via dao.resume() using its saved byte offset. Both
     *              downloads and media uploads continue from acknowledged bytes.
     *  - FAILED  → retried via dao.retry()
     */
    fun resume(context: Context, transferId: String) {
        scope.launch {
            val dao = TransferDatabase.getInstance(context).transferDao()
            val record = dao.get(transferId) ?: return@launch

            if (record.status == TransferStatus.COMPLETED.name) return@launch

            TransferWorker.clearCancel(transferId)

            val rowsUpdated = when (record.statusEnum) {
                TransferStatus.PAUSED -> dao.resume(transferId)
                TransferStatus.FAILED -> dao.retry(transferId)
                TransferStatus.UPLOADING,
                TransferStatus.DOWNLOADING,
                TransferStatus.RESUMING,
                TransferStatus.QUEUED -> {
                    // Stale in-flight state after app kill/crash — reset to QUEUED and re-run worker
                    dao.markRetrying(transferId)
                }
                else -> 0
            }

            if (rowsUpdated == 0) {
                Log.w(TAG, "[$transferId] Resume no-op — status changed concurrently, nothing to do")
                return@launch
            }

            withContext(Dispatchers.Main) {
                TransferStateHolder.onStatusChange(transferId, TransferStatus.QUEUED)
            }
            scheduleWorker(context, transferId)
            Log.d(TAG, "[$transferId] Resume scheduled from ${record.transferredBytes} bytes")
        }
    }

    /**
     * Retry a failed or paused transfer. Alias for resume().
     */
    fun retry(context: Context, transferId: String) {
        resume(context, transferId)
    }

    /**
     * Delete transfer record from Room DB and UI state (e.g. when user deletes a pending/failed message).
     */
    fun deleteTransfer(context: Context, transferId: String) {
        TransferWorker.flagCancel(transferId)
        activeUploadFiles.remove(transferId)
        try {
            WorkManager.getInstance(context).cancelUniqueWork(transferId)
        } catch (_: Exception) {}
        TransferStateHolder.clear(transferId)
        scope.launch {
            try {
                val dao = TransferDatabase.getInstance(context).transferDao()
                dao.deleteById(transferId)
            } catch (_: Exception) {}
        }
        Log.d(TAG, "[$transferId] Transfer deleted from database and state")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // APP START — restore pending transfers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Call from MainActivity.onCreate() or Application.onCreate().
     * Restores all non-COMPLETED transfers into TransferStateHolder so
     * bubbles show correct state (paused, queued) immediately.
     * Also updates Room DB from active/interrupted states to PAUSED so resume() works.
     * Does NOT auto-resume — user must tap to resume.
     */
    fun restoreOnAppStart(context: Context) {
        scope.launch {
            val dao = TransferDatabase.getInstance(context).transferDao()
            val pending = dao.getPending()
            Log.d(TAG, "Restoring ${pending.size} pending transfers")

            // Prune old completed entries > 7 days
            dao.pruneOld(System.currentTimeMillis() - 7 * 24 * 3600 * 1000L)

            for (record in pending) {
                if (record.direction == "upload") {
                    record.localPath.takeIf { it.isNotBlank() }?.let { path ->
                        if (File(path).exists()) activeUploadFiles[record.transferId] = path
                    }
                }
                when (record.statusEnum) {
                    TransferStatus.UPLOADING,
                    TransferStatus.DOWNLOADING,
                    TransferStatus.RESUMING,
                    TransferStatus.QUEUED -> {
                        // Persist PAUSED state to Room DB so DB and memory are 100% in sync
                        dao.markPaused(record.transferId)
                        withContext(Dispatchers.Main) {
                            TransferStateHolder.onPaused(
                                record.transferId,
                                record.transferredBytes,
                                record.totalBytes
                            )
                        }
                    }
                    TransferStatus.PAUSED -> {
                        withContext(Dispatchers.Main) {
                            TransferStateHolder.onPaused(
                                record.transferId,
                                record.transferredBytes,
                                record.totalBytes
                            )
                        }
                    }
                    TransferStatus.FAILED -> {
                        withContext(Dispatchers.Main) {
                            TransferStateHolder.onFailed(record.transferId)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal callbacks — called by TransferWorker
    // ─────────────────────────────────────────────────────────────────────────

    internal fun onPersonalUploadSuccess(transferId: String, msg: PersonalMessage) {
        val cb = personalUploadCallbacks.remove(transferId)
        cb?.invoke(msg)
    }

    internal fun onGroupUploadSuccess(transferId: String, msg: ChatMessage) {
        val cb = groupUploadCallbacks.remove(transferId)
        cb?.invoke(msg)
    }

    internal fun onPostMediaUploadSuccess(transferId: String, mediaUrl: String) {
        val cb = postMediaUploadCallbacks.remove(transferId)
        cb?.invoke(mediaUrl)
    }

    /** Reconnect a recreated post-composer UI to its persisted upload worker. */
    fun registerPostMediaCallback(transferId: String, callback: (String) -> Unit) {
        postMediaUploadCallbacks[transferId] = callback
    }

    internal fun onDownloadProgress(transferId: String, fraction: Float) {
        downloadProgressCallbacks[transferId]?.forEach { cb ->
            try { cb.invoke(fraction) } catch (_: Exception) {}
        }
    }

    internal fun onDownloadComplete(transferId: String, file: File) {
        downloadProgressCallbacks.remove(transferId)
        val cbs = downloadCallbacks.remove(transferId)
        cbs?.forEach { cb ->
            try { cb.invoke(file) } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun scheduleWorker(context: Context, transferId: String) {
        val data = workDataOf(TransferWorker.KEY_TRANSFER_ID to transferId)
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(data)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // Spread retries during a server/network incident instead of
            // repeatedly synchronising thousands of devices at the same pace.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag("transfer")
            .build()

        // KEEP: never start a second worker for the same transfer. REPLACE can
        // cancel a healthy upload/download and create competing progress/UI
        // callbacks, which is especially costly on slower OEM devices.
        WorkManager.getInstance(context).enqueueUniqueWork(
            transferId,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun prepareTempFile(
        context: Context,
        uri: Uri?,
        file: File?,
        fileName: String,
        ext: String,
        mediaType: String
    ): File? {
        return when {
            // Existing temp file (e.g. from camera or recorder)
            file != null && file.exists() && file.length() > 0 -> file

            // URI from picker — copy to stable cache dir
            uri != null -> {
                try {
                    val prefix = when (mediaType) {
                        "image" -> "img_"; "video" -> "vid_"; "voice" -> "voice_"
                        "audio" -> "aud_"; else -> "file_"
                    }
                    val suffix = if (ext.isNotEmpty()) ".$ext" else ".bin"
                    val temp = File.createTempFile(prefix, suffix, context.cacheDir)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temp).use { out -> input.copyTo(out) }
                    }
                    if (temp.exists() && temp.length() > 0) temp else null
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy URI to temp: ${e.message}")
                    null
                }
            }
            else -> null
        }
    }

    fun getDownloadDir(context: Context, mediaType: String, chatType: String = "chat", userId: Int = 0): File {
        val currentUserId = if (userId > 0) userId else {
            val sharedPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            sharedPrefs.getInt("userId", 0)
        }
        val section = if (chatType.equals("post", ignoreCase = true)) "post" else "chat"
        return com.security.myapplication.storage.EduConnectStorageManager.getDestinationDir(
            context = context,
            userId = currentUserId,
            section = section,
            mediaType = mediaType
        )
    }

    fun mimeTypeFromExt(ext: String, mediaType: String = ""): String = when {
        ext == "pdf"  -> "application/pdf"
        ext == "doc" || ext == "docx" -> "application/msword"
        ext == "xls" || ext == "xlsx" -> "application/vnd.ms-excel"
        ext == "ppt" || ext == "pptx" -> "application/vnd.ms-powerpoint"
        ext == "zip"  -> "application/zip"
        ext == "rar"  -> "application/x-rar-compressed"
        ext == "7z"   -> "application/x-7z-compressed"
        ext == "tar"  -> "application/x-tar"
        ext == "gz"   -> "application/gzip"
        ext == "txt"  -> "text/plain"
        ext == "csv"  -> "text/csv"
        ext == "json" -> "application/json"
        ext == "apk"  -> "application/vnd.android.package-archive"
        ext == "mp3"  -> "audio/mpeg"
        ext == "wav"  -> "audio/wav"
        ext == "m4a"  -> "audio/mp4"
        ext == "ogg" || ext == "opus" -> "audio/ogg"
        ext == "aac"  -> "audio/aac"
        ext == "flac" -> "audio/flac"
        ext == "amr"  -> "audio/amr"
        ext == "mp4"  -> "video/mp4"
        ext == "mkv"  -> "video/x-matroska"
        ext == "avi"  -> "video/x-msvideo"
        ext == "jpg" || ext == "jpeg" -> "image/jpeg"
        ext == "png"  -> "image/png"
        ext == "gif"  -> "image/gif"
        ext == "webp" -> "image/webp"
        mediaType == "image" -> "image/jpeg"
        mediaType == "video" -> "video/mp4"
        mediaType == "audio" || mediaType == "voice" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}
