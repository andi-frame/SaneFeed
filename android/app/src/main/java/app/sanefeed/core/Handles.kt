package app.sanefeed.core

import java.util.Locale

object Handles {
    private val X_HANDLE = Regex("^[a-z0-9_]{1,15}$")
    private val INSTAGRAM_HANDLE = Regex("^[a-z0-9._]{1,30}$")

    fun normalize(raw: String): String = raw.trim().removePrefix("@").lowercase(Locale.ROOT)

    fun isValid(platform: Platform, handle: String): Boolean = when (platform) {
        Platform.X -> X_HANDLE.matches(handle)
        Platform.INSTAGRAM -> INSTAGRAM_HANDLE.matches(handle)
    }

    /** Normalized handle, or null when it is not a valid handle for [platform]. */
    fun parse(platform: Platform, raw: String): String? =
        normalize(raw).takeIf { isValid(platform, it) }
}
