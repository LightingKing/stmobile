package app.pockettavern.engine

import app.pockettavern.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * 0.2.1 long-novel memory. The user's novel sent about 214k tokens per message and was never
 * compressed: 11 Skills filled the 65k default budget, the budget was then set to 0, and 0 meant
 * "never fold". History is now folded by its own length, a chapter at a time, and each request
 * carries people files, chapter digests, the working summary and the recent original text.
 */
class LongMemoryTest {
    private fun data(settings: AppSettings = AppSettings(maxContextInputTokens = 0), skills: List<SkillRecord> = emptyList(), tools: Boolean = true) = AppData(
        providers = listOf(ProviderConfig(id = "p", kind = "openai-compatible",
            models = listOf(ModelInfo(id = "m", contextTokens = 1_000_000, supportsTools = tools)))),
        skills = skills, settings = settings
    )

    /** [turns] user/assistant pairs, then the new user message. */
    private fun novel(turns: Int, prose: String = "雪落在庄园的屋檐上。") = (1..turns).flatMap { index ->
        listOf(Message(id = "u$index", content = "继续第 $index 段"), Message(id = "a$index", role = "assistant", content = "第 $index 段：$prose"))
    } + Message(id = "now", content = "接着写")

    private fun chat(messages: List<Message>, skillIds: List<String> = emptyList()) =
        Chat(providerId = "p", modelId = "m", messages = messages, skillIds = skillIds)

    private fun note(kind: String, title: String, text: String, sources: List<Message>, entity: String = "", createdAt: Long = 0) = KnowledgeNote(
        text = text, title = title, kind = kind, entity = entity, sourceMessageIds = sources.map { it.id },
        sourceHash = ContextEngine.sourceHash(sources), createdAt = createdAt)

    private fun summarized(messages: List<Message>, through: String, notes: List<KnowledgeNote> = emptyList(), rebuilt: Boolean = false): Chat {
        val end = messages.indexOfFirst { it.id == through }
        val summary = Summary(id = "s", text = "WORKING_SUMMARY", throughMessageId = through, sourceHash = ContextEngine.sourceHash(messages.take(end + 1)),
            compression = if (rebuilt) CompressionRecord(reason = "FULL_REBUILD") else null, format = HistoryCompressor.FORMAT)
        return chat(messages).copy(summaries = listOf(summary), activeSummaryId = summary.id, knowledgeNotes = notes)
    }

    @Test fun aBudgetOfZeroNoLongerMeansNeverFold() {
        // The window of 16 turns counts the message being answered. 23 finished turns + the new
        // one = 16 + a chapter of 8: due, with the budget off and the request far from any limit.
        val built = ContextEngine.build(data(), chat(novel(23)))
        assertTrue(built.budget > 900_000)
        assertTrue(built.needsCompression)
        assertEquals("HISTORY_LENGTH", built.budgetBreakdown!!.triggerReason)
        assertFalse("one turn short of a chapter", ContextEngine.build(data(), chat(novel(22))).needsCompression)
    }

    @Test fun selectedSkillsPlayNoPartInWhenHistoryIsFolded() {
        val heavy = (1..11).map { SkillRecord(id = "skill-$it", name = "大型规则 $it", content = "规则".repeat(3000)) }
        val withSkills = ContextEngine.build(data(skills = heavy), chat(novel(23), heavy.map { it.id }))
        assertTrue(withSkills.budgetBreakdown!!.skillTokens > 50_000)
        assertTrue(withSkills.needsCompression)
        assertEquals("HISTORY_LENGTH", withSkills.budgetBreakdown!!.triggerReason)
        assertFalse(ContextEngine.build(data(skills = heavy), chat(novel(20), heavy.map { it.id })).needsCompression)
    }

    @Test fun aChapterFoldKeepsTheRecentWindowAsOriginalText() {
        val built = ContextEngine.build(data(), chat(novel(23)))
        val slice = built.compression!!
        assertEquals("the oldest 8 turns become a chapter", (1..8).flatMap { listOf("u$it", "a$it") }, slice.messages.map { it.id })
        assertEquals("a8", slice.throughMessageId)
        assertEquals(8, built.budgetBreakdown!!.foldTurns)
    }

    @Test fun longProseFoldsByTokensBeforeTheTurnCount() {
        // Eight long turns of about 6,000 tokens each pass the token line well before 24 turns.
        val built = ContextEngine.build(data(), chat(novel(10, "雪".repeat(4000))))
        assertTrue(built.needsCompression)
        val kept = 10 - built.compression!!.messages.count { it.role == "user" }
        assertTrue("the recent window stays within its token cap", kept in 1..5)
    }

