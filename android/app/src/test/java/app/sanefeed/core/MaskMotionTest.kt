package app.sanefeed.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaskMotionTest {
    @Test
    fun `mask moves with the list`() {
        assertEquals(Span(400, 900), MaskMotion.place(600, 1100, 300, 2400, scrolled = 200, slack = 0))
        assertEquals(Span(800, 1300), MaskMotion.place(600, 1100, 300, 2400, scrolled = -200, slack = 0))
    }

    @Test
    fun `slack widens closed edges while moving`() {
        assertEquals(Span(350, 950), MaskMotion.place(600, 1100, 300, 2400, scrolled = 200, slack = 50))
    }

    @Test
    fun `edge on the clip boundary stays pinned because the item continues past it`() {
        // Clipped at the top: scrolling down reveals more of it above, so the mask keeps the top.
        assertEquals(Span(300, 1000), MaskMotion.place(300, 800, 300, 2400, scrolled = -200, slack = 0))
        // Clipped at the bottom: scrolling up brings more of it in from below.
        assertEquals(Span(1800, 2400), MaskMotion.place(2000, 2400, 300, 2400, scrolled = 200, slack = 0))
    }

    @Test
    fun `mask never leaves the clip`() {
        assertEquals(Span(300, 500), MaskMotion.place(400, 900, 300, 2400, scrolled = 400, slack = 0))
        assertNull(MaskMotion.place(400, 900, 300, 2400, scrolled = 700, slack = 0))
        assertEquals(Span(1700, 2400), MaskMotion.place(1600, 2300, 300, 2400, scrolled = -200, slack = 100))
    }

    @Test
    fun `slack is zero at rest`() {
        assertEquals(0, MaskMotion.slack(0))
        assertEquals(MaskMotion.slack(-150), MaskMotion.slack(150))
    }

    @Test
    fun `position jumps of lazy lists fall back to the last delta`() {
        assertEquals(379, MaskMotion.positionDelta(379, 0))
        assertEquals(0, MaskMotion.positionDelta(3000, 0))
        // The first visible item changed: the estimate jumps far ahead of the current speed.
        assertEquals(379, MaskMotion.positionDelta(3114, 379))
        assertEquals(195, MaskMotion.positionDelta(195, 379))
        assertEquals(120, MaskMotion.positionDelta(-900, 120))
        assertEquals(0, MaskMotion.positionDelta(0, 120))
    }
}
