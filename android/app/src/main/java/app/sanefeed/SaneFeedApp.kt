package app.sanefeed

import android.app.Application
import android.content.Context
import app.sanefeed.core.BuzzerHeuristic
import app.sanefeed.core.DailyCounter
import app.sanefeed.core.Platform
import app.sanefeed.data.BlocklistRepository
import app.sanefeed.data.BuzzerApi
import app.sanefeed.data.BuzzerSyncWorker
import app.sanefeed.data.PrefsCounterStore
import app.sanefeed.data.Settings
import app.sanefeed.data.edit
import app.sanefeed.health.ServiceHealth
import java.time.LocalDate
import java.util.UUID

class SaneFeedApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        BuzzerSyncWorker.schedulePeriodic(this)
    }
}

/** Process-wide singletons shared by the settings UI, the accessibility service and the sync worker. */
class AppGraph(context: Context) {
    val settings = Settings(context)
    val counter = DailyCounter(PrefsCounterStore(context), LocalDate::now)
    val blocklist = BlocklistRepository(context)
    val health = ServiceHealth()

    /** Random per-install id sent as X-Client-Id; generated once, never tied to an account. */
    val clientId: String by lazy {
        val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
        prefs.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit { putString(KEY_CLIENT_ID, id) }
        }
    }

    fun api() = BuzzerApi(settings.state.value.serverUrl, clientId)

    /** Server list ∪ manual list ∪ heuristic (when enabled). [handle] must be normalized. */
    fun isHidden(platform: Platform, handle: String): Boolean {
        val state = settings.state.value
        return blocklist.isListed(platform, handle) ||
            state.manual[platform]?.contains(handle) == true ||
            (state.heuristic && BuzzerHeuristic.flags(handle))
    }

    /** Whether [isHidden] can be true for some handle on [platform]. */
    fun hidesAnyone(platform: Platform): Boolean {
        val state = settings.state.value
        return state.heuristic || state.manual[platform]?.isNotEmpty() == true || blocklist.list.value.hasAny(platform)
    }

    private companion object {
        const val KEY_CLIENT_ID = "client_id"
    }
}
