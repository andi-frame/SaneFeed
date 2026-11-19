package app.sanefeed.accessibility.instagram

/**
 * Splits Instagram's feed into posts. A post is several list rows: header (author), media, and a
 * footer (actions, likes, caption, time). It runs from its header to its footer's bottom, or to
 * the next header while the footer is off screen. The area above the first header belongs to the
 * post before it, named by that post's caption when the footer is on screen, else by the post
 * that preceded the first header in the last scan. Pure logic: coordinates are screen pixels.
 */
internal object FeedLayout {
    class Header(val top: Int, val handle: String, val key: String)

    /** A footer row; [handle] and [key] come from its caption and are null without one. */
    class Footer(val bottom: Int, val handle: String?, val key: String?)

    class Post(val key: String, val handle: String, val top: Int, val bottom: Int)

    /** Below this, the strip above the first header is a gap or a divider, not a post. */
    const val MIN_CARRY_OVER_PX = 48

    /** [headers] and [footers] sorted top to bottom; [previous] is the last scan's result. */
    fun posts(viewportTop: Int, viewportBottom: Int, headers: List<Header>, footers: List<Footer>, previous: List<Post>): List<Post> {
        val posts = ArrayList<Post>(headers.size + 1)
        val firstTop = headers.firstOrNull()?.top ?: viewportBottom
        above(viewportTop, firstTop, headers.firstOrNull(), footers, previous)?.let(posts::add)
        for ((i, header) in headers.withIndex()) {
            val next = headers.getOrNull(i + 1)?.top ?: viewportBottom
            val footer = footers.firstOrNull { it.bottom in header.top + 1..next }
            val bottom = minOf(footer?.bottom ?: next, viewportBottom)
            val top = maxOf(header.top, viewportTop)
            if (top < bottom) posts += Post(header.key, header.handle, top, bottom)
        }
        return posts
    }

    /** The username a caption starts with: its first link, followed by the caption text. */
    fun captionAuthor(text: CharSequence, firstLink: CharSequence): String? {
        val name = firstLink.trim()
        if (name.isEmpty() || text.length <= name.length || !text.startsWith(name) || !text[name.length].isWhitespace()) return null
        return name.toString()
    }

    private fun above(viewportTop: Int, firstTop: Int, first: Header?, footers: List<Footer>, previous: List<Post>): Post? {
        val footer = footers.lastOrNull { it.bottom <= firstTop }
        val bottom = footer?.bottom ?: firstTop
        if (bottom - viewportTop < (if (footer != null) 1 else MIN_CARRY_OVER_PX)) return null
        val owner = first?.let { predecessor(it, previous) }
            ?: footer?.let(::captioned)
            // No anchor at all: one tall post fills the list, the one that filled most of it last time.
            ?: previous.takeIf { first == null }?.maxByOrNull { it.bottom - it.top }
            ?: return null
        return Post(owner.key, owner.handle, viewportTop, bottom)
    }

    private fun captioned(footer: Footer): Post? {
        val handle = footer.handle ?: return null
        val key = footer.key ?: return null
        return Post(key, handle, 0, footer.bottom)
    }

    /** The post right above [first] in the last scan, matched by key, else by author. */
    private fun predecessor(first: Header, previous: List<Post>): Post? {
        val index = previous.indexOfFirst { it.key == first.key }.takeIf { it >= 0 }
            ?: previous.indexOfLast { it.handle == first.handle }
        return previous.getOrNull(index - 1)
    }
}
