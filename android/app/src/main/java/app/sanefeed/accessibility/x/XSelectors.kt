package app.sanefeed.accessibility.x

/**
 * Every X (Twitter) UI selector in one place. X renames views between releases; when a feature
 * stops working, update the lists here. Each list is tried in order, ids before labels.
 */
internal object XSelectors {
    private const val ID = "com.twitter.android:id/"

    val FEED_LIST = listOf("android:id/list")

    /** Timeline row containing one post. */
    val POST_ROW = listOf(ID + "row", ID + "tweet_view")

    /** Node inside a row whose text or description holds "@handle". */
    val POST_AUTHOR = listOf(ID + "tweet_header", ID + "screenname_item", ID + "user_name")

    /**
     * Compose test tags (X 12.x and later, `com.x.android.main.MainActivity`). Despite its name,
     * `timeline_post` tags the header row (name, @handle, time); its parent is the post.
     */
    val POST_HEADER_TAGS = listOf("timeline_post")

    /** Post body text, the preferred content signature. */
    val POST_TEXT = listOf(ID + "tweet_content_text", ID + "tweet_text")

    /** Full-screen vertical video viewer opened from the Video tab or a video in the timeline. */
    val IMMERSIVE_VIEWER = listOf(
        ID + "immersive_video_player",
        ID + "immersive_media_player_view",
        ID + "immersive_viewer_pager",
    )

    val VIDEO_TAB = listOf(ID + "video_tab")

    /** Bottom navigation labels of the Video tab, used when [VIDEO_TAB] doesn't match. */
    val VIDEO_TAB_LABELS = listOf("Video", "Videos")

    /** Text searched for when [POST_ROW] doesn't match: every row description includes "@handle". */
    const val HANDLE_QUERY = "@"

    /** Group 1 is the handle. The first match in a row is the author (repost labels use display names). */
    val HANDLE = Regex("""@([A-Za-z0-9_]{1,15})\b""")
}
