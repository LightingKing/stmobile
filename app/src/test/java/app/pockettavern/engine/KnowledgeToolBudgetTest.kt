package app.pockettavern.engine

import app.pockettavern.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KnowledgeToolBudgetTest {
    private fun chat(text: String): Chat {
        val source = Message(id = "source", content = "林岚的原始记录。")
        val note = KnowledgeNote(id = "note", text = text, sourceMessageIds = listOf(source.id), sourceHash = ContextEngine.sourceHash(listOf(source)))
        return Chat(messages = listOf(source), knowledgeNotes = listOf(note))
    }

    @Test fun budgetedReadPaginatesChineseEmojiAndEscapedJsonWithoutMissingOrRepeatingCharacters() {
        val text = "林岚说：\"钥匙归还了\"。😀\\记录\n".repeat(180)
        val chat = chat(text)
        val recovered = StringBuilder()
        var offset = 0
        var pages = 0
        do {
            val raw = KnowledgeVault.toolResponse(chat, "history_read", JSONObject().put("id", "note:note").put("offset", offset).toString(), 650)
            assertTrue(ContextEngine.estimateTokens(raw) <= 650)
            val page = JSONObject(raw)
            assertFalse(page.has("error"))
            assertEquals(offset, page.getInt("offset"))
            assertEquals(text.length, page.getInt("totalChars"))
            val body = page.getString("text")
            assertTrue(body.isNotEmpty())
            assertFalse(body.first().isLowSurrogate())
            assertFalse(body.last().isHighSurrogate())
            recovered.append(body)
            pages++
            if (page.isNull("nextOffset")) break
            val next = page.getInt("nextOffset")
            assertEquals(offset + body.length, next)
            assertTrue(next > offset)
            offset = next
            assertTrue("pagination must make progress", pages < 200)
        } while (true)
        assertTrue(pages > 1)
        assertEquals(text, recovered.toString())
    }

    @Test fun oversizedOptionalMetadataDoesNotDisplaceAReadableFactOrChangeItsSourceRange() {
        val sources = (1..100).map { Message(id = "source-$it", content = "记录 $it") }
        val note = KnowledgeNote(id = "metadata", text = "林岚已归还钥匙。", sourceMessageIds = sources.map { it.id }, sourceHash = ContextEngine.sourceHash(sources),
            title = "标题".repeat(3000), aliases = List(100) { "别名".repeat(300) }, entity = "林岚")
        val raw = KnowledgeVault.toolResponse(Chat(messages = sources, knowledgeNotes = listOf(note)), "history_read", """{"id":"note:metadata"}""", 500)
        assertTrue(ContextEngine.estimateTokens(raw) <= 500)
        val result = JSONObject(raw)
        assertEquals(note.text, result.getString("text"))
        assertEquals(100, result.getInt("sourceMessageCount"))
        assertEquals(1, result.getInt("sourceStart"))
        assertEquals(100, result.getInt("sourceEnd"))
        assertTrue(result.getBoolean("sourceIdsTruncated"))
    }

    @Test fun insufficientBudgetIsDistinctFromNoMatchesAndUnavailableSources() {
        val chat = chat("林岚已经归还了铜钥匙。".repeat(20))
        val noRoom = KnowledgeVault.toolResponse(chat, "history_search", """{"query":"林岚"}""", 50)
        assertEquals("budget_exhausted", JSONObject(noRoom).getString("error"))
        assertTrue(ContextEngine.estimateTokens(noRoom) <= 50)
        val noReadRoom = KnowledgeVault.toolResponse(chat, "history_read", """{"id":"note:note"}""", 0)
        assertEquals("budget_exhausted", JSONObject(noReadRoom).getString("error"))
        assertTrue(ContextEngine.estimateTokens(noReadRoom) <= 50)
        assertEquals("[]", KnowledgeVault.toolResponse(chat, "history_search", """{"query":"火星飞船"}""", 50))
        val unavailableRaw = KnowledgeVault.toolResponse(chat, "history_read", """{"id":"note:missing"}""", 50)
        val unavailable = JSONObject(unavailableRaw)
        assertTrue(unavailable.has("error"))
        assertNotEquals("budget_exhausted", unavailable.getString("error"))
        assertTrue(ContextEngine.estimateTokens(unavailableRaw) <= 50)
    }

    @Test fun limitedSearchRetainsLatestMatchingUpdateBeforeAnOlderLongDescription() {
        val older = Message(id = "old", role = "assistant", content = "林岚仍是一级，尚未升级。".repeat(200))
        val latest = Message(id = "new", role = "assistant", content = "林岚已经完成二级升级。")
        val chat = Chat(messages = listOf(older, latest, Message(content = "林岚现在几级")))
        val raw = KnowledgeVault.toolResponse(chat, "history_search", """{"query":"林岚"}""", 330)
        assertTrue(ContextEngine.estimateTokens(raw) <= 330)
        val result = JSONObject(raw)
        val matches = result.getJSONArray("matches")
        val hits = (0 until matches.length()).map(matches::getJSONObject)
        assertTrue(hits.any { it.getString("id") == "message:new" && it.getString("text").contains("已经完成二级升级") })
        assertEquals(KnowledgeVault.search(chat, "林岚").size - hits.size, result.getInt("omittedMatches"))
    }

    @Test fun serializedReadAndSearchResponsesIncludingEscapesStayWithinEveryUsefulBudget() {
        val chat = chat("林岚\"\\\n😀已经归还钥匙。".repeat(1000))
        for (budget in listOf(50, 120, 300, 650, 1500, 4096)) {
            for ((name, args) in listOf("history_read" to """{"id":"note:note"}""", "history_search" to """{"query":"林岚"}""")) {
                val result = KnowledgeVault.toolResponse(chat, name, args, budget)
                assertTrue("$name exceeded $budget tokens", ContextEngine.estimateTokens(result) <= budget)
                JSONObject(result)
            }
        }
    }

    @Test fun compactionProjectionReservesTheSelectedSummaryModelsOutputAllowance() {
        val writer = ModelInfo(id = "writer", contextTokens = 1_000_000)
        val provider = ProviderConfig(id = "p", kind = "openai-compatible", models = listOf(writer))
        val summaryProvider = ProviderConfig(id = "summary", kind = "openai-compatible", models = listOf(ModelInfo(id = "summary-model", contextTokens = 1_000_000)))
        val preset = Preset(id = "preset", summaryProviderId = "summary", summaryModelId = "summary-model")
        val data = AppData(providers = listOf(provider, summaryProvider), presets = listOf(preset), settings = AppSettings(keepRecentTurns = 1))
        val chat = Chat(providerId = "p", modelId = "writer", presetId = "preset", messages = listOf(
            Message(content = "old history ".repeat(20_000)), Message(content = "继续")))
        val large = ContextEngine.build(data, chat).compression!!
        val smaller = data.copy(providers = listOf(provider, summaryProvider.copy(models = listOf(ModelInfo(id = "summary-model", contextTokens = 8192)))))
        val small = ContextEngine.build(smaller, chat).compression!!
        assertEquals(HistoryCompressor.outputLimit(1_000_000) - HistoryCompressor.outputLimit(8192), large.projectedTokens - small.projectedTokens)
        assertEquals(large.messages, small.messages)
    }
}
