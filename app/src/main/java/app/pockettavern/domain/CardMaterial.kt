package app.pockettavern.domain

/** One thing found online for a character card: a page, a video, a social post, or a link the user gave. */
data class CardSource(
    val id: String = newId(), val kind: String = PAGE, val title: String = "", val url: String = "", val author: String = "",
    /** For posts and links on Douyin, TikTok, Weibo, Xiaohongshu or X: which one ("weibo"). */
    val platform: String = "",
    val text: String = "",
    /** The whole page or the video's subtitles were read, rather than a search result's summary. */
    val full: Boolean = false,
    /** Unticked by the user: left out of the next rewrite. */
    val used: Boolean = true
) {
    companion object {
        const val PAGE = "page"
        const val VIDEO = "video"
        const val POST = "post"
        const val LINK = "link"
        /** Text the user gave: a file they added or text they pasted. */
        const val USER = "user"
    }
}

/**
 * A picture for the character: found online, or one of the user's photos or video frames. Its bytes
 * stay in the phone's memory until the form closes.
 */
data class CardPicture(val id: String = newId(), val title: String = "", val pageUrl: String = "", val mime: String = "image/jpeg", val used: Boolean = true,
    val source: String = FOUND) {
    val mine: Boolean get() = source != FOUND

    companion object {
        const val FOUND = "found"
        const val PHOTO = "photo"
        const val FRAME = "frame"
    }
}

/** What the user added to the character form themselves, before or between drafts. */
data class DraftInputs(val pictures: List<CardPicture> = emptyList(), val texts: List<CardSource> = emptyList(),
    /** Files are being read. */
    val busy: Boolean = false,
    /** What could not be added, from the last batch. */
    val notes: List<String> = emptyList()) {
    val isEmpty: Boolean get() = pictures.isEmpty() && texts.isEmpty()
}

/** Everything found for one draft, shown to the user so wrong finds can be left out before a rewrite. */
data class CardMaterial(
    val subject: String = "",
    val sources: List<CardSource> = emptyList(),
    val pictures: List<CardPicture> = emptyList(),
    /** What could not be found or read, in the user's language. */
    val notes: List<String> = emptyList(),
    /** How many pictures the model was shown for the last draft; 0 when it cannot see pictures. */
    val picturesSeen: Int = 0
)