    @Test fun anOldChatWithALongBacklogAsksInsteadOfFoldingOnItsOwn() {
        // The user's novel: 95 turns, never folded. Folding all but the window costs ten requests.
        val built = ContextEngine.build(data(), chat(novel(95)))
        val breakdown = built.budgetBreakdown!!
        assertEquals("HISTORY_BACKLOG", breakdown.triggerReason)
        assertFalse("no paid requests before the reply without the user's go", built.needsCompression)
        assertEquals(80, breakdown.foldTurns)
        assertEquals(10, LongMemory.foldRequests(breakdown.foldTurns))
        assertNotNull("the button still has a plan to run", built.compression)
        assertTrue(CompressionScope.describeBudget(breakdown).contains("整理记忆"))
    }

    @Test fun budgetPressureStillFoldsALongBacklogAsBefore() {
        val built = ContextEngine.build(data(AppSettings(maxContextInputTokens = 20_000)), chat(novel(95, "雪落在庄园的屋檐上。".repeat(10))))
        val breakdown = built.budgetBreakdown!!
        assertEquals(CompressionScope.describeBudget(breakdown), "INPUT_LIMIT_REACHED", breakdown.triggerReason)
        assertTrue(CompressionScope.describeBudget(breakdown), built.needsCompression)
    }

    @Test fun peopleFilesAndChaptersGoWithTheSummaryNewestFileWinning() {
        val messages = novel(30)
        val first = messages.take(16)
        val second = messages.subList(16, 32)
        val notes = listOf(
            note(LongMemory.CHAPTER, "雪夜", "CHAPTER_ONE_TEXT", first),
            note(LongMemory.CHAPTER, "地窖", "CHAPTER_TWO_TEXT", second),
            note(LongMemory.PROFILE, "林岚", "LIN_OLD_FILE", first, "person:林岚"),
            note(LongMemory.PROFILE, "林岚", "LIN_NEW_FILE", second, "person:林岚"),
            note(LongMemory.PROFILE, "周行", "ZHOU_FILE", first, "person:周行"),
            // Past the summary: its turns are sent as original text, so this file is not used yet.
            note(LongMemory.PROFILE, "顾砚舟", "GU_FUTURE_FILE", messages.subList(40, 42), "person:顾砚舟"),
            // Its source was edited after it was written.
            note(LongMemory.CHAPTER, "旧稿", "STALE_CHAPTER", first).copy(sourceHash = "changed")
        )
        val sent = ContextEngine.build(data(), summarized(messages, "a16", notes))
        val json = sent.messagesJson
        assertTrue(json.contains("【人物档案】") && json.contains("【章节摘要】") && json.contains("WORKING_SUMMARY"))
        assertTrue(json.contains("LIN_NEW_FILE") && json.contains("ZHOU_FILE"))
        assertFalse("only the newest file of a person", json.contains("LIN_OLD_FILE"))
        assertFalse(json.contains("GU_FUTURE_FILE"))
        assertFalse(json.contains("STALE_CHAPTER"))
        assertTrue(json.indexOf("CHAPTER_ONE_TEXT") < json.indexOf("CHAPTER_TWO_TEXT"))
        assertTrue("files, then chapters, then where things stand", json.indexOf("【人物档案】") < json.indexOf("【章节摘要】") && json.indexOf("【章节摘要】") < json.indexOf("WORKING_SUMMARY"))
        val breakdown = sent.budgetBreakdown!!
        assertTrue(breakdown.chapterTokens > 0 && breakdown.profileTokens > 0)
        assertEquals(LongMemoryTestSupport.turnsAfter(messages, "a16"), breakdown.recentTurns)
    }

    @Test fun withoutAValidSummaryNoLayersAreSent() {
        val messages = novel(5)
        val notes = listOf(note(LongMemory.CHAPTER, "雪夜", "ORPHAN_CHAPTER", messages.take(4)))
        val json = ContextEngine.build(data(), chat(messages).copy(knowledgeNotes = notes)).messagesJson
        assertFalse("the original text itself is being sent", json.contains("ORPHAN_CHAPTER"))
    }

    @Test fun manyChaptersShrinkFromTheOldestEnd() {
        val source = listOf(Message(id = "x", content = "x"))
        val chapters = (1..60).map { note(LongMemory.CHAPTER, "第${it}章标题", "情节".repeat(150) + "END$it", source) }
        val text = LongMemory.chapterPrompt(chapters, cap = 4_000)
        assertTrue(ContextEngine.estimateTokens(text) <= 4_000 + 40)
        assertTrue("the newest chapter is whole", text.contains("END60"))
        assertFalse("the oldest is down to its title", text.contains("END1\n") || text.endsWith("END1"))
        assertTrue(text.contains("第 1 章《第1章标题》") || text.contains("更早的"))
    }

