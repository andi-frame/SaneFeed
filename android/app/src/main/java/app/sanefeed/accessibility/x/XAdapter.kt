package app.sanefeed.accessibility.x

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.sanefeed.accessibility.Authored
import app.sanefeed.accessibility.Nodes
import app.sanefeed.accessibility.PlatformAdapter
import app.sanefeed.accessibility.ScreenScan
import app.sanefeed.core.Handles
import app.sanefeed.core.Platform
import app.sanefeed.core.PostKeys
import app.sanefeed.accessibility.x.XSelectors as S

/**
 * X 12.x is Jetpack Compose: the app-side id and text searches don't see its nodes, so one bounded
 * walk of the tree reads every surface. A row is a wide clickable node holding an "@handle" text:
 * timeline posts, replies and notifications (tagged header), and account rows in search and
 * typeahead. A second "@handle" inside its own clickable card is a quoted post. Wide, short nodes
 * drawn after the list (app bar, tabs, bottom navigation, reply composer) are chrome that masks
 * must not cover.
 */
class XAdapter : PlatformAdapter {
    override val platform = Platform.X

    override fun scan(root: AccessibilityNodeInfo, screen: Rect): ScreenScan {
        val walk = Walk(screen)
        walk.visit(root, Rect(screen), surface = null)
        val tabs = listOfNotNull(walk.videoTab)
        if (walk.immersive) return ScreenScan(true, emptyList(), 0, tabs)

        var top = screen.top
        var bottom = screen.bottom
        for (chrome in walk.chrome) {
            if (chrome.centerY() < screen.centerY()) top = maxOf(top, chrome.bottom) else bottom = minOf(bottom, chrome.top)
        }
        fun clipOf(list: Rect) = Rect(list.left, maxOf(list.top, top), list.right, minOf(list.bottom, bottom))
        val rows = walk.rows.ifEmpty { if (walk.headers == 0) rowsByDescription(root, screen) else emptyList() }
        val items = rows.mapNotNull { row ->
            val clip = clipOf(row.list)
            val bounds = Rect(row.bounds)
            if (bounds.intersect(clip)) Authored(row.key, row.handle, bounds, clip, row.countable) else null
        }
        return ScreenScan(false, items, maxOf(walk.headers, rows.size), tabs, walk.owner)
    }

    private class Row(val key: String, val handle: String, val bounds: Rect, val list: Rect, val countable: Boolean)

    /** An "@handle" text in a row; [card] is the part of the row it labels: a clickable card, else a top-level child. */
    private class Mention(val handle: String, val card: Rect?, val inHeader: Boolean)

