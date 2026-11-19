package app.sanefeed.health

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.sanefeed.accessibility.SaneFeedAccessibilityService
import app.sanefeed.core.Platform

/** Everything the setup checklist shows, read in one go on resume. */
data class PermissionState(
    val accessibilityEnabled: Boolean,
    val batteryUnrestricted: Boolean,
    /** Xiaomi, Redmi and POCO phones have an autostart switch no public API can read. */
    val autostartStep: Boolean,
    /** Android 13+ blocks accessibility for sideloaded apps until "Allow restricted settings". */
    val restrictedSettingsHint: Boolean,
    /** Installed versionName per platform; absent when the app isn't installed. */
    val appVersions: Map<Platform, String>,
) {
    /** Required items only; recommended ones don't stop SaneFeed from working. */
    val ready: Boolean get() = accessibilityEnabled
}

object Permissions {
    private val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco")
    private const val PLAY_STORE = "com.android.vending"

    fun read(context: Context) = PermissionState(
        accessibilityEnabled = accessibilityEnabled(context),
        batteryUnrestricted = ignoringBatteryOptimizations(context),
        autostartStep = Build.MANUFACTURER.lowercase() in XIAOMI_BRANDS,
        restrictedSettingsHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !installedFromPlay(context),
        appVersions = Platform.entries.mapNotNull { p -> versionName(context, p.packageName)?.let { p to it } }.toMap(),
    )

    /** The user's switch in Settings, which can be on while Android hasn't bound the service (see [Verdict.NotConnected]). */
    fun accessibilityEnabled(context: Context): Boolean {
        val resolver = context.contentResolver
        if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val ours = ComponentName(context, SaneFeedAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    fun ignoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun accessibilitySettings() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun batterySettings() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** MIUI/HyperOS autostart list; callers fall back to [appDetails] when it can't be opened. */
    fun autostartSettings() = Intent().setComponent(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    )

    private fun versionName(context: Context, packageName: String): String? = try {
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun installedFromPlay(context: Context): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        installer == PLAY_STORE
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
