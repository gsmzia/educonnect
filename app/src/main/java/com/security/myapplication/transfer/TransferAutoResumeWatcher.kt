package com.security.myapplication.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Monitors network connectivity changes and automatically resumes interrupted/paused transfers
 * when Internet connection is restored (WhatsApp-style auto-resume).
 *
 * Usage:
 *   TransferAutoResumeWatcher.start(applicationContext)
 */
object TransferAutoResumeWatcher {

    private const val TAG = "TransferAutoResume"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var registered = false

    @Volatile
    private var lastSweepTime = 0L

    fun start(context: Context) {
        if (registered) return
        registered = true

        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network back online (onAvailable) — scheduling resume sweep")
                triggerSweep(appContext)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    Log.d(TAG, "Network validated — scheduling resume sweep")
                    triggerSweep(appContext)
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not register network callback: ${e.message}", e)
        }
    }

    private fun triggerSweep(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastSweepTime < 2000L) return
        lastSweepTime = now

        scope.launch {
            delay(600L) // Debounce network flap/handover
            try {
                val dao = TransferDatabase.getInstance(context).transferDao()
                val retryableUploads = dao.getRetryableUploads()
                val pending = dao.getPending()
                val pausedDownloads = pending.filter { it.direction == "download" && (it.statusEnum == TransferStatus.PAUSED || it.statusEnum == TransferStatus.QUEUED) }
                val toResume = (retryableUploads + pausedDownloads).distinctBy { it.transferId }
                Log.d(TAG, "Found ${toResume.size} transfers to auto-resume (uploads: ${retryableUploads.size}, downloads: ${pausedDownloads.size})")
                for (record in toResume) {
                    Log.d(TAG, "[${record.transferId}] Auto-resuming transfer (${record.direction}, ${record.mediaType})...")
                    TransferManager.resume(context, record.transferId)
                }
                com.security.myapplication.posts.PostRepository.retryFailedPostsOnNetworkAvailable(context)
                // Feed refresh is cache-first and deduplicated; reconnecting never
                // interrupts the visible list or produces an offline warning.
                com.security.myapplication.posts.PostRepository.refreshActiveFeedOnNetworkAvailable(context)
            } catch (e: Exception) {
                Log.e(TAG, "Auto-resume sweep failed: ${e.message}", e)
            }
        }
    }
}
