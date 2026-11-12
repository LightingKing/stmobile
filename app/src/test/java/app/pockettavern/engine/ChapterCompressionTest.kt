package app.pockettavern.engine

import app.pockettavern.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The summary model writes a chapter digest and the people files along with each fold. */
class ChapterCompressionTest {
    private fun turns(count: Int) = (1..count).flatMap { index ->
        listOf(Message(id = "u$index", content = "第 $index 轮提问"), Message(id = "a$index", role = "assistant", content = "第 $index 轮：林岚与周行在雪夜赶路。"))
    }

    private fun slice(messages: List<Message>, previous: String = "") =
        CompressionSlice(messages, messages.last().id, ContextEngine.sourceHash(messages), previousSummary = previous)

    /** A protocol-correct reply with a chapter and one person, as the new prompt asks for. */
    private fun reply(input: String, chapter: String, person: String = "林岚", profile: String = "林岚：持铜钥匙。"): CompletionResult {
        val base = JSONObject(compactReply(input, "摘要：$chapter").content)
        base.put("chapter", JSONObject().put("title", chapter).put("text", "$chapter 的经过"))
        base.put("people", JSONArray().put(JSONObject().put("name", person).put("aliases", JSONArray().put("阿岚")).put("profile", profile)))
        return CompletionResult(content = base.toString(), finishReason = "stop")
    }

    @Test fun eachRequestCoversOneChapterOfTurns() = runBlocking {
        val inputs = mutableListOf<String>()
        val source = turns(20)
        val result = HistoryCompressor.compress(slice(source), 200_000, "", maxTurnsPerRequest = 8) { input ->
            inputs += input
            reply(input, "第${inputs.size}章")
        }
        assertEquals("20 turns: 8 + 8 + 4", 3, inputs.size)
        assertEquals(listOf(16, 16, 8), inputs.map { compactSourceIds(it).size })
        val chapters = result.notes.filter { it.kind == LongMemory.CHAPTER }
        assertEquals(listOf("第1章", "第2章", "第3章"), chapters.map { it.title })
        assertEquals((1..8).flatMap { listOf("u$it", "a$it") }, chapters.first().sourceMessageIds)
        assertEquals(ContextEngine.sourceHash(source.take(16)), chapters.first().sourceHash)
    }

    @Test fun peopleFilesAreHandedBackSoTheModelUpdatesThem() = runBlocking {
        val known = KnowledgeNote(text = "OLD_FILE", title = "周行", kind = LongMemory.PROFILE, entity = "person:周行",
            sourceMessageIds = listOf("x"), sourceHash = "h")
        val inputs = mutableListOf<String>()
        val result = HistoryCompressor.compress(slice(turns(16)), 200_000, "", profiles = listOf(known), maxTurnsPerRequest = 8) { input ->
            inputs += input
            reply(input, "第${inputs.size}章", profile = "FILE_FROM_REQUEST_${inputs.size}")
        }
        fun files(input: String) = JSONObject(input).getJSONArray("profiles").let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("profile") } }
        assertEquals(listOf("OLD_FILE"), files(inputs[0]))
        assertTrue("the second request sees the file the first one wrote", files(inputs[1]).contains("FILE_FROM_REQUEST_1"))
        val written = result.notes.filter { it.kind == LongMemory.PROFILE }
        assertEquals(2, written.size)
        assertEquals("person:林岚", written.first().entity)
        assertEquals(listOf("阿岚"), written.first().aliases)
    }

    @Test fun aModelThatLeavesOutTheExtrasStillFolds() = runBlocking {
        val result = HistoryCompressor.compress(slice(turns(4)), 200_000, "", maxTurnsPerRequest = 8) { compactReply(it, "只有摘要") }
        assertEquals("只有摘要", result.summaryText)
        assertTrue(result.notes.none { it.kind == LongMemory.CHAPTER || it.kind == LongMemory.PROFILE })
        // A malformed extra is ignored, not fatal.
        val odd = HistoryCompressor.compress(slice(turns(4)), 200_000, "") { input ->
            val base = JSONObject(compactReply(input, "摘要").content).put("chapter", "不是对象").put("people", JSONArray().put("也不是对象"))
            CompletionResult(content = base.toString(), finishReason = "stop")
        }
        assertEquals("摘要", odd.summaryText)
    }

    @Test fun chatsWithoutFilesSendTheSameRequestAsBefore() = runBlocking {
        var input = ""
        HistoryCompressor.compress(slice(turns(2)), 200_000, "") { input = it; compactReply(it, "摘要") }
        assertFalse(JSONObject(input).has("profiles"))
    }

    @Test fun theFoldIsAcceptedAndItsLayersAreUsedNextTime() = runBlocking {
        val messages = turns(24) + Message(id = "now", content = "接着写")
        val chat = Chat(providerId = "p", modelId = "m", messages = messages)
        val data = AppData(providers = listOf(ProviderConfig(id = "p", models = listOf(ModelInfo(id = "m", contextTokens = 1_000_000)))),
            settings = AppSettings(maxContextInputTokens = 0))
        val plan = ContextEngine.build(data, chat).compression!!
        val result = HistoryCompressor.compress(plan, 200_000, "", maxTurnsPerRequest = 8) { reply(it, "雪夜", profile = "PROFILE_TEXT") }
        val folded = IncrementalMemory.commit(chat, plan, result, "m")
        val next = ContextEngine.build(data.copy(chats = listOf(folded)), folded).messagesJson
        assertTrue(next.take(3000), next.contains("PROFILE_TEXT"))
        assertTrue(next.contains("雪夜 的经过"))
        // 24 finished turns + the new one: the window keeps 16, so turns 1-9 were folded.
        assertFalse("folded turns are no longer sent as original text", next.contains("第 1 轮：") || next.contains("第 9 轮："))
        assertTrue(next.contains("第 10 轮：") && next.contains("第 24 轮："))
    }
}
