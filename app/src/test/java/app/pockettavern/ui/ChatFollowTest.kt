package app.pockettavern.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFollowTest {
    @Test fun readerAtBottomFollowsStreamingOutput() {
        val atBottom = ChatFollow.isAtBottom(lastVisibleIndex = 4, totalItems = 5, lastItemEnd = 1200, viewportEnd = 1200, slopPx = 48)
        val following = ChatFollow.nextFollow(following = true, wasScrolling = false, scrolling = false, atBottom = atBottom)
        assertTrue(atBottom)
        assertTrue(ChatFollow.shouldAutoScroll(following, userScrolling = false, hasMessages = true))
    }

    @Test fun readerAboveNeverGetsScrolledByNewTokens() {
        val dragging = ChatFollow.nextFollow(following = true, wasScrolling = false, scrolling = true, atBottom = false)
        assertFalse(dragging)
        val settled = ChatFollow.nextFollow(dragging, wasScrolling = true, scrolling = false, atBottom = false)
        assertFalse(ChatFollow.shouldAutoScroll(settled, userScrolling = false, hasMessages = true))
        // Later tokens grow the list without any gesture; the reader stays where they are.
        val afterTokens = ChatFollow.nextFollow(settled, wasScrolling = false, scrolling = false, atBottom = false)
        assertFalse(ChatFollow.shouldAutoScroll(afterTokens, userScrolling = false, hasMessages = true))
    }

    @Test fun growthBelowTheViewportDoesNotStopFollowing() {
        val following = ChatFollow.nextFollow(following = true, wasScrolling = false, scrolling = false, atBottom = false)
        assertTrue(ChatFollow.shouldAutoScroll(following, userScrolling = false, hasMessages = true))
    }

    @Test fun scrollingBackToTheBottomResumesFollowing() {
        val returning = ChatFollow.nextFollow(following = false, wasScrolling = false, scrolling = true, atBottom = true)
        val settled = ChatFollow.nextFollow(returning, wasScrolling = true, scrolling = false, atBottom = true)
        assertTrue(ChatFollow.shouldAutoScroll(settled, userScrolling = false, hasMessages = true))
    }

    @Test fun neverFightsAnActiveGestureOrScrollsAnEmptyList() {
        assertFalse(ChatFollow.shouldAutoScroll(following = true, userScrolling = true, hasMessages = true))
        assertFalse(ChatFollow.shouldAutoScroll(following = true, userScrolling = false, hasMessages = false))
    }

    @Test fun bottomDetectionUsesTheLastItemAndASmallSlop() {
        assertTrue(ChatFollow.isAtBottom(lastVisibleIndex = 4, totalItems = 5, lastItemEnd = 1240, viewportEnd = 1200, slopPx = 48))
        assertFalse(ChatFollow.isAtBottom(lastVisibleIndex = 4, totalItems = 5, lastItemEnd = 1400, viewportEnd = 1200, slopPx = 48))
        assertFalse(ChatFollow.isAtBottom(lastVisibleIndex = 3, totalItems = 5, lastItemEnd = 1200, viewportEnd = 1200, slopPx = 48))
        assertTrue(ChatFollow.isAtBottom(lastVisibleIndex = -1, totalItems = 0, lastItemEnd = 0, viewportEnd = 1200, slopPx = 48))
    }
}
