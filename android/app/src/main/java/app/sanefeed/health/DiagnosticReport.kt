package app.sanefeed.health

import app.sanefeed.core.Platform
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Device and app facts for the report header. */
class DeviceInfo(
    val appVersion: String,
    val buildType: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdk: Int,
)

/**
 * Plain-text report users can paste into a bug report. Contains versions, setup state and health
 * counters only: never handles, post text or anything else read from the screen.
 */
object DiagnosticReport {
    fun build(
        device: DeviceInfo,
        permissions: PermissionState,
        health: HealthSnapshot,
        settings: List<Pair<String, String>>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        val time = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone)
        appendLine("SaneFeed diagnostic report")
        line("Generated", time.format(Instant.ofEpochMilli(now)))
        line("App", "${device.appVersion} (${device.buildType})")
        line("Device", "${device.manufacturer} ${device.model}")
        line("Android", "${device.androidRelease} (API ${device.sdk})")
        Platform.entries.forEach { line(it.label, permissions.appVersions[it] ?: "not installed") }

        appendLine()
        appendLine("Setup")
        line("Accessibility service", if (permissions.accessibilityEnabled) "on" else "off")
        line("Service connected", if (health.connectedAt > 0) "yes, ${ago(now, health.connectedAt)}" else "no")
        line("Battery optimization", if (permissions.batteryUnrestricted) "not optimized" else "optimized")
        if (permissions.autostartStep) line("Autostart", "manual step (not readable)")
        if (permissions.restrictedSettingsHint) line("Install source", "sideloaded (restricted settings apply)")
        settings.forEach { (key, value) -> line(key, value) }

        for (platform in Platform.entries) {
            val p = health.of(platform)
            appendLine()
            appendLine("Health: ${platform.label}")
            line("Verdict", verdictName(Verdict.of(permissions.accessibilityEnabled, health, platform)))
            line("Last event", ago(now, p.lastEventAt))
            line("Last scan", ago(now, p.lastScanAt))
            line("Last scan posts", "${p.postsLastScan} found, ${p.handlesLastScan} with handle")
            line("Feed scans", p.scans.toString())
            line("Last post recognized", ago(now, p.lastPostsAt))
            line("Last Reels/video block", ago(now, p.lastReelsBlockAt))
            line("Hidden today", p.hiddenToday.toString())
            line("Last error", p.lastError?.let { "$it (${ago(now, p.lastErrorAt)})" } ?: "none")
        }
    }

    fun verdictName(verdict: Verdict): String = when (verdict) {
        Verdict.Off -> "Off"
        Verdict.NotConnected -> "NotConnected"
        Verdict.NotSeenYet -> "NotSeenYet"
        is Verdict.NoPostsRecognized -> "NoPostsRecognized (${verdict.scans} scans)"
        is Verdict.Working -> "Working (${verdict.posts} posts last scan)"
        is Verdict.Error -> "Error"
    }

    /** "never", "12 s ago", "5 min ago", "3 h ago", "2 d ago". */
    fun ago(now: Long, at: Long): String {
        if (at <= 0) return "never"
        val seconds = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "$seconds s ago"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            else -> "${seconds / 86_400} d ago"
        }
    }

    private fun StringBuilder.line(key: String, value: String) {
        append("  ").append(key).append(": ").appendLine(value)
    }
}
