package com.security.myapplication.transfer

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single Room entity representing one upload or download transfer.
 * This is the database source of truth. WorkManager reads/writes this.
 * The UI reflects TransferStateHolder which is populated from this.
 *
 * Indices added on the columns TransferDao actually filters/sorts on
 * (status alone for getPending/getRetryable, and the chat-identity combo
 * for getForChat) so these queries stay fast as history grows instead of
 * doing a full table scan on every chat open.
 */
@Entity(
    tableName = "transfers",
    indices = [
        Index(value = ["status"]),
        Index(value = ["chatType", "peerId", "senderId"])
    ]
)
data class TransferRecord(
    /** UUID — stable across retries, restarts, and screen navigation. */
    @PrimaryKey val transferId: String,

    /** "personal", "group", or "post" media destination. */
    val chatType: String,

    /** The user initiating this transfer. */
    val senderId: Int,

    /** For personal chat: receiverId; group: groupId; post uploads: 0. */
    val peerId: Int,

    /** "upload" or "download" */
    val direction: String,

    /** "image" | "video" | "audio" | "voice" | "file" */
    val mediaType: String,

    /**
     * Absolute path to the local file.
     * Upload: temp copy in cacheDir (persists across restarts).
     * Download: target path in external storage.
     */
    val localPath: String,

    /**
     * Remote server URL.
     * Upload: empty until server confirms success.
     * Download: the URL to fetch from.
     */
    val remoteUrl: String,

    val fileName: String,
    val mimeType: String,

    /** Total file size in bytes. 0 if unknown yet. */
    val totalBytes: Long,

    /**
     * KEY FIELD: bytes successfully transferred and acknowledged.
     * Uploads: this is the server-acknowledged resumable chunk offset. It is
     * saved in Room and used to continue the same upload after pause/restart.
     * Downloads: true byte-level resume via HTTP Range, when the server honors it.
     */
    val transferredBytes: Long,

    /** TransferStatus.name */
    val status: String,

    /**
     * Server-assigned message ID after upload completion.
     * 0 until server confirms. Used to replace temp message in UI.
     */
    val serverMsgId: Int,

    /** Optional caption text for the media. */
    val caption: String,

    val createdAt: Long,
    val updatedAt: Long,

    /** Retry count — used to decide auto-retry vs terminal FAILED. */
    val retryCount: Int = 0,

    /** Recording length in seconds for voice notes/audio/video */
    val durationSec: Int = 0
) {
    val statusEnum: TransferStatus
        // Safe parse: if DB has an unknown/legacy status string, fall back to FAILED
        // instead of crashing with IllegalArgumentException from TransferStatus.valueOf()
        get() = runCatching { TransferStatus.valueOf(status) }.getOrDefault(TransferStatus.FAILED)

    val progressFraction: Float
        get() = if (totalBytes > 0) (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val progressPercent: Int
        get() = (progressFraction * 100).toInt()
}
