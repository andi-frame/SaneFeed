package app.sanefeed.health

import app.sanefeed.core.Platform

/** Whether SaneFeed is doing its job on one platform, derived from settings state and [HealthSnapshot]. */
sealed interface Verdict {
    /** The accessibility service is turned off. */
    data object Off : Verdict

    /** Turned on in Settings but Android hasn't started it; toggling it off and on usually fixes this. */
    data object NotConnected : Verdict

    /** Running, but the app hasn't been opened (or no post has been seen) since it started. */
    data object NotSeenYet : Verdict

    /** Many feed scans and no post recognized: the selectors probably no longer match this app version. */
    data class NoPostsRecognized(val scans: Int) : Verdict

    data class Working(val lastScanAt: Long, val posts: Int) : Verdict

    /** The most recent scan failed. */
    data class Error(val message: String, val at: Long) : Verdict

    companion object {
        /** About three seconds of scrolling at one scan per 150 ms. */
        const val SCANS_BEFORE_NO_POSTS = 20

        fun of(serviceEnabled: Boolean, health: HealthSnapshot, platform: Platform): Verdict {
            if (!serviceEnabled) return Off
            if (health.connectedAt == 0L) return NotConnected
            val p = health.of(platform)
            return when {
                p.lastErrorAt > 0 && p.lastErrorAt >= p.lastScanAt -> Error(p.lastError.orEmpty(), p.lastErrorAt)
                p.lastPostsAt > 0 -> Working(p.lastScanAt, p.handlesLastScan)
                p.scans >= SCANS_BEFORE_NO_POSTS -> NoPostsRecognized(p.scans)
                else -> NotSeenYet
            }
        }
    }
}
