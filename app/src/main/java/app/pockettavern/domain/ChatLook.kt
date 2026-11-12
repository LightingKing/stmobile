package app.pockettavern.domain

/**
 * How one chat looks: a background behind the messages and how strongly it is toned down so the
 * text stays readable. A character can carry one too, for the chats created with her.
 *
 * [background] is "builtin:<id>" for a scene drawn by the app, or "file:backgrounds/<name>.jpg"
 * for a picture the user chose. [dim] and [bubbleClear] are percentages; [blur] is a level 0–3.
 */
data class ChatLook(
    val background: String = "",
    val dim: Int = DEFAULT_DIM,
    val blur: Int = 0,
    val bubbleClear: Int = DEFAULT_CLEAR
) {
    /** Opacity of message cards drawn over the background. */
    val bubbleAlpha: Float get() = 1f - bubbleClear.coerceIn(0, MAX_CLEAR) / 100f

    companion object {
        const val DEFAULT_DIM = 35
        const val DEFAULT_CLEAR = 15
        const val MAX_DIM = 80
        const val MAX_BLUR = 3
        const val MAX_CLEAR = 60

        val BLUR_LABELS = listOf("无", "轻", "中", "重")
    }
}
