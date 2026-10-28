package app.pockettavern

import app.pockettavern.domain.Chat
import app.pockettavern.domain.CompressionRecord
import app.pockettavern.domain.Attachment
import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.domain.Message
import app.pockettavern.domain.MemoryRebuildState
import app.pockettavern.domain.Summary
import app.pockettavern.engine.ContextEngine
import app.pockettavern.ui.MemoryBrowser
import app.pockettavern.ui.MemoryEntryKind
import app.pockettavern.ui.memoryMatchingChunks
import org.junit.Assert.*
import org.junit.Test

class MemoryBrowserTest {
    private val messages = (1..4).map { Message(id = "m$it", content = "原文 $it", createdAt = it.toLong()) }

    @Test fun oldSummaryDoesNotInventAnIncrementalRangeFromCumulativeCoverage() {
        val summary = Summary(id = "legacy", text = "旧摘要", throughMessageId = "m4", sourceHash = ContextEngine.sourceHash(messages))
        val entry = MemoryBrowser.index(Chat(messages = messages, summaries = listOf(summary))).resolve("0:legacy")!!
        assertEquals("旧版本未记录本次增量范围", entry.metadata)
        assertEquals(4, entry.source!!.invoke().messages.size)
    }

    @Test fun summaryMetadataKeepsNewRangeSeparateFromWholeSourcePrefix() {
        val summary = Summary(id = "incremental", text = "本轮更新后的摘要", throughMessageId = "m4",
            sourceHash = ContextEngine.sourceHash(messages), compression = CompressionRecord(
                baseSummaryId = "earlier", reusedThroughMessageId = "m2", firstMessageId = "m3", lastMessageId = "m4",
                messageCount = 2, estimatedSourceTokens = 300, segmentCount = 1, reason = "ACTIVE"
            ))
        val entry = MemoryBrowser.index(Chat(messages = messages, summaries = listOf(summary))).resolve("0:incremental")!!
        assertTrue(entry.metadata.contains("截至第 2 条"))
        assertTrue(entry.metadata.contains("本次处理原文第 3–4 条"))
        assertTrue(entry.metadata.contains("本次新增 1 段记忆"))
        assertEquals(listOf(1, 2, 3, 4), entry.source!!.invoke().messages.map { it.number })
    }

    @Test fun browserMarksEffectiveFallbackInsteadOfTheInvalidSavedSelection() {
        val first = Summary(id = "first", text = "第一份摘要", throughMessageId = "m2", sourceHash = ContextEngine.sourceHash(messages.take(2)))
        val second = Summary(id = "second", text = "第二份摘要", throughMessageId = "m4", sourceHash = ContextEngine.sourceHash(messages))
        val chat = Chat(messages = messages.map { if (it.id == "m3") it.copy(content = "修改后的第三条原文") else it },
            summaries = listOf(first, second), activeSummaryId = second.id)
        val index = MemoryBrowser.index(chat)
        assertEquals("当前摘要导览", index.resolve("0:first")!!.title)
        assertTrue(index.resolve("0:first")!!.active)
        assertEquals("原选摘要（已失效）", index.resolve("0:second")!!.title)
        assertFalse(index.resolve("0:second")!!.active)
        val disabled = MemoryBrowser.index(chat.copy(activeSummaryId = ""))
        assertTrue(disabled.entries.all { !it.active && it.title != "当前摘要导览" })
        assertEquals("second", chat.activeSummaryId)
    }

    @Test fun successfulRebuildKeepsItsOwnResultWhenAnOlderSummaryBecomesActive() {
        val old = Summary(id = "old", text = "旧摘要", throughMessageId = "m1", sourceHash = ContextEngine.sourceHash(messages.take(1)))
        val rebuilt = Summary(id = "rebuilt", text = "完整重建结果", throughMessageId = "m4", sourceHash = ContextEngine.sourceHash(messages))
        val chat = Chat(id = "chat", messages = messages, summaries = listOf(old, rebuilt), activeSummaryId = rebuilt.id)
        val status = MemoryRebuildState(chatId = chat.id, phase = "success", summaryId = rebuilt.id, sourceMessageCount = messages.size, finishedAt = 1_792_700_180_000)
        val completed = MemoryBrowser.rebuildResult(chat, status)!!
        assertEquals("0:rebuilt", completed.key)
        assertTrue(completed.active)
        assertEquals("记忆重建完成 · 当前正在使用", completed.title)

        val restored = chat.copy(activeSummaryId = old.id)
        val previousResult = MemoryBrowser.rebuildResult(restored, status)!!
        assertEquals(completed.key, previousResult.key)
        assertFalse(previousResult.active)
        assertEquals("上次重建已完成，当前使用另一份摘要", previousResult.title)
        val detail = MemoryBrowser.index(restored).resolve(previousResult.key)!!
        assertEquals(rebuilt.text, detail.text)
        assertEquals("历史摘要导览", detail.title)
        assertEquals(4, detail.source!!.invoke().messages.size)
        assertEquals("old", restored.activeSummaryId)
        assertEquals(1, MemoryBrowser.source(restored, old).messages.size)
    }

