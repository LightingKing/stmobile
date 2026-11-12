package app.pockettavern.engine

import app.pockettavern.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OnDemandMemoryTest {
    private fun data(supportsTools: Boolean = true, settings: AppSettings = AppSettings()): AppData = AppData(
        providers = listOf(ProviderConfig(id = "p", kind = "openai-compatible",
            models = listOf(ModelInfo(id = "m", contextTokens = 1_000_000, supportsTools = supportsTools)))),
        settings = settings
    )

    private fun call(id: String, name: String, arguments: String = "{}"): JSONObject = JSONObject()
        .put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", arguments))

    private fun messages(raw: String): List<JSONObject> = JSONArray(raw).let { json ->
        (0 until json.length()).map(json::getJSONObject)
    }

    private fun archivedChat(): Chat {
        val source = Message(id = "source", content = "林岚在雨夜答应替周行保管铜钥匙。OLD_SOURCE_ONLY")
        val summary = Summary(id = "current-summary", text = "CURRENT_CHECKPOINT：两人已到达东码头。尚有一项与林岚有关的约定，必要时查原文。",
            throughMessageId = source.id, sourceHash = ContextEngine.sourceHash(listOf(source)))
        val oldSummary = summary.copy(id = "old-summary", text = "OLD_CHECKPOINT_ONLY")
        val note = KnowledgeNote(id = "promise", text = "林岚答应保管铜钥匙。VAULT_DETAIL_ONLY", sourceMessageIds = listOf(source.id),
            sourceHash = ContextEngine.sourceHash(listOf(source)), title = "林岚的约定", kind = "agreement", entity = "林岚", aliases = listOf("阿岚"))
        return Chat(providerId = "p", modelId = "m", messages = listOf(source, Message(id = "now", content = "继续走向船边。RECENT_DIALOGUE")),
            summaries = listOf(oldSummary, summary), activeSummaryId = summary.id, knowledgeNotes = listOf(note))
    }

    @Test fun ordinaryContinuationSendsOneCheckpointAndRecentDialogueWithoutSearchingTheVault() {
        val chat = archivedChat()
        val built = ContextEngine.build(data(), chat)
        assertTrue(built.messagesJson.contains("CURRENT_CHECKPOINT"))
        assertTrue(built.messagesJson.contains("RECENT_DIALOGUE"))
        assertFalse(built.messagesJson.contains("OLD_CHECKPOINT_ONLY"))
        assertFalse(built.messagesJson.contains("OLD_SOURCE_ONLY"))
        assertFalse(built.messagesJson.contains("VAULT_DETAIL_ONLY"))
        assertFalse(built.messagesJson.contains("【会话知识库召回】"))
        assertTrue(built.messagesJson.contains("足够时直接回应，不必每轮检索"))
        assertTrue(built.toolDefinitions.contains("history_search"))
        assertTrue(built.toolDefinitions.contains("history_read"))
    }

    @Test fun explicitToolResultSurvivesContinuationButExpiresOnNextUserTurnWithoutDeletingHistory() = runBlocking {
        val chat = archivedChat()
        val root = kotlin.io.path.createTempDirectory("on-demand-memory").toFile()
        try {
            val runtime = ToolRuntime(root)
            val result = runtime.execute("history_read", """{"id":"note:promise"}""", emptyList(), data(), chat)
            assertTrue(result.content.contains("VAULT_DETAIL_ONLY"))
            val lookup = Message(id = "lookup", role = "assistant", reasoning = "TEMP_LOOKUP_REASONING",
                toolCallsJson = JSONArray().put(call("read-1", "history_read", """{"id":"note:promise"}""")).toString())
            val evidence = Message(id = "result", role = "tool", toolCallId = "read-1", content = result.content)
            val continued = chat.copy(messages = chat.messages + lookup + evidence)
            val during = ContextEngine.build(data(), continued)
            assertTrue(during.messagesJson.contains("VAULT_DETAIL_ONLY"))
            assertTrue(during.messagesJson.contains("read-1"))
            val completed = continued.copy(messages = continued.messages + Message(role = "assistant", content = "她仍替他保管着钥匙。FINAL_PROSE") + Message(content = "接着上船。"))
            val sourceHash = ContextEngine.sourceHash(completed.messages)
            val after = ContextEngine.build(data(), completed)
            assertFalse(after.messagesJson.contains("VAULT_DETAIL_ONLY"))
            assertFalse(after.messagesJson.contains("read-1"))
            assertFalse(after.messagesJson.contains("TEMP_LOOKUP_REASONING"))
            assertTrue(after.messagesJson.contains("FINAL_PROSE"))
            assertEquals(sourceHash, ContextEngine.sourceHash(completed.messages))
            assertEquals(evidence, completed.messages.single { it.id == "result" })
        } finally { root.deleteRecursively() }
    }

    @Test fun previousMixedToolCallsKeepOtherToolsAndTheirResultsAsCompletePairs() {
        val calls = JSONArray().put(call("memory", "history_search")).put(call("research", "workspace_read"))
        val chat = Chat(providerId = "p", modelId = "m", messages = listOf(
            Message(content = "核对资料"),
            Message(role = "assistant", content = "ASSISTANT_PROSE", toolCallsJson = calls.toString()),
            Message(role = "tool", content = "EXPIRED_MEMORY_RESULT", toolCallId = "memory"),
            Message(role = "tool", content = "RESEARCH_RESULT", toolCallId = "research"),
            Message(role = "assistant", content = "答复"), Message(content = "继续")
        ))
        val built = ContextEngine.build(data(), chat)
        val outgoing = messages(built.messagesJson)
        val assistant = outgoing.single { it.optString("content") == "ASSISTANT_PROSE" }
        assertEquals(1, assistant.getJSONArray("tool_calls").length())
        assertEquals("research", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals(listOf("research"), outgoing.filter { it.optString("role") == "tool" }.map { it.getString("tool_call_id") })
        assertFalse(built.messagesJson.contains("EXPIRED_MEMORY_RESULT"))
        assertTrue(built.messagesJson.contains("RESEARCH_RESULT"))
    }

    @Test fun unsupportedModelGetsNeitherToolDefinitionsNorHiddenAutomaticRecall() {
        val built = ContextEngine.build(data(supportsTools = false), archivedChat())
        assertEquals("[]", built.toolDefinitions)
        assertFalse(built.messagesJson.contains("VAULT_DETAIL_ONLY"))
        assertTrue(built.messagesJson.contains("当前模型未启用历史检索工具"))
        assertTrue(built.messagesJson.contains("缺少信息时说明不确定"))
    }

    @Test fun reusedCallIdInAnotherExchangeDoesNotRemoveAnUnrelatedToolResult() {
        val chat = Chat(providerId = "p", modelId = "m", messages = listOf(
            Message(content = "查旧约定"),
            Message(role = "assistant", toolCallsJson = JSONArray().put(call("same-id", "history_read")).toString()),
            Message(role = "tool", content = "OLD_RECALL", toolCallId = "same-id"),
            Message(role = "assistant", content = "已核对"), Message(content = "再查公开资料"),
            Message(role = "assistant", toolCallsJson = JSONArray().put(call("same-id", "workspace_read")).toString()),
            Message(role = "tool", content = "KEEP_RESEARCH", toolCallId = "same-id"),
            Message(role = "assistant", content = "资料答复"), Message(content = "继续")
        ))
        val built = ContextEngine.build(data(), chat)
        assertFalse(built.messagesJson.contains("OLD_RECALL"))
        assertTrue(built.messagesJson.contains("KEEP_RESEARCH"))
        assertEquals(1, messages(built.messagesJson).count { it.optString("role") == "tool" })
    }

    @Test fun sourceValidatedHistoricalCheckpointsAreSearchableButNeverInjectedAsExtraSummaries() {
        val chat = archivedChat()
        val hits = KnowledgeVault.search(chat, "OLD_CHECKPOINT_ONLY")
        assertTrue(hits.any { it.id == "summary:old-summary" })
        val read = JSONObject(KnowledgeVault.read(chat, "summary:old-summary"))
        assertEquals("OLD_CHECKPOINT_ONLY", read.getString("text"))
        assertEquals(1, read.getInt("sourceEnd"))
        val edited = chat.copy(messages = chat.messages.map { if (it.id == "source") it.copy(content = "changed") else it })
        assertTrue(KnowledgeVault.search(edited, "OLD_CHECKPOINT_ONLY").none { it.id.startsWith("summary:") })
        assertTrue(JSONObject(KnowledgeVault.read(edited, "summary:old-summary")).has("error"))
    }

    @Test fun aliasSearchFindsCanonicalLaterChangesAndArchiveCoverageDoesNotCrowdOutFacts() {
        val source = Message(id = "old", content = "林岚收下铜钥匙。")
        val update = Message(id = "new", content = "林岚把铜钥匙还给周行。")
        val old = KnowledgeNote(id = "old-note", text = "收下钥匙", sourceMessageIds = listOf(source.id), sourceHash = ContextEngine.sourceHash(listOf(source)),
            entity = "林岚", aliases = listOf("阿岚"), title = "钥匙归属", kind = "person")
        val recent = KnowledgeNote(id = "new-note", text = "林岚已归还钥匙", sourceMessageIds = listOf(update.id), sourceHash = ContextEngine.sourceHash(listOf(update)), entity = "林岚", kind = "event")
        val archive = old.copy(id = "archive", text = "阿岚林岚钥匙".repeat(50), kind = "archive")
        val chat = Chat(messages = listOf(source, update, Message(content = "阿岚的钥匙呢")), knowledgeNotes = listOf(old, recent, archive))
        val hits = KnowledgeVault.search(chat, "阿岚")
        assertTrue(hits.any { it.id == "note:old-note" })
        assertTrue(hits.any { it.id == "note:new-note" })
        assertFalse(hits.any { it.id == "note:archive" })
    }

    @Test fun modelFacingSourceMetadataIsBoundedWhileFullSourceRemainsLocal() {
        val sources = (1..200).map { Message(id = "source-$it", content = "事件 $it") }
        val note = KnowledgeNote(id = "long-note", text = "铜钥匙的约定", sourceMessageIds = sources.map { it.id }, sourceHash = ContextEngine.sourceHash(sources))
        val chat = Chat(messages = sources, knowledgeNotes = listOf(note))
        val read = JSONObject(KnowledgeVault.read(chat, "note:long-note"))
        assertEquals(24, read.getJSONArray("sourceMessageIds").length())
        assertEquals(200, read.getInt("sourceMessageCount"))
        assertTrue(read.getBoolean("sourceIdsTruncated"))
        assertEquals(200, read.getInt("sourceEnd"))
        val search = JSONArray(KnowledgeVault.searchJson(chat, "铜钥匙")).getJSONObject(0)
        assertEquals(24, search.getJSONArray("sourceMessageIds").length())
        assertEquals(200, search.getInt("sourceMessageCount"))
        assertEquals(200, chat.knowledgeNotes.single().sourceMessageIds.size)
    }

    @Test fun spendingLimitTriggersCompactionBeforeMillionTokenModelWindowFills() {
        val source = (1..20).flatMap { index -> listOf(Message(content = "request $index " + "x".repeat(6000)), Message(role = "assistant", content = "reply $index " + "y".repeat(6000))) }
        val chat = Chat(providerId = "p", modelId = "m", messages = source + Message(content = "继续"))
        val limited = ContextEngine.build(data(), chat)
        assertEquals(65_536, limited.budget)
        assertTrue(limited.needsCompression)
        assertTrue(limited.messagesJson.contains(source.first().content))
        assertTrue(limited.compression!!.projectedTokens < limited.budget)
        val uncapped = ContextEngine.build(data(settings = AppSettings(maxContextInputTokens = 0)), chat)
        assertTrue(uncapped.budget > 900_000)
        // 0.2.1: a budget of 0 no longer means "never fold". History is folded by its own length.
        assertTrue(uncapped.needsCompression)
        assertEquals("HISTORY_LENGTH", uncapped.budgetBreakdown!!.triggerReason)
    }
}