    private inner class Walk(private val screen: Rect) {
        val rows = ArrayList<Row>()
        val chrome = ArrayList<Rect>()
        var headers = 0
        var owner: String? = null
        var immersive = false
        var videoTab: Rect? = null
        private var visited = 0
        private var listEnded = false
        private val navTop = screen.bottom - screen.height() / Nodes.BOTTOM_NAV_FRACTION

        /** [parent] is the bounds of [node]'s parent: a row's list. [surface] is the screen tag [node] is in. */
        fun visit(node: AccessibilityNodeInfo, parent: Rect, surface: String?) {
            if (visited++ >= MAX_NODES || immersive || !node.isVisibleToUser) return
            val bounds = Nodes.bounds(node)
            val id = node.viewIdResourceName
            if (id in S.IMMERSIVE_VIEWER && Nodes.coversScreen(node, screen)) {
                immersive = true
                return
            }
            if (id in S.VIDEO_TAB || (bounds.top >= navTop && S.VIDEO_TAB_LABELS.any { it.contentEquals(Nodes.label(node), true) })) {
                videoTab = bounds
                if (node.isSelected) immersive = true
            }
            if (id in S.POST_ROW) return viewRow(node, bounds, parent)
            val tag = if (id in S.PROFILE_SCREENS) id else surface
            if (tag in S.PROFILE_SCREENS && owner == null) owner = ownerOf(node)
            if (listEnded && isChrome(bounds)) {
                chrome += bounds
                // The bottom bar is still read for the Video tab.
                if (bounds.centerY() < screen.centerY()) return
            }
            val children = Nodes.children(node)
            if (node.isClickable && bounds.width() * 10 >= screen.width() * 8 && row(node, bounds, parent, children)) return
            val before = rows.size
            for (child in children) visit(child, bounds, tag)
            if (!listEnded && (before until rows.size).any { rows[it].list === bounds }) listEnded = true
        }

        private fun isChrome(bounds: Rect): Boolean =
            !bounds.isEmpty && bounds.width() * 2 >= screen.width() && bounds.height() * 2 < screen.height()

        /** Records [node] as a row if it holds an "@handle" text; quoted posts inside it become rows too. */
        private fun row(node: AccessibilityNodeInfo, bounds: Rect, list: Rect, children: List<AccessibilityNodeInfo>): Boolean {
            val mentions = ArrayList<Mention>()
            collect(children, depth = 1, card = null, inHeader = false, mentions)
            val author = mentions.firstOrNull() ?: return false
            // The body is the longest text directly in the row; the header holds only name and time.
            val signature = children.mapNotNull { it.text }.maxByOrNull { it.length }
            rows += Row(PostKeys.of(author.handle, signature), author.handle, bounds, list, countable = author.inHeader)
            for (quote in mentions.drop(1).filter { it.card != null && it.card != author.card }.distinctBy { it.card }) {
                rows += Row(PostKeys.of(quote.handle, "${author.handle} $signature"), quote.handle, quote.card!!, list, countable = false)
            }
            return true
        }

        private fun collect(nodes: List<AccessibilityNodeInfo>, depth: Int, card: Rect?, inHeader: Boolean, out: MutableList<Mention>) {
            for (node in nodes) {
                if (visited++ >= MAX_NODES || !node.isVisibleToUser) continue
                node.text?.let(::exactHandle)?.let {
                    out += Mention(it, card, inHeader)
                    continue
                }
                if (depth >= MAX_ROW_DEPTH) continue
                val header = node.viewIdResourceName in S.POST_HEADER_TAGS
                if (header) headers++
                val bounds = Nodes.bounds(node)
                // Small clickable nodes are buttons (reply, like, follow), never authors.
                if (node.isClickable && bounds.height() < MIN_CARD_PX) continue
                val inner = if (node.isClickable || depth == 1) bounds else card
                collect(Nodes.children(node), depth + 1, inner, inHeader || header, out)
            }
        }

        /** View-based X builds: one list row per post. */
        private fun viewRow(row: AccessibilityNodeInfo, bounds: Rect, list: Rect) {
            headers++
            val handle = authorOf(row) ?: return
            val signature = Nodes.firstById(row, S.POST_TEXT)?.text ?: row.contentDescription
            rows += Row(PostKeys.of(handle, signature), handle, bounds, list, countable = true)
        }

        private fun ownerOf(node: AccessibilityNodeInfo): String? {
            node.text?.let(::exactHandle)?.let { return it }
            val description = node.contentDescription ?: return null
            return S.PROFILE_DESCRIPTIONS.firstNotNullOfOrNull { it.find(description) }?.groupValues?.get(1)?.let { Handles.parse(platform, it) }
        }
    }

    /** Outermost wide nodes whose description mentions a handle, for builds with neither ids nor tags. */
    private fun rowsByDescription(root: AccessibilityNodeInfo, screen: Rect): List<Row> {
        val rows = ArrayList<Row>()
        for (node in Nodes.byText(root, S.HANDLE_QUERY)) {
            val description = node.contentDescription ?: continue
            val handle = handleIn(description) ?: continue
            val bounds = Nodes.bounds(node)
            if (bounds.width() * 10 < screen.width() * 8) continue
            if (rows.none { it.bounds.contains(bounds) }) rows += Row(PostKeys.of(handle, description), handle, bounds, Rect(screen), countable = true)
        }
        return rows
    }

    private fun authorOf(row: AccessibilityNodeInfo): String? {
        Nodes.firstById(row, S.POST_AUTHOR)?.let(Nodes::label)?.let(::handleIn)?.let { return it }
        row.contentDescription?.let(::handleIn)?.let { return it }
        return Nodes.byText(row, S.HANDLE_QUERY).firstNotNullOfOrNull { node -> Nodes.label(node)?.let(::handleIn) }
    }

    private fun handleIn(text: CharSequence): String? =
        S.HANDLE.find(text)?.groupValues?.get(1)?.let { Handles.parse(platform, it) }

    /** X wraps handles in invisible direction marks in some rows. */
    private fun exactHandle(text: CharSequence): String? =
        S.EXACT_HANDLE.matchEntire(text.trim { it.isWhitespace() || it == '\u200E' || it == '\uFEFF' })?.groupValues?.get(1)
            ?.let { Handles.parse(platform, it) }

    private companion object {
        /** Compose trees run to a few hundred visible nodes; this bounds a pathological one. */
        const val MAX_NODES = 900

        /** Levels below a row searched for "@handle": header or account cell, then a quoted card's header. */
        const val MAX_ROW_DEPTH = 3

        const val MIN_CARD_PX = 200
    }
}