    @Test fun completedRebuildDoesNotClaimToBeActiveWhenSummaryIsDisabledOrMissing() {
        val summary = Summary(id = "rebuilt", text = "重建结果")
        val chat = Chat(id = "chat", summaries = listOf(summary), activeSummaryId = "")
        val status = MemoryRebuildState(chatId = chat.id, phase = "success", summaryId = summary.id)
        val disabled = MemoryBrowser.rebuildResult(chat, status)!!
        assertFalse(disabled.active)
        assertEquals("0:rebuilt", disabled.key)
        assertEquals("上次重建已完成，当前未启用摘要", disabled.title)

        val missing = MemoryBrowser.rebuildResult(chat.copy(summaries = emptyList(), activeSummaryId = summary.id), status)!!
        assertFalse(missing.active)
        assertNull(missing.key)
        assertEquals("上次重建已完成，结果摘要已不存在", missing.title)
        val legacy = MemoryBrowser.rebuildResult(chat, status.copy(summaryId = ""))!!
        assertFalse(legacy.active)
        assertNull(legacy.key)
        assertEquals("上次重建已完成，结果记录不完整", legacy.title)
    }

    @Test fun editedOrTruncatedSourcesCannotClaimTheRebuildIsStillUsed() {
        val rebuilt = Summary(id = "rebuilt", text = "完整重建结果", throughMessageId = "m4", sourceHash = ContextEngine.sourceHash(messages))
        val chat = Chat(id = "chat", messages = messages, summaries = listOf(rebuilt), activeSummaryId = rebuilt.id)
        val status = MemoryRebuildState(chatId = chat.id, phase = "success", summaryId = rebuilt.id, sourceMessageCount = messages.size)
        val changed = listOf(
            chat.copy(messages = messages.mapIndexed { index, message -> if (index == 0) message.copy(content = "修改后的原文") else message }),
            chat.copy(messages = messages.dropLast(1)),
            chat.copy(summaries = listOf(rebuilt.copy(throughMessageId = "m1", sourceHash = ContextEngine.sourceHash(messages.take(1)))))
        )
        changed.forEach {
            val result = MemoryBrowser.rebuildResult(it, status)!!
            assertFalse(result.active)
            assertTrue(result.title.contains("失效"))
            assertEquals("0:rebuilt", result.key)
        }
    }

    @Test fun newMessagesDoNotInvalidateTheOriginalRebuiltPrefix() {
        val rebuilt = Summary(id = "rebuilt", text = "完整重建结果", throughMessageId = "m4", sourceHash = ContextEngine.sourceHash(messages))
        val chat = Chat(id = "chat", messages = messages + Message(id = "new", content = "继续当前剧情"), summaries = listOf(rebuilt), activeSummaryId = rebuilt.id)
        val status = MemoryRebuildState(chatId = chat.id, phase = "success", summaryId = rebuilt.id, sourceMessageCount = messages.size)
        val result = MemoryBrowser.rebuildResult(chat, status)!!
        assertTrue(result.active)
        assertEquals("记忆重建完成 · 当前正在使用", result.title)
    }

    @Test fun rebuildResultCannotNavigateToAnotherChatOrAnUnfinishedJob() {
        val chat = Chat(id = "chat", summaries = listOf(Summary(id = "rebuilt")), activeSummaryId = "rebuilt")
        val status = MemoryRebuildState(chatId = chat.id, phase = "success", summaryId = "rebuilt")
        assertNull(MemoryBrowser.rebuildResult(chat, status.copy(chatId = "other-chat")))
        listOf("running", "cancelled", "error").forEach { phase ->
            assertNull(MemoryBrowser.rebuildResult(chat, status.copy(phase = phase)))
        }
    }

