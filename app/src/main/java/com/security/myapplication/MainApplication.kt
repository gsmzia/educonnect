package com.security.myapplication

import android.app.Application
import android.util.Log
import com.security.myapplication.notifications.AppNotificationManager
import com.security.myapplication.offline.AppConnectivity
import com.security.myapplication.transfer.TransferAutoResumeWatcher
import com.security.myapplication.transfer.TransferManager

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // ── IMPORTANT: Each step has its OWN try-catch + Log.e ────────────────
        // A monolithic try { A; B; C } catch {} means if A crashes, B and C
        // silently never run. That was the root cause of the startup-chain bug.

        // Restore the bearer token before any worker, FCM or notification code
        // can initiate an authenticated request after process recreation.
        try {
            val token = getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getString("authToken", null)
            if (!token.isNullOrBlank()) {
                com.security.myapplication.network.AuthManager.setToken(token)
            }
        } catch (e: Exception) {
            Log.e("MainApplication", "Auth token restoration failed", e)
        }

        try {
            val activeUserId = getSharedPreferences("app_prefs", MODE_PRIVATE).getInt("userId", 0)
            com.security.myapplication.storage.EduConnectStorageManager.ensureFoldersExist(this, activeUserId)
        } catch (e: Exception) {
            Log.e("MainApplication", "ensureFoldersExist failed", e)
        }

        try {
            AppNotificationManager.initializeChannels(this)
        } catch (e: Exception) {
            Log.e("MainApplication", "initializeChannels failed", e)
        }

        try {
            TransferManager.restoreOnAppStart(this)
        } catch (e: Exception) {
            Log.e("MainApplication", "restoreOnAppStart failed", e)
        }

        try {
            com.security.myapplication.screens.UploadStateManager.restoreSessionState(this)
        } catch (e: Exception) {
            Log.e("MainApplication", "UploadStateManager.restoreSessionState failed", e)
        }

        // Requires ACCESS_NETWORK_STATE (now added to Manifest).
        // Previously, a SecurityException here silently killed ALL subsequent startup steps.
        try {
            TransferAutoResumeWatcher.start(this)
        } catch (e: Exception) {
            Log.e("MainApplication", "TransferAutoResumeWatcher.start failed", e)
        }

        // One process-wide source of truth for cache-first repositories.  It is
        // deliberately independent from transfer auto-resume so ordinary screens
        // can remain quiet and usable while the device is offline.
        try {
            AppConnectivity.start(this)
        } catch (e: Exception) {
            Log.e("MainApplication", "AppConnectivity.start failed", e)
        }

        try {
            val savedUserId = getSharedPreferences("app_prefs", MODE_PRIVATE).getInt("userId", -1)
            if (savedUserId > 0) {
                com.security.myapplication.notifications.AppMessageNotificationWatcher
                    .start(this, savedUserId)
            }
        } catch (e: Exception) {
            Log.e("MainApplication", "AppMessageNotificationWatcher.start failed", e)
        }

        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val token = task.result
                        Log.d("MainApplication", "FCM Device Token: $token")
                        getSharedPreferences(com.security.myapplication.notifications.EduConnectFCMService.PREFS_APP, MODE_PRIVATE)
                            .edit()
                            .putString(com.security.myapplication.notifications.EduConnectFCMService.KEY_FCM_TOKEN, token)
                            .apply()
                        val savedUserId = getSharedPreferences("app_prefs", MODE_PRIVATE).getInt("userId", -1)
                        if (savedUserId > 0 && !token.isNullOrBlank()) {
                            // Re-register after app process start. The backend may have pruned or
                            // reset its token store while this device was offline.
                            com.security.myapplication.notifications.EduConnectFCMService.syncTokenToServer(
                                this, savedUserId, token, force = true
                            )
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w("MainApplication", "Firebase token initialization skipped: ${e.message}")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            if (level >= TRIM_MEMORY_MODERATE) {
                com.security.myapplication.screens.MediaBitmapCache.clear()
            } else if (level >= TRIM_MEMORY_BACKGROUND) {
                com.security.myapplication.screens.MediaBitmapCache.trim(4 * 1024)
            }
        } catch (_: Exception) {}
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            com.security.myapplication.screens.MediaBitmapCache.clear()
        } catch (_: Exception) {}
    }
}
