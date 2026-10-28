package app.pockettavern.data

import app.pockettavern.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompactMemoryMigrationTest {
    @Test fun oldBackupsKeepMemoryAndGainIndependentSpendingBudget() {
        val raw = JSONObject(DataCodec.encode(AppData(chats = listOf(Chat(
            messages = listOf(Message(id = "m1", content = "早期人物关系原文")),
            summaries = listOf(Summary(id = "s1", text = "旧摘要", throughMessageId = "m1")),
            knowledgeNotes = listOf(KnowledgeNote(id = "n1", text = "旧关系记忆", sourceMessageIds = listOf("m1")))
        )))))
        raw.getJSONObject("settings").remove("maxContextInputTokens")
        val chat = raw.getJSONArray("chats").getJSONObject(0)
        chat.getJSONArray("summaries").getJSONObject(0).remove("format")
        val note = chat.getJSONArray("knowledgeNotes").getJSONObject(0)
        listOf("title", "kind", "entity", "aliases").forEach(note::remove)
        val migrated = DataCodec.decode(raw.toString())
        assertEquals(65_536, migrated.settings.maxContextInputTokens)
        assertEquals(0, migrated.settings.contextOverride)
        assertEquals("早期人物关系原文", migrated.chats.single().messages.single().content)
        assertEquals("", migrated.chats.single().summaries.single().format)
        assertEquals("旧关系记忆", migrated.chats.single().knowledgeNotes.single().text)
        assertEquals("legacy", migrated.chats.single().knowledgeNotes.single().kind)
        assertTrue(migrated.chats.single().knowledgeNotes.single().aliases.isEmpty())
    }

    @Test fun structuredMemoryAndExplicitUnlimitedBudgetSurviveRestart() {
        val note = KnowledgeNote(title = "归还钥匙", kind = "event", entity = "林宁", aliases = listOf("小林"),
            text = "旧持有人→归还事件→新持有人", sourceMessageIds = listOf("m1"))
        val summary = Summary(text = "当前续聊状态", format = "compact-v1", compression = CompressionRecord(requestCount = 1))
        val original = AppData(settings = AppSettings(maxContextInputTokens = 0), chats = listOf(Chat(
            knowledgeNotes = listOf(note), summaries = listOf(summary)
        )))
        val decoded = DataCodec.decode(DataCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test fun malformedSpendingBudgetDoesNotBecomeUnlimitedThroughCoercion() {
        listOf<Any>(-1, 2_000_001, 1.5, "65536", true, JSONObject()).forEach { invalid ->
            val raw = JSONObject(DataCodec.encode(AppData()))
            raw.getJSONObject("settings").put("maxContextInputTokens", invalid)
            assertTrue("Accepted $invalid", runCatching { DataCodec.decode(raw.toString()) }.isFailure)
        }
    }
}