    @Test fun pastTurnsGoWithoutTheirThinkingButTheOpenToolRoundKeepsIt() {
        val call = JSONArray().put(JSONObject().put("id", "c1").put("type", "function")
            .put("function", JSONObject().put("name", "skill_list").put("arguments", "{}"))).toString()
        val messages = listOf(
            Message(content = "第一问"), Message(role = "assistant", content = "第一答", reasoning = "OLD_THINKING"),
            Message(content = "第二问"), Message(role = "assistant", content = "", reasoning = "CURRENT_THINKING", toolCallsJson = call),
            Message(role = "tool", content = "[]", toolCallId = "c1")
        )
        val json = ContextEngine.build(data(), chat(messages)).messagesJson
        assertFalse(json.contains("OLD_THINKING"))
        assertTrue("a provider continuing its own tool round needs it", json.contains("CURRENT_THINKING"))
        assertTrue(json.contains("第一答"))
    }

    @Test fun pastFetchedPagesAreNotSentAgain() {
        fun call(id: String, name: String) = JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", "{}"))).toString()
        val messages = listOf(
            Message(content = "看看这个网页"), Message(role = "assistant", toolCallsJson = call("f1", "web_fetch")),
            Message(role = "tool", content = "OLD_PAGE_TEXT", toolCallId = "f1"),
            Message(role = "assistant", content = "网页说的是甲。"),
            Message(content = "再查一个"), Message(role = "assistant", toolCallsJson = call("f2", "public_research")),
            Message(role = "tool", content = "NEW_RESEARCH_TEXT", toolCallId = "f2")
        )
        val json = ContextEngine.build(data(), chat(messages)).messagesJson
        assertFalse(json.contains("OLD_PAGE_TEXT"))
        assertTrue(json.contains("网页说的是甲"))
        assertTrue("this turn still has its result", json.contains("NEW_RESEARCH_TEXT"))
    }

    @Test fun afterAFullRebuildTheLatestTurnsGoAgainAsOriginalText() {
        val messages = novel(30)
        val rebuilt = summarized(messages, "now", rebuilt = true)
        val json = ContextEngine.build(data(), rebuilt).messagesJson
        assertTrue("the prose itself, not only its summary", json.contains("第 30 段：") && json.contains("第 16 段："))
        assertFalse("only the recent window", json.contains("第 14 段："))
        // An incremental summary leaves the window unfolded itself, so it re-sends nothing.
        val incremental = summarized(messages, "a20")
        assertFalse(ContextEngine.build(data(), incremental).messagesJson.contains("第 20 段："))
    }

    @Test fun theOldDefaultOfEightRecentTurnsBecomesSixteenOnce() {
        val old = AppSettings(keepRecentTurns = 8, memoryRevision = 0)
        val moved = LongMemory.migrate(old)
        assertEquals(16, moved.keepRecentTurns)
        assertEquals(LongMemory.SETTINGS_REVISION, moved.memoryRevision)
        assertEquals("a value the user chose is kept", 12, LongMemory.migrate(old.copy(keepRecentTurns = 12)).keepRecentTurns)
        assertEquals("only once: choosing 8 later sticks", 8, LongMemory.migrate(moved.copy(keepRecentTurns = 8)).keepRecentTurns)
        assertEquals(16, AppSettings().keepRecentTurns)
    }

    @Test fun dueFollowsTurnsAndTokens() {
        assertFalse(LongMemory.due(liveTurns = 23, liveTokens = 1_000, keepTurns = 16))
        assertTrue(LongMemory.due(liveTurns = 24, liveTokens = 1_000, keepTurns = 16))
        assertTrue(LongMemory.due(liveTurns = 6, liveTokens = LongMemory.RECENT_TOKEN_CAP + LongMemory.CHAPTER_TOKEN_CAP + 1, keepTurns = 16))
        assertEquals(1, LongMemory.foldRequests(8))
        assertEquals(2, LongMemory.foldRequests(9))
    }
}

internal object LongMemoryTestSupport {
    /** User turns after [id], counting the new message. */
    fun turnsAfter(messages: List<Message>, id: String): Int = messages.drop(messages.indexOfFirst { it.id == id } + 1).count { it.role == "user" }
}
