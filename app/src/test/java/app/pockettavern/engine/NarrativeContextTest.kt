package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Attachment
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.Memory
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.SkillRecord
import app.pockettavern.domain.Summary
import app.pockettavern.domain.WorldEntry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Neutral fixtures verify request construction, not subjective prose quality. */
class NarrativeContextTest {
    @Test fun excludedDraftCannotLeakViaMessagesWorldBookOrSummary() {
        val draft = Message(id = "draft", role = "assistant", content = "DISCARDED_UNICORN_SCENE", contextExcluded = true)
        val chat = Chat(messages = listOf(Message(content = "在图书馆见面。"), draft, Message(content = "换一个切入点", intent = "rewrite")))
        val data = AppData(worldEntries = listOf(WorldEntry(name = "excluded-trigger", keywords = listOf("DISCARDED_UNICORN_SCENE"), content = "DISCARDED_LORE", searchRange = 1000)))
        val built = ContextEngine.build(data, chat)
        assertFalse(built.messagesJson.contains(draft.content))
        assertFalse(built.messagesJson.contains("DISCARDED_LORE"))
        assertFalse(ContextEngine.summarySource(chat.messages).joinToString().contains(draft.content))
        assertTrue("discarded source is still preserved locally", chat.messages.any { it == draft })
    }

    @Test fun excludedLatestUserDoesNotTriggerWorldBook() {
        val chat = Chat(messages = listOf(Message(content = "有效问题"), Message(content = "DISCARDED_QUERY", contextExcluded = true)))
        val data = AppData(worldEntries = listOf(WorldEntry(keywords = listOf("DISCARDED_QUERY"), content = "WRONG_LORE")))
        assertFalse(ContextEngine.build(data, chat).messagesJson.contains("WRONG_LORE"))
    }

    @Test fun confirmedMemoryDerivedFromDiscardedDraftIsNotInjected() {
        val chat = Chat(id = "story", messages = listOf(Message(id = "draft", role = "assistant", content = "错误稿", contextExcluded = true), Message(content = "继续")))
        val data = AppData(memories = listOf(
            Memory(text = "DISCARDED_MEMORY", scope = "chat", targetId = chat.id, sourceMessageId = "draft"),
            Memory(text = "CONFIRMED_INDEPENDENT_MEMORY", scope = "chat", targetId = chat.id)
        ))
        val request = ContextEngine.build(data, chat).messagesJson
        assertFalse(request.contains("DISCARDED_MEMORY"))
        assertTrue(request.contains("CONFIRMED_INDEPENDENT_MEMORY"))
    }

    @Test fun compressionKeepsOriginalBoundaryWhenRejectedMessagesAppearBetweenValidTurns() {
        val messages = listOf(
            Message(id = "u1", content = "第一幕"),
            Message(id = "old-draft", role = "assistant", content = "错误稿", contextExcluded = true),
            Message(id = "a1", role = "assistant", content = "确认后的第一幕"),
            Message(id = "discarded-request", content = "作废要求", contextExcluded = true),
            Message(id = "u2", content = "第二幕"),
            Message(id = "a2", role = "assistant", content = "当前场景"),
            Message(id = "u3", content = "继续")
        )
        val chat = Chat(messages = messages)
        val data = AppData(settings = AppSettings(keepRecentTurns = 1))
        val slice = ContextEngine.compressionSlice(data, chat)!!
        assertEquals(listOf("u1", "a1", "u2", "a2"), slice.messages.map { it.id })
        assertEquals("a2", slice.throughMessageId)
        assertEquals(ContextEngine.sourceHash(messages.take(6)), slice.sourceHash)
        val summary = Summary(id = "s", text = "FIRST_TWO_TURNS_SUMMARY", throughMessageId = slice.throughMessageId, sourceHash = slice.sourceHash)
        val after = ContextEngine.build(data, chat.copy(summaries = listOf(summary), activeSummaryId = "s"))
        val users = jsonMessages(after.messagesJson).filter { it.optString("role") == "user" }
        assertEquals(listOf("继续"), users.map { it.optString("content") })
        assertTrue(after.messagesJson.contains(summary.text))
    }

    @Test fun excludedToolCallerCannotLeaveOrphanedToolResult() {
        val call = Message(role = "assistant", toolCallsJson = """[{"id":"discarded-call","type":"function","function":{"name":"lookup","arguments":"{}"}}]""", contextExcluded = true)
        val result = Message(role = "tool", toolCallId = "discarded-call", content = "DISCARDED_TOOL_RESULT")
        val chat = Chat(messages = listOf(Message(content = "查一下"), call, result, Message(content = "继续")))
        val built = ContextEngine.build(AppData(), chat)
        assertFalse(jsonMessages(built.messagesJson).any { it.optString("role") == "tool" })
        assertFalse(ContextEngine.summarySource(chat.messages).joinToString().contains(result.content))
    }

