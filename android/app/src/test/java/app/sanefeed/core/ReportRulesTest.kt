package app.sanefeed.core

import app.sanefeed.core.ReportRules.ProofError
import app.sanefeed.core.ReportRules.ReasonError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportRulesTest {
    @Test
    fun `proof links must be https with a host`() {
        assertNull(ReportRules.proofError("https://x.com/budi/status/1"))
        assertNull(ReportRules.proofError("  HTTPS://web.archive.org/web/2026/https://x.com/a  "))
        assertEquals(ProofError.NOT_HTTPS, ReportRules.proofError("http://x.com/budi"))
        assertEquals(ProofError.NOT_HTTPS, ReportRules.proofError("x.com/budi"))
        assertEquals(ProofError.NOT_HTTPS, ReportRules.proofError("https://"))
        assertEquals(ProofError.NOT_HTTPS, ReportRules.proofError("https://has space.com"))
        assertEquals(ProofError.NOT_HTTPS, ReportRules.proofError("javascript:alert(1)"))
        assertEquals(ProofError.EMPTY, ReportRules.proofError("   "))
    }

    @Test
    fun `proof links are at most 500 characters`() {
        val base = "https://a.example/"
        assertNull(ReportRules.proofError(base + "a".repeat(500 - base.length)))
        assertEquals(ProofError.TOO_LONG, ReportRules.proofError(base + "a".repeat(501 - base.length)))
    }

    @Test
    fun `repeated links are flagged after the first`() {
        val errors = ReportRules.proofErrors(listOf("https://a.example/1", "https://a.example/1 ", "http://b"))
        assertEquals(listOf(null, ProofError.DUPLICATE, ProofError.NOT_HTTPS), errors)
    }

    @Test
    fun `reason is 10 to 1000 characters after trimming`() {
        assertEquals(ReasonError.TOO_SHORT, ReportRules.reasonError("   short    "))
        assertNull(ReportRules.reasonError("ten chars!"))
        assertNull(ReportRules.reasonError("a".repeat(1000)))
        assertEquals(ReasonError.TOO_LONG, ReportRules.reasonError("a".repeat(1001)))
    }

    @Test
    fun `draft is built only from valid input and is normalized`() {
        val draft = ReportRules.draftOrNull(Platform.X, " @Budi83749201", "  Same text as 40 accounts ", listOf(" https://x.com/a "))
        assertEquals(ReportDraft(Platform.X, "budi83749201", "Same text as 40 accounts", listOf("https://x.com/a")), draft)
        assertNull(ReportRules.draftOrNull(Platform.X, "bad handle", "Same text as 40 accounts", listOf("https://x.com/a")))
        assertNull(ReportRules.draftOrNull(Platform.X, "budi", "Same text as 40 accounts", emptyList()))
        assertNull(ReportRules.draftOrNull(Platform.X, "budi", "Same text as 40 accounts", List(6) { "https://x.com/$it" }))
    }
}
