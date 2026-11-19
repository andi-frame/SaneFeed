package app.sanefeed.core

import java.net.URI
import java.net.URISyntaxException

/** Client-side mirror of the server's report validation, so errors show inline before sending. */
object ReportRules {
    const val REASON_MIN = 10
    const val REASON_MAX = 1000
    const val PROOFS_MAX = 5
    const val PROOF_URL_MAX = 500

    enum class ReasonError { TOO_SHORT, TOO_LONG }

    enum class ProofError { EMPTY, TOO_LONG, NOT_HTTPS, DUPLICATE }

    fun reasonError(reason: String): ReasonError? {
        val length = reason.trim().length
        return when {
            length < REASON_MIN -> ReasonError.TOO_SHORT
            length > REASON_MAX -> ReasonError.TOO_LONG
            else -> null
        }
    }

    fun proofError(url: String): ProofError? {
        val trimmed = url.trim()
        return when {
            trimmed.isEmpty() -> ProofError.EMPTY
            trimmed.length > PROOF_URL_MAX -> ProofError.TOO_LONG
            !isHttpsUrl(trimmed) -> ProofError.NOT_HTTPS
            else -> null
        }
    }

    /** One error slot per link; a link repeating an earlier one is flagged as [ProofError.DUPLICATE]. */
    fun proofErrors(urls: List<String>): List<ProofError?> {
        val seen = HashSet<String>()
        return urls.map { url -> proofError(url) ?: ProofError.DUPLICATE.takeUnless { seen.add(url.trim()) } }
    }

    fun draftOrNull(platform: Platform, rawHandle: String, reason: String, urls: List<String>): ReportDraft? {
        val handle = Handles.parse(platform, rawHandle) ?: return null
        if (reasonError(reason) != null || urls.isEmpty() || urls.size > PROOFS_MAX) return null
        if (proofErrors(urls).any { it != null }) return null
        return ReportDraft(platform, handle, reason.trim(), urls.map(String::trim))
    }

    private fun isHttpsUrl(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
    } catch (_: URISyntaxException) {
        false
    }
}
