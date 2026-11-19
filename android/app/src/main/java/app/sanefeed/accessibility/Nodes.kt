package app.sanefeed.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Node queries that never throw: the target app can change its tree mid-scan, which surfaces as
 * runtime exceptions from the accessibility IPC layer. Lookups use the app-side id/text search
 * (one IPC round trip each) where they work; Compose apps are walked instead.
 */
internal object Nodes {
    /** The bottom navigation bar sits in the lowest 1/[BOTTOM_NAV_FRACTION] of the window. */
    const val BOTTOM_NAV_FRACTION = 6

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

    /**
     * Nodes for the first id in [ids] that has any, including ones laid out just off screen: a
     * list row that is partly visible still exposes its hidden children this way.
     */
    fun laidOutById(root: AccessibilityNodeInfo, ids: List<String>): List<AccessibilityNodeInfo> {
        for (id in ids) {
            val found = safe { root.findAccessibilityNodeInfosByViewId(id) }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    fun firstById(root: AccessibilityNodeInfo, ids: List<String>): AccessibilityNodeInfo? = byId(root, ids).firstOrNull()

    /** Visible nodes whose text or content description contains [text], ignoring case. */
    fun byText(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> =
        visible { root.findAccessibilityNodeInfosByText(text) }

    fun children(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        (0 until childCount(node)).mapNotNull { child(node, it) }

    fun parent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = try {
        node.parent
    } catch (_: RuntimeException) {
        null
    }

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

    private fun childCount(node: AccessibilityNodeInfo): Int = try {
        node.childCount
    } catch (_: RuntimeException) {
        0
    }

    private fun child(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? = try {
        node.getChild(index)
    } catch (_: RuntimeException) {
        null
    }

    private inline fun visible(query: () -> List<AccessibilityNodeInfo>?): List<AccessibilityNodeInfo> =
        safe(query).filter { it.isVisibleToUser }

    private inline fun safe(query: () -> List<AccessibilityNodeInfo>?): List<AccessibilityNodeInfo> = try {
        query().orEmpty()
    } catch (_: RuntimeException) {
        emptyList()
    }
}
