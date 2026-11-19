package app.sanefeed.core

object PostKeys {
    /**
     * Stable key for a post: author plus a 64-bit FNV-1a hash of a content signature
     * (caption, media description). Digits are skipped so changing like/reply counts and
     * relative timestamps ("2h") don't turn the same post into a new one.
     */
    fun of(handle: String, signature: CharSequence?): String {
        var hash = FNV_OFFSET
        if (signature != null) {
            for (i in 0 until minOf(signature.length, MAX_SIGNATURE)) {
                val c = signature[i]
                if (c in '0'..'9') continue
                hash = (hash xor c.code.toLong()) * FNV_PRIME
            }
        }
        return handle + '#' + java.lang.Long.toHexString(hash)
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
    private const val MAX_SIGNATURE = 400
}
