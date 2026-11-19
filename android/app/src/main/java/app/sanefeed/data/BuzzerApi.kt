package app.sanefeed.data

import app.sanefeed.core.CasePage
import app.sanefeed.core.ReportDraft
import app.sanefeed.core.ReportJson
import app.sanefeed.core.ReportReceipt
import app.sanefeed.core.Vote
import app.sanefeed.core.VoteTally
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Non-2xx response; [message] is the server's `error` text when it sent one. */
class ApiException(val status: Int, message: String) : IOException(message)

/** Blocking client for the SaneFeed server. Call from a background thread. */
class BuzzerApi(baseUrl: String, private val clientId: String) {
    private val base = baseUrl.trim().trimEnd('/')

    sealed interface ListFetch {
        class Updated(val body: String, val etag: String?) : ListFetch
        data object NotModified : ListFetch
    }

    fun fetchBuzzers(etag: String?): ListFetch {
        val response = request("GET", "/v1/buzzers", headers = etag?.let { mapOf("If-None-Match" to it) }.orEmpty())
        return when (response.status) {
            HttpURLConnection.HTTP_NOT_MODIFIED -> ListFetch.NotModified
            else -> ListFetch.Updated(response.ok().body, response.etag)
        }
    }

    fun submitReport(draft: ReportDraft): ReportReceipt {
        val response = request("POST", "/v1/reports", ReportJson.encodeReport(draft), identified = true).ok()
        return ReportJson.decodeReceipt(response.body, response.status)
    }

    fun pendingCases(cursor: String?): CasePage {
        val query = "limit=$PAGE_SIZE" + cursor?.let { "&cursor=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        return ReportJson.decodePage(request("GET", "/v1/reports?$query", identified = true).ok().body)
    }

    fun vote(caseId: Long, vote: Vote): VoteTally {
        val body = ReportJson.encodeVote(vote)
        return ReportJson.decodeTally(request("POST", "/v1/reports/$caseId/votes", body, identified = true).ok().body)
    }

    private class Response(val status: Int, val body: String, val etag: String?) {
        fun ok(): Response {
            if (status !in 200..299) throw ApiException(status, ReportJson.decodeError(body) ?: "HTTP $status")
            return this
        }
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        identified: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        if (base.isEmpty()) throw IOException("No server URL set")
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            throw IOException("Server URL must start with https://")
        }
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            if (identified) connection.setRequestProperty("X-Client-Id", clientId)
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(status, text, connection.getHeaderField("ETag"))
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val PAGE_SIZE = 50
    }
}
