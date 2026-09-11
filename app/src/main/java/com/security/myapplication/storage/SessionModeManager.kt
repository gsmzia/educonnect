package com.security.myapplication.storage

import android.content.Context
import com.security.myapplication.models.SessionModeRequest
import com.security.myapplication.network.ApiClient

/**
 * Keeps the Android UI/storage selector aligned with the account's
 * server-authoritative session mode.  Call [switchMode] before navigation;
 * local preferences are never changed if the server rejects the request.
 */
object SessionModeManager {
    const val ACADEMIC = "academic"
    const val SIMPLE = "simple"

    private fun key(userId: Int) = "active_session_mode_$userId"

    fun currentMode(context: Context, userId: Int, role: String? = null): String {
        val normalizedRole = role?.trim()?.lowercase()
        if (normalizedRole in setOf("simple user", "simple_user", "simpleuser", "user")) return SIMPLE
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString(key(userId), ACADEMIC)
            ?.lowercase()
            .takeIf { it == SIMPLE } ?: ACADEMIC
    }

    fun persist(context: Context, userId: Int, mode: String) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(key(userId), if (mode.lowercase() == SIMPLE) SIMPLE else ACADEMIC)
            .apply()
    }

    suspend fun switchMode(context: Context, userId: Int, requestedMode: String): String {
        val normalized = if (requestedMode.lowercase() == SIMPLE) SIMPLE else ACADEMIC
        val response = ApiClient.apiService.updateSessionMode(SessionModeRequest(normalized))
        persist(context.applicationContext, userId, response.active_session_mode)
        EduConnectStorageManager.ensureFoldersExist(context.applicationContext, userId)
        return response.active_session_mode
    }
}
