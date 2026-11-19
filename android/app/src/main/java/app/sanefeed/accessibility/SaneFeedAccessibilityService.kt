package app.sanefeed.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
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
import app.sanefeed.core.Platform
import app.sanefeed.core.Visibility
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Events are coalesced on the main thread (at most one scan per [SCAN_DELAY_MS]), the node tree
 * is read on a background thread, and the resulting [Frame] is applied to overlays back on main.
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
    private var scanScheduled = false
    private val scanQueued = AtomicBoolean(false)

    /** Written per event on main, read by the scan thread to attribute errors. */
    @Volatile private var lastEventPlatform: Platform? = null

    private val dispatchScan = Runnable {
        scanScheduled = false
        if (scanQueued.compareAndSet(false, true)) scanHandler.post(scanTask)
    }

    private val scanTask = Runnable {
        scanQueued.set(false)
        val frame = try {
            buildFrame()
        } catch (e: RuntimeException) {
            // The target app changed its tree mid-scan; fail safe and try again on the next event.
            lastEventPlatform?.let { graph.health.onError(it, e) }
            null
        }
        main.post { render(frame) }
    }

    /** While overlays are up, re-check periodically so they disappear when the user leaves the app without an event reaching us. */
    private val watchdog = Runnable { scheduleScan() }

    override fun onServiceConnected() {
        graph = (application as SaneFeedApp).graph
        overlays = OverlayManager(this) { performGlobalAction(GLOBAL_ACTION_HOME) }
        connected = true
        graph.health.connected()
        scanHandler.post { graph.blocklist.ensureLoaded() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val platform = Platform.fromPackage(event.packageName) ?: return
        lastEventPlatform = platform
        graph.health.onEvent(platform)
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
        scanScheduled = false
        main.removeCallbacksAndMessages(null)
        if (::overlays.isInitialized) overlays.clear()
        if (::graph.isInitialized) graph.health.cleared()
    }

    private fun scheduleScan() {
        if (!connected || scanScheduled) return
        scanScheduled = true
        main.postDelayed(dispatchScan, SCAN_DELAY_MS)
    }

    private fun render(frame: Frame?) {
        if (!connected) return
        overlays.render(frame)
        main.removeCallbacks(watchdog)
        if (overlays.isShowing) main.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun buildFrame(): Frame? {
        val root = targetRoot() ?: return null
        val platform = Platform.fromPackage(root.packageName) ?: return null
        root.getBoundsInScreen(screen)
        val scan = adapters.getValue(platform).scan(root, screen)
        val settings = graph.settings.state.value
        val limit = settings.limits.getValue(platform)
        val showChip = settings.counterChip

        if (scan.isReelsScreen) {
            graph.health.onShortVideoScan(platform, blocked = settings.blockReels)
            if (settings.blockReels) {
                leaveShortVideo()
                return Frame(platform, Panel.SHORT_VIDEO, graph.counter.count(platform), limit, emptyList(), emptyList(), false)
            }
        }
        backsInARow = 0

        val masks = ArrayList<PostNode>()
        var count = graph.counter.count(platform)
        for (post in scan.posts) {
            if (graph.isHidden(platform, post.handle)) {
                masks += post
                graph.health.onHidden(platform, post.key)
            } else if (count < limit &&
                Visibility.isCountable(post.bounds.top, post.bounds.bottom, scan.viewport.top, scan.viewport.bottom) &&
                graph.counter.record(platform, post.key)
            ) {
                count++
            }
        }
        if (!scan.isReelsScreen) graph.health.onFeedScan(platform, scan.candidates, scan.posts.size)
        val panel = if (count >= limit) Panel.LIMIT else null
        val tabs = if (settings.blockReels) scan.blockedTabs else emptyList()
        return Frame(platform, panel, count, limit, tabs, masks, showChip)
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
        const val SCAN_DELAY_MS = 150L
        const val WATCHDOG_MS = 1000L
        const val BACK_INTERVAL_MS = 1500L
        const val MAX_BACKS = 3
    }
}
