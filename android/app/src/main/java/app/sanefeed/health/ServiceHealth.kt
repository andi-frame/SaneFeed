package app.sanefeed.health

import app.sanefeed.core.Platform
import java.time.LocalDate
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicLongArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the service saw on one platform since it connected. Times are wall-clock millis; 0 means never. */
data class PlatformHealth(
    val lastEventAt: Long = 0,
    val lastScanAt: Long = 0,
    /** Post containers found by the last feed scan, with or without a readable author. */
    val postsLastScan: Int = 0,
    /** Posts in the last feed scan whose author handle was parsed, i.e. posts SaneFeed can act on. */
    val handlesLastScan: Int = 0,
    /** Feed scans (not Reels/video screens) since connect. */
    val scans: Int = 0,
    /** Last scan that found at least one post with a handle. */
    val lastPostsAt: Long = 0,
    val lastReelsBlockAt: Long = 0,
    /** Unique posts hidden today since the service connected. */
    val hiddenToday: Int = 0,
    val lastError: String? = null,
    val lastErrorAt: Long = 0,
)

data class HealthSnapshot(val connectedAt: Long = 0, val platforms: Map<Platform, PlatformHealth> = emptyMap()) {
    fun of(platform: Platform): PlatformHealth = platforms[platform] ?: EMPTY

    private companion object {
        val EMPTY = PlatformHealth()
    }
}

/**
 * In-process health record shared by the accessibility service (writer) and the settings UI
 * (reader). Events only stamp a primitive array; the scan path, which already allocates,
 * folds those stamps into [state].
 */
class ServiceHealth(
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val _state = MutableStateFlow(HealthSnapshot())
    val state: StateFlow<HealthSnapshot> = _state

    private val eventAt = AtomicLongArray(Platform.entries.size)
    private val hiddenKeys = EnumMap<Platform, HashSet<String>>(Platform::class.java)
    private var hiddenDay: LocalDate? = null

    fun connected() = reset(clock())

    fun cleared() = reset(0)

    /** Called for every accessibility event; must not allocate. */
    fun onEvent(platform: Platform) {
        eventAt.set(platform.ordinal, clock())
    }

    /** Records a hidden post; repeated scans of the same post count once per day. */
    @Synchronized
    fun onHidden(platform: Platform, key: String) {
        val day = today()
        if (day != hiddenDay) {
            hiddenKeys.clear()
            hiddenDay = day
        }
        val keys = hiddenKeys.getOrPut(platform, ::HashSet)
        if (keys.size < MAX_HIDDEN_KEYS) keys += key
    }

    fun onFeedScan(platform: Platform, posts: Int, handles: Int) {
        val now = clock()
        val hidden = hiddenCount(platform)
        update(platform) {
            it.copy(
                lastEventAt = eventAt.get(platform.ordinal),
                lastScanAt = now,
                postsLastScan = posts,
                handlesLastScan = handles,
                scans = it.scans + 1,
                lastPostsAt = if (handles > 0) now else it.lastPostsAt,
                hiddenToday = hidden,
            )
        }
    }

    fun onShortVideoScan(platform: Platform, blocked: Boolean) {
        val now = clock()
        update(platform) {
            it.copy(
                lastEventAt = eventAt.get(platform.ordinal),
                lastScanAt = now,
                lastReelsBlockAt = if (blocked) now else it.lastReelsBlockAt,
            )
        }
    }

    fun onError(platform: Platform, error: Throwable) {
        val message = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(MAX_ERROR_LENGTH)
        val now = clock()
        update(platform) { it.copy(lastEventAt = eventAt.get(platform.ordinal), lastError = message, lastErrorAt = now) }
    }

    @Synchronized
    private fun hiddenCount(platform: Platform): Int =
        if (hiddenDay == today()) hiddenKeys[platform]?.size ?: 0 else 0

    private inline fun update(platform: Platform, crossinline change: (PlatformHealth) -> PlatformHealth) {
        _state.update { it.copy(platforms = it.platforms + (platform to change(it.of(platform)))) }
    }

    @Synchronized
    private fun reset(connectedAt: Long) {
        for (i in 0 until eventAt.length()) eventAt.set(i, 0)
        hiddenKeys.clear()
        hiddenDay = null
        _state.value = HealthSnapshot(connectedAt)
    }

    private companion object {
        const val MAX_HIDDEN_KEYS = 2048
        const val MAX_ERROR_LENGTH = 200
    }
}
