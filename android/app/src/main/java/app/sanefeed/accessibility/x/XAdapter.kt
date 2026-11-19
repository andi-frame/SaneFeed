package app.sanefeed.accessibility.x

import android.graphics.Rect
import android.os.SystemClock
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
 * X 12.x is Jetpack Compose: the app-side id and text searches don't see its nodes, so a walk of
 * the tree reads every surface. A row is a wide clickable node holding an "@handle" text:
 * timeline posts, replies and notifications (tagged header), and account rows in search and
 * typeahead. A second "@handle" inside its own clickable card is a quoted post. Wide, short nodes
 * drawn after the list (app bar, tabs, bottom navigation, reply composer) are chrome that masks
 * must not cover.
 *
 * While a list scrolls, X answers each node request only between frames and keeps invalidating
 * the node cache, so a full walk costs a frame per node (0.2 to 1.3 s). Once a walk has found the
 * list, later scans re-read only the list, its rows and the chrome beside it, a level at a time
 * with concurrent requests, and rows already read are recognized by node identity without reading
 * them again. Once the list stops moving, one full walk re-reads everything.
 */
class XAdapter : PlatformAdapter {
    override val platform = Platform.X

    // Scan thread only.
    private var feed: Feed? = null

    /** The main list's children as the last scan read them, by node identity. */
    private var known: Map<AccessibilityNodeInfo, Known> = emptyMap()

    /** Where the main list's visible children were at the last scan, by node identity. */
    private var tops: Map<AccessibilityNodeInfo, Int> = emptyMap()

    /** When a scan last saw the main list move, uptime millis. */
    private var movedAt = 0L

    /** The list counts as moving until it has kept still for [REST_MS]. */
    private val moving get() = SystemClock.uptimeMillis() - movedAt < REST_MS

    override fun scan(root: AccessibilityNodeInfo, screen: Rect, scrolling: Boolean): ScreenScan {
        if (scrolling) movedAt = SystemClock.uptimeMillis()
        feed?.let { held -> listScan(held, screen)?.let { return it } }
        return walkScan(root, screen)
    }

    private fun walkScan(root: AccessibilityNodeInfo, screen: Rect): ScreenScan {
        // At rest the node cache answers most requests at once; in motion, fetch level by level.
        val fetched = if (moving) Nodes.subtrees(listOf(root), MAX_NODES) { node, _ -> node.isVisibleToUser } else null
        val walk = Walk(screen, fetched, findsList = true)
        walk.visit(root, Rect(screen), surface = null)
        val tabs = listOfNotNull(walk.videoTab)
        if (walk.immersive) {
            feed = null
            known = emptyMap()
            tops = emptyMap()
            return ScreenScan(true, emptyList(), 0, tabs)
        }
        val rows = walk.rows.ifEmpty { if (walk.headers == 0) rowsByDescription(root, screen) else emptyList() }
        val list = walk.list
        val listBounds = walk.listBounds
        val read = if (listBounds == null) emptyMap() else walk.read.filterValues { it.rows.first().list === listBounds }
        val moved = listBounds?.let { movedUp(known, read, it) }
        // A new list has no children in common with the last one; that's a new screen, not motion.
        if (walk.listTops.keys.any { it in tops } && walk.listTops != tops) movedAt = SystemClock.uptimeMillis()
        tops = walk.listTops
        known = read
        val bars = Bars(walk.chrome, screen)
        val held = feed
        feed = if (list != null && listBounds != null) {
            val floor = if (held != null && held.list == list) maxOf(held.barsTop, bars.top) else bars.top
            Feed(list, walk.listChrome, walk.listSurface, walk.owner, walk.videoTab, walkedAtRest = !moving, barsTop = floor)
        } else {
            null
        }
        return result(rows, bars, maxOf(walk.headers, rows.size), tabs, walk.owner, moved, listBounds, feed?.barsTop)
    }

