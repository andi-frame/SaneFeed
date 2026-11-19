package app.sanefeed.core

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuzzerListTest {
    @Test
    fun `parses entries per platform`() {
        val list = BuzzerList.parse(
            """
            {"version": 7, "updatedAt": "2026-11-19T07:00:00Z", "entries": [
              {"platform": "x", "handle": "budi83749201", "reason": "coordinated"},
              {"platform": "instagram", "handle": "some.account", "reason": null}
            ]}
            """,
        )
        assertEquals(7L, list.version)
        assertEquals("2026-11-19T07:00:00Z", list.updatedAt)
        assertEquals(2, list.size)
        assertTrue(list.contains(Platform.X, "budi83749201"))
        assertTrue(list.contains(Platform.INSTAGRAM, "some.account"))
        assertFalse(list.contains(Platform.INSTAGRAM, "budi83749201"))
    }

    @Test
    fun `normalizes handles and skips invalid rows`() {
        val list = BuzzerList.parse(
            """
            {"version": 1, "updatedAt": "x", "entries": [
              {"platform": "x", "handle": "@Mixed_Case"},
              {"platform": "x", "handle": "has.dot"},
              {"platform": "tiktok", "handle": "someone"},
              {"platform": "instagram"},
              "not an object"
            ]}
            """,
        )
        assertEquals(1, list.size)
        assertTrue(list.contains(Platform.X, "mixed_case"))
    }

    @Test
    fun `empty list`() {
        val list = BuzzerList.parse("""{"version": 0, "updatedAt": "", "entries": []}""")
        assertEquals(0, list.size)
    }

    @Test(expected = JSONException::class)
    fun `missing entries is malformed`() {
        BuzzerList.parse("""{"version": 1}""")
    }

    @Test(expected = JSONException::class)
    fun `non json is malformed`() {
        BuzzerList.parse("<html>")
    }
}
