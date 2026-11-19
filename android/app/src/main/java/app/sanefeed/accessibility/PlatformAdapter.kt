package app.sanefeed.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.sanefeed.core.Platform

/**
 * Something on screen written by [handle]: a feed post, a comment or reply, a search result, a
 * quoted post or a story avatar. [bounds] are screen coordinates, already clipped to [clip], the
 * visible part of the list the item scrolls in (app bars and tab bars excluded). Masks never leave
 * [clip]. Only [countable] items, feed posts, count toward the daily limit.
 */
class Authored(val key: String, val handle: String, val bounds: Rect, val clip: Rect, val countable: Boolean)

class ScreenScan(
    val isReelsScreen: Boolean,
    val items: List<Authored>,
    /** Post containers found, including ones whose author couldn't be read; health diagnostics only. */
    val candidates: Int,
    /** Reels / video tabs to cover, in screen coordinates. */
    val blockedTabs: List<Rect>,
    /** Account whose profile or story fills the screen, if any. */
    val owner: String? = null,
)

/**
 * Reads one app's UI. Implementations try known view ids first, then content descriptions and
 * text. Each surface (feed, comments, search, profile, stories) is read on its own and fails
 * safe: when its selectors match nothing, it contributes nothing and the app works normally.
 */
interface PlatformAdapter {
    val platform: Platform

    /** Called on the scan thread only. [screen] is the bounds of [root]'s window. */
    fun scan(root: AccessibilityNodeInfo, screen: Rect): ScreenScan
}
