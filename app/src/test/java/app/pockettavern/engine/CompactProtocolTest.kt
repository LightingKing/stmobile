package app.pockettavern.engine

import app.pockettavern.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompactProtocolTest {
    private val source = Message(id = "new", content = "林岚将铜钥匙交还周行，现在站在码头。")
    private fun slice(message: Message = source, previous: String = "") =
        CompressionSlice(listOf(message), message.id, ContextEngine.sourceHash(listOf(message)), previous)

    @Test fun oneResponseUpdatesWorkingStateAndAppendsPreciselySourcedKnowledge() = runBlocking {
        val previous = "林岚持有钥匙；两人约好明天见面。"
        val updated = "钥匙已归还周行；林岚在码头。明天见面的约定仍未完成。"
        val result = HistoryCompressor.compress(slice(previous = previous), 32768, "") { input ->
            assertEquals(previous, JSONObject(input).getString("previousSummary"))
            val response = JSONObject(compactReply(input, updated, "林岚持有→在码头归还→周行持有").content)
            response.getJSONArray("memories").getJSONObject(0)
                .put("title", "铜钥匙归还").put("kind", "event")
                .put("entity", "item:铜钥匙").put("aliases", JSONArray(listOf("银钥匙", "钥匙")))
            CompletionResult(content = response.toString(), finishReason = "stop")
        }
        assertEquals(updated, result.summaryText)
        assertEquals(1, result.requestCount)
        val note = result.notes.single { it.kind == "event" }
        assertEquals("item:铜钥匙", note.entity)
        assertEquals(listOf("银钥匙", "钥匙"), note.aliases)
        assertEquals(listOf(source.id), note.sourceMessageIds)
        assertEquals(ContextEngine.sourceHash(listOf(source)), note.sourceHash)
        assertTrue(result.notes.single { it.kind == "archive" }.text.contains("不表示所有细节均已提取"))
    }

    @Test fun noNewDurableFactStillCommitsWorkingStateWithAnHonestSourceIndex() = runBlocking {
        val result = HistoryCompressor.compress(slice(previous = "仍在码头。"), 32768, "") {
            compactReply(it, "仍在码头等待。", text = "")
        }
        assertEquals("仍在码头等待。", result.summaryText)
        assertEquals("archive", result.notes.single().kind)
        val current = Chat(messages = listOf(source))
        val withoutPrevious = slice()
        val committed = IncrementalMemory.commit(current, withoutPrevious, result, "model")
        assertEquals(result.summaryText, committed.summaries.last().text)
        assertEquals(HistoryCompressor.FORMAT, committed.summaries.last().format)
    }

    @Test fun malformedPartialOrUnverifiableResultsNeverAdvanceTheCheckpoint() = runBlocking {
        val invalidResults = listOf<(String) -> String>(
            { "普通正文不能作为结构化压缩结果" },
            { "{\"summary\":\"不完整\",\"memories\":[" },
            { "{\"summary\":\"\",\"memories\":[]}" },
            { "{\"summary\":\"有摘要但缺少增量字段\"}" },
            { JSONObject(compactReply(it, "摘要").content).apply {
                getJSONArray("memories").getJSONObject(0).put("sourceMessageIds", JSONArray(listOf("invented")))
            }.toString() },
            { JSONObject(compactReply(it, "摘要").content).apply {
                getJSONArray("memories").getJSONObject(0).put("sourceMessageIds", JSONArray(listOf(source.id, source.id)))
            }.toString() },
            { JSONObject(compactReply(it, "摘要").content).apply {
                getJSONArray("memories").getJSONObject(0).put("kind", "archive")
            }.toString() },
            { compactReply(it, "摘要").content + " trailing garbage" }
        )
        for (invalid in invalidResults) {
            var persisted = false
            val failure = runCatching {
                HistoryCompressor.compress(slice(), 32768, "", onCheckpoint = { persisted = true }) {
                    CompletionResult(content = invalid(it), finishReason = "stop")
                }
            }
            assertTrue(failure.isFailure)
            assertFalse(persisted)
        }
    }

    @Test fun invalidProviderFinishReasonsRejectEvenOtherwiseCompleteJson() = runBlocking {
        for (finish in listOf("length", "content_filter", "tool_calls", "")) {
            assertTrue(runCatching {
                HistoryCompressor.compress(slice(), 32768, "") { compactReply(it, "摘要", finishReason = finish) }
            }.isFailure)
        }
    }

    @Test fun failedLaterRequestResumesWithoutResendingTheCompletedSource() = runBlocking {
        val message = source.copy(content = "码头等待🌙".repeat(2500))
        val snapshot = slice(message)
        var checkpoint: CompactCheckpoint? = null
        val sentBefore = mutableListOf<String>()
        val failure = runCatching {
            HistoryCompressor.compress(snapshot, 8192, "", onCheckpoint = { checkpoint = it }) {
                sentBefore += it
                if (sentBefore.size == 2) throw java.io.IOException("connection interrupted")
                compactReply(it, "已完成第一批，仍在码头。")
            }
        }
        assertTrue(failure.isFailure)
        assertNotNull(checkpoint)
        assertEquals(1, checkpoint!!.requestCount)
        val resumedInputs = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = HistoryCompressor.compress(snapshot, 8192, "", resume = checkpoint,
            onProgress = { done, total -> progress += done to total }) {
            resumedInputs += it
            compactReply(it, "仍在码头，后续已整理。")
        }
        assertEquals(1, progress.first().first)
        assertEquals(JSONObject(sentBefore[1]).getJSONArray("sources").toString(), JSONObject(resumedInputs.first()).getJSONArray("sources").toString())
        assertEquals(HistorySource.memoryText(message), compactSourceText(sentBefore.first()) + resumedInputs.joinToString("") { compactSourceText(it) })
        assertEquals(1 + resumedInputs.size, result.requestCount)
        assertEquals(checkpoint!!.notes, result.notes.take(checkpoint!!.notes.size))
        assertEquals(result.requestCount to result.requestCount, progress.last())
    }

    @Test fun finalSavedCheckpointCanBeCommittedWithoutAnotherPaidRequest() = runBlocking {
        var checkpoint: CompactCheckpoint? = null
        val first = HistoryCompressor.compress(slice(), 32768, "", onCheckpoint = { checkpoint = it }) { compactReply(it, "已抵达码头。") }
        val resumed = HistoryCompressor.compress(slice(), 32768, "", resume = checkpoint) { error("Must not call the model again") }
        assertEquals(first.summaryText, resumed.summaryText)
        assertEquals(first.requestCount, resumed.requestCount)
        assertEquals(first.notes.filterNot { it.kind == "archive" }, resumed.notes.filterNot { it.kind == "archive" })
    }

    @Test fun regenerationAfterAutomaticCompressionFailureReusesThePaidCheckpoint() = runBlocking {
        val user = Message(id = "large-user", content = "整理早期人物关系和约定。".repeat(1200))
        val initial = Chat(id = "chat", providerId = "p", modelId = "m", messages = listOf(user))
        val data = AppData(providers = listOf(ProviderConfig(id = "p", models = listOf(ModelInfo(id = "m", contextTokens = 8192)))),
            presets = listOf(Preset(id = "default", maxOutput = 512)), settings = AppSettings(keepRecentTurns = 2))
        assertTrue(ContextEngine.build(data, initial).needsCompression)
        val firstSlice = ContextEngine.compressionSlice(data, initial)!!
        var checkpoint: CompactCheckpoint? = null
        val initialRequests = mutableListOf<String>()
        assertTrue(runCatching {
            HistoryCompressor.compress(firstSlice, 8192, "", onCheckpoint = { checkpoint = it }) {
                initialRequests += it
                if (initialRequests.size == 2) throw java.io.IOException("second request failed")
                compactReply(it, "第一批已整理。")
            }
        }.isFailure)
        val failedChat = initial.copy(messages = initial.messages + Message(id = "empty-error", role = "assistant", status = "error", errorMessage = "压缩请求失败"))
        val retried = ConversationTurns.regenerate(failedChat)
        val retrySlice = ContextEngine.compressionSlice(data, retried)!!
        assertNotEquals(firstSlice.sourceHash, retrySlice.sourceHash)
        assertEquals(ContextEngine.sourceHash(firstSlice.messages), ContextEngine.sourceHash(retrySlice.messages))
        val retryRequests = mutableListOf<String>()
        val result = HistoryCompressor.compress(retrySlice, 8192, "", resume = checkpoint) {
            retryRequests += it
            compactReply(it, "全部新增资料已整理。")
        }
        assertEquals(JSONObject(initialRequests[1]).getJSONArray("sources").toString(), JSONObject(retryRequests.first()).getJSONArray("sources").toString())
        assertEquals(1 + retryRequests.size, result.requestCount)
        val saved = IncrementalMemory.commit(retried, retrySlice, result, "m")
        assertEquals(retried.messages, saved.messages)
        assertEquals(retrySlice.sourceHash, saved.summaries.last().sourceHash)
        assertEquals(saved.summaries.last(), ContextEngine.resolveSummary(saved).effective)
    }

    @Test fun editedSourceOrChangedPromptInvalidatesCachedProgressAndReadsTheNewInput() = runBlocking {
        var checkpoint: CompactCheckpoint? = null
        HistoryCompressor.compress(slice(), 32768, "", onCheckpoint = { checkpoint = it }) { compactReply(it, "已抵达码头。") }
        val edited = source.copy(content = "更正：钥匙还未归还。")
        val tests = listOf(slice(edited) to "", slice() to "强调关系变化", slice(previous = "修改过的旧工作摘要") to "")
        tests.forEach { (snapshot, prompt) ->
            var calls = 0
            val result = HistoryCompressor.compress(snapshot, 32768, prompt, resume = checkpoint) {
                calls++
                assertEquals(HistorySource.memoryText(snapshot.messages.single()), compactSourceText(it))
                compactReply(it, "已根据最新原文重新整理。")
            }
            assertEquals(1, calls)
            assertEquals(1, result.requestCount)
        }
    }

    @Test fun aFailedCheckpointSaveStopsBeforeAnotherRequest() = runBlocking {
        val message = source.copy(content = "大量原文".repeat(4000))
        var calls = 0
        val progress = mutableListOf<Int>()
        val failure = runCatching {
            HistoryCompressor.compress(slice(message), 8192, "", onProgress = { done, _ -> progress += done },
                onCheckpoint = { throw java.io.IOException("disk full") }) {
                calls++
                compactReply(it, "当前状态")
            }
        }
        assertTrue(failure.isFailure)
        assertEquals(1, calls)
        assertEquals(listOf(0), progress)
    }
}
