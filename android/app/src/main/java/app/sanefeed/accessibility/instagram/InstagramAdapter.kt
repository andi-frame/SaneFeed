package app.sanefeed.accessibility.instagram

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.sanefeed.accessibility.Nodes
import app.sanefeed.accessibility.PlatformAdapter
import app.sanefeed.accessibility.PostNode
import app.sanefeed.accessibility.ScreenScan
import app.sanefeed.core.Handles
import app.sanefeed.core.Platform
import app.sanefeed.core.PostKeys
import app.sanefeed.accessibility.instagram.InstagramSelectors as S

/**
 * Instagram renders a feed post as several list rows (header, media, actions, caption), so a post
 * spans from its header to the next post's header.
 */
class InstagramAdapter : PlatformAdapter {
    override val platform = Platform.INSTAGRAM

    /** Posts from the previous scan, top to bottom; lets a post whose header scrolled away keep its area. */
    private var previous: List<PostNode> = emptyList()

    private class Anchor(val handle: String, val top: Int)

    override fun scan(root: AccessibilityNodeInfo, screen: Rect): ScreenScan {
        val viewport = Nodes.firstById(root, S.FEED_LIST)?.let(Nodes::bounds)?.takeUnless(Rect::isEmpty) ?: Rect(screen)
        val reelsTab = Nodes.firstById(root, S.REELS_TAB) ?: Nodes.bottomTab(root, screen, S.REELS_TAB_LABELS)
        val tabs = listOfNotNull(reelsTab?.let(Nodes::bounds))
        val onReels = reelsTab?.isSelected == true || Nodes.byId(root, S.REELS_VIEWER).any { Nodes.coversScreen(it, screen) }
        if (onReels) {
            previous = emptyList()
            return ScreenScan(true, emptyList(), tabs, viewport)
        }
        val posts = posts(root, viewport)
        previous = posts
        return ScreenScan(false, posts, tabs, viewport)
    }

    private fun posts(root: AccessibilityNodeInfo, viewport: Rect): List<PostNode> {
        val anchors = anchors(root, viewport)
        if (anchors.isEmpty()) return emptyList()
        val signatures = Nodes.allById(root, S.POST_SIGNATURE).map { Nodes.bounds(it).top to Nodes.label(it) }
        val posts = ArrayList<PostNode>(anchors.size + 1)
        for ((i, anchor) in anchors.withIndex()) {
            val bottom = anchors.getOrNull(i + 1)?.top ?: viewport.bottom
            val signature = signatures.firstOrNull { (top, label) -> top in anchor.top until bottom && label != null }?.second
            val bounds = Rect(viewport.left, anchor.top, viewport.right, bottom)
            if (bounds.intersect(viewport)) posts += PostNode(PostKeys.of(anchor.handle, signature), anchor.handle, bounds)
        }
        carriedOver(posts.first(), viewport)?.let { posts.add(0, it) }
        return posts
    }

    /** The post above [first] from the last scan, if its header scrolled off but its media is still visible. */
    private fun carriedOver(first: PostNode, viewport: Rect): PostNode? {
        if (first.bounds.top - viewport.top < MIN_CARRY_OVER_PX) return null
        val index = previous.indexOfFirst { it.key == first.key }
        val above = previous.getOrNull(index - 1) ?: return null
        return PostNode(above.key, above.handle, Rect(viewport.left, viewport.top, viewport.right, first.bounds.top))
    }

    private fun anchors(root: AccessibilityNodeInfo, viewport: Rect): List<Anchor> {
        val headers = Nodes.byId(root, S.POST_HEADER).map(Nodes::bounds)
        val byId = Nodes.byId(root, S.POST_AUTHOR).mapNotNull { node ->
            val handle = node.text?.let(::usernameOf) ?: return@mapNotNull null
            val name = Nodes.bounds(node)
            Anchor(handle, headers.firstOrNull { it.contains(name) }?.top ?: name.top)
        }
        val anchors = byId.ifEmpty {
            Nodes.byText(root, S.AUTHOR_DESCRIPTION_QUERY).mapNotNull { node ->
                val description = node.contentDescription ?: return@mapNotNull null
                val name = S.AUTHOR_DESCRIPTIONS.firstNotNullOfOrNull { it.find(description) }?.groupValues?.get(1)
                name?.let { Handles.parse(platform, it) }?.let { Anchor(it, Nodes.bounds(node).top) }
            }
        }
        return anchors.filter { it.top < viewport.bottom }.sortedBy(Anchor::top)
    }

    /** The header text may carry extras after the username ("name • Follow"). */
    private fun usernameOf(text: CharSequence): String? =
        Handles.parse(platform, text.trim().takeWhile { !it.isWhitespace() && it != '•' }.toString())

    private companion object {
        const val MIN_CARRY_OVER_PX = 48
    }
}
