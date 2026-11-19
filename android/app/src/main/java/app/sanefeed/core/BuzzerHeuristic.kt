package app.sanefeed.core

object BuzzerHeuristic {
    private const val MIN_TRAILING_DIGITS = 6

    /** Same result as the shared contract regex `\d{6,}$`, without allocating on the scan path. */
    fun flags(handle: String): Boolean {
        var digits = 0
        for (i in handle.length - 1 downTo 0) {
            if (handle[i] !in '0'..'9') break
            if (++digits >= MIN_TRAILING_DIGITS) return true
        }
        return false
    }
}
