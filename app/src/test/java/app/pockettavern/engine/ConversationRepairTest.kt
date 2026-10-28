package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.domain.Message
import app.pockettavern.domain.Summary
import org.junit.Assert.*
import org.junit.Test

class ConversationRepairTest {
    @Test fun suggestsExactAdjacentDuplicateButKeepsOrdinaryDialogue() {
        val user = Message(id = "u", role = "user", content = "繼續")
        val first = Message(id = "a1", role = "assistant", content = "門外傳來腳步聲。")
        val duplicate = Message(id = "a2", role = "assistant", content = first.content)
        val chat = Chat(title = "原會話", messages = listOf(user, first, duplicate))

        val suggestions = ConversationRepair.candidates(chat)

        assertEquals(listOf("a2"), suggestions.filter { it.recommended }.map { it.messageId })
        assertTrue(suggestions.single().reason.isNotBlank())
    }

    @Test fun pairsInternalToolCallWithItsResultsAndLeavesFailureDiagnosticsUnselected() {
        val chat = Chat(messages = listOf(
            Message(id = "u", role = "user", content = "查一下天气"),
            Message(id = "call", role = "assistant", content = "", toolCallsJson = "[{\"id\":\"t1\"}]"),
            Message(id = "result", role = "tool", content = "晴天", toolCallId = "t1"),
            Message(id = "reply", role = "assistant", content = "今天晴天。"),
            Message(id = "error", role = "assistant", content = "", errorMessage = "内容过滤", finishReason = "content_filter")
        ))

        val candidates = ConversationRepair.candidates(chat).associateBy { it.messageId }

        assertTrue(candidates.getValue("call").recommended)
        assertTrue(candidates.getValue("result").recommended)
        assertFalse(candidates.getValue("error").recommended)
        assertEquals(listOf("call", "result"), candidates.values.filter { it.recommended }.map { it.messageId })
    }

    @Test fun removesSelectedMessagesWithoutChangingIdentityAndRebasesUsableSummary() {
        val user = Message(id = "u", role = "user", content = "前情")
        val oldNoise = Message(id = "noise", role = "assistant", content = "重复废文")
        val tail = Message(id = "tail", role = "assistant", content = "现在剧情继续")
        val oldSummary = Summary(id = "s", text = "旧摘要", throughMessageId = "noise", sourceHash = ContextEngine.sourceHash(listOf(user, oldNoise)))
        val oldNote = KnowledgeNote(id = "n", text = "旧笔记", sourceMessageIds = listOf("noise"), sourceHash = ContextEngine.sourceHash(listOf(oldNoise)))
        val chat = Chat(id = "chat-1", title = "原会话", messages = listOf(user, oldNoise, tail), summaries = listOf(oldSummary), activeSummaryId = "s", knowledgeNotes = listOf(oldNote))

        val cleaned = ConversationRepair.remove(chat, setOf("noise"), updatedAt = 123)

        assertEquals(chat.id, cleaned.id)
        assertEquals(chat.title, cleaned.title)
        assertEquals(listOf("u", "tail"), cleaned.messages.map { it.id })
        assertEquals("s", cleaned.summaries.single().id)
        assertEquals("u", cleaned.summaries.single().throughMessageId)
        assertEquals(ContextEngine.sourceHash(listOf(user)), cleaned.summaries.single().sourceHash)
        assertEquals("s", cleaned.activeSummaryId)
        assertTrue(cleaned.knowledgeNotes.isEmpty())
        assertEquals(123L, cleaned.updatedAt)
    }

