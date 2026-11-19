package app.sanefeed.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.sanefeed.core.Platform

/** A post on screen. [bounds] are screen coordinates, already clipped to what is visible. */
class PostNode(val key: String, val handle: String, val bounds: Rect)

class ScreenScan(
    val isReelsScreen: Boolean,
    val posts: List<PostNode>,
    /** Post containers found, including ones whose author couldn't be read; health diagnostics only. */
    val candidates: Int,
    /** Reels / video tabs to cover, in screen coordinates. */
    val blockedTabs: List<Rect>,
    /** Scrolling feed area posts are measured against; the window bounds if no feed list is found. */
    val viewport: Rect,
)

/**
 * Reads one app's UI. Implementations try known view ids first, then content descriptions and
 * text. Selectors are best effort: when nothing matches, the scan is empty and the app works normally.
 */
interface PlatformAdapter {
    val platform: Platform

    /** Called on the scan thread only. [screen] is the bounds of [root]'s window. */
    fun scan(root: AccessibilityNodeInfo, screen: Rect): ScreenScan
}