    @Test fun summaryResolvesItsWholePrefixWithoutChangingActiveVersion() {
        val summary = Summary(id = "old", throughMessageId = "m3", sourceHash = ContextEngine.sourceHash(messages.take(3)))
        val chat = Chat(messages = messages, summaries = listOf(summary), activeSummaryId = "new")
        val source = MemoryBrowser.source(chat, summary)
        assertTrue(source.valid)
        assertEquals(listOf(1, 2, 3), source.messages.map { it.number })
        assertEquals("第 1–3 条 · 共 3 条消息", source.label)
        assertEquals("new", chat.activeSummaryId)
    }

    @Test fun missingSummaryBoundaryDoesNotGuessASourcePrefix() {
        val source = MemoryBrowser.source(Chat(messages = messages), Summary(throughMessageId = "deleted"))
        assertFalse(source.valid)
        assertTrue(source.messages.isEmpty())
        assertTrue(source.warning.contains("缺失"))
    }

    @Test fun sparseNoteUsesOnlyItsRecordedSourcesAndReadableNumbers() {
        val note = KnowledgeNote(sourceMessageIds = listOf("m1", "m3"), sourceHash = ContextEngine.sourceHash(listOf(messages[0], messages[2])))
        val source = MemoryBrowser.source(Chat(messages = messages), note)
        assertTrue(source.valid)
        assertEquals(listOf(1, 3), source.messages.map { it.number })
        assertEquals("第 1、3 条 · 共 2 条消息", source.label)
    }

    @Test fun deletedOrEditedNoteSourcesRemainVisibleButAreNotClaimedValid() {
        val note = KnowledgeNote(sourceMessageIds = listOf("m1", "m3"), sourceHash = ContextEngine.sourceHash(listOf(messages[0], messages[2])))
        val missing = MemoryBrowser.source(Chat(messages = messages.filterNot { it.id == "m1" }), note)
        assertFalse(missing.valid)
        assertEquals(1, missing.messages.size)
        assertTrue(missing.warning.contains("缺失 1"))
        val edited = MemoryBrowser.source(Chat(messages = messages.map { if (it.id == "m1") it.copy(content = "编辑后的原文") else it }), note)
        assertFalse(edited.valid)
        assertTrue(edited.warning.contains("变动"))
        assertEquals("编辑后的原文", edited.messages.first().message.content)
    }

    @Test fun legacyAndDuplicateSourcesAreExplicitlyUnverifiable() {
        val noHash = MemoryBrowser.source(Chat(messages = messages), Summary(throughMessageId = "m1"))
        assertFalse(noHash.valid)
        assertTrue(noHash.warning.contains("校验"))
        val duplicate = MemoryBrowser.source(Chat(messages = messages), KnowledgeNote(sourceMessageIds = listOf("m1", "m1")))
        assertFalse(duplicate.valid)
        assertEquals(1, duplicate.messages.size)
    }

