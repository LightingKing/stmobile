package app.pockettavern.engine

import app.pockettavern.data.DataCodec
import app.pockettavern.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IncrementalMemoryTest {
    @Test fun threeSavedCompressionsOnlySummarizeNewSourceAndRetainEarlierNotes() = runBlocking {
        val data = AppData(
            providers = listOf(ProviderConfig(id = "p", models = listOf(ModelInfo(id = "m", contextTokens = 24_000)))),
            presets = listOf(Preset(id = "preset", systemPrompt = "保留已确认剧情。", maxOutput = 512)),
            settings = AppSettings(keepRecentTurns = 2)
        )
        var chat = Chat(id = "chat", providerId = "p", modelId = "m", presetId = "preset")
        val alreadySummarized = linkedMapOf<String, Message>()
        val firstNotes = mutableListOf<KnowledgeNote>()
        val marker = { message: Message -> "原文标记[${message.id}]" }
        repeat(3) { round ->
            val newMessages = (1..10).flatMap { turn ->
                val userId = "r${round + 1}-u$turn"
                val answerId = "r${round + 1}-a$turn"
                listOf(
                    Message(id = userId, content = "原文标记[$userId] 请记录这次行程。"),
                    Message(id = answerId, role = "assistant", content =
                        "原文标记[$answerId] 第 ${round + 1} 批已确认事实。" + "旅队核对港口位置。".repeat(160))
                )
            }
            chat = chat.copy(messages = chat.messages + newMessages + Message(id = "r${round + 1}-tail", content = "继续"))
            val before = ContextEngine.build(data, chat)
            assertTrue("round ${round + 1} fixture must trigger compression", before.needsCompression)
            val slice = ContextEngine.compressionSlice(data, chat)!!
            assertTrue("a later increment must not select an earlier summarized message", slice.messages.none { it.id in alreadySummarized })
            val inputs = mutableListOf<String>()
            val completed = HistoryCompressor.compress(slice, 8192, "") { input ->
                inputs += input
                compactReply(input, "## 人物关系\n第 ${round + 1} 批关系锚点。\n## 当前状态\n第 ${round + 1} 批行程已确认。", finishReason = "stop")
            }
            alreadySummarized.values.forEach { old ->
                assertFalse("round ${round + 1} reread ${old.id}", inputs.any { it.contains(marker(old)) })
            }
            val oldNotes = chat.knowledgeNotes.toList()
            val oldMessages = chat.messages.toList()
            chat = IncrementalMemory.commit(chat, slice, completed, "summary-model")
            chat = DataCodec.decode(DataCodec.encode(data.copy(chats = listOf(chat)))).chats.single()
            assertEquals(oldMessages, chat.messages)
            assertEquals("standard guides must not produce redundant inherited notes", oldNotes.size + completed.notes.size, chat.knowledgeNotes.size)
            assertEquals(oldNotes, chat.knowledgeNotes.take(oldNotes.size))
            assertEquals(round + 1, chat.summaries.size)
            assertEquals(chat.summaries.last().id, chat.activeSummaryId)
            assertEquals(slice.throughMessageId, chat.summaries.last().throughMessageId)
            assertTrue(ContextEngine.build(data, chat).messagesJson.contains("历史摘要"))
            alreadySummarized.putAll(slice.messages.associateBy { it.id })
            if (round == 0) firstNotes += chat.knowledgeNotes
        }
        assertTrue(firstNotes.all { it in KnowledgeVault.validNotes(chat) })
        assertTrue(KnowledgeVault.search(chat, "第 1 批关系锚点", 20).any { hit -> firstNotes.any { hit.id == "note:${it.id}" } })
    }

    @Test fun legacySummaryWithoutNotesIsKeptVerbatimAndSearchableWithoutAnotherModelRequest() = runBlocking {
        val old = Message(id = "old", content = "早期人物原文。")
        val recent = Message(id = "recent", role = "assistant", content = "最近已经抵达码头。")
        val legacy = Summary(id = "legacy", text = "林岚是周行的姐姐，铜钥匙已归还。", throughMessageId = old.id,
            sourceHash = ContextEngine.sourceHash(listOf(old)), modelId = "old-model", createdAt = 7L)
        val current = Chat(messages = listOf(old, recent), summaries = listOf(legacy), activeSummaryId = legacy.id)
        val slice = CompressionSlice(listOf(recent), recent.id, ContextEngine.sourceHash(current.messages), previousSummary = legacy.text)
        var calls = 0
        val completed = HistoryCompressor.compress(slice, 8192, "") { input ->
            calls++
            assertFalse(input.contains(old.content))
            assertEquals(legacy.text, org.json.JSONObject(input).getString("previousSummary"))
            compactReply(input, legacy.text + "\n## 当前状态\n最近已经抵达码头。", text = "最近已经抵达码头。")
        }
        val saved = IncrementalMemory.commit(current, slice, completed, "new-model")
        assertEquals(1, calls)
        val preserved = saved.knowledgeNotes.first()
        assertEquals(legacy.text, preserved.text)
        assertEquals(listOf(old.id), preserved.sourceMessageIds)
        assertEquals(legacy.sourceHash, preserved.sourceHash)
        assertEquals(legacy.modelId, preserved.modelId)
        assertEquals(legacy.createdAt, preserved.createdAt)
        assertTrue(saved.summaries.last().text.contains("林岚是周行的姐姐"))
        assertTrue(KnowledgeVault.search(saved, "林岚 周行 姐姐").any { it.id == "note:${preserved.id}" })
        assertEquals(listOf(old, recent), saved.messages)
    }

    @Test fun anEditedSummaryIsRetainedBasedOnExactContentAndNotItsStandardHeading() = runBlocking {
        val old = Message(id = "old", content = "队伍来到码头。")
        val note = KnowledgeNote(text = "## 当前状态\n队伍来到码头。", sourceMessageIds = listOf(old.id), sourceHash = ContextEngine.sourceHash(listOf(old)))
        val initial = Chat(messages = listOf(old), knowledgeNotes = listOf(note))
        val edited = Summary(id = "edited", text = HistoryCompressor.overview(initial) + "\n手动补充：林岚和周行是姐弟。",
            throughMessageId = old.id, sourceHash = ContextEngine.sourceHash(initial.messages))
        val recent = Message(id = "recent", content = "现在登船。")
        val current = initial.copy(messages = initial.messages + recent, summaries = listOf(edited), activeSummaryId = edited.id)
        val slice = CompressionSlice(listOf(recent), recent.id, ContextEngine.sourceHash(current.messages), previousSummary = edited.text)
        val completed = HistoryCompressor.compress(slice, 8192, "") { compactReply(it, "## 当前状态\n队伍已登船。", finishReason = "stop") }
        val saved = IncrementalMemory.commit(current, slice, completed, "model")
        assertEquals(note, saved.knowledgeNotes.first())
        assertEquals(edited.text, saved.knowledgeNotes[1].text)
        assertTrue(KnowledgeVault.search(saved, "手动补充 林岚 周行", 20).any { it.id == "note:${saved.knowledgeNotes[1].id}" })

        // Re-selecting the same edited version must not duplicate its preserved copy.
        val selectedAgain = saved.copy(activeSummaryId = edited.id)
        val duplicateAttempt = completed.copy(notes = completed.notes.map { it.copy(id = newId()) })
        val next = IncrementalMemory.commit(selectedAgain, slice, duplicateAttempt, "model")
        assertEquals(1, next.knowledgeNotes.count { it.text == edited.text && it.sourceHash == edited.sourceHash })
    }

    @Test fun sourceChangesAndIncompleteResultsCannotReplaceCurrentMemory() = runBlocking {
        val old = Message(id = "old", content = "已确认的早期关系。")
        val recent = Message(id = "recent", content = "新增原文。")
        val tail = Message(id = "tail", content = "继续。")
        val chat = Chat(messages = listOf(old, recent, tail))
        val slice = CompressionSlice(listOf(old, recent), recent.id, ContextEngine.sourceHash(listOf(old, recent)))
        val completed = HistoryCompressor.compress(slice, 8192, "") { compactReply(it, "有效分段记忆。", finishReason = "stop") }
        val changed = chat.copy(messages = listOf(old.copy(content = "编辑过的早期关系。"), recent, tail))
        assertTrue(runCatching { IncrementalMemory.commit(changed, slice, completed, "model") }.isFailure)
        assertTrue(runCatching { IncrementalMemory.commit(chat, slice, completed.copy(notes = emptyList()), "model") }.isFailure)
        val incompleteNote = KnowledgeNote(text = "只涵盖第一条。", sourceMessageIds = listOf(old.id), sourceHash = ContextEngine.sourceHash(listOf(old)))
        assertTrue(runCatching { IncrementalMemory.commit(chat, slice, completed.copy(notes = listOf(incompleteNote)), "model") }.isFailure)
        val incorrectHash = completed.copy(notes = completed.notes.map { it.copy(sourceHash = "wrong") })
        assertTrue(runCatching { IncrementalMemory.commit(chat, slice, incorrectHash, "model") }.isFailure)
        assertTrue(runCatching { IncrementalMemory.commit(chat.copy(messages = chat.messages + old), slice, completed, "model") }.isFailure)

        val newTail = tail.copy(content = "只改动尚未压缩的尾部。")
        val appended = Message(id = "after", content = "后续新增。")
        val valid = chat.copy(messages = listOf(old, recent, newTail, appended))
        assertEquals(valid.messages, IncrementalMemory.commit(valid, slice, completed, "model").messages)
    }

    @Test fun changedPreviousSummaryCannotBeSilentlyReplacedWhileTheSourceRemainsTheSame() = runBlocking {
        val old = Message(id = "old", content = "旧事实。")
        val recent = Message(id = "recent", content = "新事实。")
        val previous = Summary(id = "previous", text = "旧摘要事实。", throughMessageId = old.id, sourceHash = ContextEngine.sourceHash(listOf(old)))
        val chat = Chat(messages = listOf(old, recent), summaries = listOf(previous.copy(text = "生成期间修改的摘要。")), activeSummaryId = previous.id)
        val slice = CompressionSlice(listOf(recent), recent.id, ContextEngine.sourceHash(chat.messages), previousSummary = previous.text)
        val completed = HistoryCompressor.compress(slice, 8192, "") { compactReply(it, "新增分段。", finishReason = "stop") }
        assertTrue(runCatching { IncrementalMemory.commit(chat, slice, completed, "model") }.isFailure)
        assertEquals("生成期间修改的摘要。", chat.summaries.single().text)
        assertTrue(chat.knowledgeNotes.isEmpty())
    }
}
