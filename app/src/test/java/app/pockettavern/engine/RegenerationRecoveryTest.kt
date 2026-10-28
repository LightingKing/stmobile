package app.pockettavern.engine

import app.pockettavern.data.DataCodec
import app.pockettavern.domain.*
import org.junit.Assert.*
import org.junit.Test

class RegenerationRecoveryTest {
    private val user = Message(id = "user", content = "（重新生成）", intent = "rewrite")
    private val failure = Message(id = "failure", role = "assistant", status = "error", errorMessage = "网络连接失败")

    private fun reopen(chat: Chat) = DataCodec.decode(DataCodec.encode(AppData(chats = listOf(chat)))).chats.single()

    @Test fun retryInterruptedBeforeNewPlaceholderStillHasAnActionAfterReopen() {
        val original = Chat(messages = listOf(user, failure))
        val reopened = reopen(ConversationTurns.regenerate(original))
        assertTrue(reopened.messages.last().contextExcluded)
        assertEquals("failure", ConversationTurns.regenerationAnchor(reopened)?.id)
        val retried = ConversationTurns.regenerate(reopened)
        assertEquals(reopened.messages, retried.messages)
        assertEquals(user.content, retried.messages.first().content)
        assertEquals(failure.errorMessage, retried.messages.last().errorMessage)
    }

    @Test fun unansweredUserAfterReopenHasAnActionWithoutAddingAnotherPrompt() {
        val reopened = reopen(Chat(messages = listOf(user)))
        assertEquals(user.id, ConversationTurns.regenerationAnchor(reopened)?.id)
        assertEquals(reopened, ConversationTurns.regenerate(reopened))
    }

    @Test fun latestActiveReplyWinsOverTrailingDiscardedDrafts() {
        val active = Message(id = "accepted", role = "assistant", content = "正文")
        val chat = Chat(messages = listOf(user, active, failure.copy(contextExcluded = true)))
        assertEquals(active.id, ConversationTurns.regenerationAnchor(reopen(chat))?.id)
    }

    @Test fun excludedLaterUserDoesNotHideOrStealTheCurrentTurnAction() {
        val chat = Chat(messages = listOf(user, failure,
            Message(id = "excluded-user", content = "作废提问", contextExcluded = true),
            failure.copy(id = "excluded-reply", contextExcluded = true)))
        assertEquals(failure.id, ConversationTurns.regenerationAnchor(reopen(chat))?.id)
        val withoutActiveReply = chat.copy(messages = chat.messages.map {
            if (it.id == failure.id) it.copy(contextExcluded = true) else it
        })
        assertEquals(failure.id, ConversationTurns.regenerationAnchor(reopen(withoutActiveReply))?.id)
    }

    @Test fun regeneratedReplyAppendedAfterExcludedTurnsRemainsTheCurrentAction() {
        val chat = Chat(messages = listOf(user, failure.copy(contextExcluded = true),
            Message(id = "excluded-user", contextExcluded = true),
            Message(id = "new-reply", role = "assistant", content = "新正文")))
        assertEquals("new-reply", ConversationTurns.regenerationAnchor(reopen(chat))?.id)
    }

    @Test fun failureStoppedAndRestoredInterruptedReplyCanBeRetried() {
        for (status in listOf("error", "stopped", "interrupted", "complete")) {
            val chat = reopen(Chat(messages = listOf(user, failure.copy(status = status))))
            assertEquals(status, failure.id, ConversationTurns.regenerationAnchor(chat)?.id)
        }
    }

    @Test fun streamingAndChatsWithoutAnActiveUserHaveNoRegenerationAction() {
        assertNull(ConversationTurns.regenerationAnchor(Chat(messages = listOf(user, failure.copy(status = "streaming")))))
        assertNull(ConversationTurns.regenerationAnchor(Chat(messages = listOf(user.copy(contextExcluded = true), failure))))
        assertNull(ConversationTurns.regenerationAnchor(Chat(messages = listOf(failure))))
        assertNull(ConversationTurns.regenerationAnchor(Chat()))
    }
}