    @Test fun excludedCallIdDoesNotHideLaterActiveToolResultWhenProviderReusesIds() {
        val calls = """[{"id":"call-1","type":"function","function":{"name":"lookup","arguments":"{}"}}]"""
        val chat = Chat(messages = listOf(
            Message(role = "assistant", toolCallsJson = calls, contextExcluded = true),
            Message(role = "tool", toolCallId = "call-1", content = "DISCARDED_TOOL_RESULT"),
            Message(content = "重新查阅"),
            Message(role = "assistant", toolCallsJson = calls),
            Message(role = "tool", toolCallId = "call-1", content = "CURRENT_TOOL_RESULT")
        ))
        val request = ContextEngine.build(AppData(), chat).messagesJson
        assertFalse(request.contains("DISCARDED_TOOL_RESULT"))
        assertTrue(request.contains("CURRENT_TOOL_RESULT"))
    }

    @Test fun excludedAndIntentEditsInvalidateSummaryWithoutInvalidatingDefaultLegacyState() {
        val source = Message(id = "source", content = "以前的设定")
        val defaultHash = ContextEngine.sourceHash(listOf(source))
        assertEquals(defaultHash, ContextEngine.sourceHash(listOf(source.copy(contextExcluded = false, intent = "dialogue"))))
        assertNotEquals(defaultHash, ContextEngine.sourceHash(listOf(source.copy(contextExcluded = true))))
        assertNotEquals(defaultHash, ContextEngine.sourceHash(listOf(source.copy(intent = "correction"))))
        val summary = Summary(id = "s", text = "STALE_SCENE_SUMMARY", throughMessageId = source.id, sourceHash = defaultHash)
        val chat = Chat(messages = listOf(source.copy(contextExcluded = true), Message(content = "继续")), summaries = listOf(summary), activeSummaryId = summary.id)
        assertFalse(ContextEngine.build(AppData(), chat).messagesJson.contains(summary.text))
    }

    @Test fun correctionsAndRewritesHaveSeparateAuthorInstructionsIncludingAttachments() {
        val correction = Message(content = "钥匙已经交给馆长。", intent = "correction", attachments = listOf(Attachment(name = "note.txt", mime = "text/plain", dataUrl = "交接记录")))
        val corrected = jsonMessages(ContextEngine.build(AppData(), Chat(messages = listOf(correction))).messagesJson).last()
        val content = corrected.getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(content.contains("剧情外纠错"))
        assertTrue(content.contains("不推进时间"))
        assertTrue(content.contains(correction.content))
        assertTrue(corrected.toString().contains("交接记录"))
        val correctionReply = NarrativePolicy.messageContent(Message(role = "assistant", intent = "correction", content = "已确认，钥匙在馆长手里。"))
        assertTrue(correctionReply.contains("不是角色发言或剧情事件"))
        val rewrite = NarrativePolicy.messageContent(Message(content = "换一个事件安排", intent = "rewrite"))
        assertTrue(rewrite.contains("重新构思"))
        assertTrue(rewrite.contains("不属于已发生剧情"))
        assertTrue(rewrite.contains("不要仅换近义词"))
        assertTrue(rewrite.contains("不附自评"))
        val ordinary = Message(content = "请计算 2 + 2")
        assertEquals(ordinary.content, NarrativePolicy.messageContent(ordinary))
    }

    @Test fun historicalAuthorControlsKeepTheirFactsWithoutReplayingThisTurnInstructions() {
        val chat = Chat(messages = listOf(
            Message(id="old-correction",content="钥匙已经交给馆长。",intent="correction",attachments=listOf(Attachment(name="note.txt",mime="text/plain",dataUrl="交接记录"))),
            Message(role="assistant",content="已确认。",intent="correction"),
            Message(id="old-rewrite",content="从窗边开始写。",intent="rewrite"),
            Message(role="assistant",content="馆长望向窗外。"),
            Message(id="current",content="继续")
        ))
        val users = jsonMessages(ContextEngine.build(AppData(),chat).messagesJson).filter { it.optString("role")=="user" }
        assertTrue(users[0].toString().contains("钥匙已经交给馆长"))
        assertTrue(users[0].toString().contains("交接记录"))
        assertTrue(users[1].toString().contains("从窗边开始写"))
        assertTrue(users.take(2).all { it.toString().contains("历史") })
        assertFalse(users.any { it.toString().contains("【处理方式】") })
        assertFalse(users.any { it.toString().contains("本轮只落实") })
        assertEquals("继续",users.last().optString("content"))
    }

