package app.sanefeed.core

enum class Platform(val id: String, val packageName: String, val label: String) {
    X("x", "com.twitter.android", "X"),
    INSTAGRAM("instagram", "com.instagram.android", "Instagram");

    companion object {
        fun fromId(id: String?): Platform? = entries.firstOrNull { it.id == id }

        fun fromPackage(packageName: CharSequence?): Platform? = when {
            packageName == null -> null
            packageName.contentEquals(X.packageName) -> X
            packageName.contentEquals(INSTAGRAM.packageName) -> INSTAGRAM
            else -> null
        }
    }
}
