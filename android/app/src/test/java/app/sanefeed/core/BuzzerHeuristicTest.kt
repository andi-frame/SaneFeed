package app.sanefeed.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BuzzerHeuristicTest {
    private val contract = Regex("""\d{6,}$""")

    @Test
    fun `flags handles ending in six or more digits`() {
        assertEquals(true, BuzzerHeuristic.flags("budi83749201"))
        assertEquals(true, BuzzerHeuristic.flags("123456"))
        assertEquals(false, BuzzerHeuristic.flags("budi12345"))
        assertEquals(false, BuzzerHeuristic.flags("budi123456x"))
        assertEquals(false, BuzzerHeuristic.flags(""))
    }

    @Test
    fun `matches the contract regex`() {
        listOf("a1234567", "a12345", "1234567_", "x_000000", "999999", "ab12cd345678", "plain").forEach {
            assertEquals(it, contract.containsMatchIn(it), BuzzerHeuristic.flags(it))
        }
    }
}
