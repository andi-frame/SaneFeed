package app.sanefeed.core

/** Vertical extent on screen, [top] inclusive, [bottom] exclusive. */
data class Span(val top: Int, val bottom: Int)

/**
 * Where to draw a mask between scans. Scans are slow on a scrolling list, so masks follow the
 * scroll deltas the app reports and are widened while it moves; the next scan corrects them.
 */
object MaskMotion {
    /**
     * The mask for an item last scanned at [top]..[bottom] in a list visible over
     * [clipTop]..[clipBottom], after the list content moved up by [scrolled] px. An edge on the
     * clip boundary is open: the item continues past it, so that edge stays pinned to the
     * boundary. [slack] widens the other edges to cover motion until the next update. X and
     * Instagram slide their bars away as content moves up, by as much as it moved, so unless the
     * content moves down the clip grows by that much plus [slack] until the next scan measures the
     * bars again. Null when nothing of the item is left on screen.
     */
    fun place(top: Int, bottom: Int, clipTop: Int, clipBottom: Int, scrolled: Int, slack: Int): Span? {
        val reveal = if (scrolled >= 0) scrolled + slack else 0
        val shownTop = maxOf(0, clipTop - reveal)
        val shownBottom = clipBottom + reveal
        val newTop = if (top <= clipTop) shownTop else maxOf(shownTop, top - scrolled - slack)
        val newBottom = if (bottom >= clipBottom) shownBottom else minOf(shownBottom, bottom - scrolled + slack)
        return if (newTop < newBottom) Span(newTop, newBottom) else null
    }

    /**
     * Extra cover while a list moves: the apps report scrolling about every 100 ms, so the list
     * can travel about [lastDelta], the distance of the last report, before the next one arrives.
     * [MARGIN_PX] covers the frame or two a window move takes to show.
     */
    fun slack(lastDelta: Int): Int = if (lastDelta == 0) 0 else Math.abs(lastDelta) + MARGIN_PX

    /**
     * Scroll distance from the change in a list's reported position, for lists that report no
     * delta (Compose). Lazy lists report an estimate that jumps when the first visible item
     * changes, so a change far off the current speed or against its direction is taken as
     * [previous], the last delta. [previous] 0 means the list was at rest.
     */
    fun positionDelta(change: Int, previous: Int): Int {
        if (change == 0 || previous == 0) return if (Math.abs(change) <= MAX_START_PX) change else 0
        val plausible = (change > 0) == (previous > 0) && Math.abs(change) <= 2 * Math.abs(previous) + MAX_START_PX
        return if (plausible) change else previous
    }

    private const val MARGIN_PX = 24

    /** Largest believable movement in the first ~100 ms of a scroll. */
    private const val MAX_START_PX = 400
}
