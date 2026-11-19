package app.sanefeed.accessibility.x

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.sanefeed.accessibility.Nodes
import app.sanefeed.accessibility.PlatformAdapter
import app.sanefeed.accessibility.PostNode
import app.sanefeed.accessibility.ScreenScan
import app.sanefeed.core.Handles
import app.sanefeed.core.Platform
import app.sanefeed.core.PostKeys
import app.sanefeed.accessibility.x.XSelectors as S

class XAdapter : PlatformAdapter {
    override val platform = Platform.X

    override fun scan(root: AccessibilityNodeInfo, screen: Rect): ScreenScan {
        val viewport = Nodes.firstById(root, S.FEED_LIST)?.let(Nodes::bounds)?.takeUnless(Rect::isEmpty) ?: Rect(screen)
        val videoTab = Nodes.firstById(root, S.VIDEO_TAB) ?: Nodes.bottomTab(root, screen, S.VIDEO_TAB_LABELS)
        val tabs = listOfNotNull(videoTab?.let(Nodes::bounds))
        val immersive = videoTab?.isSelected == true ||
            Nodes.byId(root, S.IMMERSIVE_VIEWER).any { Nodes.coversScreen(it, screen) }
        if (immersive) return ScreenScan(true, emptyList(), tabs, viewport)
        return ScreenScan(false, posts(root, viewport), tabs, viewport)
    }

    private fun posts(root: AccessibilityNodeInfo, viewport: Rect): List<PostNode> {
        val rows = Nodes.byId(root, S.POST_ROW).ifEmpty { rowsByDescription(root, viewport) }
        return rows.mapNotNull { row ->
            val bounds = Nodes.bounds(row)
            if (!bounds.intersect(viewport)) return@mapNotNull null
            val handle = authorOf(row) ?: return@mapNotNull null
            val signature = Nodes.firstById(row, S.POST_TEXT)?.text ?: row.contentDescription
            PostNode(PostKeys.of(handle, signature), handle, bounds)
        }
    }

    /** Outermost wide nodes whose description mentions a handle; nested matches are skipped. */
    private fun rowsByDescription(root: AccessibilityNodeInfo, viewport: Rect): List<AccessibilityNodeInfo> {
        val rows = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        for (node in Nodes.byText(root, S.HANDLE_QUERY)) {
            if (node.contentDescription?.let(S.HANDLE::containsMatchIn) != true) continue
            val bounds = Nodes.bounds(node)
            if (bounds.width() * 10 < viewport.width() * 8) continue
            if (rows.none { (_, outer) -> outer.contains(bounds) }) rows += node to bounds
        }
        return rows.map { it.first }
    }

    private fun authorOf(row: AccessibilityNodeInfo): String? {
        Nodes.firstById(row, S.POST_AUTHOR)?.let(Nodes::label)?.let(::handleIn)?.let { return it }
        row.contentDescription?.let(::handleIn)?.let { return it }
        return Nodes.byText(row, S.HANDLE_QUERY).firstNotNullOfOrNull { node -> Nodes.label(node)?.let(::handleIn) }
    }

    private fun handleIn(text: CharSequence): String? =
        S.HANDLE.find(text)?.groupValues?.get(1)?.let { Handles.parse(platform, it) }
}
