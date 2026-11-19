package app.sanefeed.data

import android.content.Context
import android.content.SharedPreferences
import app.sanefeed.BuildConfig
import app.sanefeed.core.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class SettingsState(
    val limits: Map<Platform, Int>,
    val blockReels: Boolean,
    val heuristic: Boolean,
    val counterChip: Boolean,
    val serverUrl: String,
    val manual: Map<Platform, Set<String>>,
    val disclosureAccepted: Boolean,
    val reportedCases: Set<Long>,
)

/** User preferences. [state] is readable from any thread; writers persist and publish in one step. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state

    fun setLimit(platform: Platform, limit: Int) {
        val value = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        prefs.edit { putInt(limitKey(platform), value) }
        _state.update { it.copy(limits = it.limits + (platform to value)) }
    }

    fun setBlockReels(on: Boolean) {
        prefs.edit { putBoolean(KEY_BLOCK_REELS, on) }
        _state.update { it.copy(blockReels = on) }
    }

    fun setHeuristic(on: Boolean) {
        prefs.edit { putBoolean(KEY_HEURISTIC, on) }
        _state.update { it.copy(heuristic = on) }
    }

    fun setCounterChip(on: Boolean) {
        prefs.edit { putBoolean(KEY_COUNTER_CHIP, on) }
        _state.update { it.copy(counterChip = on) }
    }

    fun setServerUrl(url: String) {
        val value = url.trim().trimEnd('/')
        prefs.edit { putString(KEY_SERVER_URL, value) }
        _state.update { it.copy(serverUrl = value) }
    }

    fun acceptDisclosure() {
        prefs.edit { putBoolean(KEY_DISCLOSURE, true) }
        _state.update { it.copy(disclosureAccepted = true) }
    }

    /** [handle] must already be normalized and valid. */
    fun addManual(platform: Platform, handle: String) = updateManual(platform) { it + handle }

    fun removeManual(platform: Platform, handle: String) = updateManual(platform) { it - handle }

    fun addReportedCase(caseId: Long) {
        val cases = _state.value.reportedCases + caseId
        prefs.edit { putStringSet(KEY_REPORTED, cases.mapTo(HashSet(), Long::toString)) }
        _state.update { it.copy(reportedCases = cases) }
    }

    @Synchronized
    private fun updateManual(platform: Platform, change: (Set<String>) -> Set<String>) {
        val handles = change(_state.value.manual[platform].orEmpty())
        prefs.edit { putStringSet(manualKey(platform), handles) }
        _state.update { it.copy(manual = it.manual + (platform to handles)) }
    }

    private fun read() = SettingsState(
        limits = Platform.entries.associateWith { prefs.getInt(limitKey(it), DEFAULT_LIMIT) },
        blockReels = prefs.getBoolean(KEY_BLOCK_REELS, true),
        heuristic = prefs.getBoolean(KEY_HEURISTIC, true),
        counterChip = prefs.getBoolean(KEY_COUNTER_CHIP, false),
        serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: BuildConfig.DEFAULT_SERVER_URL,
        // getStringSet returns a live instance that must not be mutated, so copy it.
        manual = Platform.entries.associateWith { prefs.getStringSet(manualKey(it), null).orEmpty().toSet() },
        disclosureAccepted = prefs.getBoolean(KEY_DISCLOSURE, false),
        reportedCases = prefs.getStringSet(KEY_REPORTED, null).orEmpty().mapNotNullTo(HashSet(), String::toLongOrNull),
    )

    private fun limitKey(platform: Platform) = "limit.${platform.id}"
    private fun manualKey(platform: Platform) = "manual.${platform.id}"

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 999
        private const val KEY_BLOCK_REELS = "block_reels"
        private const val KEY_HEURISTIC = "heuristic"
        private const val KEY_COUNTER_CHIP = "counter_chip"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DISCLOSURE = "disclosure_accepted"
        private const val KEY_REPORTED = "reported_cases"
    }
}

internal inline fun SharedPreferences.edit(block: SharedPreferences.Editor.() -> Unit) {
    edit().apply(block).apply()
}
