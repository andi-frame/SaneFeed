package app.sanefeed.accessibility

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import app.sanefeed.R
import app.sanefeed.core.MaskMotion
import app.sanefeed.core.Platform

/** What the overlays should show after one scan. */
internal class Frame(
    val platform: Platform,
    val panel: Panel?,
    val count: Int,
    val limit: Int,
    val blockedTabs: List<Rect>,
    val masks: List<Authored>,
    val showChip: Boolean,
    /** Account named by the [Panel.BLOCKED] panel. */
    val owner: String? = null,
    /** The service's scroll total when the scan read the screen; masks follow scrolling from there. */
    val scrolledAt: Int = 0,
    /** When that scan started, uptime millis. */
    val scannedAt: Long = 0,
    /** Visible area of the main list, for the [shield]. */
    val viewport: Rect? = null,
    /** Cover list content revealed by scrolling that no scan has read yet (see [OverlayManager.follow]). */
    val shield: Boolean = false,
)

internal enum class Panel { LIMIT, SHORT_VIDEO, BLOCKED }

/**
 * Owns every overlay window. Main thread only. Mask windows are reused in screen order, so a mask
 * that stays on screen keeps its window and only moves; between scans masks follow the scroll
 * deltas the app reports ([follow]). Windows are relaid out only when their bounds change.
 */