    @Test fun currentAuthorModeStaysActiveAcrossToolContinuationWithoutReactivatingOlderModes() {
        val chat = Chat(messages = listOf(
            Message(content="上次重新写",intent="rewrite"),
            Message(role="assistant",content="旧场景"),
            Message(id="current",content="核对钥匙持有人",intent="correction")
        ))
        fun users(current:Chat) = jsonMessages(ContextEngine.build(AppData(),current).messagesJson).filter { it.optString("role")=="user" }
        val before = users(chat)
        val calls = """[{"id":"history-call","type":"function","function":{"name":"history_search","arguments":"{}"}}]"""
        val continued = chat.copy(messages=chat.messages + Message(role="assistant",intent="correction",content="我先核对来源。",toolCallsJson=calls) + Message(role="tool",content="馆长持有钥匙",toolCallId="history-call"))
        val after = users(continued)
        assertEquals(before.map { it.toString() },after.map { it.toString() })
        assertEquals(1,after.count { it.toString().contains("【处理方式】") })
        assertTrue(after.last().toString().contains("本轮只落实"))
        assertFalse(after.first().toString().contains("【处理方式】"))
    }

    @Test fun narrativePolicyIsScopedToWritingAndExplicitAuthorControls() {
        val generic = Preset(name = "通用助手", systemPrompt = "准确回答问题")
        assertEquals("", NarrativePolicy.prompt(Chat(messages = listOf(Message(content = "查阅天气"))), generic, emptyList()))
        assertEquals("", NarrativePolicy.prompt(Chat(), generic, listOf(SkillRecord(name = "计算", content = "小说故事角色扮演"))))
        assertTrue(NarrativePolicy.prompt(Chat(characterId = "fictional-guide"), generic, emptyList()).contains("叙事连续性"))
        assertTrue(NarrativePolicy.prompt(Chat(), generic, listOf(SkillRecord(name = "小说写作"))).contains("叙事连续性"))
        assertTrue(NarrativePolicy.prompt(Chat(messages = listOf(Message(intent = "correction"))), generic, emptyList()).contains("叙事连续性"))
    }

    @Test fun pinnedSceneAndContinuitySurviveCompressionAndAreBudgeted() {
        val source = Message(id = "past", content = "先前场景")
        val summary = Summary(id = "s", text = "旧记忆摘要", throughMessageId = source.id, sourceHash = ContextEngine.sourceHash(listOf(source)))
        val chat = Chat(messages = listOf(source, Message(content = "继续")), summaries = listOf(summary), activeSummaryId = "s", continuityNotes = "SCENE_CANON：17 点，图书馆，钥匙在馆长手中。")
        val built = ContextEngine.build(AppData(), chat)
        assertTrue(built.messagesJson.contains(chat.continuityNotes))
        assertTrue(built.messagesJson.contains("不能推翻后续已发生的变化"))
        assertTrue(built.messagesJson.contains("各自") || built.messagesJson.contains("不同经历"))
        assertTrue(built.estimatedTokens > ContextEngine.build(AppData(), chat.copy(continuityNotes = "")).estimatedTokens)
    }

    @Test fun historyToolsAreAvailableBeforeFirstSummaryAndUnsupportedModelsCannotClaimToolAccess() {
        val model = ModelInfo(id = "m", supportsTools = true)
        val data = AppData(providers = listOf(ProviderConfig(id = "p", models = listOf(model))))
        val chat = Chat(providerId = "p", modelId = model.id, messages = listOf(Message(content = "馆长收到钥匙"), Message(role = "assistant", content = "她锁好书柜。"), Message(content = "钥匙在哪")))
        val built = ContextEngine.build(data, chat)
        assertTrue(built.toolDefinitions.contains("history_search"))
        assertTrue(built.toolDefinitions.contains("history_read"))
        assertTrue(built.messagesJson.contains("只有命中早于当前摘要或存在实际矛盾时"))
        assertTrue(built.messagesJson.contains("证据足够就停止检索并回答"))
        val unsupported = ContextEngine.build(data.copy(providers = listOf(ProviderConfig(id = "p", models = listOf(model.copy(supportsTools = false))))), chat)
        assertFalse(unsupported.toolDefinitions.contains("history_search"))
        assertTrue(unsupported.messagesJson.contains("不要声称已搜索"))
        val empty = ContextEngine.build(data, chat.copy(messages = emptyList()))
        assertFalse(empty.toolDefinitions.contains("history_search"))
        val attachmentOnly = ContextEngine.build(data, chat.copy(messages = listOf(Message(attachments = listOf(Attachment(name = "history.txt", mime = "text/plain", dataUrl = "馆长收到钥匙"))))))
        assertTrue(attachmentOnly.toolDefinitions.contains("history_search"))
    }

    private fun jsonMessages(raw: String): List<org.json.JSONObject> {
        val json = JSONArray(raw)
        return (0 until json.length()).map(json::getJSONObject)
    }
}
