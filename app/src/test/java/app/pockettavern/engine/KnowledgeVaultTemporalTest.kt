package app.pockettavern.engine

import app.pockettavern.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KnowledgeVaultTemporalTest {
    @Test fun currentAndEarlierToolPlansAreNotReturnedAsHistoricalEvidence() {
        val source = Message(id = "fact", role = "assistant", content = "林岚把钥匙交还给周行。")
        val calls = """[{"id":"lookup","type":"function","function":{"name":"history_search","arguments":"{\"query\":\"林岚\"}"}}]"""
        val planned = Message(id = "plan", role = "assistant", content = "我先查一下林岚的钥匙、琥珀盒和先前约定。", toolCallsJson = calls)
        val chat = Chat(messages = listOf(source, Message(content = "继续"), planned))
        assertTrue(KnowledgeVault.search(chat, "琥珀盒").isEmpty())
        assertEquals(listOf("message:fact"), KnowledgeVault.search(chat, "林岚 钥匙").map { it.id })
        val later = chat.copy(messages = chat.messages + Message(role = "tool", toolCallId = "lookup", content = "林岚 琥珀盒") + Message(content = "接着写"))
        assertTrue(KnowledgeVault.search(later, "琥珀盒").isEmpty())
        assertEquals(planned, later.messages.single { it.id == "plan" })
    }

    @Test fun attachedSourceOnToolMessageRemainsSearchableAtItsOriginalOffsetWithoutIndexingThePlan() {
        val plan = Message(id = "plan-attachment", role = "assistant", content = "我先查询琥珀盒。".repeat(100),
            reasoning = "等待核对。".repeat(500), toolCallsJson = """[{"id":"lookup","function":{"name":"history_search","arguments":"{}"}}]""",
            attachments = listOf(Attachment(name = "source.txt", mime = "text/plain", dataUrl = "林岚把铜钥匙归还给周行。")))
        val chat = Chat(messages = listOf(plan, Message(content = "继续")))
        assertTrue(KnowledgeVault.search(chat, "琥珀盒").isEmpty())
        val hit = KnowledgeVault.search(chat, "铜钥匙").single()
        val full = HistorySource.summaryText(plan)
        assertEquals(full.indexOf("林岚把铜钥匙"), hit.offset)
        val read = JSONObject(KnowledgeVault.read(chat, hit.id, hit.offset))
        assertEquals("林岚把铜钥匙归还给周行。", read.getString("text"))
        assertFalse(hit.text.contains("我先查询"))
    }

    @Test fun recentUpdateSurvivesHigherScoringOldPassagesAndResultsFollowConversationOrder() {
        val early = (1..8).map { index -> Message(id = "old-$index", role = "assistant",
            content = "林岚正在讨论二级药剂升级方案；林岚当前级别仍然是一级。", createdAt = 10_000L - index) }
        val update = Message(id = "update", role = "assistant", content = "后来林岚已升为二级。", createdAt = 1)
        val chat = Chat(messages = early + update + Message(content = "林岚药剂升级后的当前级别？"))
        val hits = KnowledgeVault.search(chat, "林岚 二级 药剂 升级 当前级别")
        assertEquals(6, hits.size)
        assertTrue(hits.any { it.id == "message:update" && it.text.contains("已升为二级") })
        assertEquals(hits.map { it.sourceEnd }.sorted(), hits.map { it.sourceEnd })
        assertEquals(9, hits.last().sourceEnd)

        val json = JSONArray(KnowledgeVault.searchJson(chat, "林岚 二级 药剂 升级 当前级别"))
        val newest = json.getJSONObject(json.length() - 1)
        assertEquals("message:update", newest.getString("id"))
        assertEquals(9, newest.getInt("sourceStart"))
        assertEquals(9, newest.getInt("sourceEnd"))
        val read = JSONObject(KnowledgeVault.read(chat, "message:old-1"))
        assertEquals(1, read.getInt("sourceEnd"))
        assertTrue(read.getString("chronologyNotice").contains("不证明目前"))
    }

    @Test fun tightAutomaticRecallPreservesTheNewestChangeInsteadOfTruncatingItAtTheEnd() {
        val sources = (1..7).map { index -> Message(id = "earlier-$index", role = "assistant",
            content = "林岚的药剂记录：" + "旧的训练资料。".repeat(50) + "林岚仍然是一级。") }
        val newest = Message(id = "newest", role = "assistant",
            content = "林岚已经完成二级升级，原来一级的记录已过时。")
        val chat = Chat(messages = sources + newest + Message(content = "林岚现在等级是多少？"))
        for (budget in listOf(240, 400, 800, 1800)) {
            val recalled = KnowledgeVault.recall(chat, "林岚 药剂", budget)
            assertTrue("Latest evidence missing at $budget tokens", recalled.contains("已经完成二级升级"))
            assertTrue("Budget $budget exceeded", ContextEngine.estimateTokens(recalled) <= budget)
        }
    }

    @Test fun explicitCorrectionsRemainEvidenceButExcludedDraftsAndTheirNotesCannotReturn() {
        val draft = Message(id = "draft", role = "assistant", content = "林岚已经离开港口。",
            attachments = listOf(Attachment(mime = "text/plain", dataUrl = "废稿中出现唯一线索紫水晶。")))
        val originalNote = KnowledgeNote(id = "old-note", text = draft.content, sourceMessageIds = listOf(draft.id),
            sourceHash = ContextEngine.sourceHash(listOf(draft)))
        val correction = Message(id = "correction", content = "纠正：林岚仍然在港口，离开的是周行。", intent = "correction")
        val chat = Chat(messages = listOf(draft.copy(contextExcluded = true), correction, Message(content = "林岚在哪？")),
            knowledgeNotes = listOf(originalNote))
        assertTrue(KnowledgeVault.search(chat, "紫水晶").isEmpty())
        val hits = KnowledgeVault.search(chat, "林岚 港口")
        assertEquals(listOf("message:correction"), hits.map { it.id })
        assertEquals("correction", hits.single().intent)
        assertTrue(KnowledgeVault.recall(chat, "林岚", 500).contains("作者纠错"))
        assertTrue(JSONObject(KnowledgeVault.read(chat, "message:draft")).has("error"))
        assertTrue(JSONObject(KnowledgeVault.read(chat, "note:old-note")).has("error"))
        assertEquals("correction", JSONObject(KnowledgeVault.read(chat, "message:correction")).getString("intent"))
    }

    @Test fun lowBudgetPrefersLatestOriginalOverAnImperfectNoteFromTheSameSource() {
        val source = Message(id = "source", role = "assistant", content = "林岚已完成二级升级，旧记录已过时。")
        val note = KnowledgeNote(id = "note", text = "林岚过去一直是一级。", sourceMessageIds = listOf(source.id),
            sourceHash = ContextEngine.sourceHash(listOf(source)))
        val chat = Chat(messages = listOf(source, Message(content = "林岚现在几级？")), knowledgeNotes = listOf(note))
        val recalled = KnowledgeVault.recall(chat, "林岚", 190)
        assertTrue(recalled.contains("已完成二级升级"))
        assertTrue(ContextEngine.estimateTokens(recalled) <= 190)
    }

    @Test fun noteSourcesExposeTheirActualRangeRatherThanTheirStorageOrCreationOrder() {
        val first = Message(id = "first", role = "assistant", content = "银钥匙交给林岚。")
        val second = Message(id = "second", role = "assistant", content = "银钥匙又归还给周行。")
        fun note(id: String, message: Message) = KnowledgeNote(id = id, text = message.content,
            sourceMessageIds = listOf(message.id), sourceHash = ContextEngine.sourceHash(listOf(message)))
        val chat = Chat(messages = listOf(first, second, Message(content = "银钥匙在哪？")),
            knowledgeNotes = listOf(note("later", second), note("earlier", first)))
        val hits = KnowledgeVault.search(chat, "银钥匙")
        assertEquals(hits.map { it.sourceEnd }.sorted(), hits.map { it.sourceEnd })
        assertEquals(1, hits.first { it.id == "note:earlier" }.sourceStart)
        assertEquals(2, hits.first { it.id == "note:later" }.sourceEnd)
    }
}
