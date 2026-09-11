package com.security.myapplication.storage

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * EDUCONNECT SCOPED MEDIA STORAGE MANAGER
 * Working directory: Android/media/com.security.myapplication/EDUConnect/
 *
 * Provides isolated storage hierarchies for:
 *   1. Universities (e.g. "Abasyn University Peshawar/")
 *   2. Simple User ("Simple User/")
 *
 * Inside each tenant directory:
 *   ├── EDUConnect Chats/
 *   │   ├── EDUConnect Images/
 *   │   ├── EDUConnect Videos/
 *   │   ├── EDUConnect Audio/
 *   │   └── EDUConnect Files/
 *   ├── EDUConnect Posts/
 *   │   ├── EDUConnect Images/
 *   │   ├── EDUConnect Videos/
 *   │   ├── EDUConnect Audio/
 *   │   └── EDUConnect Files/
 *   └── EDUConnect Database/
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object EduConnectStorageManager {

    private const val TAG = "EduConnectStorage"
    const val ROOT_FOLDER_NAME = "EDUConnect"
    const val SIMPLE_USER_TENANT = "Simple User"
    const val FALLBACK_CAMPUS_TENANT = "Campus Academic"

    private val MEDIA_SUBFOLDERS = listOf(
        "EDUConnect Images",
        "EDUConnect Videos",
        "EDUConnect Audio",
        "EDUConnect Files"
    )

    /**
     * Resolves the app-specific working root:
     * /storage/emulated/0/Android/media/com.security.myapplication/EDUConnect/
     *
     * This location is ideal for resumable `.part` transfers but Android may
     * remove it when the app is uninstalled. Completed user downloads are also
     * published through MediaStore into the shared Downloads hierarchy; use
     * [getPersistentRelativePath] for that uninstall-safe destination.
     */
    fun getMediaRoot(context: Context): File {
        return try {
            val mediaDirs = context.externalMediaDirs
            val baseMediaDir = mediaDirs.firstOrNull { it != null }
                ?: File(Environment.getExternalStorageDirectory(), "Android/media/${context.packageName}")
            
            val eduConnectRoot = File(baseMediaDir, ROOT_FOLDER_NAME)
            if (!eduConnectRoot.exists()) {
                eduConnectRoot.mkdirs()
            }
            eduConnectRoot
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve externalMediaDirs, falling back: ${e.message}")
            val fallback = File(context.getExternalFilesDir(null) ?: context.filesDir, ROOT_FOLDER_NAME)
            if (!fallback.exists()) fallback.mkdirs()
            fallback
        }
    }

    /**
     * Sanitizes folder name to prevent illegal file system characters.
     */
    fun sanitizeFolderName(rawName: String): String {
        val sanitized = rawName.replace(Regex("[\\/:*?\"<>|]"), "_").trim()
        return if (sanitized.isNotBlank()) sanitized else SIMPLE_USER_TENANT
    }

    /**
     * Resolves active tenant name (University Name or "Simple User") with STRICT ISOLATION.
     *
     * • Simple User login -> ALWAYS returns "Simple User", never university.
     * • Student / Teacher login -> ALWAYS returns University name (or fallback campus name),
     *   NEVER "Simple User".
     */
    fun getActiveTenant(context: Context, userId: Int): String {
        val sharedPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        val activeMode = if (userId > 0) sharedPrefs.getString("active_session_mode_$userId", null) else null
        val role = sharedPrefs.getString("role", "Student") ?: "Student"

        // Pure Simple User mode
        val isSimpleUserRole = role.equals("Simple User", ignoreCase = true) || role.equals("SIMPLE_USER", ignoreCase = true)
        // The server-authoritative session API persists "simple". Retain the
        // older "simple_user" spelling only for pre-migration installations.
        // Without accepting both, a Student/Teacher switched to Simple mode
        // could be routed back into the academic media tenant.
        if (activeMode.equals("simple", ignoreCase = true) ||
            activeMode.equals("simple_user", ignoreCase = true) ||
            isSimpleUserRole) {
            return SIMPLE_USER_TENANT
        }

        // Academic mode: Student or Teacher
        // 1. Check user-specific cached university name
        if (userId > 0) {
            val cachedUniName = sharedPrefs.getString("cached_uni_name_$userId", null)
            if (!cachedUniName.isNullOrBlank() && cachedUniName != "none") {
                return sanitizeFolderName(cachedUniName)
            }
        }

        // Never use another account's general preference or the first folder on
        // disk. Either fallback could route a newly signed-in student into a
        // different university's media tree.
        // 2. Check this user's university key.
        val uniKey = if (userId > 0) sharedPrefs.getString("cached_uni_key_$userId", null) else null
        if (!uniKey.isNullOrBlank() && uniKey != "none") {
            val keyTenant = "Campus_${uniKey.trim().uppercase()}"
            return sanitizeFolderName(keyTenant)
        }

        // Final guard: For student/teacher, use academic fallback so it never contaminates Simple User!
        return FALLBACK_CAMPUS_TENANT
    }

    /**
     * Sets or caches the active university name for a given user.
     */
    fun setCachedUniversityName(context: Context, userId: Int, universityName: String) {
        if (userId <= 0 || universityName.isBlank()) return
        val cleanName = sanitizeFolderName(universityName)
        val sharedPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .putString("cached_uni_name_$userId", cleanName)
            .apply()
        
        // Immediately ensure directory structure for this university exists
        createTenantFolderTree(context, cleanName)
    }

    /**
     * Automatically verifies and creates all required directories on app startup or screen opening.
     * Ensures directories exist on every launch without deleting any existing files.
     */
    fun ensureFoldersExist(context: Context, userId: Int = 0) {
        try {
            // 1. Always ensure Simple User directory tree
            createTenantFolderTree(context, SIMPLE_USER_TENANT)

            // 2. If an active academic tenant is resolved, ensure its tree
            val tenant = getActiveTenant(context, userId)
            if (tenant != SIMPLE_USER_TENANT) {
                createTenantFolderTree(context, tenant)
            }

            // 3. Ensure any existing university folders on disk have full subfolder tree
            val root = getMediaRoot(context)
            val existingDirs = root.listFiles { f -> f.isDirectory }
            existingDirs?.forEach { dir ->
                createTenantFolderTree(context, dir.name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring folder existence: ${e.message}")
        }
    }

    /**
     * Creates the complete folder tree for a specific tenant.
     */
    fun createTenantFolderTree(context: Context, tenantName: String) {
        val root = getMediaRoot(context)
        val tenantFolder = File(root, sanitizeFolderName(tenantName))

        val chatsFolder = File(tenantFolder, "EDUConnect Chats")
        val postsFolder = File(tenantFolder, "EDUConnect Posts")
        val dbFolder = File(tenantFolder, "EDUConnect Database")

        if (!chatsFolder.exists()) chatsFolder.mkdirs()
        if (!postsFolder.exists()) postsFolder.mkdirs()
        if (!dbFolder.exists()) dbFolder.mkdirs()

        for (sub in MEDIA_SUBFOLDERS) {
            val chatSub = File(chatsFolder, sub)
            if (!chatSub.exists()) chatSub.mkdirs()

            val postSub = File(postsFolder, sub)
            if (!postSub.exists()) postSub.mkdirs()
        }
    }

    /**
     * Resolves subfolder name for media types.
     */
    fun getSubCategoryFolder(mediaType: String): String {
        return when (mediaType.lowercase().trim()) {
            "image" -> "EDUConnect Images"
            "video" -> "EDUConnect Videos"
            "audio", "voice" -> "EDUConnect Audio"
            else -> "EDUConnect Files"
        }
    }

    /**
     * Resolves the exact destination folder for a download or saved file.
     */
    fun getDestinationDir(
        context: Context,
        userId: Int,
        section: String,
        mediaType: String = ""
    ): File {
        val tenant = getActiveTenant(context, userId)
        return getDestinationDirForTenant(context, tenant, section, mediaType)
    }

    /**
     * Resolves destination folder directly for a known tenant name.
     */
    fun getDestinationDirForTenant(
        context: Context,
        tenantName: String,
        section: String,
        mediaType: String = ""
    ): File {
        val root = getMediaRoot(context)
        val tenantFolder = File(root, sanitizeFolderName(tenantName))
        
        val targetDir = when (section.lowercase().trim()) {
            "post", "posts" -> File(File(tenantFolder, "EDUConnect Posts"), getSubCategoryFolder(mediaType))
            "database", "db" -> File(tenantFolder, "EDUConnect Database")
            else -> File(File(tenantFolder, "EDUConnect Chats"), getSubCategoryFolder(mediaType))
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return targetDir
    }

    /**
     * Shared-storage relative path for completed user downloads.
     *
     * This is deliberately a MediaStore path, not an app-specific `File` path:
     * Android retains MediaStore Downloads after an uninstall, so a later
     * install can find the same media again without ever scanning another
     * tenant's directory.
     */
    fun getPersistentRelativePath(
        context: Context,
        userId: Int,
        section: String,
        mediaType: String
    ): String {
        val tenant = sanitizeFolderName(getActiveTenant(context, userId))
        val collection = if (section.equals("post", ignoreCase = true) || section.equals("posts", ignoreCase = true)) {
            "EDUConnect Posts"
        } else {
            "EDUConnect Chats"
        }
        return "Download/$ROOT_FOLDER_NAME/$tenant/$collection/${getSubCategoryFolder(mediaType)}/"
    }

}
