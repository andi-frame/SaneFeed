package app.sanefeed.accessibility.instagram

import app.sanefeed.accessibility.instagram.FeedLayout.Footer
import app.sanefeed.accessibility.instagram.FeedLayout.Header
import app.sanefeed.accessibility.instagram.FeedLayout.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedLayoutTest {
    private fun spans(posts: List<Post>) = posts.map { "${it.handle} ${it.top}-${it.bottom}" }

    @Test
    fun `post runs from its header to its footer`() {
        val posts = FeedLayout.posts(
            320, 2400,
            listOf(Header(500, "ani", "a"), Header(1900, "budi", "b")),
            listOf(Footer(1700, "ani", "ac")),
            emptyList(),
        )
        // The gap between ani's footer and budi's header (a suggestion row) stays uncovered.
        assertEquals(listOf("ani 500-1700", "budi 1900-2400"), spans(posts))
    }

    @Test
    fun `area above the first header is named by the caption on screen`() {
        val posts = FeedLayout.posts(
            320, 2400,
            listOf(Header(1200, "budi", "b")),
            listOf(Footer(1100, "ani", "ac")),
            emptyList(),
        )
        assertEquals(listOf("ani 320-1100", "budi 1200-2400"), spans(posts))
    }

    @Test
    fun `area above the first header keeps the post that preceded it`() {
        val previous = listOf(Post("a", "ani", 320, 900), Post("b", "budi", 900, 2400))
        val posts = FeedLayout.posts(320, 2400, listOf(Header(700, "budi", "b")), emptyList(), previous)
        assertEquals(listOf("ani 320-700", "budi 700-2400"), spans(posts))
    }

    @Test
    fun `history wins over a caption when both exist`() {
        val previous = listOf(Post("a", "ani", 320, 900), Post("b", "budi", 900, 2400))
        val posts = FeedLayout.posts(320, 2400, listOf(Header(800, "budi", "b")), listOf(Footer(750, "cici", "cc")), previous)
        assertEquals("ani 320-750", spans(posts).first())
    }

    @Test
    fun `a sliver above the first header without a footer is not a post`() {
        val previous = listOf(Post("a", "ani", 320, 900), Post("b", "budi", 900, 2400))
        val posts = FeedLayout.posts(320, 2400, listOf(Header(340, "budi", "b")), emptyList(), previous)
        assertEquals(listOf("budi 340-2400"), spans(posts))
    }

    @Test
    fun `without any anchor the tall post on screen keeps its author`() {
        val previous = listOf(Post("a", "ani", 320, 500), Post("b", "budi", 500, 2400))
        val posts = FeedLayout.posts(320, 2400, emptyList(), emptyList(), previous)
        assertEquals(listOf("budi 320-2400"), spans(posts))
        assertTrue(FeedLayout.posts(320, 2400, emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `predecessor falls back to the author when the key changed`() {
        val previous = listOf(Post("a", "ani", 320, 900), Post("b-old", "budi", 900, 2400))
        val posts = FeedLayout.posts(320, 2400, listOf(Header(700, "budi", "b-new")), emptyList(), previous)
        assertEquals("ani 320-700", spans(posts).first())
    }

    @Test
    fun `caption author is the first link followed by text`() {
        assertEquals("cnnidlite", FeedLayout.captionAuthor("cnnidlite Setelah lebih dari satu dekade", "cnnidlite"))
        assertNull(FeedLayout.captionAuthor("View likes", "cnnidlite"))
        assertNull(FeedLayout.captionAuthor("cnnidlite", "cnnidlite"))
        assertNull(FeedLayout.captionAuthor("cnnidlitex caption", "cnnidlite"))
    }
}
