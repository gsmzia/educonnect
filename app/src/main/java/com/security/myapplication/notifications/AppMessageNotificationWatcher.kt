package com.security.myapplication.notifications

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * APP MESSAGE NOTIFICATION WATCHER (Universal Background & Foreground Sync Engine)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Coordinates app foreground/background lifecycle state and suppresses incoming
 * notifications for whichever chat the user is actively viewing.
 * Delegates actual network synchronization to [NotificationSyncService].
 */
object AppMessageNotificationWatcher {

    private var appContext: Context? = null
    private var currentUserId: Int = 0

    // Lifecycle tracking
    @Volatile
    var isAppInForeground: Boolean = false
        private set

    private var lifecycleRegistered = false

    // Currently open chat screen (only suppresses notification if user is actively in foreground looking at it)
    @Volatile
    private var activeChatType: String? = null // "direct" or "group"
    @Volatile
    private var activeChatId: Int? = null

    // Public read-only aliases used by NotificationSyncService
    val activeChatTypePublic get() = activeChatType
    val activeChatIdPublic   get() = activeChatId

    /**
     * Mark a chat as currently open so incoming messages in this chat don't pop a notification while looking at it.
     */
    fun setActiveChat(type: String?, id: Int?) {
        activeChatType = type
        activeChatId = id
    }

    /**
     * Starts the continuous background/foreground message notification watcher for the logged-in user.
     */
    fun start(context: Context, userId: Int) {
        if (userId <= 0) return
        appContext = context.applicationContext
        currentUserId = userId

        // Login can complete after the activity has already received its
        // lifecycle callbacks.  Mark that existing Activity as foreground now,
        // otherwise an incoming FCM message could briefly notify over the chat
        // the user is already looking at.
        (context as? Activity)?.let { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                isAppInForeground = true
            }
        }

        // Register Activity Lifecycle Callbacks once to track foreground/minimized state
        if (!lifecycleRegistered) {
            val app = context.applicationContext as? Application
            app?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private var startedCount = 0

                override fun onActivityStarted(activity: Activity) {
                    startedCount++
                    isAppInForeground = startedCount > 0
                }

                override fun onActivityStopped(activity: Activity) {
                    startedCount = (startedCount - 1).coerceAtLeast(0)
                    isAppInForeground = startedCount > 0
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) { isAppInForeground = true }
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
            lifecycleRegistered = true
        }

        // Initialize notification channels immediately
        AppNotificationManager.initializeChannels(context.applicationContext)
        NotificationDeduplicator.init(context.applicationContext, userId)

        refreshDeliveryMode(context, userId)
    }

    /**
     * Keeps polling strictly as a fallback. Play Services alone is not enough:
     * the device must also have a successfully registered FCM token.
     */
    fun refreshDeliveryMode(context: Context, userId: Int) {
        if (userId <= 0) return

        // Check Google Play Services availability for FCM.
        // If Google Play Services is available, FCM delivers real-time instant push notifications with 0% battery drain,
        // so background polling is disabled to eliminate duplicate notifications.
        // If Google Play Services is NOT available (e.g. Huawei devices, custom ROMs, non-GMS devices),
        // we start NotificationSyncService as a fallback polling engine so notifications still work 100%.
        val isGmsAvailable = try {
            val gms = com.google.android.gms.common.GoogleApiAvailability.getInstance()
            gms.isGooglePlayServicesAvailable(context.applicationContext) == com.google.android.gms.common.ConnectionResult.SUCCESS
        } catch (_: Throwable) {
            false
        }

        val fcmReady = EduConnectFCMService.isTokenSyncedForUser(context, userId)
        if (isGmsAvailable && fcmReady) {
            android.util.Log.d("NotifWatcher", "FCM ready: starting low-frequency safety sync")
        } else {
            android.util.Log.d("NotifWatcher", "FCM is not ready: starting notification sync fallback")
        }
        // Never treat a local success marker as proof that every future FCM push
        // will arrive.  This small safety net catches stale tokens, temporary FCM
        // outages, and OEM background restrictions without duplicating messages.
        try {
            NotificationSyncService.start(context.applicationContext, userId)
        } catch (e: Exception) {
            android.util.Log.e("NotifWatcher", "Failed to start NotificationSyncService: ${e.message}")
        }
    }

    /**
     * Stops the notification watcher (e.g. on logout).
     */
    fun stop() {
        val uid = currentUserId
        val ctx = appContext
        activeChatType = null
        activeChatId = null
        isAppInForeground = false
        currentUserId = 0
        ctx?.let {
            try {
                NotificationSyncService.stop(it)
            } catch (_: Exception) {}
            if (uid > 0) {
                NotificationDeduplicator.clearForUser(it, uid)
            }
        }
    }
}

