package com.security.myapplication.transfer

import android.app.Notification
import android.content.Context
import com.security.myapplication.notifications.AppNotificationManager

/**
 * Manages transfer progress notifications via [AppNotificationManager].
 * One notification per active transfer, updated in-place (no duplicates).
 * Shows: file name, real % progress, indeterminate bar when starting.
 */
object TransferNotificationHelper {

    // This must exactly match AppNotificationManager's transfer ID calculation.
    // WorkManager's foreground notification and our progress update are the same
    // notification, not two notifications for the same download/upload.
    private const val TRANSFER_NOTIFICATION_BASE = 900_000

    /**
     * Stable notification ID for a transfer. The foreground service and the
     * regular progress update intentionally use this same ID.
     */
    fun notifId(transferId: String): Int {
        return (transferId.hashCode() and 0x7FFFFFFF) % 90_000 + TRANSFER_NOTIFICATION_BASE
    }

    fun createChannel(context: Context) {
        AppNotificationManager.initializeChannels(context)
    }

    /**
     * Build a foreground-service-compatible notification for WorkManager.
     * Called at worker start and updated on each progress tick.
     */
    fun build(
        context: Context,
        transferId: String,
        fileName: String,
        direction: String,
        percent: Int
    ): Notification {
        return AppNotificationManager.showTransferProgressNotification(
            context = context,
            transferId = transferId,
            fileName = fileName,
            direction = direction,
            percent = percent
        )
    }

    /**
     * Post or update a visible progress notification.
     * Safe to call from any thread.
     */
    fun notify(
        context: Context,
        transferId: String,
        fileName: String,
        direction: String,
        percent: Int
    ) {
        AppNotificationManager.showTransferProgressNotification(
            context = context,
            transferId = transferId,
            fileName = fileName,
            direction = direction,
            percent = percent
        )
    }

    /**
     * Show a one-shot completion notification (non-ongoing, tappable).
     */
    fun notifyCompleted(context: Context, transferId: String, fileName: String, direction: String) {
        AppNotificationManager.showTransferCompletedNotification(
            context = context,
            transferId = transferId,
            fileName = fileName,
            direction = direction
        )
    }

    /** Cancel / remove a notification by transferId. */
    fun cancel(context: Context, transferId: String) {
        AppNotificationManager.cancelTransferNotification(context, transferId)
    }
}