    @Test fun hugeOriginalIsLosslesslyChunkedIncludingSurrogatePairs() {
        val original = ("人物关系🌙\n".repeat(8_000)) + "最终结尾"
        val chunks = MemoryBrowser.chunks(original, 31)
        assertEquals(original, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 31 })
        assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
        assertEquals("", MemoryBrowser.chunks("").single())
    }

    @Test fun fullTextSearchFindsTheTailAndCrossChunkBoundary() {
        val chunks = listOf("以前的关系与关键", "约定仍然存在", "The END")
        assertEquals(listOf(0, 1), memoryMatchingChunks(chunks, "关键约定"))
        assertEquals(listOf(2), memoryMatchingChunks(chunks, "end"))
        assertEquals(emptyList<Int>(), memoryMatchingChunks(chunks, "不存在的人"))
        assertEquals(listOf(0, 1, 2), memoryMatchingChunks(chunks, ""))
    }

    @Test fun attachmentOnlyOriginalKeepsAllTextWithoutRenderingMediaBase64() {
        val text = "很早的人物关系\n".repeat(600) + "最终附件结尾"
        val encoded = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val original = MemoryBrowser.messageText(Message(attachments = listOf(
            Attachment(name = "记录.md", mime = "text/markdown", dataUrl = "data:text/markdown;base64,$encoded"),
            Attachment(name = "图像.png", mime = "image/png", dataUrl = "data:image/png;base64,IMAGESECRET")
        )))
        assertTrue(original.contains(text))
        assertTrue(original.contains("图像.png"))
        assertFalse(original.contains("IMAGESECRET"))
        assertFalse(original.contains(encoded))
    }

    @Test fun percentEncodedAndLegacyPlainTextAttachmentsAreReadable() {
        val original = MemoryBrowser.messageText(Message(attachments = listOf(
            Attachment(name = "plain", mime = "text/plain", dataUrl = "原样文本+保留"),
            Attachment(name = "encoded", mime = "application/json", dataUrl = "data:application/json,%7B%22name%22%3A%22A+B%22%7D")
        )))
        assertTrue(original.contains("原样文本+保留"))
        assertTrue(original.contains("{\"name\":\"A+B\"}"))
    }

    @Test fun globalSearchFindsAnEarlyPersonOmittedByTheCurrentOverview() {
        val chat = Chat(
            messages = listOf(Message(id = "early", content = "乔宁是早期认识的旧友。")),
            summaries = listOf(Summary(id = "current", text = "最近正在港口找人。")),
            activeSummaryId = "current",
            knowledgeNotes = listOf(KnowledgeNote(id = "earlier-note", text = "乔宁曾交付旧书店的钥匙。"))
        )
        val index = MemoryBrowser.index(chat)
        assertTrue(MemoryBrowser.search(index, "乔宁", section = 0).isEmpty())
        val results = MemoryBrowser.search(index, "乔宁")
        assertEquals(setOf(MemoryEntryKind.NOTE, MemoryEntryKind.ORIGINAL), results.map { it.entry.kind }.toSet())
        assertTrue(results.all { it.excerpt.contains("乔宁") })
        assertEquals("current", chat.activeSummaryId)
    }

    @Test fun globalSearchIncludesTextAttachmentTailsAndHistoricalSummaries() {
        val attachmentText = "历史记录。".repeat(8_000) + "乔宁保留了早期契约。"
        val encoded = java.util.Base64.getEncoder().encodeToString(attachmentText.toByteArray(Charsets.UTF_8))
        val chat = Chat(
            messages = listOf(Message(id = "attachment", content = "请参考附件", attachments = listOf(
                Attachment(name = "完整会话.md", mime = "text/markdown", dataUrl = "data:text/markdown;base64,$encoded")
            ))),
            summaries = listOf(Summary(id = "current", text = "最近正在港口找人。"), Summary(id = "old", text = "乔宁是旧友。")),
            activeSummaryId = "current"
        )
        val index = MemoryBrowser.index(chat)
        val results = MemoryBrowser.search(index, "乔宁")
        assertEquals(setOf("0:old", "2:attachment"), results.map { it.entry.key }.toSet())
        val attachment = results.single { it.entry.kind == MemoryEntryKind.ORIGINAL }
        assertTrue(attachment.excerpt.contains("乔宁"))
        assertTrue(attachment.excerpt.length < 400)
        assertTrue(index.resolve(attachment.entry.key)!!.text.contains(attachmentText))
        assertEquals(1, MemoryBrowser.search(index, "完整会话.md").size)
    }

    @Test fun globalResultsRouteByKindEvenWhenRecordIdsCollide() {
        val chat = Chat(
            messages = listOf(Message(id = "same", content = "乔宁的原文")),
            summaries = listOf(Summary(id = "same", text = "乔宁的旧摘要")),
            knowledgeNotes = listOf(KnowledgeNote(id = "same", text = "乔宁的分段记忆"))
        )
        val index = MemoryBrowser.index(chat)
        val results = MemoryBrowser.search(index, "乔宁")
        assertEquals(3, results.map { it.entry.key }.distinct().size)
        results.forEach { result -> assertSame(result.entry, index.resolve(result.entry.key)) }
        assertEquals("1:same", MemoryBrowser.initialKey(chat, "same", section = 1))
        assertEquals(MemoryEntryKind.NOTE, index.resolve("1:same")?.kind)
        assertNull(index.resolve("2:missing"))
    }
}
