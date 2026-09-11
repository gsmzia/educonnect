//noinspection AndroidRoomSqlInspection, SqlResolve, SqlNoDataSourceInspection
@file:Suppress("AndroidRoomSqlInspection", "RoomUnresolvedSymbol", "RoomUnresolvedTable", "SqlResolve", "SqlNoDataSourceInspection")

package com.security.myapplication.transfer

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Production-level Room DAO for file transfers (Uploads + Downloads).
 *
 * Responsibilities:
 * - Persist transfer state
 * - Observe transfer state
 * - Atomically update progress
 * - Protect terminal states
 * - Handle pause/resume/failure
 * - Support WorkManager recovery
 * - Clean old completed records
 */
//noinspection AndroidRoomSqlInspection, SqlResolve, SqlNoDataSourceInspection
@Suppress("AndroidRoomSqlInspection", "SqlResolve", "SqlNoDataSourceInspection")
@Dao
interface TransferDao {

    // ---------------------------------------------------------------------
    // INSERT / UPSERT
    // ---------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: TransferRecord): Long

    @Query(
        """
        UPDATE transfers
        SET
            chatType = :chatType,
            peerId = :peerId,
            senderId = :senderId,
            direction = :direction,
            mediaType = :mediaType,
            localPath = :localPath,
            remoteUrl = :remoteUrl,
            fileName = :fileName,
            mimeType = :mimeType,
            totalBytes = :totalBytes,
            transferredBytes = :transferredBytes,
            status = :status,
            serverMsgId = :serverMsgId,
            caption = :caption,
            retryCount = :retryCount,
            updatedAt = :updatedAt
        WHERE transferId = :transferId
        """
    )
    suspend fun update(
        transferId: String,
        chatType: String,
        peerId: Int,
        senderId: Int,
        direction: String,
        mediaType: String,
        localPath: String,
        remoteUrl: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
        transferredBytes: Long,
        status: String,
        serverMsgId: Int,
        caption: String,
        retryCount: Int,
        updatedAt: Long
    ): Int

    /**
     * True upsert: inserts new or updates all fields of existing transfer.
     */
    @Transaction
    suspend fun upsert(record: TransferRecord) {
        val inserted = insert(record)
        if (inserted == -1L) {
            update(
                transferId       = record.transferId,
                chatType         = record.chatType,
                peerId           = record.peerId,
                senderId         = record.senderId,
                direction        = record.direction,
                mediaType        = record.mediaType,
                localPath        = record.localPath,
                remoteUrl        = record.remoteUrl,
                fileName         = record.fileName,
                mimeType         = record.mimeType,
                totalBytes       = record.totalBytes,
                transferredBytes = record.transferredBytes,
                status           = record.status,
                serverMsgId      = record.serverMsgId,
                caption          = record.caption,
                retryCount       = record.retryCount,
                updatedAt        = record.updatedAt
            )
        }
    }

    // ---------------------------------------------------------------------
    // READ
    // ---------------------------------------------------------------------

    @Query("SELECT * FROM transfers WHERE transferId = :id LIMIT 1")
    suspend fun get(id: String): TransferRecord?

    /**
     * Gets all transfers that can potentially be restored (uploads + downloads).
     */
    @Query(
        """
        SELECT * FROM transfers 
        WHERE status IN ('QUEUED', 'UPLOADING', 'DOWNLOADING', 'RESUMING', 'PAUSED') 
        ORDER BY createdAt ASC
        """
    )
    suspend fun getPending(): List<TransferRecord>

    @Query(
        """
        SELECT * FROM transfers 
        WHERE chatType = :chatType AND peerId = :peerId AND senderId = :senderId 
        ORDER BY createdAt DESC
        """
    )
    suspend fun getForChat(chatType: String, peerId: Int, senderId: Int): List<TransferRecord>

    /**
     * Gets all incomplete (pending, in-flight, paused, or failed) transfers for this specific chat,
     * including text outbox messages and media uploads/downloads.
     */
    @Query(
        """
        SELECT * FROM transfers 
        WHERE chatType = :chatType AND peerId = :peerId AND senderId = :senderId 
          AND status IN ('QUEUED', 'UPLOADING', 'DOWNLOADING', 'RESUMING', 'PAUSED', 'FAILED')
        ORDER BY createdAt ASC
        """
    )
    suspend fun getIncompleteForChat(chatType: String, peerId: Int, senderId: Int): List<TransferRecord>

    /**
     * Gets all upload transfers that can be automatically retried/resumed when internet returns.
     */
    @Query(
        """
        SELECT * FROM transfers 
        WHERE direction = 'upload' 
          AND status IN ('QUEUED', 'PAUSED', 'FAILED')
        ORDER BY createdAt ASC
        """
    )
    suspend fun getRetryableUploads(): List<TransferRecord>

    // ---------------------------------------------------------------------
    // STATE TRANSITIONS (Handles both Uploads and Downloads)
    // ---------------------------------------------------------------------

    @Query("UPDATE transfers SET status = 'UPLOADING', updatedAt = :time WHERE transferId = :id AND status IN ('QUEUED', 'RESUMING')")
    suspend fun startUpload(id: String, time: Long = System.currentTimeMillis()): Int

    @Query("UPDATE transfers SET status = 'DOWNLOADING', updatedAt = :time WHERE transferId = :id AND status IN ('QUEUED', 'RESUMING')")
    suspend fun startDownload(id: String, time: Long = System.currentTimeMillis()): Int

