package app.sanefeed.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.sanefeed.AppGraph
import app.sanefeed.BuildConfig
import app.sanefeed.SaneFeedApp
import app.sanefeed.accessibility.instagram.InstagramAdapter
import app.sanefeed.accessibility.x.XAdapter
import app.sanefeed.core.MaskMotion
import app.sanefeed.core.Platform
import app.sanefeed.core.Visibility

/**
 * The node tree is read on a background thread and the resulting [Frame] is applied to overlays
 * back on main. The first event after a quiet spell scans at once; while events keep coming, scans
 * run back to back, at most one per [MIN_SCAN_INTERVAL_MS], plus one after the last event. Between
 * scans, masks follow the scroll deltas the apps report, so they move with the list instead of
 * jumping after it. When the app reports no scrolling while scans see its list move, masks glide
 * at the speed the last two scans measured instead (see [glide]).
 */
class SaneFeedAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val scanThread = HandlerThread("sanefeed-scan").apply { start() }
    private val scanHandler = Handler(scanThread.looper)
    private lateinit var graph: AppGraph
    private lateinit var overlays: OverlayManager

    // Scan thread only.
    private val adapters: Map<Platform, PlatformAdapter> = listOf(XAdapter(), InstagramAdapter()).associateBy { it.platform }
    private val screen = Rect()
    private var lastBackAt = 0L
    private var backsInARow = 0

    // Main thread only.
    private var connected = false
    private var scanPosted = false
    private var scanRunning = false
    private var scanWanted = false
    private var lastScanAt = 0L

    /** Total px the target list has scrolled its content up since connect, from scroll events. */
    private var scrolled = 0
    private var lastDelta = 0

    /** When the app last reported a vertical scroll position or delta. */
    private var scrollReportedAt = 0L

    /**
     * Content px per ms the list moved between the last two scans, used while the app reports no
     * scrolling: X's reports start a few hundred ms into a fling, and X stops sending them at all
     * once the accessibility service restarts (an update or a rebind) until X itself restarts.
     */
    private var glideSpeed = 0f
    private var glideAt = 0L
    private var glideUntil = 0L
    private var glideRemainder = 0f
    private var lastFrameScannedAt = 0L
    private var lastFrameMoved = false

    /** Last position a list without scroll deltas reported; null until the first such event on a screen. */
    private var lastScrollY: Int? = null

    /** Written per event on main, read by the scan thread to attribute errors. */
    @Volatile private var lastEventPlatform: Platform? = null

    private val dispatchScan = Runnable {
        scanPosted = false
        scanRunning = true
        lastScanAt = SystemClock.uptimeMillis()
        val scrolledAt = scrolled
        val scannedAt = lastScanAt
        val scrolling = lastDelta != 0
        scanHandler.post { scan(scrolledAt, scannedAt, scrolling) }
    }

    /** Advances [scrolled] by [glideSpeed] every frame, as scroll events would, for [GLIDE_MS] after the last scan that saw motion. */
    private val glide = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val exact = glideSpeed * (now - glideAt) + glideRemainder
            val delta = exact.toInt()
            glideRemainder = exact - delta
            glideAt = now
            if (delta != 0) move(delta, perReport = (glideSpeed * REPORT_INTERVAL_MS).toInt())
            if (now < glideUntil) main.postDelayed(this, FRAME_MS)
        }
    }

    /** Once scrolling stops, masks shrink back from their in-motion slack to the scanned bounds. */
    private val settle = Runnable {
        lastDelta = 0
        braced = false
        overlays.follow(scrolled, 0)
        scheduleScan()
    }

    /** Whether masks are widened by [BRACE_PX] because content changed while no scroll was reported (see [brace]). */
    private var braced = false

    /** While overlays are up, re-check periodically so they disappear when the user leaves the app without an event reaching us. */
    private val watchdog = Runnable { scheduleScan() }

    override fun onServiceConnected() {
        graph = (application as SaneFeedApp).graph
        overlays = OverlayManager(
            this,
            onLeaveApp = { performGlobalAction(GLOBAL_ACTION_HOME) },
            onGoBack = { performGlobalAction(GLOBAL_ACTION_BACK) },
        )
        connected = true
        graph.health.connected()
        TestHooks.attach(this)
        scanHandler.post { graph.blocklist.ensureLoaded() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val platform = Platform.fromPackage(event.packageName) ?: return
        lastEventPlatform = platform
        graph.health.onEvent(platform)
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScrolled(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> lastScrollY = null
            // X reports a fling's scrolling only a few hundred ms in; until then its content changes are the only sign.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (platform == Platform.X) brace()
            else -> Unit
        }
        scheduleScan()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        scanThread.quitSafely()
        super.onDestroy()
    }

    private fun disconnect() {
        connected = false
        scanPosted = false
        scanRunning = false
        scanWanted = false
        TestHooks.detach(this)
        main.removeCallbacksAndMessages(null)
        if (::overlays.isInitialized) overlays.clear()
        if (::graph.isInitialized) graph.health.cleared()
    }

    private fun onScrolled(event: AccessibilityEvent) {
        if (!connected) return
        val delta = scrollDelta(event) ?: return
        scrollReportedAt = SystemClock.uptimeMillis()
        main.removeCallbacks(glide)
        if (delta != 0) move(delta, perReport = delta)
    }

    /**
     * Content changed with no scroll reported: X may have started a fling. Widens masks by
     * [BRACE_PX] until the first report, glide or [settle], so a blocked post can't slide out
     * from under its mask in the meantime.
     */
    private fun brace() {
        if (!connected || lastDelta != 0 || braced) return
        braced = true
        overlays.follow(scrolled, MaskMotion.slack(BRACE_PX))
        main.removeCallbacks(settle)
        main.postDelayed(settle, SETTLE_MS)
    }

    /** The list moved its content up by [delta] px; [perReport] is how far it moves between two scroll reports. */
    private fun move(delta: Int, perReport: Int) {
        scrolled += delta
        lastDelta = perReport
        overlays.follow(scrolled, MaskMotion.slack(perReport))
        main.removeCallbacks(settle)
        main.postDelayed(settle, SETTLE_MS)
    }

    /**
     * Starts, updates or stops [glide] from what [frame]'s scan saw the list do, unless the app
     * reported scrolling around that scan. X's reports also start a few hundred ms into a fling.
     */
    private fun steer(frame: Frame) {
        val previous = lastFrameScannedAt
        val wasMoving = lastFrameMoved
        lastFrameScannedAt = frame.scannedAt
        lastFrameMoved = frame.moved != null && frame.moved != 0
        if (scrollReportedAt >= frame.scannedAt - REPORT_INTERVAL_MS) return
        val moved = frame.moved
        // After a quiet spell the scan starts on the first event of the scroll, so the motion it saw took about one report interval.
        val since = frame.scannedAt - previous
        val elapsed = if (wasMoving && since <= MAX_GLIDE_BASIS_MS) since else minOf(since, REPORT_INTERVAL_MS)
        if (moved == null || moved == 0 || previous == 0L || elapsed <= 0) {
            main.removeCallbacks(glide)
            return
        }
        val now = SystemClock.uptimeMillis()
        glideSpeed = moved.toFloat() / elapsed
        glideUntil = now + GLIDE_MS
        main.removeCallbacks(glide)
        glideAt = now
        glideRemainder = 0f
        main.post(glide)
    }

    /** Px the list moved its content up, or null when the event says nothing about vertical scrolling. */
    private fun scrollDelta(event: AccessibilityEvent): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && event.scrollDeltaY != UNDEFINED_DELTA) return event.scrollDeltaY
        // Compose lists report only their position, and horizontal lists no vertical range.
        if (event.maxScrollY <= 0) return null
        val previous = lastScrollY
        lastScrollY = event.scrollY
        return previous?.let { MaskMotion.positionDelta(event.scrollY - it, lastDelta) }
    }

    private fun scheduleScan() {
        if (!connected) return
        if (scanRunning) {
            scanWanted = true
            return
        }
        if (scanPosted) return
        scanPosted = true
        val wait = lastScanAt + MIN_SCAN_INTERVAL_MS - SystemClock.uptimeMillis()
        main.postDelayed(dispatchScan, wait.coerceAtLeast(0))
    }

    /** Scan thread. */
    private fun scan(scrolledAt: Int, scannedAt: Long, scrolling: Boolean) {
        val frame = try {
            buildFrame(scrolledAt, scannedAt, scrolling)
        } catch (e: RuntimeException) {
            // The target app changed its tree mid-scan: keep the overlays as they are until the next scan.
            lastEventPlatform?.let { graph.health.onError(it, e) }
            main.post(::scanDone)
            return
        }
        main.post { render(frame) }
    }

    private fun render(frame: Frame?) {
        if (connected) {
            frame?.let(::steer)
            overlays.render(frame, scrolled, MaskMotion.slack(if (lastDelta == 0 && braced) BRACE_PX else lastDelta))
            main.removeCallbacks(watchdog)
            if (overlays.isShowing) main.postDelayed(watchdog, WATCHDOG_MS)
        }
        scanDone()
    }

    private fun scanDone() {
        scanRunning = false
        if (scanWanted) {
            scanWanted = false
            scheduleScan()
        }
    }

    private fun buildFrame(scrolledAt: Int, scannedAt: Long, scrolling: Boolean): Frame? {
        val root = targetRoot() ?: return null
        val platform = Platform.fromPackage(root.packageName) ?: return null
        root.getBoundsInScreen(screen)
        val requestsBefore = Nodes.requests.get()
        val startedAt = SystemClock.uptimeMillis()
        val scan = adapters.getValue(platform).scan(root, screen, scrolling)
        if (BuildConfig.DEBUG) {
            val ms = SystemClock.uptimeMillis() - startedAt
            Log.d(SCAN_TAG, "$platform requests=${Nodes.requests.get() - requestsBefore} items=${scan.items.size} ms=$ms")
        }
        val settings = graph.settings.state.value
        val limit = settings.limits.getValue(platform)
        val showChip = settings.counterChip

        val owner = scan.owner?.takeIf { graph.isHidden(platform, it) }
        if (owner != null) {
            backsInARow = 0
            return Frame(platform, Panel.BLOCKED, graph.counter.count(platform), limit, emptyList(), emptyList(), false, owner)
        }
        if (scan.isReelsScreen) {
            graph.health.onShortVideoScan(platform, blocked = settings.blockReels)
            if (settings.blockReels) {
                leaveShortVideo()
                return Frame(platform, Panel.SHORT_VIDEO, graph.counter.count(platform), limit, emptyList(), emptyList(), false)
            }
        }
        backsInARow = 0

        val masks = ArrayList<Authored>()
        var count = graph.counter.count(platform)
        for (item in scan.items) {
            if (graph.isHidden(platform, item.handle)) {
                masks += item
                graph.health.onHidden(platform, item.key)
            } else if (item.countable && count < limit &&
                Visibility.isCountable(item.bounds.top, item.bounds.bottom, item.clip.top, item.clip.bottom) &&
                graph.counter.record(platform, item.key)
            ) {
                count++
            }
        }
        if (!scan.isReelsScreen) graph.health.onFeedScan(platform, scan.candidates, scan.items.count { it.countable })
        val panel = if (count >= limit) Panel.LIMIT else null
        val tabs = if (settings.blockReels) scan.blockedTabs else emptyList()
        val shield = platform == Platform.X && graph.hidesAnyone(platform)
        return Frame(
            platform, panel, count, limit, tabs, masks, showChip,
            scrolledAt = scrolledAt, scannedAt = scannedAt, viewport = scan.viewport, shield = shield, moved = scan.moved,
        )
    }

    /** Presses Back at most once per [BACK_INTERVAL_MS] and gives up after [MAX_BACKS] tries, so it can't loop. */
    private fun leaveShortVideo() {
        val now = SystemClock.elapsedRealtime()
        if (backsInARow >= MAX_BACKS || now - lastBackAt < BACK_INTERVAL_MS) return
        lastBackAt = now
        backsInARow++
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** Root of the topmost X or Instagram window, or null when neither is on screen. */
    private fun targetRoot(): AccessibilityNodeInfo? {
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (Platform.fromPackage(root.packageName) != null) return root
        }
        return null
    }

    private companion object {
        /** Floor between scan starts while events keep coming; a scan itself takes longer while a list scrolls. */
        const val MIN_SCAN_INTERVAL_MS = 60L

        /** Scroll events arrive about every [REPORT_INTERVAL_MS] while a list moves; a longer gap means it stopped. */
        const val REPORT_INTERVAL_MS = 100L
        const val SETTLE_MS = 160L

        /** Masks glide this long past the last scan that saw motion, about one slow scan. */
        const val GLIDE_MS = 250L

        /** Scans further apart than this measure no usable speed. */
        const val MAX_GLIDE_BASIS_MS = 600L
        const val FRAME_MS = 16L

        /** How far a list may move before the first scroll report or scan of a fling. */
        const val BRACE_PX = 300
        const val WATCHDOG_MS = 1000L
        const val BACK_INTERVAL_MS = 1500L
        const val MAX_BACKS = 3

        /** AccessibilityEvent's value for a scroll delta the app didn't set. */
        const val UNDEFINED_DELTA = -1

        /** Debug builds log each scan's node requests and duration under this tag. */
        const val SCAN_TAG = "SaneFeedScan"
    }
}
