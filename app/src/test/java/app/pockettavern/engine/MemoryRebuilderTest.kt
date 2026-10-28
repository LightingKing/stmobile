package app.pockettavern.engine

import app.pockettavern.data.DataCodec
import app.pockettavern.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryRebuilderTest {
    @Test fun fullRebuildReadsOriginalTextAndPreservesConversationAndOldSummaryVersions() = runBlocking {
        val early = Message(id = "early", content = "林岚是周行的姐姐。")
        val recent = Message(id = "recent", role = "assistant", content = "两人已经来到港口。")
        val oldSummary = Summary(id = "old-summary", text = "过时的关系摘要")
        val oldNote = KnowledgeNote(id = "old-note", text = "过时的知识笔记")
        val chat = Chat(
            id = "chat", title = "继续旅程", messages = listOf(early, recent),
            summaries = listOf(oldSummary), activeSummaryId = oldSummary.id,
            knowledgeNotes = listOf(oldNote), skillIds = listOf("skill"), presetId = "roleplay"
        )
        val snapshot = MemoryRebuilder.snapshot(chat)
        val inputs = mutableListOf<String>()
        val completed = HistoryCompressor.compress(snapshot, 4096, "") { source ->
            inputs += source
            compactReply(source, "林岚是周行的姐姐，两人已经来到港口。", finishReason = "stop")
        }
        val result = MemoryRebuilder.commit(chat, snapshot, completed, "summary-model")

        assertTrue(inputs.joinToString().contains(early.content))
        assertTrue(inputs.joinToString().contains(recent.content))
        assertFalse(inputs.joinToString().contains("过时"))
        assertEquals(chat.messages, result.messages)
        assertEquals(chat.id, result.id)
        assertEquals(chat.title, result.title)
        assertEquals(chat.skillIds, result.skillIds)
        assertEquals(chat.presetId, result.presetId)
        assertEquals(oldSummary, result.summaries.first())
        assertEquals(2, result.summaries.size)
        assertEquals(result.summaries.last().id, result.activeSummaryId)
        assertEquals(recent.id, result.summaries.last().throughMessageId)
        assertEquals(snapshot.sourceHash, result.summaries.last().sourceHash)
        assertEquals("summary-model", result.summaries.last().modelId)
        val scope = result.summaries.last().compression!!
        assertEquals("FULL_REBUILD", scope.reason)
        assertEquals("", scope.baseSummaryId)
        assertEquals(completed.requestCount, scope.segmentCount)
        assertEquals(completed.requestCount, scope.requestCount)
        assertTrue(CompressionScope.describe(result, scope).contains("从全部原文重建"))
        assertTrue(result.knowledgeNotes.none { it.id == oldNote.id })
        assertTrue(result.knowledgeNotes.all { it.modelId == "summary-model" })
        assertEquals(result.knowledgeNotes, KnowledgeVault.validNotes(result))
    }

    @Test fun textAttachmentAloneCanBeRebuiltAndTheWholeAttachmentReachesTheModel() = runBlocking {
        val text = "林岚和周行是姐弟，银钥匙已归还。"
        val source = Message(id = "attached", attachments = listOf(Attachment(name = "剧情.md", mime = "text/markdown", dataUrl = text)))
        val chat = Chat(messages = listOf(source))
        val snapshot = MemoryRebuilder.snapshot(chat)
        var input = ""
        val completed = HistoryCompressor.compress(snapshot, 4096, "") {
            input = it
            compactReply(it, text)
        }
        val result = MemoryRebuilder.commit(chat, snapshot, completed, "model")
        assertTrue(input.contains(text))
        assertEquals(listOf(source), result.messages)
        assertTrue(result.knowledgeNotes.all { it.sourceMessageIds == listOf(source.id) })
        assertEquals(result.knowledgeNotes, KnowledgeVault.validNotes(result))
    }

    @Test fun anEmptyOrMediaOnlyConversationIsRejectedBeforeARequest() {
        val candidates = listOf(
            Chat(),
            Chat(messages = listOf(Message())),
            Chat(messages = listOf(Message(attachments = listOf(Attachment(mime = "image/png", dataUrl = "data:image/png;base64,aW1hZ2U=")))))
        )
        candidates.forEach { assertTrue(runCatching { MemoryRebuilder.snapshot(it) }.isFailure) }
    }

    @Test fun duplicateOrBlankMessageIdsAreRejectedBeforeARebuildRequest() = runBlocking {
        val first = Message(id = "first", content = "第一条原文", createdAt = 1L)
        val cases = listOf(
            listOf(first, first.copy()),
            listOf(first, first.copy(content = "相同编号下的另一条原文")),
            listOf(first, Message(id = "", content = "空编号原文")),
            listOf(first, Message(id = " \t", content = "空白编号原文"))
        )
        cases.forEach { messages ->
            val chat = Chat(messages = messages, summaries = listOf(Summary(id = "old", text = "原摘要")), activeSummaryId = "old")
            var requests = 0
            val failure = runCatching {
                HistoryCompressor.compress(MemoryRebuilder.snapshot(chat), 32768, "") {
                    requests++
                    compactReply(it, "分段记忆", finishReason = "stop")
                }
            }.exceptionOrNull()
            assertNotNull("Invalid message identities must stop before a paid request", failure)
            assertTrue(failure?.message.orEmpty().contains("消息 ID"))
            assertEquals(0, requests)
            assertEquals(messages, chat.messages)
            assertEquals("old", chat.activeSummaryId)
        }
    }

    @Test fun commitRejectsAmbiguousIdsEvenWhenGivenAPreviouslyCreatedSnapshot() = runBlocking {
        val originals = (1..300).map { Message(id = "message-$it", content = "原文 $it", createdAt = it.toLong()) }
        val cases = listOf(
            originals + originals[18].copy(),
            listOf(Message(id = "", content = "旧快照中的空编号原文", createdAt = 1L))
        )
        cases.forEach { messages ->
            val chat = Chat(messages = messages, summaries = listOf(Summary(id = "old", text = "原摘要")), activeSummaryId = "old")
            // Bypass snapshot validation to represent a snapshot created before the guard existed.
            val snapshot = CompressionSlice(messages, messages.last().id, ContextEngine.sourceHash(messages))
            val completed = HistoryCompression("所有分段已整理", listOf(KnowledgeNote(text = "快照", sourceMessageIds = messages.map { it.id }, sourceHash = snapshot.sourceHash)))
            val failure = runCatching { MemoryRebuilder.commit(chat, snapshot, completed, "model") }.exceptionOrNull()
            assertNotNull("A successful commit must never create a summary ending at the first duplicate ID", failure)
            assertTrue(failure?.message.orEmpty().contains("消息 ID"))
            assertEquals(messages, chat.messages)
            assertEquals("old", chat.activeSummaryId)
            assertEquals(1, chat.summaries.size)
        }
    }

    @Test fun savedRebuildCanBeVerifiedAfterAStorageRoundTrip() = runBlocking {
        val messages = (1..30).map { Message(id = "message-$it", content = "原文 $it", createdAt = it.toLong()) }
        val old = Summary(id = "old", text = "此前摘要", throughMessageId = messages[18].id,
            sourceHash = ContextEngine.sourceHash(messages.take(19)))
        val chat = Chat(messages = messages, summaries = listOf(old), activeSummaryId = old.id)
        val snapshot = MemoryRebuilder.snapshot(chat)
        val completed = HistoryCompressor.compress(snapshot, 32768, "") {
            compactReply(it, "完整分段记忆", finishReason = "stop")
        }
        val result = MemoryRebuilder.commit(chat, snapshot, completed, "summary-model")
        val saved = DataCodec.decode(DataCodec.encode(AppData(chats = listOf(result)))).chats.single()

        MemoryRebuilder.verifyApplied(saved, result.activeSummaryId, messages.size)
        MemoryRebuilder.verifyApplied(saved, result.activeSummaryId, messages.size, result.knowledgeNotes.map { it.id })
        assertNotEquals(old.id, saved.activeSummaryId)
        assertEquals(messages.last().id, saved.summaries.last().throughMessageId)
        assertEquals(messages, saved.messages)
        assertEquals(old, saved.summaries.first())
    }

    @Test fun verificationRejectsAnOldInactiveMissingOrChangedSavedResult() {
        val messages = (1..30).map { Message(id = "message-$it", content = "原文 $it", createdAt = it.toLong()) }
        val old = Summary(id = "old", text = "旧摘要", throughMessageId = messages[18].id,
            sourceHash = ContextEngine.sourceHash(messages.take(19)))
        val fresh = Summary(id = "fresh", text = "新摘要", throughMessageId = messages.last().id,
            sourceHash = ContextEngine.sourceHash(messages))
        val chat = Chat(messages = messages, summaries = listOf(old, fresh), activeSummaryId = fresh.id)
        val invalid = listOf(
            chat.copy(activeSummaryId = old.id),
            chat.copy(summaries = listOf(old)),
            chat.copy(summaries = listOf(old, fresh, fresh.copy())),
            chat.copy(summaries = listOf(old, fresh.copy(text = ""))),
            chat.copy(summaries = listOf(old, fresh.copy(throughMessageId = old.throughMessageId))),
            chat.copy(summaries = listOf(old, fresh.copy(sourceHash = ""))),
            chat.copy(messages = messages.dropLast(1)),
            chat.copy(messages = messages + Message(id = "later", content = "新增原文")),
            chat.copy(messages = messages.mapIndexed { index, message -> if (index == 0) message.copy(content = "原文已编辑") else message }),
            chat.copy(messages = messages.dropLast(1) + messages[18]),
            chat.copy(messages = messages.dropLast(1) + messages.last().copy(id = " "))
        )
        invalid.forEachIndexed { index, saved ->
            val failure = runCatching { MemoryRebuilder.verifyApplied(saved, fresh.id, messages.size) }.exceptionOrNull()
            assertNotNull("Invalid saved result $index must not report success", failure)
            assertTrue("Verification errors need a readable explanation", failure?.message.orEmpty().isNotBlank())
        }
        listOf("", " ", "missing").forEach { id ->
            assertTrue(runCatching { MemoryRebuilder.verifyApplied(chat, id, messages.size) }.isFailure)
        }
        listOf(-1, 0, messages.size - 1, messages.size + 1).forEach { count ->
            assertTrue(runCatching { MemoryRebuilder.verifyApplied(chat, fresh.id, count) }.isFailure)
        }
        assertTrue(runCatching { MemoryRebuilder.verifyApplied(Chat(), "missing", 0) }.isFailure)
    }

    @Test fun verificationRejectsMissingSavedKnowledgeNotesEvenWhenSummaryIsCorrect() {
        val messages = listOf(Message(id = "first", content = "第一条原文"), Message(id = "last", content = "第二条原文"))
        val summary = Summary(id = "rebuilt", text = "完整摘要", throughMessageId = messages.last().id,
            sourceHash = ContextEngine.sourceHash(messages))
        val saved = Chat(messages = messages, summaries = listOf(summary), activeSummaryId = summary.id)

        val failure = runCatching { MemoryRebuilder.verifyApplied(saved, summary.id, messages.size) }.exceptionOrNull()
        assertNotNull("A saved summary cannot confirm that its missing knowledge notes were saved", failure)
        assertTrue(failure?.message.orEmpty().isNotBlank())
    }

    @Test fun verificationRejectsDamagedOrIncompleteSavedKnowledgeNotes() {
        val messages = listOf(Message(id = "first", content = "第一条原文"), Message(id = "last", content = "第二条原文"))
        val summary = Summary(id = "rebuilt", text = "完整摘要", throughMessageId = messages.last().id,
            sourceHash = ContextEngine.sourceHash(messages))
        val first = KnowledgeNote(id = "note-first", text = "第一段记忆", sourceMessageIds = listOf(messages.first().id),
            sourceHash = ContextEngine.sourceHash(messages.take(1)))
        val last = KnowledgeNote(id = "note-last", text = "第二段记忆", sourceMessageIds = listOf(messages.last().id),
            sourceHash = ContextEngine.sourceHash(messages.takeLast(1)))
        val saved = Chat(messages = messages, summaries = listOf(summary), activeSummaryId = summary.id, knowledgeNotes = listOf(first, last))
        MemoryRebuilder.verifyApplied(saved, summary.id, messages.size)

        val damaged = listOf(
            listOf(first.copy(text = " "), last),
            listOf(first.copy(sourceHash = "wrong-hash"), last),
            listOf(first.copy(sourceMessageIds = listOf("missing")), last),
            listOf(first.copy(sourceMessageIds = emptyList()), last),
            listOf(first.copy(sourceMessageIds = listOf("first", "first"),
                sourceHash = ContextEngine.sourceHash(listOf(messages.first(), messages.first()))), last),
            listOf(first),
            listOf(first.copy(id = " "), last),
            listOf(first, last.copy(id = first.id))
        )
        damaged.forEachIndexed { index, notes ->
            val failure = runCatching {
                MemoryRebuilder.verifyApplied(saved.copy(knowledgeNotes = notes), summary.id, messages.size)
            }.exceptionOrNull()
            assertNotNull("Damaged or incomplete saved notes $index must not report success", failure)
            assertTrue(failure?.message.orEmpty().isNotBlank())
        }

        val expectedIds = listOf(first.id, last.id)
        MemoryRebuilder.verifyApplied(saved, summary.id, messages.size, expectedIds)
        listOf(emptyList(), expectedIds.reversed(), expectedIds + "missing", listOf("older-first", "older-last")).forEach { expected ->
            assertTrue("Saved notes must match the exact rebuilt note IDs and order",
                runCatching { MemoryRebuilder.verifyApplied(saved, summary.id, messages.size, expected) }.isFailure)
        }
        val replaced = saved.copy(knowledgeNotes = listOf(first.copy(id = "older-first"), last.copy(id = "older-last")))
        assertTrue(runCatching { MemoryRebuilder.verifyApplied(replaced, summary.id, messages.size, expectedIds) }.isFailure)
    }

    @Test fun changesToOriginalTextOrderAttachmentsOrMessageCountRejectTheCommit() = runBlocking {
        val early = Message(id = "early", content = "林岚是周行的姐姐。")
        val later = Message(id = "later", content = "银钥匙已归还。")
        val chat = Chat(messages = listOf(early, later), summaries = listOf(Summary(id = "old", text = "旧摘要")), activeSummaryId = "old")
        val snapshot = MemoryRebuilder.snapshot(chat)
        val completed = HistoryCompressor.compress(snapshot, 4096, "") {
            compactReply(it, "完整摘要", finishReason = "stop")
        }
        val changed = listOf(
            chat.copy(messages = listOf(early.copy(content = "新的关系"), later)),
            chat.copy(messages = listOf(later, early)),
            chat.copy(messages = listOf(early.copy(attachments = listOf(Attachment(mime = "text/plain", dataUrl = "新的附件"))), later)),
            chat.copy(messages = chat.messages + Message(id = "new", content = "新的剧情")),
            chat.copy(messages = listOf(early))
        )
        changed.forEach { current ->
            assertTrue(runCatching { MemoryRebuilder.commit(current, snapshot, completed, "model") }.isFailure)
            assertEquals("old", current.activeSummaryId)
        }
    }

    @Test fun missingOrUnverifiedKnowledgeSegmentsCannotClaimACompleteRebuild() {
        val first = Message(id = "first", content = "林岚是周行的姐姐。")
        val second = Message(id = "second", content = "他们已经抵达港口。")
        val chat = Chat(messages = listOf(first, second))
        val snapshot = MemoryRebuilder.snapshot(chat)
        val note = KnowledgeNote(text = "姐弟关系", sourceMessageIds = listOf(first.id), sourceHash = ContextEngine.sourceHash(listOf(first)))
        val candidates = listOf(
            HistoryCompression("摘要", emptyList()),
            HistoryCompression("摘要", listOf(note)),
            HistoryCompression("摘要", listOf(note.copy(sourceMessageIds = listOf(first.id, second.id)))),
            HistoryCompression("", listOf(note))
        )
        candidates.forEach { assertTrue(runCatching { MemoryRebuilder.commit(chat, snapshot, it, "model") }.isFailure) }
    }

    @Test fun rebuildingHonorsTheConfiguredWindowAndRejectsAnUnusableWindowBeforeCallingAModel() = runBlocking {
        assertEquals(8192, MemoryRebuilder.effectiveWindow(32768, 8192))
        assertEquals(4096, MemoryRebuilder.effectiveWindow(4096, 8192))
        assertEquals(32768, MemoryRebuilder.effectiveWindow(0, 0))
        assertEquals(8192, MemoryRebuilder.effectiveWindow(0, 8192))
        val snapshot = MemoryRebuilder.snapshot(Chat(messages = listOf(Message(content = "已有剧情"))))
        var called = false
        val failure = runCatching {
            HistoryCompressor.compress(snapshot, MemoryRebuilder.effectiveWindow(32768, 1024), "") {
                called = true
                compactReply(it, "摘要", finishReason = "stop")
            }
        }
        assertTrue(failure.isFailure)
        assertFalse(called)
    }

    @Test fun everySegmentFitsTheSmallerConfiguredWindow() = runBlocking {
        val snapshot = MemoryRebuilder.snapshot(Chat(messages = listOf(Message(content = "银钥匙已归还，林岚仍然记得约定。".repeat(1800)))))
        val window = MemoryRebuilder.effectiveWindow(32768, 4096)
        var calls = 0
        HistoryCompressor.compress(snapshot, window, "准确保留关系") { source ->
            calls++
            val input = ContextEngine.estimateTokens(HistoryCompressor.GUIDE + "准确保留关系" + source)
            assertTrue(input + HistoryCompressor.outputLimit(window) + 512 <= window)
            compactReply(source, "完整分段", finishReason = "stop")
        }
        assertTrue(calls > 1)
    }
}
