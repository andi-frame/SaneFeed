package app.sanefeed.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class Vote(val wire: String) {
    AGREE("agree"),
    DISAGREE("disagree");

    companion object {
        fun fromWire(value: String?): Vote? = entries.firstOrNull { it.wire == value }
    }
}

enum class CaseStatus(val wire: String) {
    PENDING("pending"),
    CONFIRMED("confirmed"),
    REJECTED("rejected");

    companion object {
        fun fromWire(value: String?): CaseStatus? = entries.firstOrNull { it.wire == value }
    }
}

data class Proof(val url: String, val reason: String, val createdAt: String)

data class ReportCase(
    val id: Long,
    val platform: Platform,
    val handle: String,
    val status: CaseStatus,
    val reporters: Int,
    val agree: Int,
    val disagree: Int,
    val priority: Double,
    val createdAt: String,
    val updatedAt: String,
    val proofs: List<Proof>,
    val myVote: Vote?,
)

data class CasePage(val cases: List<ReportCase>, val nextCursor: String?)

data class ReportDraft(val platform: Platform, val handle: String, val reason: String, val proofs: List<String>)

/** [appended] is true when the report joined an existing pending case (HTTP 200 instead of 201). */
data class ReportReceipt(val caseId: Long, val appended: Boolean)

data class VoteTally(val agree: Int, val disagree: Int, val myVote: Vote)

/** JSON mapping for the reports v2 API. Decoders throw [org.json.JSONException] on malformed bodies. */
object ReportJson {
    fun encodeReport(draft: ReportDraft): String = JSONObject()
        .put("platform", draft.platform.id)
        .put("handle", draft.handle)
        .put("reason", draft.reason)
        .put("proofs", JSONArray(draft.proofs))
        .toString()

    fun encodeVote(vote: Vote): String = JSONObject().put("vote", vote.wire).toString()

    fun decodeReceipt(body: String, httpStatus: Int): ReportReceipt =
        ReportReceipt(JSONObject(body).getLong("caseId"), appended = httpStatus == 200)

    fun decodeTally(body: String): VoteTally {
        val json = JSONObject(body)
        val vote = Vote.fromWire(json.getString("myVote")) ?: throw JSONException("unknown vote")
        return VoteTally(json.getInt("agree"), json.getInt("disagree"), vote)
    }

    fun decodePage(body: String): CasePage {
        val json = JSONObject(body)
        val items = json.getJSONArray("cases")
        val cases = (0 until items.length()).mapNotNull { decodeCase(items.getJSONObject(it)) }
        return CasePage(cases, json.optStringOrNull("nextCursor"))
    }

    /** Server `{"error": "..."}` message, or null if the body has none. */
    fun decodeError(body: String?): String? =
        body?.let { runCatching { JSONObject(it).optStringOrNull("error") }.getOrNull() }

    /** Null for cases on a platform this client doesn't know. */
    private fun decodeCase(json: JSONObject): ReportCase? {
        val platform = Platform.fromId(json.getString("platform")) ?: return null
        val proofs = json.getJSONArray("proofs")
        return ReportCase(
            id = json.getLong("id"),
            platform = platform,
            handle = json.getString("handle"),
            status = CaseStatus.fromWire(json.getString("status")) ?: throw JSONException("unknown status"),
            reporters = json.getInt("reporters"),
            agree = json.getInt("agree"),
            disagree = json.getInt("disagree"),
            priority = json.getDouble("priority"),
            createdAt = json.getString("createdAt"),
            updatedAt = json.getString("updatedAt"),
            proofs = (0 until proofs.length()).map {
                val proof = proofs.getJSONObject(it)
                Proof(proof.getString("url"), proof.getString("reason"), proof.getString("createdAt"))
            },
            myVote = Vote.fromWire(json.optStringOrNull("myVote")),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
}
