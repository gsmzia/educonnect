package com.security.myapplication.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide connectivity state.  Screens observe this state instead of each
 * inventing a ConnectivityManager check or treating an offline cache as an
 * error.  It holds no Activity reference.
 */
object AppConnectivity {
    private const val TAG = "AppConnectivity"
    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            val manager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            started = true
            update(manager)
            try {
                manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = update(manager)
                    override fun onLost(network: Network) = update(manager)
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(manager)
                })
            } catch (error: SecurityException) {
                // ACCESS_NETWORK_STATE is declared, but a quiet cache-first fallback is
                // safer than crashing if an OEM/device policy rejects the callback.
                Log.w(TAG, "Network callback unavailable", error)
            }
        }
    }

    /** Refresh synchronously reads the small system network state; it never does I/O. */
    fun refresh(context: Context): Boolean {
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        update(manager)
        return _isOnline.value
    }

    private fun update(manager: ConnectivityManager) {
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        _isOnline.value = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}

/**
 * Shared Room-backed payload store for cache-first repositories.  Callers keep
 * their typed models and serializers; this class only owns reliable local I/O.
 */
object OfflineFirstRepository {
    suspend fun read(context: Context, scope: String, key: String): String? =
        OfflineCacheDatabase.getInstance(context).offlineCacheDao().read(scope, key)

    suspend fun write(context: Context, scope: String, key: String, payload: String) {
        OfflineCacheDatabase.getInstance(context).offlineCacheDao().upsert(
            OfflinePayload(scope, key, payload, System.currentTimeMillis())
        )
    }

    suspend fun remove(context: Context, scope: String, key: String) {
        OfflineCacheDatabase.getInstance(context).offlineCacheDao().delete(scope, key)
    }

    suspend fun removeByPrefix(context: Context, scope: String, keyPrefix: String) {
        OfflineCacheDatabase.getInstance(context).offlineCacheDao().deleteByPrefix(scope, keyPrefix)
    }
}