    /** Re-reads only [feed]'s list and chrome; null when the screen changed or a full walk is due. */
    private fun listScan(feed: Feed, screen: Rect): ScreenScan? {
        val parents = feed.chrome.map { it.first }
        if (!Nodes.refreshAll(listOf(feed.list) + parents).all { it } || !feed.list.isVisibleToUser) return null
        val listBounds = Nodes.bounds(feed.list)
        val children = Nodes.childrenOfAll(listOf(feed.list) + parents)

        val chrome = ArrayList<Rect>()
        for ((i, pair) in feed.chrome.withIndex()) {
            val siblings = children[i + 1]
            val at = siblings.indexOf(pair.second)
            if (at < 0) return null
            for (node in siblings.subList(at + 1, siblings.size)) {
                val bounds = Nodes.bounds(node)
                if (node.isVisibleToUser && isChrome(bounds, screen)) chrome += bounds
            }
        }
        val bars = Bars(chrome, screen)

        val rows = ArrayList<Row>()
        val now = HashMap<AccessibilityNodeInfo, Known>()
        val unread = ArrayList<AccessibilityNodeInfo>()
        val nowTops = HashMap<AccessibilityNodeInfo, Int>()
        for (child in children[0]) {
            if (!child.isVisibleToUser) continue
            val bounds = Nodes.bounds(child)
            nowTops[child] = bounds.top
            val was = known[child]
            if (was == null) {
                unread += child
                continue
            }
            val shifted = was.at(bounds, listBounds)
            rows += shifted.rows
            now[child] = shifted
        }
        if (nowTops != tops) {
            movedAt = SystemClock.uptimeMillis()
            feed.walkedAtRest = false
        } else if (!feed.walkedAtRest && !moving) {
            return null
        }
        tops = nowTops

        if (unread.isNotEmpty()) {
            val fetched = Nodes.subtrees(unread, MAX_NODES) { node, depth -> depth < MAX_ROW_DEPTH && searchesInRow(node) }
            val walk = Walk(screen, fetched, findsList = false)
            // Children without a post are read again every scan: X may expose a post's author only once its header is uncovered.
            for (child in unread) {
                walk.visit(child, listBounds, feed.surface)
                if (walk.immersive) return null
                walk.read[child]?.let { now[child] = it }
            }
            rows += walk.rows
        }
        val moved = movedUp(known, now, listBounds)
        known = now
        feed.barsTop = maxOf(feed.barsTop, bars.top)
        return result(rows, bars, rows.size, listOfNotNull(feed.videoTab), feed.owner, moved, listBounds, feed.barsTop)
    }

    /**
     * [list] is the main list, if known, and [barsTop] the lowest the bars above it have reached on
     * this screen: X slides its app bar back in as the list scrolls down, so content the scan
     * hasn't read only ever appears below that.
     */
    private fun result(
        rows: List<Row>,
        bars: Bars,
        candidates: Int,
        tabs: List<Rect>,
        owner: String?,
        moved: Int?,
        list: Rect?,
        barsTop: Int?,
    ): ScreenScan {
        val items = rows.mapNotNull { row ->
            val clip = bars.clip(row.list)
            val bounds = Rect(row.bounds)
            if (bounds.intersect(clip)) Authored(row.key, row.handle, bounds, clip, row.countable) else null
        }
        val viewport = list?.let { bars.clip(it).apply { top = maxOf(top, barsTop ?: top) } }?.takeIf { it.top < it.bottom }
            ?: items.firstOrNull { it.countable }?.clip
        return ScreenScan(false, items, candidates, tabs, owner, viewport, moved)
    }

    /** Px the list moved its content up from [before] to [after], the median over children both read; null when none. */
    private fun movedUp(before: Map<AccessibilityNodeInfo, Known>, after: Map<AccessibilityNodeInfo, Known>, list: Rect): Int? {
        val moves = after.mapNotNull { (node, now) -> before[node]?.let { -shift(it.bounds, now.bounds, list) } }.sorted()
        return moves.getOrNull(moves.size / 2)
    }

    /** The screen area left between chrome bars at the top and at the bottom. */
    private class Bars(chrome: List<Rect>, screen: Rect) {
        var top = screen.top
            private set
        private var bottom = screen.bottom

        init {
            for (bar in chrome) {
                if (bar.centerY() < screen.centerY()) top = maxOf(top, bar.bottom) else bottom = minOf(bottom, bar.top)
            }
        }

        fun clip(list: Rect) = Rect(list.left, maxOf(list.top, top), list.right, minOf(list.bottom, bottom))
    }

    private class Row(val key: String, val handle: String, val bounds: Rect, val list: Rect, val countable: Boolean) {
        fun at(bounds: Rect, list: Rect) = Row(key, handle, Rect(bounds), list, countable)

        fun shifted(dy: Int, list: Rect) = Row(key, handle, Rect(bounds).apply { offset(0, dy) }, list, countable)
    }

    /** A list child as a scan read it at [bounds]: its author's row, then quoted posts (no rows: it holds no post). */
    private class Known(val bounds: Rect, val rows: List<Row>) {
        fun at(now: Rect, list: Rect): Known {
            val dy = shift(bounds, now, list)
            return Known(Rect(now), rows.mapIndexed { i, row -> if (i == 0) row.at(now, list) else row.shifted(dy, list) })
        }
    }

    /**
     * The list the last full walk found and what it learned around it. [chrome] pairs each node on
     * the path to the list that has more children with its child on that path: the children after
     * it are drawn over the list. [surface] is the screen tag the list is in.
     */
    private class Feed(
        val list: AccessibilityNodeInfo,
        val chrome: List<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>>,
        val surface: String?,
        val owner: String?,
        val videoTab: Rect?,
        /** Whether a full walk has run since the list last moved. */
        var walkedAtRest: Boolean,
        /** See [result]. */
        var barsTop: Int,
    )

