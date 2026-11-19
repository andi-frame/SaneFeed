package app.sanefeed.core

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportJsonTest {
    private val case = """
        {"id": 12, "platform": "x", "handle": "budi83749201", "status": "pending",
         "reporters": 3, "agree": 5, "disagree": 1, "priority": 0.61,
         "createdAt": "2026-11-18T10:00:00Z", "updatedAt": "2026-11-19T07:00:00Z",
         "proofs": [{"url": "https://x.com/budi83749201/status/1", "reason": "Same text as 40 accounts", "createdAt": "2026-11-18T10:00:00Z"}],
         "myVote": "agree"}
    """

    @Test
    fun `encodes a report`() {
        val json = JSONObject(
            ReportJson.encodeReport(ReportDraft(Platform.INSTAGRAM, "some.account", "Posts the same caption", listOf("https://a.example/1"))),
        )
        assertEquals("instagram", json.getString("platform"))
        assertEquals("some.account", json.getString("handle"))
        assertEquals("Posts the same caption", json.getString("reason"))
        assertEquals("https://a.example/1", json.getJSONArray("proofs").getString(0))
        assertEquals(1, json.getJSONArray("proofs").length())
    }

    @Test
    fun `encodes a vote`() {
        assertEquals("disagree", JSONObject(ReportJson.encodeVote(Vote.DISAGREE)).getString("vote"))
    }

    @Test
    fun `decodes receipt and tells new cases from appended ones`() {
        val created = ReportJson.decodeReceipt("""{"caseId": 4, "status": "pending"}""", 201)
        assertEquals(4L, created.caseId)
        assertFalse(created.appended)
        assertTrue(ReportJson.decodeReceipt("""{"caseId": 4, "status": "pending"}""", 200).appended)
    }

    @Test
    fun `decodes a page of cases`() {
        val page = ReportJson.decodePage("""{"cases": [$case], "nextCursor": "abc"}""")
        assertEquals("abc", page.nextCursor)
        val c = page.cases.single()
        assertEquals(12L, c.id)
        assertEquals(Platform.X, c.platform)
        assertEquals(CaseStatus.PENDING, c.status)
        assertEquals(3, c.reporters)
        assertEquals(5, c.agree)
        assertEquals(1, c.disagree)
        assertEquals(0.61, c.priority, 1e-9)
        assertEquals(Vote.AGREE, c.myVote)
        assertEquals("Same text as 40 accounts", c.proofs.single().reason)
    }

    @Test
    fun `null cursor and vote, unknown platforms skipped`() {
        val noVote = case.replace("\"myVote\": \"agree\"", "\"myVote\": null")
        val other = case.replace("\"platform\": \"x\"", "\"platform\": \"tiktok\"")
        val page = ReportJson.decodePage("""{"cases": [$noVote, $other], "nextCursor": null}""")
        assertNull(page.nextCursor)
        assertNull(page.cases.single().myVote)
    }

    @Test
    fun `decodes a vote tally`() {
        val tally = ReportJson.decodeTally("""{"agree": 6, "disagree": 1, "myVote": "agree"}""")
        assertEquals(VoteTally(6, 1, Vote.AGREE), tally)
    }

    @Test(expected = JSONException::class)
    fun `unknown status is malformed`() {
        ReportJson.decodePage("""{"cases": [${case.replace("pending", "archived")}], "nextCursor": null}""")
    }

    @Test
    fun `reads server error text`() {
        assertEquals("rate limited", ReportJson.decodeError("""{"error": "rate limited"}"""))
        assertNull(ReportJson.decodeError("<html>"))
        assertNull(ReportJson.decodeError(null))
    }
}
