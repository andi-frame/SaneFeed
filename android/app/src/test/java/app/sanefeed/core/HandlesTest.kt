package app.sanefeed.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandlesTest {
    @Test
    fun `normalize trims, strips one leading at sign and lowercases`() {
        assertEquals("budi_83", Handles.normalize("  @Budi_83 "))
        assertEquals("@budi", Handles.normalize("@@Budi"))
        assertEquals("i.am.real", Handles.normalize("I.Am.Real"))
    }

    @Test
    fun `x handles allow letters digits underscore up to 15`() {
        assertTrue(Handles.isValid(Platform.X, "a"))
        assertTrue(Handles.isValid(Platform.X, "abcdefghij_1234"))
        assertFalse(Handles.isValid(Platform.X, "abcdefghij_12345"))
        assertFalse(Handles.isValid(Platform.X, "has.dot"))
        assertFalse(Handles.isValid(Platform.X, ""))
    }

    @Test
    fun `instagram handles allow dots and up to 30 chars`() {
        assertTrue(Handles.isValid(Platform.INSTAGRAM, "has.dot_and_1"))
        assertTrue(Handles.isValid(Platform.INSTAGRAM, "a".repeat(30)))
        assertFalse(Handles.isValid(Platform.INSTAGRAM, "a".repeat(31)))
        assertFalse(Handles.isValid(Platform.INSTAGRAM, "dash-no"))
    }

    @Test
    fun `parse normalizes then validates`() {
        assertEquals("budi", Handles.parse(Platform.X, " @BUDI "))
        assertNull(Handles.parse(Platform.X, "@bu di"))
        assertNull(Handles.parse(Platform.X, "@"))
    }
}
