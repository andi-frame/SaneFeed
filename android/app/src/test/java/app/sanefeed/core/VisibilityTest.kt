package app.sanefeed.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityTest {
    @Test
    fun `fully visible post counts`() {
        assertTrue(Visibility.isCountable(300, 500, 100, 1100))
    }

    @Test
    fun `post clipped at an edge counts once it fills half the viewport`() {
        assertFalse(Visibility.isCountable(900, 1100, 100, 1100))
        assertTrue(Visibility.isCountable(600, 1100, 100, 1100))
        assertTrue(Visibility.isCountable(100, 600, 100, 1100))
    }

    @Test
    fun `post outside the viewport does not count`() {
        assertFalse(Visibility.isCountable(1100, 1300, 100, 1100))
    }

    @Test
    fun `post keys ignore digits so changing counts keep the key`() {
        assertEquals(PostKeys.of("budi", "Budi @budi 2h Hello 5 likes"), PostKeys.of("budi", "Budi @budi 3h Hello 12 likes"))
        assertNotEquals(PostKeys.of("budi", "Hello"), PostKeys.of("budi", "Goodbye"))
        assertNotEquals(PostKeys.of("budi", "Hello"), PostKeys.of("ani", "Hello"))
    }
}
