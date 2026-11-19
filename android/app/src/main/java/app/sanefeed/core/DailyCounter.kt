package app.sanefeed.core

import java.time.LocalDate
import java.util.EnumMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Counts unique posts per platform per local day. Keys seen today are kept in a bounded,
 * insertion-ordered set so a post is never counted twice while scrolling back and forth.
 */
class DailyCounter(
    private val store: Store,
    private val today: () -> LocalDate,
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
) {
    class Day(val date: LocalDate, val count: Int, val keys: List<String>)

    interface Store {
        fun load(platform: Platform): Day?
        fun save(platform: Platform, day: Day)
    }

    private class State(var date: LocalDate, var count: Int, val keys: LinkedHashSet<String>)

    private val states = EnumMap<Platform, State>(Platform::class.java)
    private val _counts = MutableStateFlow<Map<Platform, Int>>(emptyMap())

    /** Today's counts; re-checked against the clock on every [count], [record] and [refresh]. */
    val counts: StateFlow<Map<Platform, Int>> = _counts

    /** Returns true when [key] was not seen today and the count went up. */
    @Synchronized
    fun record(platform: Platform, key: String): Boolean {
        val state = current(platform)
        if (!state.keys.add(key)) return false
        if (state.keys.size > maxKeys) state.keys.remove(state.keys.first())
        state.count++
        store.save(platform, Day(state.date, state.count, state.keys.toList()))
        publish()
        return true
    }

    @Synchronized
    fun count(platform: Platform): Int = current(platform).count

    @Synchronized
    fun refresh() {
        Platform.entries.forEach(::current)
    }

    private fun current(platform: Platform): State {
        val date = today()
        val state = states[platform] ?: store.load(platform).let { saved ->
            State(saved?.date ?: date, saved?.count ?: 0, LinkedHashSet(saved?.keys.orEmpty()))
        }.also {
            states[platform] = it
            publish()
        }
        if (state.date != date) {
            state.date = date
            state.count = 0
            state.keys.clear()
            store.save(platform, Day(date, 0, emptyList()))
            publish()
        }
        return state
    }

    private fun publish() {
        _counts.value = states.mapValues { it.value.count }
    }

    companion object {
        const val DEFAULT_MAX_KEYS = 2048
    }
}