    /**
     * Updates transfer progress for BOTH uploads and downloads.
     */
    @Query(
        """
        UPDATE transfers
        SET
            transferredBytes = CASE
                WHEN :bytes < 0 THEN 0
                WHEN :bytes > totalBytes AND totalBytes > 0 THEN totalBytes
                ELSE :bytes
            END,
            status = :status,
            updatedAt = :time
        WHERE transferId = :id
          AND status IN ('QUEUED', 'UPLOADING', 'DOWNLOADING', 'RESUMING', 'PAUSED')
        """
    )
    suspend fun updateProgress(id: String, bytes: Long, status: String, time: Long = System.currentTimeMillis()): Int

    @Query(
        """
        UPDATE transfers
        SET
            transferredBytes = CASE
                WHEN :bytes < 0 THEN 0
                WHEN :bytes > totalBytes AND totalBytes > 0 THEN totalBytes
                ELSE :bytes
            END,
            status = 'UPLOADING',
            updatedAt = :time
        WHERE transferId = :id
          AND status IN ('QUEUED', 'UPLOADING', 'RESUMING')
        """
    )
    suspend fun updateUploadProgress(id: String, bytes: Long, time: Long = System.currentTimeMillis()): Int

    /** Stores the actual HTTP response size before progress is flushed. */
    @Query("UPDATE transfers SET totalBytes = :totalBytes, updatedAt = :time WHERE transferId = :id")
    suspend fun updateTotalBytes(id: String, totalBytes: Long, time: Long = System.currentTimeMillis()): Int

    /**
     * Marks upload/download as completed.
     */
    @Query(
        """
        UPDATE transfers
        SET
            status = 'COMPLETED',
            remoteUrl = CASE WHEN :url != '' THEN :url ELSE remoteUrl END,
            serverMsgId = :msgId,
            transferredBytes = CASE WHEN totalBytes > 0 THEN totalBytes ELSE transferredBytes END,
            updatedAt = :time
        WHERE transferId = :id
          AND status IN ('QUEUED', 'UPLOADING', 'DOWNLOADING', 'RESUMING', 'PAUSED')
        """
    )
    suspend fun markCompleted(id: String, url: String, msgId: Int, time: Long = System.currentTimeMillis()): Int

    /**
     * Pauses an active transfer (preserves transferredBytes).
     */
    @Query(
        """
        UPDATE transfers
        SET status = 'PAUSED', updatedAt = :time
        WHERE transferId = :id
          AND status IN ('QUEUED', 'UPLOADING', 'DOWNLOADING', 'RESUMING')
        """
    )
    suspend fun markPaused(id: String, time: Long = System.currentTimeMillis()): Int

    @Query("UPDATE transfers SET status = 'QUEUED', updatedAt = :time WHERE transferId = :id AND status = 'PAUSED'")
    suspend fun resume(id: String, time: Long = System.currentTimeMillis()): Int

    @Query(
        """
        UPDATE transfers
        SET status = 'FAILED', retryCount = retryCount + 1, updatedAt = :time
        WHERE transferId = :id
          AND status IN ('QUEUED', 'UPLOADING', 'DOWNLOADING', 'RESUMING', 'PAUSED')
        """
    )
    suspend fun markFailed(id: String, time: Long = System.currentTimeMillis()): Int

    @Query("UPDATE transfers SET status = 'QUEUED', updatedAt = :time WHERE transferId = :id AND status = 'FAILED'")
    suspend fun retry(id: String, time: Long = System.currentTimeMillis()): Int

    /**
     * NEW: used by TransferWorker's in-flight auto-retry path (network blip, 5xx, etc).
     * Goes straight from an ACTIVE state back to QUEUED with retryCount bumped, WITHOUT
     * ever passing through the terminal FAILED state — this is what makes the
     * WorkManager BackoffCriteria actually take effect instead of dead-ending every
     * transient error into a manual-retry-only FAILED transfer.
     */
    @Query(
        """
        UPDATE transfers
        SET status = 'QUEUED', retryCount = retryCount + 1, updatedAt = :time
        WHERE transferId = :id
          AND status IN ('QUEUED', 'UPLOADING', 'DOWNLOADING', 'RESUMING', 'PAUSED')
        """
    )
    suspend fun markRetrying(id: String, time: Long = System.currentTimeMillis()): Int

    @Query(
        """
        UPDATE transfers
        SET status = 'CANCELLED', updatedAt = :time
        WHERE transferId = :id
          AND status IN ('QUEUED', 'UPLOADING', 'DOWNLOADING', 'RESUMING', 'PAUSED')
        """
    )
    suspend fun markCancelled(id: String, time: Long = System.currentTimeMillis()): Int

    // ---------------------------------------------------------------------
    // RETRY / RECOVERY
    // ---------------------------------------------------------------------

    @Query("UPDATE transfers SET status = 'QUEUED', updatedAt = :time WHERE status IN ('UPLOADING', 'DOWNLOADING')")
    suspend fun recoverInterruptedUploads(time: Long = System.currentTimeMillis()): Int

    // ---------------------------------------------------------------------
    // CLEANUP
    // ---------------------------------------------------------------------

    @Query("DELETE FROM transfers WHERE status = 'COMPLETED' AND updatedAt < :before")
    suspend fun pruneCompleted(before: Long): Int

    /** Alias for backward compatibility with TransferManager */
    suspend fun pruneOld(before: Long): Int = pruneCompleted(before)

    @Query("DELETE FROM transfers WHERE status = 'CANCELLED' AND updatedAt < :before")
    suspend fun pruneCancelled(before: Long): Int

    @Delete
    suspend fun delete(record: TransferRecord): Int

    @Query("DELETE FROM transfers WHERE transferId = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM transfers")
    suspend fun deleteAll(): Int
}
