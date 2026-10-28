package app.pockettavern.engine

import app.pockettavern.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryContinuityCompressionTest {
    @Test fun rebuildKeepsFullSourceAuditWithoutSendingExcludedTextOrAttachmentsToTheModel() = runBlocking {
        val rejected = Message(id = "rejected", role = "assistant", content = "废稿暗号：黑森林。", reasoning = "废稿推理暗号",
            contextExcluded = true, attachments = listOf(Attachment(mime = "text/plain", dataUrl = "废稿附件暗号")))
        val correction = Message(id = "correction", content = "更正：林岚仍在港口，尚未离开。", intent = "correction")
        val accepted = Message(id = "accepted", role = "assistant", content = "林岚把钥匙还给周行。",
            reasoning = "推理假设：钥匙已经融化。", toolCallsJson = """[{"arguments":"工具猜测：林岚已经失踪"}]""")
        val chat = Chat(messages = listOf(rejected, correction, accepted, rejected.copy(id = "rejected-tail")))
        val snapshot = MemoryRebuilder.snapshot(chat)
        val sent = mutableListOf<String>()
        val result = HistoryCompressor.compress(snapshot, 8192, "") {
            sent += it
            compactReply(it, "## 作者更正\n林岚尚未离开港口。\n## 当前状态\n钥匙已归还周行。", finishReason = "stop")
        }
        assertFalse(sent.joinToString().contains("废稿"))
        assertFalse(sent.joinToString().contains("黑森林"))
        assertFalse(sent.joinToString().contains("钥匙已经融化"))
        assertFalse(sent.joinToString().contains("林岚已经失踪"))
        assertTrue(sent.joinToString().contains("intent=correction"))
        assertTrue(sent.joinToString().contains(accepted.content))
        assertEquals(chat.messages.map { it.id }.toSet(), result.notes.flatMap { it.sourceMessageIds }.toSet())
        val rebuilt = MemoryRebuilder.commit(chat, snapshot, result, "fake-summary-model")
        MemoryRebuilder.verifyApplied(rebuilt, rebuilt.activeSummaryId, chat.messages.size)
        assertEquals(chat.messages, rebuilt.messages)
        assertTrue(KnowledgeVault.search(rebuilt, "黑森林").isEmpty())
    }

    @Test fun entirelyExcludedSliceCanBeCoveredWithoutCallingAModelOrReintroducingItsText() = runBlocking {
        val message = Message(id = "discarded", content = "已经否决的冒险剧情", contextExcluded = true)
        val slice = CompressionSlice(listOf(message), message.id, ContextEngine.sourceHash(listOf(message)))
        var called = false
        val result = HistoryCompressor.compress(slice, 8192, "") {
            called = true
            error("Excluded source must not trigger a model request")
        }
        assertFalse(called)
        assertEquals(listOf(message.id), result.notes.single().sourceMessageIds)
        assertFalse(result.summaryText.contains(message.content))
        assertTrue(KnowledgeVault.search(Chat(messages = listOf(message), knowledgeNotes = result.notes), "").isEmpty())
        assertTrue(runCatching { MemoryRebuilder.snapshot(Chat(messages = listOf(message))) }.isFailure)
    }

    @Test fun excludingOrReclassifyingSourceDuringRebuildCannotSilentlyCommitStaleMemory() = runBlocking {
        val source = Message(id = "source", content = "林岚还在港口。")
        val chat = Chat(messages = listOf(source))
        val snapshot = MemoryRebuilder.snapshot(chat)
        val result = HistoryCompressor.compress(snapshot, 8192, "") {
            compactReply(it, "林岚还在港口。", finishReason = "stop")
        }
        for (changed in listOf(source.copy(contextExcluded = true), source.copy(intent = "correction"))) {
            assertTrue(runCatching { MemoryRebuilder.commit(chat.copy(messages = listOf(changed)), snapshot, result, "model") }.isFailure)
        }
    }

    @Test fun overviewRetainsRecentStateChangesEvenWhenLastSegmentIsAboutAnotherSubject() {
        val first = Message(id = "first", content = "林岚升级。")
        val second = Message(id = "second", content = "周行拿到钥匙。")
        val third = Message(id = "third", content = "车队离开港口。")
        fun note(id: String, message: Message, state: String) = KnowledgeNote(id = id,
            text = "## 人物关系\n队友仍在一起。\n## 当前状态\n$state", sourceMessageIds = listOf(message.id),
            sourceHash = ContextEngine.sourceHash(listOf(message)))
        val notes = listOf(note("upgrade", first, "林岚已完成二级升级。"), note("key", second, "钥匙由周行保管。"),
            note("location", third, "车队已离开港口。"))
        val chat = Chat(messages = listOf(first, second, third), knowledgeNotes = notes.reversed())
        val overview = HistoryCompressor.overview(chat)
        assertTrue(overview.contains("林岚已完成二级升级"))
        assertTrue(overview.contains("钥匙由周行保管"))
        assertTrue(overview.contains("车队已离开港口"))
        assertTrue(ContextEngine.estimateTokens(overview) <= 2400)
    }
}
