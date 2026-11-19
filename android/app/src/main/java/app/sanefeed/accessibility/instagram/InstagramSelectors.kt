package app.sanefeed.accessibility.instagram

/**
 * Every Instagram UI selector in one place. Instagram renames views between releases; when a
 * feature stops working, update the lists here. Each list is tried in order, ids before labels.
 */
internal object InstagramSelectors {
    private const val ID = "com.instagram.android:id/"
    private const val USERNAME = """([A-Za-z0-9._]{1,30})"""

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

    /** App bar parts drawn over the top of the feed list; the feed starts below them. */
    val TOP_BAR = listOf(
        ID + "main_feed_action_bar",
        ID + "action_bar_new_title_container",
        ID + "action_bar_title_view",
        ID + "action_bar_left_button",
    )

    /** Header row on top of every feed post. */
    val POST_HEADER = listOf(ID + "row_feed_profile_header")

    /** Username text inside [POST_HEADER]. */
    val POST_AUTHOR = listOf(ID + "row_feed_photo_profile_name")

    /** Nodes whose description or text identifies a post's content, in preference order. */
    val POST_SIGNATURE = listOf(
        ID + "row_feed_photo_imageview",
        ID + "carousel_image",
        ID + "zoomable_view_container",
        ID + "carousel_video_media_group",
        ID + "row_feed_comment_textview_layout",
    )

    /** Like/comment/share bar; its parent is the footer row that also holds the caption. */
    val POST_ACTIONS = listOf(ID + "row_feed_view_group_buttons")

    /** Text searched for when [POST_AUTHOR] doesn't match; matching nodes go through [AUTHOR_DESCRIPTIONS]. */
    const val AUTHOR_DESCRIPTION_QUERY = "profile picture"

    /** Header avatar descriptions that name the author. Group 1 is the username. */
    val AUTHOR_DESCRIPTIONS = listOf(
        Regex("""^Profile picture of @?$USERNAME""", RegexOption.IGNORE_CASE),
        Regex("""^@?$USERNAME's profile picture""", RegexOption.IGNORE_CASE),
    )

    /** Comment and reply rows: the avatar's description names the author and its parent is the row. */
    const val COMMENT_AVATAR_QUERY = "'s profile"
    val COMMENT_AVATAR = Regex("""^Go to $USERNAME's profile$""")

    /** Stories tray avatars; the avatar's parent also holds the username label. */
    const val STORY_AVATAR_QUERY = "'s story,"
    val STORY_AVATAR = Regex("""^$USERNAME's story, \d+ of \d+""")

    /** Full-screen story viewer, and its header naming the account. */
    val STORY_VIEWER = listOf(ID + "reel_viewer_root")
    val STORY_OWNER = listOf(ID + "reel_viewer_text_container")
    val STORY_OWNER_DESCRIPTION = Regex("""^$USERNAME's story""")

    /** Profile screens: [PROFILE_BAR] holds [PROFILE_USERNAME], which stays on screen while scrolling. */
    val PROFILE_BAR = listOf(ID + "profile_action_bar")
    val PROFILE_USERNAME = listOf(ID + "action_bar_title")

    /** Account rows in search results and recent searches. */
    val SEARCH_USER_ROW = listOf(ID + "row_search_user_container")
    val SEARCH_USERNAME = listOf(ID + "row_search_user_username")

    /** Suggestion rows whose subtitle starts with the username ("cnnindonesia • 2.9M followers"). */
    val SEARCH_SUBTITLE = listOf(ID + "row_search_keyword_subtitle")
    val SEARCH_SUBTITLE_USERNAME = Regex("""^$USERNAME •""")

    /** Explore grid tiles; their description names the author ("Reel by name at row 1, column 2"). */
    val EXPLORE_TILE = listOf(ID + "grid_card_layout_container", ID + "image_preview")
    val EXPLORE_TILE_AUTHOR = Regex(""" by $USERNAME at row \d+, column \d+$""")
    val EXPLORE_GRID = listOf(ID + "recycler_view")

    /** Search field and topic pills drawn over the top of the Explore grid. */
    val EXPLORE_TOP_BAR = listOf(ID + "action_bar_search_edit_text", ID + "pill_bar_rv")
}
