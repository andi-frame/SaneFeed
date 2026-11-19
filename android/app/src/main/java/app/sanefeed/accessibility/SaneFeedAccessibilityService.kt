package app.sanefeed.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.sanefeed.AppGraph
import app.sanefeed.SaneFeedApp
import app.sanefeed.accessibility.instagram.InstagramAdapter
import app.sanefeed.accessibility.x.XAdapter
import app.sanefeed.core.MaskMotion
import app.sanefeed.core.PostKeys
import app.sanefeed.core.Platform
import app.sanefeed.core.Visibility

/**
 * The node tree is read on a background thread and the resulting [Frame] is applied to overlays
 * back on main. The first event after a quiet spell scans at once; while events keep coming, scans
 * run back to back, at most one per [MIN_SCAN_INTERVAL_MS], plus one after the last event. Between
 * scans, masks follow the scroll deltas the apps report, so they move with the list instead of
 * jumping after it.
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

    /** Last position a list without scroll deltas reported; null until the first such event on a screen. */
    private var lastScrollY: Int? = null

    /** Written per event on main, read by the scan thread to attribute errors. */
    @Volatile private var lastEventPlatform: Platform? = null

    /** Masks on screen; main thread. */
    private var shownMasks: List<Authored> = emptyList()

    /** Author of the masked post the user last tapped, and when; written on main, read by the scan thread. */
    @Volatile private var openedFrom: String? = null
    @Volatile private var openedAt = 0L

    private val dispatchScan = Runnable {
        scanPosted = false
        scanRunning = true
        lastScanAt = SystemClock.uptimeMillis()
        val scrolledAt = scrolled
        scanHandler.post { scan(scrolledAt) }
    }

    /** Once scrolling stops, masks shrink back from their in-motion slack to the scanned bounds. */
    private val settle = Runnable {
        lastDelta = 0
        overlays.follow(scrolled, 0)
        scheduleScan()
    }

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
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClicked(event)
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
        if (delta == 0) return
        scrolled += delta
        lastDelta = delta
        overlays.follow(scrolled, MaskMotion.slack(delta))
        main.removeCallbacks(settle)
        main.postDelayed(settle, SETTLE_MS)
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

    /**
     * Remembers the author when the user taps a masked post (masks pass touches through), so the
     * post detail it opens stays covered. A tap on another post forgets it; taps on buttons don't.
     */
    private fun onClicked(event: AccessibilityEvent) {
        val source = try {
            event.source
        } catch (_: RuntimeException) {
            null
        } ?: return
        val clicked = Rect().also(source::getBoundsInScreen)
        val masked = shownMasks.filter { Rect.intersects(it.bounds, clicked) }.maxByOrNull { overlap(it.bounds, clicked) }
        if (masked != null) {
            openedFrom = masked.handle
            openedAt = SystemClock.uptimeMillis()
        } else if (clicked.width() * 10 >= resources.displayMetrics.widthPixels * 8) {
            openedFrom = null
        }
    }

    private fun overlap(a: Rect, b: Rect): Int {
        val shared = Rect(a)
        return if (shared.intersect(b)) shared.width() * shared.height() else 0
    }

    /** Scan thread. The mask for a post detail opened from a masked post. */
    private fun openedMask(platform: Platform, opened: Region?): Authored? {
        val from = openedFrom ?: return null
        if (opened == null) {
            // Give the detail screen time to open before forgetting the tap.
            if (SystemClock.uptimeMillis() - openedAt > OPEN_TIMEOUT_MS) openedFrom = null
            return null
        }
        if (!graph.isHidden(platform, from)) return null
        return Authored(PostKeys.of(from, "opened"), from, opened.bounds, opened.clip, countable = false)
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
    private fun scan(scrolledAt: Int) {
        val frame = try {
            buildFrame(scrolledAt)
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
            overlays.render(frame, scrolled, MaskMotion.slack(lastDelta))
            shownMasks = frame?.masks.orEmpty()
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

    private fun buildFrame(scrolledAt: Int): Frame? {
        val root = targetRoot() ?: return null
        val platform = Platform.fromPackage(root.packageName) ?: return null
        root.getBoundsInScreen(screen)
        val scan = adapters.getValue(platform).scan(root, screen)
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
        openedMask(platform, scan.opened)?.let {
            masks += it
            graph.health.onHidden(platform, it.key)
        }
        if (!scan.isReelsScreen) graph.health.onFeedScan(platform, scan.candidates, scan.items.count { it.countable })
        val panel = if (count >= limit) Panel.LIMIT else null
        val tabs = if (settings.blockReels) scan.blockedTabs else emptyList()
        return Frame(platform, panel, count, limit, tabs, masks, showChip, scrolledAt = scrolledAt)
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

        /** Scroll events arrive about every 100 ms while a list moves; a longer gap means it stopped. */
        const val SETTLE_MS = 160L

        /** How long after tapping a masked post its detail screen may take to open. */
        const val OPEN_TIMEOUT_MS = 2000L
        const val WATCHDOG_MS = 1000L
        const val BACK_INTERVAL_MS = 1500L
        const val MAX_BACKS = 3

        /** AccessibilityEvent's value for a scroll delta the app didn't set. */
        const val UNDEFINED_DELTA = -1
    }
}
