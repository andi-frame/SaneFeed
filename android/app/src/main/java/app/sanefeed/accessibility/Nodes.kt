package app.sanefeed.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Node queries that never throw: the target app can change its tree mid-scan, which surfaces as
 * runtime exceptions from the accessibility IPC layer. Lookups use the app-side id/text search
 * (one IPC round trip each) instead of walking the tree node by node.
 */
internal object Nodes {
    private const val BOTTOM_NAV_FRACTION = 6

    /** Visible nodes for the first id in [ids] that has any. */
    fun byId(root: AccessibilityNodeInfo, ids: List<String>): List<AccessibilityNodeInfo> {
        for (id in ids) {
            val found = visible { root.findAccessibilityNodeInfosByViewId(id) }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** Visible nodes for every id in [ids]. */
    fun allById(root: AccessibilityNodeInfo, ids: List<String>): List<AccessibilityNodeInfo> =
        ids.flatMap { id -> visible { root.findAccessibilityNodeInfosByViewId(id) } }

    fun firstById(root: AccessibilityNodeInfo, ids: List<String>): AccessibilityNodeInfo? = byId(root, ids).firstOrNull()

    /** Visible nodes whose text or content description contains [text], ignoring case. */
    fun byText(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> =
        visible { root.findAccessibilityNodeInfosByText(text) }

    fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    /** Content description, falling back to text. */
    fun label(node: AccessibilityNodeInfo): CharSequence? = node.contentDescription ?: node.text

    /** A node in the bottom navigation bar labelled exactly one of [labels], ignoring case. */
    fun bottomTab(root: AccessibilityNodeInfo, screen: Rect, labels: List<String>): AccessibilityNodeInfo? {
        val navTop = screen.bottom - screen.height() / BOTTOM_NAV_FRACTION
        for (label in labels) {
            for (node in byText(root, label)) {
                if (label.contentEquals(label(node), ignoreCase = true) && bounds(node).top >= navTop) return node
            }
        }
        return null
    }

    /** Whether [node] fills most of [screen], i.e. it is a full-screen viewer rather than a preview. */
    fun coversScreen(node: AccessibilityNodeInfo, screen: Rect): Boolean =
        bounds(node).height() * 10 >= screen.height() * 6

    private inline fun visible(query: () -> List<AccessibilityNodeInfo>?): List<AccessibilityNodeInfo> = try {
        query()?.filter { it.isVisibleToUser }.orEmpty()
    } catch (_: RuntimeException) {
        emptyList()
    }
}