    /** An "@handle" text in a row; [card] is the part of the row it labels: a clickable card, else a top-level child. */
    private class Mention(val handle: String, val card: Rect?, val inHeader: Boolean)

    /**
     * One pass over a tree. Children come from [fetched] when it has them. With [findsList], the
     * first list holding rows is recorded with its chrome; otherwise nodes are read as list children.
     */
    private inner class Walk(
        private val screen: Rect,
        private val fetched: Map<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>?,
        private val findsList: Boolean,
    ) {
        val rows = ArrayList<Row>()
        val chrome = ArrayList<Rect>()
        var headers = 0
        var owner: String? = null
        var immersive = false
        var videoTab: Rect? = null
        var list: AccessibilityNodeInfo? = null
        var listBounds: Rect? = null
        var listSurface: String? = null
        var listChrome: List<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>> = emptyList()
        var listTops: Map<AccessibilityNodeInfo, Int> = emptyMap()

        /** Rows found per row node. */
        val read = HashMap<AccessibilityNodeInfo, Known>()

        private val path = ArrayList<AccessibilityNodeInfo>()
        private var visited = 0
        private var listEnded = false
        private val navTop = screen.bottom - screen.height() / Nodes.BOTTOM_NAV_FRACTION

        private fun children(node: AccessibilityNodeInfo) = fetched?.get(node) ?: Nodes.children(node)

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
            if (listEnded && isChrome(bounds, screen)) {
                chrome += bounds
                // The bottom bar is still read for the Video tab.
                if (bounds.centerY() < screen.centerY()) return
            }
            val children = children(node)
            if (node.isClickable && bounds.width() * 10 >= screen.width() * 8) {
                if (row(node, bounds, parent, children) || recall(node, bounds, parent)) return
            }
            val before = rows.size
            path += node
            for (child in children) visit(child, bounds, tag)
            if (findsList && !listEnded && (before until rows.size).any { rows[it].list === bounds }) {
                listEnded = true
                list = node
                listBounds = bounds
                listSurface = tag
                listChrome = path.zipWithNext().filter { (ancestor, _) -> Nodes.childCount(ancestor) > 1 }
                listTops = children.filter { it.isVisibleToUser }.associateWith { Nodes.bounds(it).top }
            }
            path.removeAt(path.lastIndex)
        }

        /** Records [node] as a row if it holds an "@handle" text; quoted posts inside it become rows too. */
        private fun row(node: AccessibilityNodeInfo, bounds: Rect, list: Rect, children: List<AccessibilityNodeInfo>): Boolean {
            val mentions = ArrayList<Mention>()
            collect(children, depth = 1, card = null, inHeader = false, mentions)
            val author = mentions.firstOrNull() ?: return false
            val first = rows.size
            // The body is the longest text directly in the row; the header holds only name and time.
            val signature = children.mapNotNull { it.text }.maxByOrNull { it.length }
            rows += Row(PostKeys.of(author.handle, signature), author.handle, bounds, list, countable = author.inHeader)
            for (quote in mentions.drop(1).filter { it.card != null && it.card != author.card }.distinctBy { it.card }) {
                rows += Row(PostKeys.of(quote.handle, "${author.handle} $signature"), quote.handle, quote.card!!, list, countable = false)
            }
            read[node] = Known(Rect(bounds), rows.subList(first, rows.size).toList())
            return true
        }

        /** A row whose author no longer shows (its header moved under a bar or off screen), as an earlier scan read it. */
        private fun recall(node: AccessibilityNodeInfo, bounds: Rect, list: Rect): Boolean {
            val was = known[node]?.takeIf { it.rows.isNotEmpty() } ?: return false
            val shifted = was.at(bounds, list)
            rows += shifted.rows
            read[node] = shifted
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
                collect(children(node), depth + 1, inner, inHeader || header, out)
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

    /** Whether [Walk.collect] looks inside [node] (mirrors its checks, for fetching ahead). */
    private fun searchesInRow(node: AccessibilityNodeInfo): Boolean =
        node.isVisibleToUser && node.text?.let(::exactHandle) == null && !(node.isClickable && Nodes.bounds(node).height() < MIN_CARD_PX)

    private fun isChrome(bounds: Rect, screen: Rect): Boolean =
        !bounds.isEmpty && bounds.width() * 2 >= screen.width() && bounds.height() * 2 < screen.height()

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

        /** X updates node bounds about every 100 ms while a list moves; still for longer means it stopped. */
        const val REST_MS = 300L
    }
}

/**
 * How far a list child moved down from [was] to [now]. Compose clips a partly visible node's
 * bounds to the [list], so the edge that isn't clipped tells.
 */
private fun shift(was: Rect, now: Rect, list: Rect): Int = if (now.top <= list.top) now.bottom - was.bottom else now.top - was.top
