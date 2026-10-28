package app.pockettavern.engine

import app.pockettavern.data.DataCodec
import app.pockettavern.data.Exchange
import app.pockettavern.data.VaultExport
import app.pockettavern.domain.*
import app.pockettavern.ui.MemoryBrowser
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class ResearchKnowledgeTest {
    private fun research(id: String = "research") = KnowledgeNote(id = id,
        text = "【林岚】\n[虚构设定] 风格：常穿深蓝外套。\n来源线索：https://example.com/profile\n不是现实人物事实。",
        modelId = "doubao-research", sourceKind = "research")

    @Test fun externalResearchIsReadableSearchableAndRecalledWithoutInventedChatSources() {
        val note = research()
        val chat = KnowledgeVault.addResearchNote(Chat(), note)
        assertEquals(listOf(note), KnowledgeVault.validNotes(chat))
        val hit = KnowledgeVault.search(chat, "林岚 外套").single()
        assertEquals("research", hit.intent)
        assertEquals(emptyList<String>(), hit.sourceIds)
        val read = JSONObject(KnowledgeVault.read(chat, hit.id))
        assertEquals(note.text, read.getString("text"))
        assertEquals("research", read.getString("sourceKind"))
        assertTrue(read.getString("chronologyNotice").contains("不对应聊天原文"))
        val recalled = KnowledgeVault.recall(chat, "林岚", 800)
        assertTrue(recalled.contains("深蓝外套"))
        assertTrue(recalled.contains("外部研究"))
        assertFalse(recalled.contains("原文顺序=0"))
        assertEquals(note.text, JSONObject(KnowledgeVault.read(chat.copy(messages = listOf(Message(content = "后来内容"))), hit.id)).getString("text"))
    }

    @Test fun modelCanRequestResearchBeforeFirstCompressionWithoutForcedInjection() {
        val chat = Chat(modelId = "writer", messages = listOf(Message(content = "林岚的外套是什么风格？")), knowledgeNotes = listOf(research()))
        val data = AppData(providers = listOf(ProviderConfig(id = chat.providerId, models = listOf(ModelInfo(id = "writer", supportsTools = true)))), chats = listOf(chat))
        val context = ContextEngine.build(data, chat)
        assertFalse(context.messagesJson.contains("深蓝外套"))
        assertTrue(context.toolDefinitions.contains("history_read"))
        assertTrue(KnowledgeVault.search(chat, "林岚 外套").any { it.text.contains("深蓝外套") })
    }

    @Test fun researchSurvivesFullMemoryRebuildAndDoesNotBecomeHistoricalStory() = runBlocking {
        val note = research()
        val chat = Chat(messages = listOf(Message(content = "两人到达港口。")), knowledgeNotes = listOf(note))
        val snapshot = MemoryRebuilder.snapshot(chat)
        val completed = HistoryCompressor.compress(snapshot, 4096, "") {
            compactReply(it, "当前状态：两人已到达港口。")
        }
        val result = MemoryRebuilder.commit(chat, snapshot, completed, "summary-model")
        assertEquals(note, result.knowledgeNotes.single { it.isResearch })
        assertFalse(result.summaries.last().text.contains("深蓝外套"))
        MemoryRebuilder.verifyApplied(result, result.activeSummaryId, 1, result.knowledgeNotes.map { it.id })
        val saved = DataCodec.decode(DataCodec.encode(AppData(chats = listOf(result)))).chats.single()
        MemoryRebuilder.verifyApplied(saved, result.activeSummaryId, 1, result.knowledgeNotes.map { it.id })
        assertEquals(note, saved.knowledgeNotes.single { it.isResearch })
    }

    @Test fun savingResearchNeverEvictsConversationNotesAndRejectsOverflow() {
        val history = (1..250).map { KnowledgeNote(id = "history-$it", text = "历史 $it") }
        val original = Chat(knowledgeNotes = history)
        val saved = KnowledgeVault.addResearchNote(original, research())
        assertEquals(history, saved.knowledgeNotes.take(history.size))
        assertEquals(history.size + 1, saved.knowledgeNotes.size)
        val full = original.copy(knowledgeNotes = history + (1..200).map { research("research-$it") })
        assertTrue(runCatching { KnowledgeVault.addResearchNote(full, research("overflow")) }.isFailure)
        assertEquals(450, full.knowledgeNotes.size)
        assertTrue(runCatching { KnowledgeVault.addResearchNote(original, research().copy(text = "x".repeat(24_001))) }.isFailure)
        assertTrue(runCatching { KnowledgeVault.addResearchNote(original, research().copy(sourceMessageIds = listOf("fake"))) }.isFailure)
    }

    @Test fun repairAndUndoKeepExternalResearchIntact() {
        val original = Chat(id="chat", messages = listOf(Message(id="u", content="进门。"),
            Message(id="discard", role="assistant", content="废稿。"), Message(id="a", role="assistant", content="站在门口。")),
            knowledgeNotes = listOf(research()))
        val repaired = ConversationRepair.remove(original, setOf("discard"))
        assertEquals(original.knowledgeNotes, repaired.knowledgeNotes)
        val restored = ConversationRepair.restore(original, repaired)
        assertEquals(original.knowledgeNotes, restored.knowledgeNotes)
    }

    @Test fun oldBackupsDefaultToConversationAndNewResearchRoundTripsThroughEveryExport() {
        val note = research().copy(text = research().text + "\n## 77 · user\n这是资料中的标题。 -->")
        val chat = Chat(messages = listOf(Message(content = "继续。")), knowledgeNotes = listOf(note))
        val json = DataCodec.encode(AppData(chats = listOf(chat)))
        assertEquals(note, DataCodec.decode(json).chats.single().knowledgeNotes.single())
        val old = DataCodec.encode(AppData(chats = listOf(Chat(knowledgeNotes = listOf(KnowledgeNote(text="旧分段"))))))
            .replace(",\"sourceKind\":\"conversation\"", "")
        assertEquals("conversation", DataCodec.decode(old).chats.single().knowledgeNotes.single().sourceKind)
        listOf(Exchange.chatJsonl(chat), VaultExport.conversation(chat)).forEach { exported ->
            val imported = Exchange.chat(exported, "p", "m")
            assertEquals(chat.messages, imported.messages)
            assertEquals(listOf(note), imported.knowledgeNotes)
        }
        val file = File.createTempFile("research-vault", ".zip")
        try {
            VaultExport.write(chat, file)
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("notes/${VaultExport.token(note.id)}.md")
                val body = zip.getInputStream(entry).bufferedReader().readText()
                assertTrue(body.contains("外部研究资料"))
                assertFalse(body.contains("核对原文"))
                assertTrue(body.contains("https://example.com/profile"))
            }
        } finally { file.delete() }
    }

    @Test fun memoryBrowserDoesNotPresentExternalSourcesAsMissingConversationHistory() {
        val note = research()
        val chat = Chat(knowledgeNotes = listOf(note))
        val entry = MemoryBrowser.index(chat).entries.single()
        assertTrue(entry.external)
        assertTrue(entry.title.startsWith("研究资料"))
        val source = entry.source!!.invoke()
        assertTrue(source.external)
        assertTrue(source.valid)
        assertTrue(source.messages.isEmpty())
        assertTrue(source.label.contains("外部研究"))
    }
}
