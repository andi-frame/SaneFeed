package app.sanefeed.accessibility.instagram

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.sanefeed.accessibility.Authored
import app.sanefeed.accessibility.Nodes
import app.sanefeed.accessibility.PlatformAdapter
import app.sanefeed.accessibility.ScreenScan
import app.sanefeed.core.Handles
import app.sanefeed.core.Platform
import app.sanefeed.core.PostKeys
import app.sanefeed.accessibility.instagram.InstagramSelectors as S

/**
 * Instagram is View-based, so every surface is found with the app-side id and text searches.
 * Screens with the feed list (home, a profile's post list) are read for posts and the stories
 * tray; other screens for comment rows, search results and Explore tiles. A full-screen profile
 * or story reports its account as the screen owner.
 */
class InstagramAdapter : PlatformAdapter {
    override val platform = Platform.INSTAGRAM

    /** Feed posts from the previous scan, top to bottom; lets a post whose header scrolled away keep its area. */
    private var previous: List<FeedLayout.Post> = emptyList()

    override fun scan(root: AccessibilityNodeInfo, screen: Rect): ScreenScan {
        val reelsTab = Nodes.firstById(root, S.REELS_TAB) ?: Nodes.bottomTab(root, screen, S.REELS_TAB_LABELS)
        val tabs = listOfNotNull(reelsTab?.let(Nodes::bounds))
        val onReels = reelsTab?.isSelected == true || Nodes.byId(root, S.REELS_VIEWER).any { Nodes.coversScreen(it, screen) }
        if (onReels) {
            previous = emptyList()
            return ScreenScan(true, emptyList(), 0, tabs)
        }
        storyOwner(root, screen)?.let { return ScreenScan(false, emptyList(), 0, tabs, owner = it) }

        val owner = profileOwner(root)
        val feed = Nodes.firstById(root, S.FEED_LIST)
        if (feed == null) {
            previous = emptyList()
            return ScreenScan(false, comments(root) + searchRows(root) + exploreTiles(root, screen), 0, tabs, owner)
        }
        val viewport = belowTopBar(root, Nodes.bounds(feed).takeUnless(Rect::isEmpty) ?: Rect(screen))
        val headers = Nodes.byId(root, S.POST_HEADER).map(Nodes::bounds)
        val posts = feedPosts(root, headers, viewport)
        return ScreenScan(false, posts + stories(root, viewport), headers.size, tabs, owner)
    }

    private fun feedPosts(root: AccessibilityNodeInfo, headerBounds: List<Rect>, viewport: Rect): List<Authored> {
        val signatures = Nodes.allById(root, S.POST_SIGNATURE).mapNotNull { node -> Nodes.label(node)?.let { Nodes.bounds(node).top to it } }
        val anchors = anchors(root, headerBounds).filter { it.second < viewport.bottom }.sortedBy { it.second }
        val headers = anchors.mapIndexed { i, (handle, top) ->
            val bottom = anchors.getOrNull(i + 1)?.second ?: viewport.bottom
            val signature = signatures.firstOrNull { it.first in top until bottom }?.second
            FeedLayout.Header(top, handle, PostKeys.of(handle, signature))
        }
        val footers = footers(root, viewport)
        val posts = FeedLayout.posts(viewport.top, viewport.bottom, headers, footers, previous)
        previous = posts
        return posts.map { Authored(it.key, it.handle, Rect(viewport.left, it.top, viewport.right, it.bottom), viewport, countable = true) }
    }

    /** Post authors with the top of their header row. */
    private fun anchors(root: AccessibilityNodeInfo, headers: List<Rect>): List<Pair<String, Int>> {
        val byId = Nodes.byId(root, S.POST_AUTHOR).mapNotNull { node ->
            val handle = node.text?.let(::usernameOf) ?: return@mapNotNull null
            val name = Nodes.bounds(node)
            handle to (headers.firstOrNull { it.contains(name) }?.top ?: name.top)
        }
        return byId.ifEmpty {
            Nodes.byText(root, S.AUTHOR_DESCRIPTION_QUERY).mapNotNull { node ->
                val name = node.contentDescription?.let { matchUsername(S.AUTHOR_DESCRIPTIONS, it) } ?: return@mapNotNull null
                name to Nodes.bounds(node).top
            }
        }
    }

    /** Footer rows on screen, read even when their action bar has just scrolled off. */
    private fun footers(root: AccessibilityNodeInfo, viewport: Rect): List<FeedLayout.Footer> =
        Nodes.laidOutById(root, S.POST_ACTIONS).mapNotNull { actions ->
            val row = Nodes.parent(actions) ?: return@mapNotNull null
            val bounds = Nodes.bounds(row)
            if (!bounds.intersect(viewport)) return@mapNotNull null
            val caption = Nodes.children(row).firstNotNullOfOrNull(::caption)
            FeedLayout.Footer(bounds.bottom, caption?.first, caption?.let { PostKeys.of(it.first, it.second) })
        }.sortedBy { it.bottom }

    /** A caption starts with the author's username, which is also its first link. */
    private fun caption(node: AccessibilityNodeInfo): Pair<String, CharSequence>? {
        val text = node.text ?: return null
        val link = Nodes.children(node).firstOrNull()?.let(Nodes::label) ?: return null
        val username = FeedLayout.captionAuthor(text, link)?.let { Handles.parse(platform, it) } ?: return null
        return username to text
    }

