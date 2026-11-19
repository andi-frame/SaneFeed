package app.sanefeed.health

import app.sanefeed.core.Platform
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictTest {
    private var now = 1_000_000L
    private var today = LocalDate.of(2026, 11, 19)
    private val health = ServiceHealth({ now }, { today })

    private fun verdict(enabled: Boolean = true, platform: Platform = Platform.INSTAGRAM) =
        Verdict.of(enabled, health.state.value, platform)

    @Test
    fun `off when the service is disabled, even with stale health`() {
        health.connected()
        health.onFeedScan(Platform.INSTAGRAM, 3, 3)
        assertEquals(Verdict.Off, verdict(enabled = false))
    }

    @Test
    fun `not connected when enabled but never bound`() {
        assertEquals(Verdict.NotConnected, verdict())
    }

    @Test
    fun `not seen yet right after connect`() {
        health.connected()
        assertEquals(Verdict.NotSeenYet, verdict())
    }

    @Test
    fun `working once a post with a handle is recognized`() {
        health.connected()
        health.onEvent(Platform.INSTAGRAM)
        now += 500
        health.onFeedScan(Platform.INSTAGRAM, posts = 4, handles = 4)
        assertEquals(Verdict.Working(now, 4), verdict())
        assertEquals(Verdict.NotSeenYet, verdict(platform = Platform.X))
    }

    @Test
    fun `stays working on screens without posts after posts were seen`() {
        health.connected()
        health.onFeedScan(Platform.X, 2, 2)
        repeat(Verdict.SCANS_BEFORE_NO_POSTS * 2) { health.onFeedScan(Platform.X, 0, 0) }
        assertTrue(verdict(platform = Platform.X) is Verdict.Working)
    }

    @Test
    fun `no posts recognized after enough empty feed scans`() {
        health.connected()
        repeat(Verdict.SCANS_BEFORE_NO_POSTS - 1) { health.onFeedScan(Platform.X, 3, 0) }
        assertEquals(Verdict.NotSeenYet, verdict(platform = Platform.X))
        health.onFeedScan(Platform.X, 3, 0)
        assertEquals(Verdict.NoPostsRecognized(Verdict.SCANS_BEFORE_NO_POSTS), verdict(platform = Platform.X))
    }

    @Test
    fun `reels screens don't count as empty feed scans`() {
        health.connected()
        repeat(Verdict.SCANS_BEFORE_NO_POSTS * 2) { health.onShortVideoScan(Platform.INSTAGRAM, blocked = true) }
        assertEquals(Verdict.NotSeenYet, verdict())
        assertEquals(now, health.state.value.of(Platform.INSTAGRAM).lastReelsBlockAt)
    }

    @Test
    fun `error until the next successful scan`() {
        health.connected()
        health.onFeedScan(Platform.INSTAGRAM, 1, 1)
        now += 1_000
        health.onError(Platform.INSTAGRAM, IllegalStateException("node gone"))
        assertEquals(Verdict.Error("IllegalStateException: node gone", now), verdict())
        now += 1_000
        health.onFeedScan(Platform.INSTAGRAM, 1, 1)
        assertTrue(verdict() is Verdict.Working)
    }

    @Test
    fun `cleared health reads as not connected`() {
        health.connected()
        health.onFeedScan(Platform.INSTAGRAM, 1, 1)
        health.cleared()
        assertEquals(Verdict.NotConnected, verdict())
    }

    @Test
    fun `scan folds in the last event time`() {
        health.connected()
        health.onEvent(Platform.X)
        val eventAt = now
        now += 200
        health.onFeedScan(Platform.X, 0, 0)
        assertEquals(eventAt, health.state.value.of(Platform.X).lastEventAt)
        assertEquals(now, health.state.value.of(Platform.X).lastScanAt)
    }

    @Test
    fun `hidden posts are counted once per day`() {
        health.connected()
        repeat(3) { health.onHidden(Platform.X, "a#1") }
        health.onHidden(Platform.X, "b#2")
        health.onFeedScan(Platform.X, 2, 2)
        assertEquals(2, health.state.value.of(Platform.X).hiddenToday)
        today = today.plusDays(1)
        health.onFeedScan(Platform.X, 2, 2)
        assertEquals(0, health.state.value.of(Platform.X).hiddenToday)
    }

    @Test
    fun `report has versions and health but no handles`() {
        health.connected()
        health.onHidden(Platform.X, "budi83749201#abc")
        health.onFeedScan(Platform.X, 2, 2)
        val permissions = PermissionState(
            accessibilityEnabled = true,
            batteryUnrestricted = false,
            autostartStep = true,
            restrictedSettingsHint = false,
            appVersions = mapOf(Platform.X to "12.43.0-prod.01"),
        )
        val report = DiagnosticReport.build(
            DeviceInfo("1.0.0", "debug", "Xiaomi", "2406APNFAG", "16", 36),
            permissions,
            health.state.value,
            listOf("Server" to "http://127.0.0.1:8787"),
            now,
        )
        assertTrue(report.contains("X: 12.43.0-prod.01"))
        assertTrue(report.contains("Instagram: not installed"))
        assertTrue(report.contains("Verdict: Working (2 posts last scan)"))
        assertTrue(report.contains("Autostart: manual step"))
        assertTrue(report.contains("Hidden today: 1"))
        assertFalse(report.contains("budi"))
    }

    @Test
    fun `ago formats coarse relative times`() {
        assertEquals("never", DiagnosticReport.ago(10_000, 0))
        assertEquals("12 s ago", DiagnosticReport.ago(20_000, 8_000))
        assertEquals("5 min ago", DiagnosticReport.ago(5 * 60_000L + 1, 1))
        assertEquals("3 h ago", DiagnosticReport.ago(3 * 3_600_000L + 1, 1))
    }
}
