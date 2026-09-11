package com.security.myapplication.transfer

/**
 * AUDITED — NO CHANGES NEEDED. Included as-is so the whole folder is a clean drop-in.
 *
 * Complete lifecycle of a single upload or download transfer.
 * This is the single source of truth — stored in Room, reflected in UI.
 */
enum class TransferStatus {
    /** Waiting in WorkManager queue, not yet started. */
    QUEUED,

    /** Actively transferring bytes right now. */
    UPLOADING,

    /** For downloads only — same as UPLOADING but direction is inbound. */
    DOWNLOADING,

    /** User paused OR network dropped. Bytes saved in DB. Ready to resume. */
    PAUSED,

    /** Resume has been requested, coroutine starting. */
    RESUMING,

    /** Server confirmed receipt / file fully downloaded. Terminal state. */
    COMPLETED,

    /** Non-recoverable error after max retries. Terminal state. */
    FAILED,

    /** User explicitly cancelled. Terminal state. */
    CANCELLED;

    val isActive: Boolean get() = this == UPLOADING || this == DOWNLOADING || this == RESUMING
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
    val canResume: Boolean get() = this == PAUSED || this == FAILED
}
