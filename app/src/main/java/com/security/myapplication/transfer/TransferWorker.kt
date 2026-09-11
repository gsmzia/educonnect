package com.security.myapplication.transfer

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.security.myapplication.network.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * WorkManager CoroutineWorker — the universal transfer engine.
 *
 * Handles BOTH uploads and downloads for ALL media types:
 * image, video, audio, voice, file (pdf, zip, rar, docx, etc.)
 *
 * DESIGN NOTE (why this file looks different from a "simple" implementation):
 * The byte-copy loop (writeTo / input.read) is 100% synchronous and NEVER calls a
 * suspend function or blocks on I/O other than the stream itself. Progress is
 * published to Room + Compose state by a SEPARATE coroutine ("the pump") that wakes
 * up on a fixed timer and reads an AtomicLong. This is exactly how smooth,
 * WhatsApp-grade progress bars are built: the network pipe is never stalled waiting
 * on disk/DB/UI work.
 */
class TransferWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_TRANSFER_ID = "transfer_id"
        private const val TAG = "TransferWorker"

        /** Stream copy buffer. 64KB gives noticeably better throughput than 16KB on modern devices/networks. */
        private const val BUFFER_SIZE = 64 * 1024

        /** Each server-acknowledged upload chunk. Kept small enough for reliable mobile retries. */
        private const val RESUMABLE_UPLOAD_CHUNK_SIZE = 1024 * 1024

        /** How often progress is flushed to Room + Compose state. ~5x/sec reads as "smooth" to the eye. */
        private const val PROGRESS_FLUSH_INTERVAL_MS = 200L

        /** How often the system notification is updated. Throttled well below OS rate-limiting (~1/sec). */
        private const val NOTIF_FLUSH_INTERVAL_MS = 800L

        /** Auto-retry ceiling before a transfer is marked permanently FAILED (needs manual retry). */
        private const val MAX_AUTO_RETRIES = 5

        /** Active cancel flags — transferId -> should stop. Cooperative, checked every chunk. */
        val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

        /**
         * A phone must never be asked to stream/decode an unbounded number of large
         * files at once.  Two concurrent transfers keep the UI, disk and RAM stable
         * on slower devices while WorkManager retains the remaining jobs as a queue.
         */
        private val mediaTransferSlots = Semaphore(2)

        fun isCancelled(transferId: String) = cancelFlags[transferId]?.get() == true

        fun flagCancel(transferId: String) {
            cancelFlags.getOrPut(transferId) { AtomicBoolean(false) }.set(true)
        }

        fun clearCancel(transferId: String) {
            cancelFlags.remove(transferId)
        }
    }

    private val db by lazy { TransferDatabase.getInstance(applicationContext) }
    private val dao by lazy { db.transferDao() }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val transferId = inputData.getString(KEY_TRANSFER_ID) ?: "transfer"
        val record = try { dao.get(transferId) } catch (_: Exception) { null }
        val fileName = when {
            record?.mediaType == "text" -> "Message"
            else -> record?.fileName ?: "Media"
        }
        val direction = if (record?.direction == "upload") {
            if (record?.mediaType == "text") "Sending" else "Uploading"
        } else "Downloading"
        val percent = record?.progressPercent ?: 0
        val notif = TransferNotificationHelper.build(applicationContext, transferId, fileName, direction, percent)
        val nId = TransferNotificationHelper.notifId(transferId)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(nId, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(nId, notif)
        }
    }

    // CoroutineWorker defaults to a CPU-oriented dispatcher. All transfer entry
    // work below can block on disk, sockets and media metadata, so confine it to
    // the shared IO dispatcher while explicit UI state changes remain on Main.
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        doWorkOnIo()
    }

    private suspend fun doWorkOnIo(): Result {
        val transferId = inputData.getString(KEY_TRANSFER_ID) ?: return Result.failure()
        val startedAt = SystemClock.elapsedRealtime()

        val record = dao.get(transferId) ?: run {
            Log.w(TAG, "[$transferId] No record in DB — skipping")
            return Result.failure()
        }

        // Guard: never re-execute a completed transfer
        if (record.status == TransferStatus.COMPLETED.name) {
            Log.d(TAG, "[$transferId] Already COMPLETED — not re-running")
            withContext(Dispatchers.Main) { TransferStateHolder.onCompleted(transferId) }
            return Result.success()
        }

        clearCancel(transferId)

        // Mark UPLOADING / DOWNLOADING in DB + UI
        val activeStatus = if (record.direction == "upload") TransferStatus.UPLOADING else TransferStatus.DOWNLOADING
        Log.d(TAG, "[$transferId] START direction=${record.direction} type=${record.mediaType} bytes=${record.transferredBytes}/${record.totalBytes} thread=${Thread.currentThread().name}")
        dao.updateProgress(transferId, record.transferredBytes, activeStatus.name, System.currentTimeMillis())
        withContext(Dispatchers.Main) {
            TransferStateHolder.onProgress(transferId, record.transferredBytes, record.totalBytes, activeStatus)
        }

        // Set foreground notification safely with Android 14 dataSync type
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "[$transferId] Could not set foreground info: ${e.message}")
        }

        // doUpload/doTextUpload/doDownload handle ALL of their own exceptions internally and always
        // return a Result. This try/catch is just a last-resort safety net.
        return try {
            when {
                record.direction == "upload" && record.mediaType == "text" -> doTextUpload(record)
                else -> mediaTransferSlots.withPermit {
                    Log.d(TAG, "[$transferId] Acquired media transfer slot")
                    if (record.direction == "upload") doUpload(record) else doDownload(record)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$transferId] Unhandled transfer error: ${e.message}", e)
            handleErrorResult(transferId, record, e)
        } finally {
            TransferNotificationHelper.cancel(applicationContext, transferId)
            Log.d(TAG, "[$transferId] END elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPLOAD
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun doUpload(record: TransferRecord): Result = coroutineScope {
        val transferId = record.transferId
        val file = File(record.localPath)

        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "[$transferId] Source file missing: ${record.localPath}")
            handleFailure(transferId)
            return@coroutineScope Result.failure()
        }

        val totalBytes = file.length()

        val caption = record.caption.ifBlank {
            when (record.mediaType) {
                "image" -> "📷 Photo"
                "video" -> "🎥 Video"
                "voice" -> "🎤 Voice Note"
                "audio" -> record.fileName
                else -> record.fileName
            }
        }
        // Prefer stored durationSec from TransferRecord (set at recording time).
        // Fall back to MediaMetadataRetriever only if the stored value is 0 (legacy / non-voice uploads).
        val durationSec: Int = if (record.durationSec > 0) {
            record.durationSec
        } else if (record.mediaType == "voice" || record.mediaType == "audio" || record.mediaType == "video") {
            try {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(file.absolutePath)
                val dStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                mmr.release()
                val ms = dStr?.toLongOrNull() ?: 0L
                ((ms + 500L) / 1000L).toInt()
            } catch (_: Exception) { 0 }
        } else 0
        var acknowledgedBytes = record.transferredBytes.coerceIn(0L, totalBytes)
        var lastNotifAt = 0L
        var lastProgressAt = 0L
        var completedPersonal: com.security.myapplication.models.PersonalMessage? = null
        var completedGroup: com.security.myapplication.models.ChatMessage? = null
        var completedPostMediaUrl: String? = null

        try {
            RandomAccessFile(file, "r").use { source ->
                // A zero-byte final check also repairs the tiny crash window where
                // Room has 100% but the final server response was never handled.
                var needsFinalCheck = acknowledgedBytes == totalBytes
                while (acknowledgedBytes < totalBytes || needsFinalCheck) {
                    if (isCancelled(transferId)) throw TransferPauseException("Upload paused by user")

                    source.seek(acknowledgedBytes)
                    val chunkSize = minOf(RESUMABLE_UPLOAD_CHUNK_SIZE.toLong(), totalBytes - acknowledgedBytes)
                        .toInt()
                    val chunkBytes = ByteArray(chunkSize)
                    if (chunkSize > 0) source.readFully(chunkBytes)

                    val chunk = MultipartBody.Part.createFormData(
                        "chunk",
                        record.fileName,
                        chunkBytes.toRequestBody(record.mimeType.toMediaTypeOrNull())
                    )
                    fun form(value: String) = value.toRequestBody("text/plain".toMediaTypeOrNull())

                    // Progress is persisted only after this request returns. That means every
                    // saved percentage is an offset the server has actually stored.
                    val isFinal = acknowledgedBytes + chunkSize >= totalBytes
                    val response = if (record.chatType == "post") {
                        ApiClient.apiService.uploadResumablePostMediaChunk(
                            transferId = form(transferId), authorId = form(record.senderId.toString()),
                            mediaType = form(record.mediaType.uppercase()), fileName = form(record.fileName),
                            totalBytes = form(totalBytes.toString()), offset = form(acknowledgedBytes.toString()),
                            isFinal = form(isFinal.toString()), chunk = chunk
                        )
                    } else {
                        ApiClient.apiService.uploadResumableMediaChunk(
                            transferId = form(transferId), chatType = form(record.chatType),
                            senderId = form(record.senderId.toString()), peerId = form(record.peerId.toString()),
                            text = form(caption), messageType = form(record.mediaType), durationSec = form(durationSec.toString()),
                            fileName = form(record.fileName), totalBytes = form(totalBytes.toString()),
                            offset = form(acknowledgedBytes.toString()), isFinal = form(isFinal.toString()), chunk = chunk
                        )
                    }
                    val serverOffset = response.received_bytes.coerceIn(0L, totalBytes)
                    if (!response.completed && serverOffset == acknowledgedBytes) {
                        throw IOException("Server did not acknowledge this upload chunk")
                    }
                    val now = System.currentTimeMillis()
                    acknowledgedBytes = serverOffset
                    // Acknowledgements can arrive much faster than a frame on Wi-Fi.
                    // Persist/render only on the shared cadence, while the server keeps
                    // the exact offset for pause/resume after every chunk.
                    if (now - lastProgressAt >= PROGRESS_FLUSH_INTERVAL_MS || response.completed) {
                        lastProgressAt = now
                        publishProgress(transferId, acknowledgedBytes, totalBytes, TransferStatus.UPLOADING)
                    }
                    if (now - lastNotifAt >= NOTIF_FLUSH_INTERVAL_MS || response.completed) {
                        lastNotifAt = now
                        val percent = ((acknowledgedBytes * 100) / totalBytes).toInt()
                        TransferNotificationHelper.notify(applicationContext, transferId, record.fileName, "Uploading", percent)
                    }

                    if (response.completed) {
                        completedPersonal = response.personal_message
                        completedGroup = response.group_message
                        completedPostMediaUrl = response.media_url
                        break
                    }
                    needsFinalCheck = false
                }
            }

            when (record.chatType) {
                "personal" -> {
                    val result = completedPersonal ?: throw IOException("Upload completed without a personal message")
                    handleUploadSuccess(record, result.id, result.media_url ?: "")
                    withContext(Dispatchers.Main.immediate) { TransferManager.onPersonalUploadSuccess(transferId, result) }
                }
                "group" -> {
                    val result = completedGroup ?: throw IOException("Upload completed without a group message")
                    handleUploadSuccess(record, result.id, result.media_url ?: "")
                    withContext(Dispatchers.Main.immediate) { TransferManager.onGroupUploadSuccess(transferId, result) }
                }
                "post" -> {
                    val mediaUrl = completedPostMediaUrl ?: throw IOException("Post media upload completed without URL")
                    handleUploadSuccess(record, 0, mediaUrl)
                    withContext(Dispatchers.Main.immediate) { TransferManager.onPostMediaUploadSuccess(transferId, mediaUrl) }
                }
                else -> throw IOException("Unknown upload destination")
            }
            Result.success()
        } catch (e: Exception) {
            if (e is TransferPauseException || isCancelled(transferId)) {
                handlePause(transferId, acknowledgedBytes, totalBytes)
                Result.success()
            } else {
                handleErrorResult(transferId, record.copy(transferredBytes = acknowledgedBytes), e)
            }
        }
    }

    private suspend fun handleUploadSuccess(record: TransferRecord, serverMsgId: Int, url: String) = withContext(NonCancellable) {
        dao.markCompleted(record.transferId, url, serverMsgId, System.currentTimeMillis())
        withContext(Dispatchers.Main) {
            TransferStateHolder.onCompleted(record.transferId)
        }
        // Direct WhatsApp-style dismissal: As soon as send completes, remove the upload progress notification immediately!
        TransferNotificationHelper.cancel(applicationContext, record.transferId)
        clearCancel(record.transferId)

        // Cache file under remote URL & filename so local playback is 0ms instant
        try {
            val sourceFile = File(record.localPath)
            if (sourceFile.exists() && sourceFile.length() > 0 && url.isNotBlank()) {
                val fullUrl = when {
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    url.startsWith("content://") || url.startsWith("file://") -> url
                    url.startsWith("/uploads/") -> ApiClient.BASE_URL.removeSuffix("/") + url
                    url.startsWith("uploads/") -> ApiClient.BASE_URL.removeSuffix("/") + "/" + url
                    else -> ApiClient.BASE_URL.removeSuffix("/") + "/" + url.removePrefix("/")
                }
                FileInputStream(sourceFile).use { input ->
                    com.security.myapplication.models.MediaManager.saveDiskCache(applicationContext, fullUrl, input)
                }
                FileInputStream(sourceFile).use { input ->
                    com.security.myapplication.models.MediaManager.saveDiskCache(applicationContext, url, input)
                }
                val fname = url.substringAfterLast("/")
                if (fname.isNotBlank()) {
                    FileInputStream(sourceFile).use { input ->
                        com.security.myapplication.models.MediaManager.saveDiskCache(applicationContext, fname, input)
                    }
                    if (serverMsgId > 0) {
                        FileInputStream(sourceFile).use { input ->
                            com.security.myapplication.models.MediaManager.saveDiskCache(applicationContext, serverMsgId, fname, input)
                        }
                    }
                }

                // A successful sender upload must also have a durable local
                // copy. Disk cache is intentionally disposable; MediaStore is
                // what lets the sender retain their own media if the cloud copy
                // later expires or the app is reinstalled.
                val mediaType = com.security.myapplication.models.AppMediaType.valueOf(
                    when (record.mediaType) {
                        "image" -> "IMAGE"
                        "video" -> "VIDEO"
                        "audio", "voice" -> "AUDIO"
                        else -> "DOCUMENT"
                    }
                )
                // The message renderer resolves persistent media from the
                // delivery URL. Use that stable filename (rather than only the
                // picker filename) so an uninstall/reinstall can find this
                // sender copy without guessing Cloudinary's URL name.
                val persistentName = fname.substringBefore('?').takeIf { it.isNotBlank() }
                    ?: record.fileName
                com.security.myapplication.models.MediaManager.saveToPublicGallery(
                    context = applicationContext,
                    sourceFile = sourceFile,
                    fileName = persistentName,
                    type = mediaType,
                    section = record.chatType,
                    userId = record.senderId
                )
            }
        } catch (_: Exception) {}

        Log.d(TAG, "[${record.transferId}] Upload COMPLETED — serverMsgId=$serverMsgId")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEXT UPLOAD (Persistent Outbox for Offline & Guaranteed Delivery)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun doTextUpload(record: TransferRecord): Result = coroutineScope {
        val transferId = record.transferId
        try {
            if (record.chatType == "personal") {
                val req = com.security.myapplication.models.PersonalMessageCreateRequest(
                    sender_id = record.senderId,
                    receiver_id = record.peerId,
                    text = record.caption,
                    idempotency_key = transferId
                )
                val result = ApiClient.apiService.sendPersonalMessage(
                    idempotencyKey = transferId,
                    request = req
                )
                handleTextUploadSuccess(record, result.id)
                withContext(Dispatchers.Main.immediate) {
                    TransferManager.onPersonalUploadSuccess(transferId, result)
                }
            } else {
                val req = com.security.myapplication.models.ChatRequest(
                    group_id = record.peerId,
                    sender_id = record.senderId,
                    text = record.caption,
                    idempotency_key = transferId
                )
                val result = ApiClient.apiService.sendMessage(
                    groupId = record.peerId,
                    idempotencyKey = transferId,
                    msg = req
                )
                handleTextUploadSuccess(record, result.id)
                withContext(Dispatchers.Main.immediate) {
                    TransferManager.onGroupUploadSuccess(transferId, result)
                }
            }
            Result.success()
        } catch (e: Exception) {
            if (e is TransferPauseException || isCancelled(transferId)) {
                handlePause(transferId, 0L, record.totalBytes)
                Result.success()
            } else {
                handleErrorResult(transferId, record, e)
            }
        }
    }

    private suspend fun handleTextUploadSuccess(record: TransferRecord, serverMsgId: Int) = withContext(NonCancellable) {
        dao.markCompleted(record.transferId, "", serverMsgId, System.currentTimeMillis())
        withContext(Dispatchers.Main) {
            TransferStateHolder.onCompleted(record.transferId)
        }
        TransferNotificationHelper.cancel(applicationContext, record.transferId)
        clearCancel(record.transferId)
        Log.d(TAG, "[${record.transferId}] Text upload COMPLETED — serverMsgId=$serverMsgId")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DOWNLOAD
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun doDownload(record: TransferRecord): Result = coroutineScope {
        val transferId = record.transferId
        val fullUrl = when {
            record.remoteUrl.startsWith("http://") || record.remoteUrl.startsWith("https://") -> record.remoteUrl
            record.remoteUrl.startsWith("content://") || record.remoteUrl.startsWith("file://") -> record.remoteUrl
            record.remoteUrl.startsWith("/uploads/") -> ApiClient.BASE_URL.removeSuffix("/") + record.remoteUrl
            record.remoteUrl.startsWith("uploads/") -> ApiClient.BASE_URL.removeSuffix("/") + "/" + record.remoteUrl
            else -> ApiClient.BASE_URL.removeSuffix("/") + "/" + record.remoteUrl.removePrefix("/")
        }

        val targetFile = File(record.localPath)
        targetFile.parentFile?.mkdirs()
        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")

        // Guard: file already fully downloaded and finalized on disk?
        if (targetFile.exists() && targetFile.length() > 0 && !partFile.exists()) {
            withContext(NonCancellable) {
                dao.markCompleted(transferId, record.remoteUrl, 0, System.currentTimeMillis())
                withContext(Dispatchers.Main) {
                    TransferStateHolder.onCompleted(transferId)
                    TransferManager.onDownloadComplete(transferId, targetFile)
                }
            }
            return@coroutineScope Result.success()
        }

        val requestedResumeFrom = if (partFile.exists()) partFile.length() else 0L

        val conn = URL(fullUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        if (requestedResumeFrom > 0) {
            conn.setRequestProperty("Range", "bytes=$requestedResumeFrom-")
        }

        try {
            conn.connect()
            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                handleFailure(transferId)
                return@coroutineScope Result.failure()
            }

            // BUG FIX: only treat this as a true resume if the server actually honored the
            // Range request (206). If it ignored Range and sent the whole file back (200),
            // we must start counting from 0 and overwrite — otherwise the byte counter and
            // the on-disk file silently go out of sync (progress shows wrong % / file corrupts).
            val serverSupportsResume = responseCode == HttpURLConnection.HTTP_PARTIAL
            val effectiveResumeFrom = if (serverSupportsResume) requestedResumeFrom else 0L

            // contentLength is a 32-bit API and overflows for large videos/files.
            val contentLength = conn.contentLengthLong.takeIf { it > 0L } ?: record.totalBytes
            val totalBytes = if (serverSupportsResume) effectiveResumeFrom + contentLength else contentLength

            if (totalBytes > 0 && record.totalBytes != totalBytes) {
                // updateProgress() clamps against the stored total, so update the
                // database total first or a real Content-Length can never reach UI.
                dao.updateTotalBytes(transferId, totalBytes, System.currentTimeMillis())
            }
            // The .part file is authoritative for a download checkpoint. A
            // throttled progress tick can lag behind the last bytes written.
            if (record.transferredBytes != effectiveResumeFrom || totalBytes > 0 && record.totalBytes != totalBytes) {
                dao.updateProgress(transferId, effectiveResumeFrom, TransferStatus.DOWNLOADING.name, System.currentTimeMillis())
            }

            val downloadedAtomic = AtomicLong(effectiveResumeFrom)
            val wasCancelled = AtomicBoolean(false)

            val pumpJob = launch(Dispatchers.IO) {
                var lastNotifAt = 0L
                while (isActive) {
                    delay(PROGRESS_FLUSH_INTERVAL_MS)
                    try {
                        val downloaded = downloadedAtomic.get()
                        publishProgress(transferId, downloaded, totalBytes, TransferStatus.DOWNLOADING)
                        val now = System.currentTimeMillis()
                        if (now - lastNotifAt >= NOTIF_FLUSH_INTERVAL_MS) {
                            lastNotifAt = now
                            val percent = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0
                            TransferNotificationHelper.notify(applicationContext, transferId, record.fileName, "Downloading", percent)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "[$transferId] Progress tick failed (non-fatal): ${t.message}")
                    }
                }
            }

            try {
                conn.inputStream.use { input ->
                    FileOutputStream(partFile, serverSupportsResume).use { out ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = effectiveResumeFrom
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (isCancelled(transferId)) {
                                wasCancelled.set(true)
                                break
                            }
                            out.write(buffer, 0, read)
                            downloaded += read
                            downloadedAtomic.set(downloaded)
                        }
                        out.flush()
                    }
                }
            } finally {
                pumpJob.cancel()
            }

            if (wasCancelled.get()) {
                handlePause(transferId, downloadedAtomic.get(), totalBytes)
                return@coroutineScope Result.success()
            }

            val finalDownloaded = partFile.length()
            // A valid download with a known expected size must be exact. Accepting
            // a 99%-complete file marks corrupt videos/documents as completed.
            if (finalDownloaded <= 0L || totalBytes > 0 && finalDownloaded != totalBytes) {
                Log.w(TAG, "[$transferId] Download size mismatch: $finalDownloaded / $totalBytes")
                dao.updateProgress(transferId, finalDownloaded, TransferStatus.DOWNLOADING.name, System.currentTimeMillis())
                handleFailure(transferId)
                return@coroutineScope Result.failure()
            }

            // Atomic promotion from .part to final targetFile
            if (targetFile.exists()) targetFile.delete()
            val renamed = partFile.renameTo(targetFile)
            if (!renamed) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }

            withContext(NonCancellable) {
                dao.markCompleted(transferId, record.remoteUrl, 0, System.currentTimeMillis())

                // Cache file in internal media cache for instant local lookup
                try {
                    FileInputStream(targetFile).use { input ->
                        com.security.myapplication.models.MediaManager.saveDiskCache(applicationContext, record.remoteUrl, input)
                    }
                    FileInputStream(targetFile).use { input ->
                        com.security.myapplication.models.MediaManager.saveDiskCache(applicationContext, record.fileName, input)
                    }
                } catch (_: Exception) {}

                // Save to EduConnect public folder (visible in Files / Gallery apps)
                try {
                    val mediaType = com.security.myapplication.models.AppMediaType.valueOf(
                        when (record.mediaType) {
                            "image"          -> "IMAGE"
                            "video"          -> "VIDEO"
                            "audio", "voice" -> "AUDIO"
                            else             -> "DOCUMENT"
                        }
                    )
                    com.security.myapplication.models.MediaManager.saveToPublicGallery(
                        context = applicationContext,
                        sourceFile = targetFile,
                        fileName = record.fileName,
                        type = mediaType,
                        section = record.chatType,
                        userId = record.senderId
                    )
                } catch (_: Exception) {}

                // Complete MediaStore publication before UI listeners fire.
                // Direct-chat lifecycle receipts are emitted by those listeners,
                // so a cloud asset can never be retired before the device has
                // made its durable local-save attempt.
                withContext(Dispatchers.Main) {
                    TransferStateHolder.onCompleted(transferId)
                    TransferManager.onDownloadComplete(transferId, targetFile)
                }
                TransferNotificationHelper.notifyCompleted(applicationContext, transferId, record.fileName, "Downloading")
                clearCancel(transferId)
            }
            Log.d(TAG, "[$transferId] Download COMPLETED — $finalDownloaded bytes at ${targetFile.absolutePath}")
            Result.success()
        } catch (e: Exception) {
            if (isCancelled(transferId)) {
                val saved = if (partFile.exists()) partFile.length() else 0L
                handlePause(transferId, saved, record.totalBytes)
                Result.success()
            } else {
                // Preserve the actual on-disk checkpoint before retry/failure
                // state is written, so Room and the resumable .part file agree.
                val saved = if (partFile.exists()) partFile.length() else 0L
                dao.updateProgress(transferId, saved, TransferStatus.DOWNLOADING.name, System.currentTimeMillis())
                handleErrorResult(transferId, record.copy(transferredBytes = saved), e)
            }
        } finally {
            conn.disconnect()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared progress / error handling
    // ─────────────────────────────────────────────────────────────────────────

    /** Called ONLY from the pump coroutines — never from the hot byte-copy loops. */
    private suspend fun publishProgress(transferId: String, transferred: Long, total: Long, status: TransferStatus) {
        dao.updateProgress(transferId, transferred, status.name, System.currentTimeMillis())
        withContext(Dispatchers.Main) {
            TransferStateHolder.onProgress(transferId, transferred, total, status)
            if (transferId.endsWith("-download")) {
                val fraction = if (total > 0) (transferred.toFloat() / total).coerceIn(0.01f, 0.99f) else 0.05f
                val msgId = transferId.removeSuffix("-download").toIntOrNull()
                if (msgId != null) {
                    com.security.myapplication.models.MediaManager.mediaProgressMap[msgId] = fraction
                }
                TransferManager.onDownloadProgress(transferId, fraction)
            }
        }
    }

    /**
     * Persist a pause. Wrapped in NonCancellable because pause can be triggered by a
     * forced Job cancellation (e.g. WorkManager pulling the network constraint out from
     * under us) — without this guard, the very write that's supposed to save the resume
     * point would itself throw CancellationException and never happen.
     */
    private suspend fun handlePause(transferId: String, transferredBytes: Long, totalBytes: Long) = withContext(NonCancellable) {
        dao.updateProgress(transferId, transferredBytes, TransferStatus.PAUSED.name, System.currentTimeMillis())
        withContext(Dispatchers.Main) {
            TransferStateHolder.onPaused(transferId, transferredBytes, totalBytes)
            if (transferId.endsWith("-download")) {
                val msgId = transferId.removeSuffix("-download").toIntOrNull()
                if (msgId != null) {
                    com.security.myapplication.models.MediaManager.mediaProgressMap.remove(msgId)
                }
            }
        }
        Log.d(TAG, "[$transferId] Paused at $transferredBytes / $totalBytes bytes")
    }

    private suspend fun handleFailure(transferId: String) = withContext(NonCancellable) {
        dao.markFailed(transferId)
        withContext(Dispatchers.Main) {
            TransferStateHolder.onFailed(transferId)
            if (transferId.endsWith("-download")) {
                val msgId = transferId.removeSuffix("-download").toIntOrNull()
                if (msgId != null) {
                    com.security.myapplication.models.MediaManager.mediaProgressMap.remove(msgId)
                }
            }
        }
        TransferNotificationHelper.cancel(applicationContext, transferId)
        Log.e(TAG, "[$transferId] FAILED (terminal)")
    }

    /**
     * Decides retry vs permanent failure. This is what makes the already-configured
     * WorkManager BackoffCriteria actually mean something — previously every exception
     * returned Result.failure() unconditionally, so backoff/retry never engaged.
     */
    private suspend fun handleErrorResult(transferId: String, record: TransferRecord, e: Exception): Result =
        withContext(NonCancellable) {
            Log.e(TAG, "[$transferId] Transfer error: ${e.javaClass.simpleName}: ${e.message}")
            val attemptsSoFar = dao.get(transferId)?.retryCount ?: record.retryCount
            if (isRetryableError(e) && attemptsSoFar < MAX_AUTO_RETRIES) {
                dao.markRetrying(transferId)
                withContext(Dispatchers.Main) {
                    TransferStateHolder.onStatusChange(transferId, TransferStatus.QUEUED)
                }
                Log.w(TAG, "[$transferId] Retryable error — attempt ${attemptsSoFar + 1}/$MAX_AUTO_RETRIES, WorkManager will back off and retry")
                Result.retry()
            } else {
                handleFailure(transferId)
                Result.failure()
            }
        }

    /**
     * Network hiccups get auto-retried; things that will never succeed on their own
     * (bad request, missing file, unauthorized, etc.) fail immediately instead of
     * wasting 5 retries. If your ApiClient's Retrofit service returns Response<T>
     * instead of throwing on non-2xx, adjust the HttpException branch accordingly.
     */
    private fun isRetryableError(e: Throwable): Boolean = when (e) {
        is SocketTimeoutException, is UnknownHostException, is InterruptedIOException -> true
        is HttpException -> e.code() in 500..599 || e.code() == 429
        is IOException -> true
        else -> false
    }
}

/** Internal exception used to signal a user-requested pause inside ProgressBody. */
private class TransferPauseException(message: String) : IOException(message)
