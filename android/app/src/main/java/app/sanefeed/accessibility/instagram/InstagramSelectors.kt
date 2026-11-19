package app.sanefeed.accessibility.instagram

/**
 * Every Instagram UI selector in one place. Instagram renames views between releases; when a
 * feature stops working, update the lists here. Each list is tried in order, ids before labels.
 */
internal object InstagramSelectors {
    private const val ID = "com.instagram.android:id/"

    /** Full-screen Reels player (Reels tab, or a reel opened from the feed or a profile). */
    val REELS_VIEWER = listOf(
        ID + "clips_viewer_view_pager",
        ID + "clips_viewer_container",
        ID + "clips_video_container",
    )

    val REELS_TAB = listOf(ID + "clips_tab")

    /** Bottom navigation label of the Reels tab, used when [REELS_TAB] doesn't match. */
    val REELS_TAB_LABELS = listOf("Reels")

    val FEED_LIST = listOf("android:id/list")

    /** Header row on top of every feed post. */
    val POST_HEADER = listOf(ID + "row_feed_profile_header")

    /** Username text inside [POST_HEADER]. */
    val POST_AUTHOR = listOf(ID + "row_feed_photo_profile_name")

    /** Nodes whose description or text identifies a post's content, in preference order. */
    val POST_SIGNATURE = listOf(
        ID + "row_feed_photo_imageview",
        ID + "carousel_image",
        ID + "zoomable_view_container",
        ID + "row_feed_comment_textview_layout",
    )

    /** Text searched for when [POST_AUTHOR] doesn't match; matching nodes go through [AUTHOR_DESCRIPTIONS]. */
    const val AUTHOR_DESCRIPTION_QUERY = "profile picture"

    /** Header avatar descriptions that name the author. Group 1 is the username. */
    val AUTHOR_DESCRIPTIONS = listOf(
        Regex("""^Profile picture of @?([A-Za-z0-9._]{1,30})""", RegexOption.IGNORE_CASE),
        Regex("""^@?([A-Za-z0-9._]{1,30})'s profile picture""", RegexOption.IGNORE_CASE),
    )
}
