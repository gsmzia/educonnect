package com.security.myapplication.transfer

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap

/**
 * AUDITED — NO CHANGES NEEDED. Included as-is so the whole folder is a clean drop-in.
 *
 * Compose-observable in-memory state for active transfers.
 *
 * The DB (TransferDatabase) is the source of truth for persistence.
 * This object is the source of truth for real-time UI rendering.
 *
 * WorkManager writes here on every progress tick.
 * Bubble composables read from here via Compose state observation.
 *
 * Key: transferId (UUID string)
 */
object TransferStateHolder {

    /** 0.0f → 1.0f fraction of bytes transferred. */
    val progressMap: SnapshotStateMap<String, Float> = mutableStateMapOf()

    /** Current lifecycle status. */
    val statusMap: SnapshotStateMap<String, TransferStatus> = mutableStateMapOf()

    /** (transferredBytes, totalBytes) for display as "1.2 MB / 4.5 MB". */
    val bytesMap: SnapshotStateMap<String, Pair<Long, Long>> = mutableStateMapOf()

    // ── Write helpers (called from WorkManager / coroutines) ─────────────────

    fun onProgress(transferId: String, transferred: Long, total: Long, status: TransferStatus) {
        val fraction = if (total > 0) (transferred.toFloat() / total).coerceIn(0f, 1f) else 0.01f
        progressMap[transferId] = fraction
        bytesMap[transferId] = Pair(transferred, total)
        statusMap[transferId] = status
    }

    fun onStatusChange(transferId: String, status: TransferStatus) {
        statusMap[transferId] = status
        if (status.isTerminal) {
            progressMap.remove(transferId)
            bytesMap.remove(transferId)
        }
    }

    fun onCompleted(transferId: String) {
        statusMap[transferId] = TransferStatus.COMPLETED
        progressMap.remove(transferId)
        bytesMap.remove(transferId)
    }

    fun onFailed(transferId: String) {
        statusMap[transferId] = TransferStatus.FAILED
        progressMap.remove(transferId)
        bytesMap.remove(transferId)
    }

    fun onPaused(transferId: String, transferred: Long, total: Long) {
        statusMap[transferId] = TransferStatus.PAUSED
        progressMap[transferId] = if (total > 0) (transferred.toFloat() / total).coerceIn(0f, 1f) else 0f
        bytesMap[transferId] = Pair(transferred, total)
    }

    fun clear(transferId: String) {
        progressMap.remove(transferId)
        statusMap.remove(transferId)
        bytesMap.remove(transferId)
    }

    // ── Read helpers (called from Composables) ────────────────────────────────

    fun progressFor(transferId: String): Float = progressMap[transferId] ?: 0f
    fun statusFor(transferId: String): TransferStatus = statusMap[transferId] ?: TransferStatus.QUEUED
    fun bytesFor(transferId: String): Pair<Long, Long> = bytesMap[transferId] ?: Pair(0L, 0L)
    fun isActive(transferId: String): Boolean = statusFor(transferId).isActive
    fun isCompleted(transferId: String): Boolean = statusFor(transferId) == TransferStatus.COMPLETED
}
