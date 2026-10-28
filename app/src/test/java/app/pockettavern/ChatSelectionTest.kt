package app.pockettavern

import app.pockettavern.domain.Chat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSelectionTest {
    @Test fun persistedSelectionWinsOverNewestChat() {
        val older = Chat(id = "older", updatedAt = 10)
        val newer = Chat(id = "newer", updatedAt = 20)

        assertEquals("older", preferredChatId(listOf(older, newer), "older"))
    }

    @Test fun missingSelectionFallsBackToMostRecentlyUpdatedChat() {
        val older = Chat(id = "older", updatedAt = 10)
        val newer = Chat(id = "newer", updatedAt = 20)

        assertEquals("newer", preferredChatId(listOf(older, newer), "deleted"))
    }

    @Test fun noChatsRestoreAnEmptySelection() {
        assertTrue(preferredChatId(emptyList(), "missing").isEmpty())
    }
}
