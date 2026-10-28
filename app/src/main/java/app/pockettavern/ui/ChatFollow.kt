package app.pockettavern.ui

/** Whether the conversation list follows streaming output or leaves the reader where they are. */
internal object ChatFollow {
    /** Larger than any item, so the list clamps to the very end of the last message. */
    const val END_OFFSET = Int.MAX_VALUE / 4

    fun isAtBottom(lastVisibleIndex: Int, totalItems: Int, lastItemEnd: Int, viewportEnd: Int, slopPx: Int): Boolean =
        totalItems == 0 || (lastVisibleIndex == totalItems - 1 && lastItemEnd - viewportEnd <= slopPx)

    /** Only a scroll and the position it settles at change the decision; growing content never does. */
    fun nextFollow(following: Boolean, wasScrolling: Boolean, scrolling: Boolean, atBottom: Boolean): Boolean =
        if (scrolling || wasScrolling) atBottom else following

    fun shouldAutoScroll(following: Boolean, userScrolling: Boolean, hasMessages: Boolean): Boolean =
        following && !userScrolling && hasMessages
}
