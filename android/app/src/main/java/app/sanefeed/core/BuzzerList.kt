package app.sanefeed.core

import java.util.EnumMap
import org.json.JSONObject

/** Immutable snapshot of the server buzzer list, indexed per platform for O(1) lookups. */
class BuzzerList(
    val version: Long,
    val updatedAt: String,
    private val handles: Map<Platform, Set<String>>,
) {
    val size: Int = handles.values.sumOf { it.size }

    fun contains(platform: Platform, handle: String): Boolean = handles[platform]?.contains(handle) == true

    companion object {
        val EMPTY = BuzzerList(0, "", emptyMap())

        /**
         * Parses a `GET /v1/buzzers` body. Entries with an unknown platform or invalid handle are
         * skipped so one bad row can't discard the whole list; a malformed document throws.
         */
        fun parse(json: String): BuzzerList {
            val root = JSONObject(json)
            val entries = root.getJSONArray("entries")
            val handles = EnumMap<Platform, HashSet<String>>(Platform::class.java)
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val platform = Platform.fromId(entry.optString("platform")) ?: continue
                val handle = Handles.parse(platform, entry.optString("handle")) ?: continue
                handles.getOrPut(platform, ::HashSet).add(handle)
            }
            return BuzzerList(root.getLong("version"), root.optString("updatedAt"), handles)
        }
    }
}