    @Test fun removingSummaryCoveredWasteKeepsTheActiveContinuationContext() {
        val early = Message(id = "early", role = "user", content = "EARLY_RAW_SCENE")
        val waste = Message(id = "waste", role = "assistant", content = "需要清理的争论废文")
        val continueUser = Message(id = "continue", role = "user", content = "继续当前剧情")
        val currentEnding = Message(id = "ending", role = "assistant", content = "林岚关上仓库门，等周行回答。")
        val summary = Summary(
            id = "summary",
            text = "林岚与周行是姐弟，二人已经抵达庄园仓库。",
            throughMessageId = waste.id,
            sourceHash = ContextEngine.sourceHash(listOf(early, waste))
        )
        val note = KnowledgeNote(
            id = "relationship",
            text = "林岚是周行的姐姐。",
            sourceMessageIds = listOf(early.id, waste.id),
            sourceHash = ContextEngine.sourceHash(listOf(early, waste))
        )
        val chat = Chat(
            id = "story",
            messages = listOf(early, waste, continueUser, currentEnding),
            summaries = listOf(summary),
            activeSummaryId = summary.id,
            knowledgeNotes = listOf(note)
        )

        val cleaned = ConversationRepair.remove(chat, setOf(waste.id), updatedAt = 123)

        val rebasedSummary = cleaned.summaries.single()
        assertEquals(summary.id, cleaned.activeSummaryId)
        assertEquals(early.id, rebasedSummary.throughMessageId)
        assertEquals(ContextEngine.sourceHash(listOf(early)), rebasedSummary.sourceHash)
        assertEquals(listOf(early.id), cleaned.knowledgeNotes.single().sourceMessageIds)
        val context = ContextEngine.build(app.pockettavern.domain.AppData(), cleaned).messagesJson
        assertTrue(context.contains("林岚与周行是姐弟"))
        assertTrue(context.contains("继续当前剧情"))
        assertTrue(context.contains("林岚关上仓库门"))
        assertFalse(context.contains("EARLY_RAW_SCENE"))
        assertFalse(context.contains("需要清理的争论废文"))
    }