internal class OverlayManager(
    context: Context,
    private val onLeaveApp: () -> Unit,
    private val onGoBack: () -> Unit,
) {
    private val context = ContextThemeWrapper(context, R.style.Theme_SaneFeed)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val inflater = LayoutInflater.from(this.context)

    private class Mask(val view: TextView, val params: WindowManager.LayoutParams) {
        lateinit var item: Authored
    }

    private val masks = ArrayList<Mask>()
    private val maskPool = ArrayList<Mask>()
    private var masksScrolledAt = 0
    private val tabs = ArrayList<View>()
    private val tabPool = ArrayList<View>()
    private val panel: View by lazy { inflater.inflate(R.layout.overlay_panel, null) }
    private var panelShown: Panel? = null
    private val chip: TextView by lazy { inflater.inflate(R.layout.overlay_chip, null) as TextView }
    private var chipShown = false
    private val span = Rect()

    /** Blank mask over the strip a scroll revealed since the last scan started; its authors are unknown. */
    private val shield: View by lazy { inflater.inflate(R.layout.overlay_mask, null) }
    private val shieldParams = layoutParams(touchable = false, opaque = true)
    private var shieldShown = false
    private var shieldArea: Rect? = null
    private var scannedAt = 0L

    val isShowing: Boolean get() = panelShown != null || chipShown || masks.isNotEmpty() || tabs.isNotEmpty() || shieldShown

    /** [scrolled] and [slack] are as in [follow]. */
    fun render(frame: Frame?, scrolled: Int, slack: Int) {
        if (frame == null) return clear()
        if (frame.panel != null) {
            showPanel(frame)
            renderMasks(emptyList(), 0, 0, 0)
            shieldArea = null
            placeShield(0, 0)
            renderTabs(emptyList())
            setChip(null)
            return
        }
        hidePanel()
        renderTabs(frame.blockedTabs)
        renderMasks(frame.masks, frame.scrolledAt, scrolled, slack)
        // A scan mid-fling may find no post; the list hasn't moved on screen, so keep its last area.
        shieldArea = if (frame.shield) frame.viewport ?: shieldArea else null
        scannedAt = frame.scannedAt
        placeShield(scrolled, slack)
        setChip(if (frame.showChip) context.getString(R.string.overlay_chip, frame.count, frame.limit) else null)
    }

    /**
     * Moves masks to where their items are now: the target list has scrolled content up by
     * [scrolled] - (the total when the masks were scanned), and may move [slack] px more before
     * the next update.
     */
    fun follow(scrolled: Int, slack: Int) {
        for (mask in masks) place(mask, scrolled - masksScrolledAt, slack)
        placeShield(scrolled, slack)
    }

    /**
     * Shows the shield once a scan has taken [SHIELD_DELAY_MS] without covering what the list
     * revealed since it started: the bottom strip when content moved up, the top strip when it
     * moved down, widened by [slack]. The next scan replaces it with real masks.
     */
    private fun placeShield(scrolled: Int, slack: Int) {
        val area = shieldArea
        val moved = scrolled - masksScrolledAt
        // Once up, the shield stays and only resizes as scans land, so it doesn't blink at scan rate.
        if (area == null || moved == 0 || (!shieldShown && SystemClock.uptimeMillis() - scannedAt < SHIELD_DELAY_MS)) {
            if (shieldShown) windowManager.removeViewImmediate(shield)
            shieldShown = false
            return
        }
        val reach = Math.abs(moved) + slack
        if (moved > 0) span.set(area.left, maxOf(area.top, area.bottom - reach), area.right, area.bottom)
        else span.set(area.left, area.top, area.right, minOf(area.bottom, area.top + reach))
        val params = shieldParams
        if (shieldShown && params.x == span.left && params.y == span.top && params.width == span.width() && params.height == span.height()) return
        params.x = span.left
        params.y = span.top
        params.width = span.width()
        params.height = span.height()
        if (shieldShown) windowManager.updateViewLayout(shield, params) else windowManager.addView(shield, params)
        shieldShown = true
    }

    fun clear() {
        hidePanel()
        renderMasks(emptyList(), 0, 0, 0)
        shieldArea = null
        placeShield(0, 0)
        renderTabs(emptyList())
        setChip(null)
    }

    /**
     * A mask whose item is still on screen keeps its window; other windows are handed to new
     * items in screen order. Windows are moved and added before any is removed, so no frame
     * shows a gap between an old mask leaving and its replacement arriving.
     */
    private fun renderMasks(items: List<Authored>, scrolledAt: Int, scrolled: Int, slack: Int) {
        masksScrolledAt = scrolledAt
        val moved = scrolled - scrolledAt
        val byKey = HashMap<String, Mask>(masks.size)
        for (mask in masks) byKey[mask.item.key] = mask
        val kept = ArrayList<Mask>(items.size)
        val fresh = ArrayList<Authored>()
        for (item in items) {
            val mask = byKey.remove(item.key)
            if (mask == null) {
                fresh += item
            } else {
                kept += mask
                show(mask, item, moved, slack, attach = false)
            }
        }
        val spare = masks.filterTo(ArrayList()) { it !in kept }.apply { sortBy { it.item.bounds.top } }
        for (item in fresh.sortedBy { it.bounds.top }) {
            val reused = spare.removeFirstOrNull()
            val mask = reused ?: maskPool.removeLastOrNull() ?: newMask()
            kept += mask
            show(mask, item, moved, slack, attach = reused == null)
        }
        for (mask in spare) {
            windowManager.removeViewImmediate(mask.view)
            maskPool += mask
        }
        masks.clear()
        masks += kept
    }

    private fun show(mask: Mask, item: Authored, moved: Int, slack: Int, attach: Boolean) {
        mask.item = item
        val text = context.getString(R.string.overlay_hidden, item.handle)
        if (!text.contentEquals(mask.view.text)) mask.view.text = text
        place(mask, moved, slack, attach)
    }

    private fun newMask(): Mask =
        // Opaque: nothing of the covered post shows through.
        Mask(inflater.inflate(R.layout.overlay_mask, null) as TextView, layoutParams(touchable = false, opaque = true))

    /** Positions [mask] for content moved up by [moved] px; hides it while its item is off screen. */
    private fun place(mask: Mask, moved: Int, slack: Int, attach: Boolean = false) {
        val item = mask.item
        val visible = MaskMotion.place(item.bounds.top, item.bounds.bottom, item.clip.top, item.clip.bottom, moved, slack)
        if (visible == null) span.setEmpty() else span.set(item.bounds.left, visible.top, item.bounds.right, visible.bottom)
        val params = mask.params
        val hidden = span.isEmpty
        val changed = attach || params.x != span.left || params.y != span.top || params.width != span.width() || params.height != span.height()
        if (!hidden) {
            params.x = span.left
            params.y = span.top
            params.width = span.width()
            params.height = span.height()
        }
        val visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (mask.view.visibility != visibility) mask.view.visibility = visibility
        if (attach) windowManager.addView(mask.view, params) else if (changed && !hidden) windowManager.updateViewLayout(mask.view, params)
    }

    private fun renderTabs(rects: List<Rect>) {
        while (tabs.size > rects.size) {
            val view = tabs.removeAt(tabs.lastIndex)
            windowManager.removeViewImmediate(view)
            tabPool += view
        }
        for ((i, rect) in rects.withIndex()) {
            val attached = i < tabs.size
            val view = if (attached) tabs[i] else tabPool.removeLastOrNull() ?: inflater.inflate(R.layout.overlay_tab_blocker, null)
            // Touchable: taps on the covered tab land on this window, not on the app.
            placeTab(view, rect, attached)
            if (!attached) tabs += view
        }
    }

    private fun showPanel(frame: Frame) {
        val panelType = frame.panel ?: return
        val title = when (panelType) {
            Panel.LIMIT -> context.getString(R.string.overlay_limit_title)
            Panel.BLOCKED -> context.getString(R.string.overlay_blocked_title, frame.owner)
            Panel.SHORT_VIDEO -> when (frame.platform) {
                Platform.INSTAGRAM -> context.getString(R.string.overlay_reels_title)
                Platform.X -> context.getString(R.string.overlay_video_title)
            }
        }
        val body = when (panelType) {
            Panel.LIMIT -> context.getString(R.string.overlay_limit_body, frame.count, frame.limit, frame.platform.label)
            Panel.BLOCKED -> context.getString(R.string.overlay_blocked_body)
            Panel.SHORT_VIDEO -> context.getString(R.string.overlay_short_video_body)
        }
        panel.findViewById<TextView>(R.id.overlay_title).text = title
        panel.findViewById<TextView>(R.id.overlay_body).text = body
        panel.findViewById<TextView>(R.id.overlay_leave).apply {
            text = context.getString(if (panelType == Panel.BLOCKED) R.string.overlay_go_back else R.string.overlay_leave_app)
            setOnClickListener {
                clear()
                if (panelType == Panel.BLOCKED) onGoBack() else onLeaveApp()
            }
        }
        if (panelShown != null) {
            panelShown = panelType
            return
        }
        val params = layoutParams(touchable = true).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        windowManager.addView(panel, params)
        panelShown = panelType
    }

    private fun hidePanel() {
        if (panelShown == null) return
        windowManager.removeViewImmediate(panel)
        panelShown = null
    }

    private fun setChip(text: String?) {
        if (text == null) {
            if (chipShown) windowManager.removeViewImmediate(chip)
            chipShown = false
            return
        }
        if (!text.contentEquals(chip.text)) chip.text = text
        if (chipShown) return
        val params = layoutParams(touchable = false).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = context.resources.getDimensionPixelOffset(R.dimen.overlay_chip_top)
        }
        windowManager.addView(chip, params)
        chipShown = true
    }

    private fun placeTab(view: View, rect: Rect, attached: Boolean) {
        val params = (view.layoutParams as? WindowManager.LayoutParams)?.takeIf { attached } ?: layoutParams(touchable = true)
        if (attached && params.x == rect.left && params.y == rect.top && params.width == rect.width() && params.height == rect.height()) return
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width()
        params.height = rect.height()
        if (attached) windowManager.updateViewLayout(view, params) else windowManager.addView(view, params)
    }

    /** Positioned in absolute screen coordinates, matching AccessibilityNodeInfo.getBoundsInScreen. */
    private fun layoutParams(touchable: Boolean, opaque: Boolean = false) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        if (opaque) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
    }
}

/** How long a scan may run during a scroll before the strip it hasn't read gets covered. */
private const val SHIELD_DELAY_MS = 80L
