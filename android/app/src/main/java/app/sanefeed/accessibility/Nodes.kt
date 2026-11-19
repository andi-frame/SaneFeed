package app.sanefeed.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Node queries that never throw: the target app can change its tree mid-scan, which surfaces as
 * runtime exceptions from the accessibility IPC layer. Lookups use the app-side id/text search
 * (one IPC round trip each) where they work, and walk the tree only for Compose apps.
 */
internal object Nodes {
    private const val BOTTOM_NAV_FRACTION = 6
    private const val MAX_WALK = 1500

    /** A node matched by [byTag] and its parent (null for the root). */
    class Tagged(val node: AccessibilityNodeInfo, val parent: AccessibilityNodeInfo?)

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

    /**
     * Visible nodes, top to bottom, whose view id equals one of [tags]. Jetpack Compose apps expose
     * test tags as view ids, but the app-side id and text searches don't see Compose nodes, so this
     * walks the tree (bounded to [MAX_WALK] nodes). Matches are not descended into.
     */
    fun byTag(root: AccessibilityNodeInfo, tags: List<String>): List<Tagged> {
        val found = ArrayList<Tagged>()
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        val parents = ArrayDeque<AccessibilityNodeInfo?>()
        nodes.addLast(root)
        parents.addLast(null)
        var visited = 0
        while (nodes.isNotEmpty() && visited++ < MAX_WALK) {
            val node = nodes.removeLast()
            val parent = parents.removeLast()
            if (!node.isVisibleToUser) continue
            if (node.viewIdResourceName in tags) {
                found += Tagged(node, parent)
                continue
            }
            // Children are pushed last-first so they pop in screen order.
            for (i in childCount(node) - 1 downTo 0) {
                val child = child(node, i) ?: continue
                nodes.addLast(child)
                parents.addLast(node)
            }
        }
        return found
    }

    fun children(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        (0 until childCount(node)).mapNotNull { child(node, it) }

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

    private inline fun visible(query: () -> List<AccessibilityNodeInfo>?): List<AccessibilityNodeInfo> = try {
        query()?.filter { it.isVisibleToUser }.orEmpty()
    } catch (_: RuntimeException) {
        emptyList()
    }
}
