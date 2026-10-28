package app.pockettavern.engine

import app.pockettavern.domain.Attachment
import app.pockettavern.domain.Chat
import app.pockettavern.domain.CompressionRecord
import app.pockettavern.domain.ContextBudgetBreakdown
import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.domain.MemoryRebuildState
import app.pockettavern.domain.Message
import app.pockettavern.domain.Summary
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryDiagnosticsTest {
    private val messages = (1..3).map { Message(id = "m$it", content = "Synthetic message $it", createdAt = it.toLong()) }

    @Test fun budgetDiagnosticsExportOnlyNumericFieldsAndKnownReasons() {
        val raw = ContextBudgetBreakdown(modelWindow = 1_000_000, inputBudget = 65_536,
            estimatedInputTokens = 60_000, triggerTokens = 52_428, threshold = .8,
            skillTokens = 35_000, summaryTokens = 5000, recentTokens = 15_000,
            triggerReason = "PRIVATE_BUDGET_REASON", blockedReason = "PRIVATE_BLOCK_REASON")
        val original = summary("budget", messages, 10).copy(text = "PRIVATE_WORKING_SUMMARY",
            compression = CompressionRecord(budgetBreakdown = raw))
        val report = JSONObject(MemoryDiagnostics.report(Chat(messages = messages, summaries = listOf(original)), null))
        val budget = findSummary(report, "budget").getJSONObject("compression").getJSONObject("budgetBreakdown")
        assertEquals("UNKNOWN", budget.getString("triggerReason"))
        assertEquals("UNKNOWN", budget.getString("blockedReason"))
        assertEquals(65_536, budget.getInt("inputBudget"))
        assertEquals(60_000, budget.getInt("estimatedInputTokens"))
        assertEquals(35_000, budget.getInt("skillTokens"))
        assertEquals(24, keys(budget).size)
        keys(budget).filterNot { it in setOf("triggerReason", "blockedReason", "projectedTokens") }.forEach {
            assertTrue("Only numeric diagnostics are allowed for $it", budget.get(it) is Number)
        }
        assertTrue(budget.isNull("projectedTokens"))
        assertFalse(report.toString().contains("PRIVATE_"))
    }

    @Test fun unnormalizedBudgetReasonsAndNumbersCannotLeakOrBreakDiagnosticJson() {
        val raw = app.pockettavern.data.DataCodec.gson.fromJson("""{"triggerReason":null,"blockedReason":null,"skillTokens":-1}""", ContextBudgetBreakdown::class.java)
        val original = summary("budget", messages, 10).copy(compression = CompressionRecord(budgetBreakdown = raw))
        val report = JSONObject(MemoryDiagnostics.report(Chat(messages = messages, summaries = listOf(original)), null))
        val budget = findSummary(report, "budget").getJSONObject("compression").getJSONObject("budgetBreakdown")
        assertEquals(0, budget.getInt("skillTokens"))
        assertEquals(0.0, budget.getDouble("threshold"), 0.0)
        assertEquals("UNKNOWN", budget.getString("triggerReason"))
        assertEquals("", budget.getString("blockedReason"))
        val nonFinite = original.copy(compression = CompressionRecord(budgetBreakdown = ContextBudgetBreakdown(threshold = Double.POSITIVE_INFINITY)))
        val nonFiniteReport = JSONObject(MemoryDiagnostics.report(Chat(messages = messages, summaries = listOf(nonFinite)), null))
        assertEquals(0.0, findSummary(nonFiniteReport, "budget").getJSONObject("compression").getJSONObject("budgetBreakdown").getDouble("threshold"), 0.0)
    }

    @Test fun reportsCurrentLatestAndRebuiltSummaryAsSeparateMetadataWithoutChangingChat() {
        val old = summary("old", messages.take(1), 10)
        val latest = summary("latest", messages, 30)
        val chat = Chat(id = "chat-诊断", messages = messages, summaries = listOf(latest, old), activeSummaryId = old.id)
        val rebuild = MemoryRebuildState(
            chatId = chat.id, phase = "success", completed = 2, total = 2, noteCount = 2,
            summaryId = latest.id, sourceMessageCount = 3, finishedAt = 40
        )

        val text = MemoryDiagnostics.report(chat, rebuild)
        val report = JSONObject(text)
        assertTrue("The report must be readable JSON", text.contains('\n'))
        assertTrue("UTF-8 identifiers should stay readable", text.contains("chat-诊断"))
        assertEquals(text, String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertEquals("chat-诊断", report.getString("chatId"))
        assertEquals(1, report.getInt("schemaVersion"))
        assertEquals(3, report.getInt("messageCount"))
        assertEquals(3, report.getInt("uniqueMessageIdCount"))
        assertEquals(0, report.getInt("duplicateMessageIdCount"))
        assertEquals(0, report.getInt("blankMessageIdCount"))
        assertEquals("m3", report.getString("lastMessageId"))
        assertEquals(2, report.getInt("lastMessageIdFirstIndex"))
        assertEquals("old", report.getString("activeSummaryId"))
        assertTrue(report.getBoolean("activeSummaryFound"))
        assertEquals("latest", report.getString("latestSummaryId"))
        assertFalse(report.getBoolean("activeMatchesLatestSummary"))
        assertEquals(2, report.getInt("summaryCount"))
        val active = findSummary(report, old.id)
        assertTrue(active.getBoolean("isActive"))
        assertEquals(10L, active.getLong("createdAt"))
        assertEquals("diagnostic-model", active.getString("modelId"))
        assertEquals("m1", active.getString("throughMessageId"))
        assertEquals(0, active.getInt("endIndex"))
        assertEquals(1, active.getInt("coverageCount"))
        assertEquals(1, active.getInt("cutoffMatchCount"))
        assertTrue(active.getBoolean("sourceValid"))
        assertFalse(findSummary(report, latest.id).getBoolean("isActive"))
        assertEquals(3, findSummary(report, latest.id).getInt("coverageCount"))
        val state = report.getJSONObject("rebuild")
        assertEquals(latest.id, state.getString("summaryId"))
        assertEquals("success", state.getString("phase"))
        assertEquals(2, state.getInt("completed"))
        assertEquals(2, state.getInt("total"))
        assertEquals(2, state.getInt("noteCount"))
        assertEquals(3, state.getInt("sourceMessageCount"))
        assertEquals(40L, state.getLong("finishedAt"))
        assertEquals("old", chat.activeSummaryId)
        assertEquals(listOf(latest, old), chat.summaries)
        assertEquals(messages, chat.messages)
    }

    @Test fun exportsOnlyWhitelistedMetadataAndNeverPrivatePayloadsOrRawErrors() {
        val sentinels = listOf(
            "PRIVATE_TITLE", "PRIVATE_CONTENT", "PRIVATE_REASONING", "PRIVATE_TOOLS",
            "PRIVATE_TOOL_ID", "PRIVATE_ERROR", "PRIVATE_FINISH_REASON", "PRIVATE_REQUEST_ID",
            "PRIVATE_ATTACHMENT_NAME", "PRIVATE_ATTACHMENT_PATH", "PRIVATE_ATTACHMENT_DATA",
            "PRIVATE_SUMMARY_TEXT", "PRIVATE_NOTE_TEXT", "PRIVATE_REBUILD_ERROR_sk_secret",
            "PRIVATE_SUMMARY_FORMAT", "PRIVATE_NOTE_TITLE", "PRIVATE_ENTITY", "PRIVATE_ALIAS"
        )
        val message = Message(
            id = "message", content = sentinels[1], reasoning = sentinels[2], toolCallsJson = sentinels[3],
            toolCallId = sentinels[4], errorMessage = sentinels[5], finishReason = sentinels[6], requestId = sentinels[7],
            attachments = listOf(Attachment(name = sentinels[8], path = sentinels[9], dataUrl = sentinels[10]))
        )
        val chat = Chat(
            id = "chat", title = sentinels[0], messages = listOf(message),
            summaries = listOf(summary("summary", listOf(message), 10).copy(text = sentinels[11], format = sentinels[14])),
            activeSummaryId = "summary",
            knowledgeNotes = listOf(KnowledgeNote(
                id = "note", text = sentinels[12], sourceMessageIds = listOf(message.id),
                sourceHash = ContextEngine.sourceHash(listOf(message)), title = sentinels[15], entity = sentinels[16], aliases = listOf(sentinels[17])
            ))
        )
        val text = MemoryDiagnostics.report(chat, MemoryRebuildState(chatId = chat.id, phase = "error", message = sentinels[13]))
        sentinels.forEach { assertFalse("Private payload escaped into diagnostics: $it", text.contains(it)) }
        val report = JSONObject(text)
        assertEquals(topLevelKeys + "rebuild", keys(report))
        val summaries = report.getJSONArray("summaries")
        assertEquals(1, summaries.length())
        assertEquals(summaryKeys, keys(summaries.getJSONObject(0)))
        assertEquals("legacy", summaries.getJSONObject(0).getString("format"))
        assertEquals(rebuildKeys, keys(report.getJSONObject("rebuild")))
        assertEquals(1, report.getInt("noteCount"))
        assertEquals(1, report.getInt("validNoteCount"))
    }

    @Test fun duplicateCutoffReportsItsFirstPositionButNeverClaimsAValidSource() {
        val duplicate = messages.first()
        val repeated = messages + duplicate + duplicate
        val chat = Chat(
            messages = repeated,
            summaries = listOf(summary("summary", listOf(duplicate), 10)),
            knowledgeNotes = listOf(KnowledgeNote(
                sourceMessageIds = listOf(duplicate.id), sourceHash = ContextEngine.sourceHash(listOf(duplicate))
            ))
        )
        val report = JSONObject(MemoryDiagnostics.report(chat, null))
        assertEquals(5, report.getInt("messageCount"))
        assertEquals(3, report.getInt("uniqueMessageIdCount"))
        assertEquals(2, report.getInt("duplicateMessageIdCount"))
        assertEquals(0, report.getInt("blankMessageIdCount"))
        assertEquals(0, report.getInt("lastMessageIdFirstIndex"))
        val summary = findSummary(report, "summary")
        assertEquals(0, summary.getInt("endIndex"))
        assertEquals(1, summary.getInt("coverageCount"))
        assertEquals(3, summary.getInt("cutoffMatchCount"))
        assertFalse(summary.getBoolean("sourceValid"))
        assertEquals(0, report.getInt("validNoteCount"))
    }

    @Test fun blankAndMissingCutoffsHaveNoCoverageAndBlankSourcesAreUnsafe() {
        val withBlanks = listOf(messages[0], Message(id = ""), Message(id = " "), messages[1])
        val chat = Chat(messages = withBlanks, summaries = listOf(
            Summary(id = "blank", throughMessageId = "", sourceHash = ContextEngine.sourceHash(withBlanks.take(2))),
            Summary(id = "missing", throughMessageId = "gone"),
            summary("with-blank-source", withBlanks, 10)
        ))
        val report = JSONObject(MemoryDiagnostics.report(chat, null))
        assertEquals(4, report.getInt("messageCount"))
        assertEquals(2, report.getInt("uniqueMessageIdCount"))
        assertEquals(0, report.getInt("duplicateMessageIdCount"))
        assertEquals(2, report.getInt("blankMessageIdCount"))
        listOf("blank", "missing").forEach { id ->
            val summary = findSummary(report, id)
            assertEquals(-1, summary.getInt("endIndex"))
            assertEquals(0, summary.getInt("coverageCount"))
            assertFalse(summary.getBoolean("sourceValid"))
        }
        assertFalse(findSummary(report, "with-blank-source").getBoolean("sourceValid"))
    }

    @Test fun summariesAndNotesValidateOnlyUnambiguousMatchingSources() {
        val original = summary("valid", messages, 10)
        val notes = listOf(
            KnowledgeNote(id = "valid", sourceMessageIds = listOf("m1", "m3"), sourceHash = ContextEngine.sourceHash(listOf(messages[0], messages[2]))),
            KnowledgeNote(id = "missing", sourceMessageIds = listOf("gone"), sourceHash = "not-empty"),
            KnowledgeNote(id = "blank", sourceMessageIds = listOf(""), sourceHash = "not-empty"),
            KnowledgeNote(id = "duplicate", sourceMessageIds = listOf("m1", "m1"), sourceHash = ContextEngine.sourceHash(listOf(messages[0], messages[0]))),
            KnowledgeNote(id = "edited", sourceMessageIds = listOf("m1"), sourceHash = ContextEngine.sourceHash(listOf(messages[0].copy(content = "Changed")))),
            KnowledgeNote(id = "legacy", sourceMessageIds = listOf("m1")),
            KnowledgeNote(id = "empty", sourceHash = ContextEngine.sourceHash(emptyList()))
        )
        val chat = Chat(messages = messages, summaries = listOf(
            original, original.copy(id = "edited", sourceHash = "not-matching"), original.copy(id = "legacy", sourceHash = "")
        ), knowledgeNotes = notes)
        val report = JSONObject(MemoryDiagnostics.report(chat, null))
        assertTrue(findSummary(report, "valid").getBoolean("sourceValid"))
        assertFalse(findSummary(report, "edited").getBoolean("sourceValid"))
        assertFalse(findSummary(report, "legacy").getBoolean("sourceValid"))
        assertEquals(7, report.getInt("noteCount"))
        assertEquals(1, report.getInt("validNoteCount"))
    }

    @Test fun emptyChatAndDanglingActiveSummaryRemainReadableWithoutARebuild() {
        val report = JSONObject(MemoryDiagnostics.report(Chat(id = "empty", activeSummaryId = "missing"), null))
        assertEquals(topLevelKeys, keys(report))
        assertEquals(0, report.getInt("messageCount"))
        assertEquals("", report.getString("lastMessageId"))
        assertEquals(-1, report.getInt("lastMessageIdFirstIndex"))
        assertEquals("missing", report.getString("activeSummaryId"))
        assertFalse(report.getBoolean("activeSummaryFound"))
        assertEquals("", report.getString("latestSummaryId"))
        assertFalse(report.getBoolean("activeMatchesLatestSummary"))
        assertEquals(0, report.getInt("summaryCount"))
        assertEquals(0, report.getJSONArray("summaries").length())
        assertEquals(0, report.getInt("noteCount"))
        assertEquals(0, report.getInt("validNoteCount"))
    }

    @Test fun latestSummaryUsesCreationTimeThenLastListPositionForTies() {
        val first = summary("first", messages, 30)
        val older = summary("older", messages, 10)
        val last = summary("last", messages, 30)
        val chat = Chat(messages = messages, summaries = listOf(first, older, last), activeSummaryId = last.id)
        val report = JSONObject(MemoryDiagnostics.report(chat, null))
        assertEquals("last", report.getString("latestSummaryId"))
        assertTrue(report.getBoolean("activeMatchesLatestSummary"))
    }

    @Test fun excludesAnotherChatsRebuildAndNormalizesUnexpectedPhaseText() {
        val chat = Chat(id = "current")
        val foreign = MemoryRebuildState(chatId = "other-chat", summaryId = "other-summary", message = "PRIVATE_REBUILD_ERROR")
        val other = MemoryDiagnostics.report(chat, foreign)
        assertFalse(JSONObject(other).has("rebuild"))
        assertFalse(other.contains("other-chat"))
        assertFalse(other.contains("other-summary"))
        val unexpected = MemoryDiagnostics.report(chat, foreign.copy(chatId = chat.id, phase = "PRIVATE_PHASE_ERROR_sk_secret"))
        assertEquals("unknown", JSONObject(unexpected).getJSONObject("rebuild").getString("phase"))
        assertFalse(unexpected.contains("PRIVATE_PHASE_ERROR_sk_secret"))
        assertFalse(unexpected.contains("PRIVATE_REBUILD_ERROR"))
    }

    private fun summary(id: String, sources: List<Message>, createdAt: Long) = Summary(
        id = id, text = "Synthetic summary", throughMessageId = sources.last().id,
        sourceHash = ContextEngine.sourceHash(sources), createdAt = createdAt, modelId = "diagnostic-model"
    )

    private fun findSummary(report: JSONObject, id: String): JSONObject {
        val summaries = report.getJSONArray("summaries")
        return (0 until summaries.length()).map(summaries::getJSONObject).single { it.getString("id") == id }
    }

    private fun keys(value: JSONObject): Set<String> = value.keys().asSequence().toSet()

    private val topLevelKeys = setOf(
        "schemaVersion", "chatId", "messageCount", "uniqueMessageIdCount", "duplicateMessageIdCount", "blankMessageIdCount",
        "lastMessageId", "lastMessageIdFirstIndex", "activeSummaryId", "activeSummaryFound", "latestSummaryId", "activeMatchesLatestSummary",
        "summaryCount", "summaries", "noteCount", "validNoteCount", "researchNoteCount", "effectiveSummaryId", "summaryResolution"
    )
    private val summaryKeys = setOf(
        "id", "createdAt", "modelId", "throughMessageId", "endIndex", "coverageCount", "sourceValid", "isActive", "cutoffMatchCount", "format"
    )
    private val rebuildKeys = setOf("chatId", "phase", "completed", "total", "noteCount", "summaryId", "sourceMessageCount", "finishedAt")
}
