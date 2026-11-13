package app.pockettavern.domain

/** One thing found online for a character card: a page, a video, or a link the user gave. */
data class CardSource(
    val id: String = newId(), val kind: String = PAGE, val title: String = "", val url: String = "", val author: String = "",
    val text: String = "",
    /** The whole page or the video's subtitles were read, rather than a search result's summary. */
    val full: Boolean = false,
    /** Unticked by the user: left out of the next rewrite. */
    val used: Boolean = true
) {
    companion object {
        const val PAGE = "page"
        const val VIDEO = "video"
        const val LINK = "link"
    }
}

/** A picture found for the character. Its bytes stay in the phone's memory until the form closes. */
data class CardPicture(val id: String = newId(), val title: String = "", val pageUrl: String = "", val mime: String = "image/jpeg", val used: Boolean = true)

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