    private fun comments(root: AccessibilityNodeInfo): List<Authored> {
        var clip: Rect? = null
        return Nodes.byText(root, S.COMMENT_AVATAR_QUERY).mapNotNull { avatar ->
            val handle = avatar.contentDescription?.let { matchUsername(listOf(S.COMMENT_AVATAR), it) } ?: return@mapNotNull null
            val row = Nodes.parent(avatar) ?: return@mapNotNull null
            val list = clip ?: scrollingAncestor(row)?.let(Nodes::bounds)?.also { clip = it } ?: return@mapNotNull null
            authored("comment", handle, Nodes.bounds(row), list, row.text ?: row.contentDescription)
        }
    }

    private fun stories(root: AccessibilityNodeInfo, viewport: Rect): List<Authored> =
        Nodes.byText(root, S.STORY_AVATAR_QUERY).mapNotNull { avatar ->
            // The clickable ring, not the image inside it, so each story is read once.
            if (!avatar.isClickable) return@mapNotNull null
            val handle = avatar.contentDescription?.let { matchUsername(listOf(S.STORY_AVATAR), it) } ?: return@mapNotNull null
            val item = Nodes.parent(avatar) ?: avatar
            authored("story", handle, Nodes.bounds(item), viewport, null)
        }

    private fun searchRows(root: AccessibilityNodeInfo): List<Authored> {
        val rowNodes = Nodes.byId(root, S.SEARCH_USER_ROW)
        val subtitles = Nodes.byId(root, S.SEARCH_SUBTITLE)
        val list = (rowNodes.firstOrNull() ?: subtitles.firstOrNull()?.let(Nodes::parent))
            ?.let(Nodes::parent)?.let(Nodes::bounds) ?: return emptyList()
        val rows = rowNodes.map(Nodes::bounds)
        val users = Nodes.byId(root, S.SEARCH_USERNAME).mapNotNull { name ->
            val handle = name.text?.let(::usernameOf) ?: return@mapNotNull null
            val bounds = Nodes.bounds(name)
            val row = rows.firstOrNull { it.contains(bounds) } ?: return@mapNotNull null
            authored("search", handle, Rect(row), list, null)
        }
        val suggestions = subtitles.mapNotNull { subtitle ->
            val handle = subtitle.text?.let { matchUsername(listOf(S.SEARCH_SUBTITLE_USERNAME), it) } ?: return@mapNotNull null
            val row = Nodes.parent(subtitle) ?: return@mapNotNull null
            authored("search", handle, Nodes.bounds(row), list, subtitle.text)
        }
        return users + suggestions
    }

    private fun exploreTiles(root: AccessibilityNodeInfo, screen: Rect): List<Authored> {
        val tiles = Nodes.allById(root, S.EXPLORE_TILE)
        if (tiles.isEmpty()) return emptyList()
        val grid = Nodes.firstById(root, S.EXPLORE_GRID)?.let(Nodes::bounds) ?: Rect(screen)
        val clip = belowTopBar(root, grid, S.EXPLORE_TOP_BAR)
        return tiles.mapNotNull { tile ->
            val description = tile.contentDescription ?: return@mapNotNull null
            val handle = S.EXPLORE_TILE_AUTHOR.find(description)?.groupValues?.get(1)?.let { Handles.parse(platform, it) } ?: return@mapNotNull null
            authored("explore", handle, Nodes.bounds(tile), clip, description)
        }
    }

    private fun authored(kind: String, handle: String, bounds: Rect, clip: Rect, signature: CharSequence?): Authored? {
        if (!bounds.intersect(clip)) return null
        return Authored(PostKeys.of(handle, "$kind $signature"), handle, bounds, Rect(clip), countable = false)
    }

    /** The account of a full-screen story, which also covers the feed behind it. */
    private fun storyOwner(root: AccessibilityNodeInfo, screen: Rect): String? {
        if (Nodes.byId(root, S.STORY_VIEWER).none { Nodes.coversScreen(it, screen) }) return null
        val description = Nodes.firstById(root, S.STORY_OWNER)?.contentDescription ?: return null
        return matchUsername(listOf(S.STORY_OWNER_DESCRIPTION), description)
    }

    private fun profileOwner(root: AccessibilityNodeInfo): String? {
        if (Nodes.firstById(root, S.PROFILE_BAR) == null) return null
        return Nodes.firstById(root, S.PROFILE_USERNAME)?.text?.let(::usernameOf)
    }

    /** [list] minus the bars in [bars] drawn over its top (Instagram's lists scroll under them). */
    private fun belowTopBar(root: AccessibilityNodeInfo, list: Rect, bars: List<String> = S.TOP_BAR): Rect {
        val limit = list.top + list.height() / 4
        for (bar in Nodes.allById(root, bars).map(Nodes::bounds)) {
            if (bar.bottom > list.top && bar.top < limit) list.top = maxOf(list.top, minOf(bar.bottom, limit))
        }
        return list
    }

    private fun scrollingAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = Nodes.parent(node)
        repeat(MAX_ANCESTORS) {
            val parent = current ?: return null
            if (parent.isScrollable) return parent
            current = Nodes.parent(parent)
        }
        return null
    }

    private fun matchUsername(patterns: List<Regex>, text: CharSequence): String? =
        patterns.firstNotNullOfOrNull { it.find(text) }?.groupValues?.get(1)?.let { Handles.parse(platform, it) }

    /** The header text may carry extras after the username ("name • Follow", "a and b"). */
    private fun usernameOf(text: CharSequence): String? =
        Handles.parse(platform, text.trim().takeWhile { !it.isWhitespace() && it != '•' }.toString())

    private companion object {
        const val MAX_ANCESTORS = 4
    }
}
