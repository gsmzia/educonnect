package com.security.myapplication.models

import android.content.Context
import com.security.myapplication.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Reports a direct-message attachment only after the download callback has
 * supplied a verified local file.  It deliberately has no Activity/Compose
 * reference, so a rotation or navigation cannot leak a screen or cancel a
 * valid receipt.  The API is idempotent; failed reports remain retryable on a
 * later local open/download and server-side lifecycle cleanup also retries the
 * provider deletion after a receipt was recorded.
 */
object PersonalMediaReceiptReporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightMessageIds = ConcurrentHashMap.newKeySet<Int>()

    fun reportLocalSave(
        context: Context,
        messageId: Int,
        localFile: java.io.File,
        mediaType: AppMediaType,
        ownerUserId: Int,
    ) {
        if (messageId <= 0 || !inFlightMessageIds.add(messageId)) return
        scope.launch {
            try {
                // A worker normally publishes first, but this second call is a
                // cheap MediaStore duplicate check.  It also covers an existing
                // local file restored before this lifecycle feature existed.
                val persisted = MediaManager.saveToPublicGallery(
                    context = context.applicationContext,
                    sourceFile = localFile,
                    fileName = localFile.name,
                    type = mediaType,
                    section = "chat",
                    userId = ownerUserId,
                )
                if (!persisted) return@launch
                ApiClient.apiService.confirmPersonalMediaConsumed(messageId)
            } catch (_: Exception) {
                // Download stays usable locally.  Do not show an unrelated
                // network error or retry in a tight loop on the UI thread.
            } finally {
                inFlightMessageIds.remove(messageId)
            }
        }
    }
}
