package app.sanefeed.accessibility

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import app.sanefeed.R
import app.sanefeed.core.Platform

/** What the overlays should show after one scan. */
internal class Frame(
    val platform: Platform,
    val panel: Panel?,
    val count: Int,
    val limit: Int,
    val blockedTabs: List<Rect>,
    val masks: List<PostNode>,
    val showChip: Boolean,
)

internal enum class Panel { LIMIT, SHORT_VIDEO }

/**
 * Owns every overlay window. Main thread only. Views are pooled: masks keep their window while
 * their post stays on screen and only move; detached views are reused for the next post.
 */
internal class OverlayManager(context: Context, private val onLeaveApp: () -> Unit) {
    private val context = ContextThemeWrapper(context, R.style.Theme_SaneFeed)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val inflater = LayoutInflater.from(this.context)

    private var masks = HashMap<String, TextView>()
    private var nextMasks = HashMap<String, TextView>()
    private val maskPool = ArrayList<TextView>()
    private val tabs = ArrayList<View>()
    private val tabPool = ArrayList<View>()
    private val panel: View by lazy {
        inflater.inflate(R.layout.overlay_panel, null).apply {
            findViewById<View>(R.id.overlay_leave).setOnClickListener {
                clear()
                onLeaveApp()
            }
        }
    }
    private var panelShown = false
    private val chip: TextView by lazy { inflater.inflate(R.layout.overlay_chip, null) as TextView }
    private var chipShown = false

    val isShowing: Boolean get() = panelShown || chipShown || masks.isNotEmpty() || tabs.isNotEmpty()

    fun render(frame: Frame?) {
        if (frame == null) return clear()
        if (frame.panel != null) {
            showPanel(frame)
            renderMasks(emptyList())
            renderTabs(emptyList())
            setChip(null)
            return
        }
        hidePanel()
        renderTabs(frame.blockedTabs)
        renderMasks(frame.masks)
        setChip(if (frame.showChip) context.getString(R.string.overlay_chip, frame.count, frame.limit) else null)
    }

    fun clear() {
        hidePanel()
        renderMasks(emptyList())
        renderTabs(emptyList())
        setChip(null)
    }

    private fun renderMasks(posts: List<PostNode>) {
        for (post in posts) {
            if (nextMasks.containsKey(post.key)) continue
            val existing = masks.remove(post.key)
            val view = existing ?: maskPool.removeLastOrNull() ?: inflater.inflate(R.layout.overlay_mask, null) as TextView
            view.text = context.getString(R.string.overlay_hidden, post.handle)
            place(view, post.bounds, touchable = false, attached = existing != null)
            nextMasks[post.key] = view
        }
        for (view in masks.values) {
            windowManager.removeViewImmediate(view)
            maskPool += view
        }
        masks.clear()
        masks = nextMasks.also { nextMasks = masks }
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
            place(view, rect, touchable = true, attached = attached)
            if (!attached) tabs += view
        }
    }

    private fun showPanel(frame: Frame) {
        val title = if (frame.panel == Panel.LIMIT) {
            context.getString(R.string.overlay_limit_title)
        } else when (frame.platform) {
            Platform.INSTAGRAM -> context.getString(R.string.overlay_reels_title)
            Platform.X -> context.getString(R.string.overlay_video_title)
        }
        val body = if (frame.panel == Panel.LIMIT) {
            context.getString(R.string.overlay_limit_body, frame.count, frame.limit, frame.platform.label)
        } else {
            context.getString(R.string.overlay_short_video_body)
        }
        panel.findViewById<TextView>(R.id.overlay_title).text = title
        panel.findViewById<TextView>(R.id.overlay_body).text = body
        if (panelShown) return
        val params = layoutParams(touchable = true).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        windowManager.addView(panel, params)
        panelShown = true
    }

    private fun hidePanel() {
        if (!panelShown) return
        windowManager.removeViewImmediate(panel)
        panelShown = false
    }

    private fun setChip(text: String?) {
        if (text == null) {
            if (chipShown) windowManager.removeViewImmediate(chip)
            chipShown = false
            return
        }
        chip.text = text
        if (chipShown) return
        val params = layoutParams(touchable = false).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = context.resources.getDimensionPixelOffset(R.dimen.overlay_chip_top)
        }
        windowManager.addView(chip, params)
        chipShown = true
    }

    private fun place(view: View, rect: Rect, touchable: Boolean, attached: Boolean) {
        val params = (view.layoutParams as? WindowManager.LayoutParams)?.takeIf { attached } ?: layoutParams(touchable)
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width()
        params.height = rect.height()
        if (attached) windowManager.updateViewLayout(view, params) else windowManager.addView(view, params)
    }

    /** Positioned in absolute screen coordinates, matching AccessibilityNodeInfo.getBoundsInScreen. */
    private fun layoutParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
    }
}
