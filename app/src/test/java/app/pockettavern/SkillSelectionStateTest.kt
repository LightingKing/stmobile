package app.pockettavern

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.SkillRecord
import app.pockettavern.domain.Summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillSelectionStateTest {
    @Test fun chatSkillUpdatePreservesOrderAndOtherChatFieldsWhileEnablingOnlyNewSelections() {
        val existing = SkillRecord(id = "existing", name = "原有", enabled = false)
        val firstNew = SkillRecord(id = "new-1", name = "新增一", enabled = false)
        val secondNew = SkillRecord(id = "new-2", name = "新增二", enabled = false)
        val originalSummary = Summary(id = "summary", text = "旧摘要")
        val editedSummary = originalSummary.copy(text = "新摘要")
        val chat = Chat(
            id = "chat", title = "保留标题", providerId = "provider", modelId = "model",
            messages = listOf(Message(id = "message", content = "原文")),
            summaries = listOf(originalSummary), skillIds = listOf(existing.id), updatedAt = 10L
        )
        val other = Chat(id = "other", title = "其它会话", skillIds = listOf(secondNew.id))
        val source = AppData(chats = listOf(chat, other), skills = listOf(existing, firstNew, secondNew))

        val updated = applyChatSkillUpdate(
            source, chat.id, listOf(secondNew.id, existing.id, firstNew.id), listOf(editedSummary), updatedAt = 99L
        )

        val result = updated.chats.first()
        assertEquals(listOf(secondNew.id, existing.id, firstNew.id), result.skillIds)
        assertEquals("保留标题", result.title)
        assertEquals("provider", result.providerId)
        assertEquals("model", result.modelId)
        assertEquals(chat.messages, result.messages)
        assertEquals(listOf(editedSummary), result.summaries)
        assertEquals(99L, result.updatedAt)
        assertEquals(other, updated.chats[1])
        assertFalse(updated.skills.single { it.id == existing.id }.enabled)
        assertTrue(updated.skills.single { it.id == firstNew.id }.enabled)
        assertTrue(updated.skills.single { it.id == secondNew.id }.enabled)
    }

    @Test fun unconfirmedAdultSkillIsRemovedWithoutBeingEnabled() {
        val adult = SkillRecord(id = "adult", adult = true, enabled = false)
        val summary = Summary(id = "summary", text = "保留")
        val source = AppData(chats = listOf(Chat(id = "chat")), skills = listOf(adult))

        val updated = applyChatSkillUpdate(source, "chat", listOf(adult.id), listOf(summary), updatedAt = 20L)

        assertTrue(updated.chats.single().skillIds.isEmpty())
        assertEquals(listOf(summary), updated.chats.single().summaries)
        assertFalse(updated.skills.single().enabled)
    }
}
