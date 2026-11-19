package app.sanefeed.core

object Visibility {
    /**
     * Whether a post counts as seen. Accessibility bounds are already clipped to the viewport, so
     * the full height of a post touching a viewport edge is unknown: such a post counts only once
     * its visible part fills at least half the viewport. Posts inside the viewport count at once.
     */
    fun isCountable(top: Int, bottom: Int, viewportTop: Int, viewportBottom: Int): Boolean {
        val visible = minOf(bottom, viewportBottom) - maxOf(top, viewportTop)
        if (visible <= 0) return false
        val clipped = top <= viewportTop || bottom >= viewportBottom
        return !clipped || visible * 2 >= viewportBottom - viewportTop
    }
}
