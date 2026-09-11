package com.security.myapplication.notifications

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.util.Locale

/**
 * Helper utility for handling Android battery optimization and aggressive OEM background restrictions
 * (Xiaomi MIUI/HyperOS, Vivo FuntouchOS, Oppo/Realme ColorOS, Huawei EMUI, Samsung OneUI).
 *
 * PLAY STORE POLICY NOTE:
 * Proactively prompting users to disable battery optimization via
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is restricted by Google Play policy to apps whose
 * core function requires it (e.g. alarm clocks, VOIP, IoT/companion apps, file transfer apps,
 * medical monitoring apps). Using it in a general-purpose app without an approved use-case
 * declared in Play Console can trigger a policy rejection or app suspension. Confirm this app
 * actually qualifies before shipping this flow to production.
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptHelper"

    /** The settings page that was successfully opened, if any. */
    enum class SettingsDestination {
        DIRECT_SYSTEM_DIALOG,
        GENERAL_BATTERY_SETTINGS,
        OEM_BACKGROUND_SETTINGS,
        APP_INFO,
        UNAVAILABLE
    }

    /**
     * Returns true if battery optimization is IGNORED (app is unrestricted).
     * Returns false if battery optimization is STILL ACTIVE (OS can kill/restrict it),
     * including when the underlying check itself fails.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check isIgnoringBatteryOptimizations", e)
            false
        }
    }

    /**
     * Requests exemption from battery optimizations via the direct system dialog:
     * "Stop optimizing battery usage? [Allow] [Deny]"
     * Requires android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in the Manifest.
     */
    fun requestIgnoreBatteryOptimizations(context: Context): SettingsDestination {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Doze / battery optimization does not exist before API 23.
            return openAutostartOrAppSettings(context)
        }
        val directRequest = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        if (launchSafely(context, directRequest, "direct battery request")) {
            return SettingsDestination.DIRECT_SYSTEM_DIALOG
        }
        return openGeneralBatterySettings(context)
    }

    /**
     * Opens standard Android Application Details / App Info screen.
     */
    fun openAppSettings(context: Context): SettingsDestination {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        return if (launchSafely(context, intent, "app info")) {
            SettingsDestination.APP_INFO
        } else {
            SettingsDestination.UNAVAILABLE
        }
    }

    /**
     * Attempts to open the OEM-specific Autostart / Background Manager settings screen.
     * Covers Xiaomi, Vivo, Oppo, Realme, Huawei, Honor, OnePlus, Transsion (Tecno/Infinix), Samsung.
     * Falls back to general App Info if no OEM activity resolves.
     */
    fun openAutostartOrAppSettings(context: Context): SettingsDestination {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT)
        val oemIntents = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
            )
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.MainGuideActivity"))
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> listOf(
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.battery.AppBatteryBehaviorActivity"))
                // Removed: com.coloros.safecenter...FloatWindowListActivity — that screen is the
                // "display over other apps" permission page, NOT autostart/background management.
                // Keeping it in the fallback chain was silently opening the wrong settings screen.
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
            )
            manufacturer.contains("samsung") -> listOf(
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"))
            )
            manufacturer.contains("oneplus") -> listOf(
                Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
            )
            manufacturer.contains("transsion") || manufacturer.contains("tecno") || manufacturer.contains("infinix") -> listOf(
                Intent().setComponent(ComponentName("com.transsion.phonemanager", "com.transsion.phonemanager.settings.AutoStartAppListActivity")),
                Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.AutoStartAppListActivity"))
            )
            else -> emptyList()
        }

        for (intent in oemIntents) {
            if (launchSafely(context, intent, "OEM background settings")) {
                return SettingsDestination.OEM_BACKGROUND_SETTINGS
            }
        }

        return openGeneralBatterySettings(context)
    }

    private fun openGeneralBatterySettings(context: Context): SettingsDestination {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return if (launchSafely(context, intent, "general battery settings")) {
            SettingsDestination.GENERAL_BATTERY_SETTINGS
        } else {
            openAppSettings(context)
        }
    }

    private fun launchSafely(context: Context, intent: Intent, label: String): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) {
                Log.i(TAG, "No activity available for $label")
                false
            } else {
                context.startActivity(intent)
                true
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "$label is unavailable", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to open $label", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Could not open $label", e)
            false
        }
    }

    /**
     * Returns detected readable brand name.
     */
    fun getDeviceBrandName(): String {
        val m = Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT)
        return when {
            m.contains("xiaomi") -> "Xiaomi / Redmi"
            m.contains("poco") -> "POCO"
            m.contains("vivo") -> "Vivo"
            m.contains("iqoo") -> "iQOO"
            m.contains("oppo") -> "Oppo"
            m.contains("realme") -> "Realme"
            m.contains("huawei") -> "Huawei"
            m.contains("honor") -> "Honor"
            m.contains("samsung") -> "Samsung"
            m.contains("oneplus") -> "OnePlus"
            m.contains("tecno") -> "Tecno"
            m.contains("infinix") -> "Infinix"
            m.contains("google") -> "Google Pixel"
            else -> Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Returns concise, brand-specific setup guidance in professional English.
     *
     * @param appName Display name of the host app, e.g.
     *   `context.applicationInfo.loadLabel(context.packageManager).toString()`.
     *   Pass it in instead of a hardcoded literal so this helper stays reusable across
     *   apps / white-label builds instead of always showing one fixed app name.
     */
    fun getBrandInstructions(appName: String): List<String> {
        val m = Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT)
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> listOf(
                "Open the provided settings link, select $appName, and enable Autostart.",
                "Open App battery saver and select 'No restrictions' or 'Unrestricted'.",
                "Optionally lock $appName in Recent apps to improve background reliability."
            )
            m.contains("vivo") || m.contains("iqoo") -> listOf(
                "Open the provided settings link and select $appName.",
                "Enable 'Allow background power usage' or 'High background power usage'.",
                "Enable Autostart and, if available, lock $appName in Recent apps."
            )
            m.contains("oppo") || m.contains("realme") -> listOf(
                "Open the provided settings link, select $appName, and enable Auto-launch.",
                "Enable 'Allow background activity' or select 'Unrestricted'.",
                "Optionally lock $appName in Recent apps."
            )
            m.contains("huawei") || m.contains("honor") -> listOf(
                "Open the provided settings link and select $appName.",
                "Choose 'Manage manually' and enable Auto-launch, Secondary launch, and Run in background."
            )
            m.contains("samsung") -> listOf(
                "Open App info → Battery and select 'Unrestricted'.",
                "In Device Care → Battery → Background usage limits, add $appName to 'Never sleeping apps'."
            )
            else -> listOf(
                "Open the provided settings link and select $appName.",
                "Enable 'Allow background power usage', 'Unrestricted', or 'Don't optimize'.",
                "If available, enable background data for $appName."
            )
        }
    }
}