    @Test fun refusesToRemoveAllUserDialogue() {
        val chat = Chat(messages = listOf(Message(id = "u", role = "user", content = "唯一输入"), Message(id = "a", role = "assistant", content = "答复")))
        try {
            ConversationRepair.remove(chat, setOf("u"))
            fail("expected removal to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("至少保留一条用户消息"))
        }
    }

    @Test fun restoringSnapshotKeepsMessagesCreatedAfterTheRepair() {
        val originalUser = Message(id = "u1", role = "user", content = "原始前情")
        val removedReply = Message(id = "a1", role = "assistant", content = "被误删的剧情")
        val original = Chat(id = "story", title = "原会话", modelId = "old-model", messages = listOf(originalUser, removedReply))
        val continuedUser = Message(id = "u2", role = "user", content = "后来继续")
        val continuedReply = Message(id = "a2", role = "assistant", content = "后来生成的剧情")
        val current = original.copy(modelId = "new-model", messages = listOf(originalUser, continuedUser, continuedReply))

        val restored = ConversationRepair.restore(original, current, updatedAt = 456)

        assertEquals(listOf("u1", "a1", "u2", "a2"), restored.messages.map { it.id })
        assertEquals("new-model", restored.modelId)
        assertEquals("原会话", restored.title)
        assertEquals(456L, restored.updatedAt)
    }

    @Test fun restoringSnapshotKeepsOnlyMemoryWhoseOriginalHashStillMatches() {
        val originalUser = Message(id = "u1", role = "user", content = "原始前情")
        val removedReply = Message(id = "a1", role = "assistant", content = "被误删的剧情")
        val originalSummary = Summary(
            id = "old-summary",
            text = "修复前摘要",
            throughMessageId = removedReply.id,
            sourceHash = ContextEngine.sourceHash(listOf(originalUser, removedReply))
        )
        val originalNote = KnowledgeNote(
            id = "old-note",
            text = "修复前知识",
            sourceMessageIds = listOf(removedReply.id),
            sourceHash = ContextEngine.sourceHash(listOf(removedReply))
        )
        val original = Chat(
            id = "story",
            messages = listOf(originalUser, removedReply),
            summaries = listOf(originalSummary),
            activeSummaryId = originalSummary.id,
            knowledgeNotes = listOf(originalNote)
        )
        val continuedUser = Message(id = "u2", role = "user", content = "后来继续")
        val continuedReply = Message(id = "a2", role = "assistant", content = "后来生成的剧情")
        val repairedMessages = listOf(originalUser, continuedUser, continuedReply)
        val currentSummary = Summary(
            id = "new-summary",
            text = "修复后重建摘要，包含后来剧情",
            throughMessageId = continuedReply.id,
            sourceHash = ContextEngine.sourceHash(repairedMessages)
        )
        val currentNote = KnowledgeNote(
            id = "new-note",
            text = "后来剧情的知识笔记",
            sourceMessageIds = listOf(continuedUser.id, continuedReply.id),
            sourceHash = ContextEngine.sourceHash(listOf(continuedUser, continuedReply))
        )
        val current = original.copy(
            messages = repairedMessages,
            summaries = listOf(currentSummary),
            activeSummaryId = currentSummary.id,
            knowledgeNotes = listOf(currentNote)
        )

        val restored = ConversationRepair.restore(original, current, updatedAt = 456)

        assertEquals(listOf("u1", "a1", "u2", "a2"), restored.messages.map { it.id })
        assertEquals(listOf("old-summary"), restored.summaries.map { it.id })
        assertEquals("old-summary", restored.activeSummaryId)
        assertEquals(originalSummary.sourceHash, restored.summaries.single().sourceHash)
        assertEquals(listOf("old-note", "new-note"), restored.knowledgeNotes.map { it.id })
        assertEquals(
            ContextEngine.sourceHash(listOf(continuedUser, continuedReply)),
            restored.knowledgeNotes.single { it.id == "new-note" }.sourceHash
        )
    }

    @Test fun restoringSnapshotPreservesCurrentEditsAndDoesNotRevalidateOldMemory() {
        val originalUser = Message(id = "u1", role = "user", content = "旧的人物关系")
        val removedReply = Message(id = "a1", role = "assistant", content = "误删剧情")
        val originalSummary = Summary(
            id = "same-summary",
            text = "旧摘要",
            throughMessageId = originalUser.id,
            sourceHash = ContextEngine.sourceHash(listOf(originalUser))
        )
        val originalNote = KnowledgeNote(
            id = "same-note",
            text = "旧知识",
            sourceMessageIds = listOf(originalUser.id),
            sourceHash = ContextEngine.sourceHash(listOf(originalUser))
        )
        val original = Chat(
            id = "story",
            messages = listOf(originalUser, removedReply),
            summaries = listOf(originalSummary),
            activeSummaryId = originalSummary.id,
            knowledgeNotes = listOf(originalNote)
        )
        val editedUser = originalUser.copy(content = "修复后更正的人物关系")
        val continuedReply = Message(id = "a2", role = "assistant", content = "后来剧情")
        val currentSummary = originalSummary.copy(
            text = "更正后的摘要",
            sourceHash = ContextEngine.sourceHash(listOf(editedUser))
        )
        val currentNote = originalNote.copy(
            text = "更正后的知识",
            sourceHash = ContextEngine.sourceHash(listOf(editedUser))
        )
        val current = original.copy(
            messages = listOf(editedUser, continuedReply),
            summaries = listOf(currentSummary),
            knowledgeNotes = listOf(currentNote)
        )

        val restored = ConversationRepair.restore(original, current)

        assertEquals(listOf("修复后更正的人物关系", "误删剧情", "后来剧情"), restored.messages.map { it.content })
        assertEquals("更正后的摘要", restored.summaries.single().text)
        assertEquals(currentSummary.sourceHash, restored.summaries.single().sourceHash)
        assertEquals("更正后的知识", restored.knowledgeNotes.single().text)
        assertEquals(currentNote.sourceHash, restored.knowledgeNotes.single().sourceHash)
    }

    @Test fun invalidCurrentMemoryWithSameIdCannotHideValidSnapshotMemory() {
        val user = Message(id = "u1", role = "user", content = "原始前情")
        val removedReply = Message(id = "a1", role = "assistant", content = "恢复剧情")
        val originalSummary = Summary(
            id = "summary",
            text = "有效旧摘要",
            throughMessageId = removedReply.id,
            sourceHash = ContextEngine.sourceHash(listOf(user, removedReply))
        )
        val originalNote = KnowledgeNote(
            id = "note",
            text = "有效旧知识",
            sourceMessageIds = listOf(removedReply.id),
            sourceHash = ContextEngine.sourceHash(listOf(removedReply))
        )
        val original = Chat(
            id = "story",
            messages = listOf(user, removedReply),
            summaries = listOf(originalSummary),
            activeSummaryId = originalSummary.id,
            knowledgeNotes = listOf(originalNote)
        )
        val current = original.copy(
            messages = listOf(user),
            summaries = listOf(originalSummary.copy(text = "失效新摘要", sourceHash = "stale")),
            knowledgeNotes = listOf(originalNote.copy(text = "失效新知识", sourceHash = "stale"))
        )

        val restored = ConversationRepair.restore(original, current)

        assertEquals("有效旧摘要", restored.summaries.single().text)
        assertEquals("summary", restored.activeSummaryId)
        assertEquals("有效旧知识", restored.knowledgeNotes.single().text)
    }

    @Test fun restoringSnapshotDropsInvalidPostRepairMemoryInsteadOfBlessingIt() {
        val originalUser = Message(id = "u1", role = "user", content = "原始前情")
        val removedReply = Message(id = "a1", role = "assistant", content = "被误删的剧情")
        val original = Chat(id = "story", messages = listOf(originalUser, removedReply))
        val continuedUser = Message(id = "u2", role = "user", content = "后来继续")
        val continuedReply = Message(id = "a2", role = "assistant", content = "后来生成的剧情")
        val current = original.copy(
            messages = listOf(originalUser, continuedUser, continuedReply),
            summaries = listOf(Summary(id = "bad-summary", throughMessageId = continuedReply.id, sourceHash = "stale")),
            activeSummaryId = "bad-summary",
            knowledgeNotes = listOf(KnowledgeNote(id = "bad-note", sourceMessageIds = listOf(continuedReply.id), sourceHash = "stale"))
        )

        val restored = ConversationRepair.restore(original, current)

        assertTrue(restored.summaries.isEmpty())
        assertTrue(restored.activeSummaryId.isEmpty())
        assertTrue(restored.knowledgeNotes.isEmpty())
    }

    @Test fun removingAnyPartOfToolCallExpandsToTheWholeToolGroup() {
        val user = Message(id = "u", role = "user", content = "查两项资料")
        val call = Message(
            id = "call",
            role = "assistant",
            toolCallsJson = """[{"id":"t1"},{"id":"t2"}]"""
        )
        val firstResult = Message(id = "result-1", role = "tool", content = "第一项", toolCallId = "t1")
        val secondResult = Message(id = "result-2", role = "tool", content = "第二项", toolCallId = "t2")
        val reply = Message(id = "reply", role = "assistant", content = "资料已整理。")
        val chat = Chat(messages = listOf(user, call, firstResult, secondResult, reply))

        assertEquals(
            setOf("call", "result-1", "result-2"),
            ConversationRepair.expandSelection(chat, setOf("result-1"))
        )
        assertEquals(
            listOf("u", "reply"),
            ConversationRepair.remove(chat, setOf("call")).messages.map { it.id }
        )
        assertEquals(
            listOf("u", "reply"),
            ConversationRepair.remove(chat, setOf("result-1")).messages.map { it.id }
        )
    }

    @Test fun repeatedRepairsAddNewMessagesToTheOriginalSnapshot() {
        val originalUser = Message(id = "u1", role = "user", content = "原始前情")
        val firstRemoved = Message(id = "a1", role = "assistant", content = "第一次清理的废文")
        val firstSnapshot = Chat(id = "story", messages = listOf(originalUser, firstRemoved))
        val laterUser = Message(id = "u2", role = "user", content = "修复后继续")
        val laterReply = Message(id = "a2", role = "assistant", content = "第二次清理时误删的剧情")
        val beforeSecondRepair = firstSnapshot.copy(messages = listOf(originalUser, laterUser, laterReply))

        val accumulatedSnapshot = ConversationRepair.extendSnapshot(firstSnapshot, beforeSecondRepair)
        val afterSecondRepair = beforeSecondRepair.copy(messages = listOf(originalUser, laterUser))
        val restored = ConversationRepair.restore(accumulatedSnapshot, afterSecondRepair)

        assertEquals(listOf("u1", "a1", "u2", "a2"), accumulatedSnapshot.messages.map { it.id })
        assertEquals(listOf("u1", "a1", "u2", "a2"), restored.messages.map { it.id })
        assertEquals("第二次清理时误删的剧情", restored.messages.last().content)
    }

    @Test fun snapshotMustShareMessagesAndContainSomethingMissingFromCurrentChat() {
        val kept = Message(id = "kept", role = "user", content = "保留")
        val removed = Message(id = "removed", role = "assistant", content = "已清理")
        val snapshot = Chat(id = "story", messages = listOf(kept, removed))

        assertTrue(ConversationRepair.snapshotAppliesTo(snapshot, Chat(id = "story", messages = listOf(kept))))
        assertFalse(ConversationRepair.snapshotAppliesTo(snapshot, snapshot))
        assertFalse(ConversationRepair.snapshotAppliesTo(snapshot, Chat(id = "story", messages = listOf(Message(id = "other")))))
        assertFalse(ConversationRepair.snapshotAppliesTo(snapshot, Chat(id = "different", messages = listOf(kept))))
    }
}
