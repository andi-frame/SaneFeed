package app.sanefeed.data

import android.content.Context
import app.sanefeed.core.BuzzerList
import app.sanefeed.core.Platform
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONException

data class SyncStatus(val lastSyncAt: Long, val lastError: String?)

/**
 * Server buzzer list cached in filesDir. The in-memory [BuzzerList] is swapped atomically, so the
 * accessibility scan thread reads it lock-free while the sync worker replaces it.
 */
class BlocklistRepository(context: Context) {
    private val file = File(context.filesDir, "buzzers.json")
    private val prefs = context.getSharedPreferences("blocklist", Context.MODE_PRIVATE)
    private val _list = MutableStateFlow(BuzzerList.EMPTY)
    private val _status = MutableStateFlow(SyncStatus(prefs.getLong(KEY_LAST_SYNC, 0), prefs.getString(KEY_ERROR, null)))
    @Volatile private var loaded = false

    val list: StateFlow<BuzzerList> = _list
    val status: StateFlow<SyncStatus> = _status

    fun isListed(platform: Platform, handle: String): Boolean = _list.value.contains(platform, handle)

    /** Reads the cached file once. Disk I/O: call off the main thread. */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        try {
            _list.value = BuzzerList.parse(file.readText())
        } catch (_: JSONException) {
            file.delete()
            prefs.edit { remove(KEY_ETAG) }
        } catch (_: IOException) {
            // Unreadable cache: keep the empty list; the next sync rewrites the file.
        }
    }

    /** Fetches the list if it changed since the cached ETag. Blocking; throws on network or parse errors. */
    @Synchronized
    fun sync(api: BuzzerApi) {
        ensureLoaded()
        try {
            val etag = prefs.getString(KEY_ETAG, null).takeIf { file.exists() }
            when (val result = api.fetchBuzzers(etag)) {
                is BuzzerApi.ListFetch.NotModified -> Unit
                is BuzzerApi.ListFetch.Updated -> {
                    val parsed = BuzzerList.parse(result.body)
                    val tmp = File(file.path + ".tmp")
                    tmp.writeText(result.body)
                    if (!tmp.renameTo(file)) throw IOException("Could not save the list")
                    prefs.edit { putString(KEY_ETAG, result.etag) }
                    _list.value = parsed
                }
            }
            val now = System.currentTimeMillis()
            prefs.edit {
                putLong(KEY_LAST_SYNC, now)
                remove(KEY_ERROR)
            }
            _status.value = SyncStatus(now, null)
        } catch (e: Exception) {
            val message = when (e) {
                is IOException -> e.message ?: e.javaClass.simpleName
                is JSONException -> "The server sent an unreadable list"
                else -> throw e
            }
            prefs.edit { putString(KEY_ERROR, message) }
            _status.value = _status.value.copy(lastError = message)
            throw e
        }
    }

    private companion object {
        const val KEY_ETAG = "etag"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_ERROR = "last_error"
    }
}
