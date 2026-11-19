package app.sanefeed.accessibility.x

/**
 * Every X (Twitter) UI selector in one place. X renames views between releases; when a feature
 * stops working, update the lists here. Each list is tried in order, ids before labels.
 */
internal object XSelectors {
    private const val ID = "com.twitter.android:id/"

    /** Timeline row containing one post (View-based X builds). */
    val POST_ROW = listOf(ID + "row", ID + "tweet_view")

    /** Node inside a [POST_ROW] whose text or description holds "@handle". */
    val POST_AUTHOR = listOf(ID + "tweet_header", ID + "screenname_item", ID + "user_name")

    /** Post body text inside a [POST_ROW], the preferred content signature. */
    val POST_TEXT = listOf(ID + "tweet_content_text", ID + "tweet_text")

    /**
     * Compose test tags (X 12.x and later, `com.x.android.main.MainActivity`). Despite its name,
     * `timeline_post` tags the header (name, @handle, time) of a post, reply or notification;
     * its parent is the post. Account rows (search, typeahead) have no tag, only an "@handle" text.
     */
    val POST_HEADER_TAGS = listOf("timeline_post")

    /** Compose screen tag of a profile; its first "@handle" text outside a post is the account. */
    val PROFILE_SCREENS = listOf("UserProfileScreen")

    /** Profile top bar labels naming the account; used once the header has scrolled away. Group 1 is the handle. */
    val PROFILE_DESCRIPTIONS = listOf(Regex("""^Ask Grok about @([A-Za-z0-9_]{1,15})$"""))

    /** Full-screen vertical video viewer opened from the Video tab or a video in the timeline. */
    val IMMERSIVE_VIEWER = listOf(
        ID + "immersive_video_player",
        ID + "immersive_media_player_view",
        ID + "immersive_viewer_pager",
    )

    val VIDEO_TAB = listOf(ID + "video_tab")

    /** Bottom navigation labels of the Video tab, used when [VIDEO_TAB] doesn't match. */
    val VIDEO_TAB_LABELS = listOf("Video", "Videos")

    /** Text searched for when no row is found: every row description includes "@handle". */
    const val HANDLE_QUERY = "@"

    /** Group 1 is the handle. The first match in a row is the author (repost labels use display names). */
    val HANDLE = Regex("""@([A-Za-z0-9_]{1,15})\b""")

    /** A text that is exactly one handle, as in post headers, quoted posts and account rows. Group 1 is the handle. */
    val EXACT_HANDLE = Regex("""^@([A-Za-z0-9_]{1,15})$""")
}
