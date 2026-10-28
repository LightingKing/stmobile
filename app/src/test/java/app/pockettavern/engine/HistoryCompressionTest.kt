package app.pockettavern.engine

import app.pockettavern.domain.*
import app.pockettavern.data.DataCodec
import app.pockettavern.data.VaultExport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class HistoryCompressionTest {
    @Test fun segmentStartIsReportedBeforeEachRequestAndProgressOnlyAfterCompletion() = runBlocking {
        val source = Message(id = "long", content = "林岚在港口归还银钥匙。".repeat(900))
        val starts = mutableListOf<Pair<Int, Int>>()
        val progress = mutableListOf<Pair<Int, Int>>()
        var calls = 0
        HistoryCompressor.compress(
            MemoryRebuilder.snapshot(Chat(messages = listOf(source))), 4096, "",
            onProgress = { completed, total -> progress += completed to total },
            onSegmentStart = { segment, total -> starts += segment to total }
        ) {
            calls++
            assertEquals("segment ${calls - 1} must be completed before request $calls", calls - 1, progress.lastOrNull()?.first ?: 0)
            compactReply(it, "完成分段 $calls", finishReason = "stop")
        }
        assertEquals((1..calls).toList(), starts.map { it.first })
        assertTrue(starts.all { it.second >= it.first })
        assertEquals((0..calls).toList(), progress.map { it.first })
        assertEquals(calls to calls, progress.last())
        assertTrue(progress.all { it.second >= it.first })
    }

    @Test fun progressReportsThePlannedTotalAndOnlyCompletedSegments() = runBlocking {
        val source = Message(id = "long", content = "林岚在港口归还银钥匙。".repeat(900))
        val progress = mutableListOf<Pair<Int, Int>>()
        var calls = 0
        val result = HistoryCompressor.compress(
            MemoryRebuilder.snapshot(Chat(messages = listOf(source))), 4096, "",
            onProgress = { completed, total -> progress += completed to total }
        ) {
            calls++
            compactReply(it, "完成分段 $calls", finishReason = "stop")
        }
        assertTrue(calls > 1)
        assertEquals((0..calls).toList(), progress.map { it.first })
        assertEquals(calls to calls, progress.last())
        assertTrue(progress.all { it.second >= it.first })
        assertEquals(calls, result.requestCount)
        assertEquals(calls + 1, result.notes.size)
    }

    @Test fun segmentStartReportsTheInFlightSegmentSeparatelyFromCompletion() = runBlocking {
        val source = Message(id = "long", content = "林岚在港口归还银钥匙。".repeat(900))
        val starts = mutableListOf<Pair<Int, Int>>()
        val progress = mutableListOf<Pair<Int, Int>>()
        var calls = 0
        HistoryCompressor.compress(
            MemoryRebuilder.snapshot(Chat(messages = listOf(source))), 4096, "",
            onProgress = { completed, total -> progress += completed to total },
            onSegmentStart = { segment, total -> starts += segment to total }
        ) {
            calls++
            compactReply(it, "完成分段 $calls", finishReason = "stop")
        }
        assertEquals((1..calls).toList(), starts.map { it.first })
        assertTrue(starts.all { it.second >= it.first })
        assertEquals((0..calls).toList(), progress.map { it.first })
        assertEquals(calls to calls, progress.last())
        assertTrue(progress.all { it.second >= it.first })
    }

    @Test fun aFailedSegmentDoesNotReportCompletionOrReturnAPartialRebuild() = runBlocking {
        val source = Message(id = "long", content = "林岚在港口归还银钥匙。".repeat(900))
        val progress = mutableListOf<Pair<Int, Int>>()
        var calls = 0
        val failure = runCatching {
            HistoryCompressor.compress(
                MemoryRebuilder.snapshot(Chat(messages = listOf(source))), 4096, "",
                onProgress = { completed, total -> progress += completed to total }
            ) {
                calls++
                if (calls == 2) throw java.io.IOException("第二段网络中断")
                compactReply(it, "完整分段", finishReason = "stop")
            }
        }
        assertTrue(failure.isFailure)
        assertEquals(2, calls)
        assertEquals(listOf(0, 1), progress.map { it.first })
        assertTrue(progress.all { it.second > 1 })
    }

    @Test fun cancelledTransportCannotReturnASuccessfulSummary() = runBlocking {
        val source = Message(id = "source", content = "林岚在港口归还银钥匙。")
        val progress = mutableListOf<Pair<Int, Int>>()
        var returned = false
        val task = launch {
            HistoryCompressor.compress(
                MemoryRebuilder.snapshot(Chat(messages = listOf(source))), 4096, "",
                onProgress = { completed, total -> progress += completed to total }
            ) {
                currentCoroutineContext().cancel()
                // Simulates a transport that ignores cancellation while returning its last response.
                compactReply(it, "已完成", finishReason = "stop")
            }
            returned = true
        }
        task.join()
        assertTrue(task.isCancelled)
        assertFalse(returned)
        assertEquals(listOf(0 to 1), progress)
    }

    @Test fun cancellationAtFinalProgressCannotCommitTheResult() = runBlocking {
        val source = Message(id = "source", content = "林岚在港口归还银钥匙。")
        var returned = false
        val task = launch {
            val taskContext = currentCoroutineContext()
            HistoryCompressor.compress(
                MemoryRebuilder.snapshot(Chat(messages = listOf(source))), 4096, "",
                onProgress = { completed, total -> if (completed == total) taskContext.cancel() }
            ) { compactReply(it, "已完成", finishReason = "stop") }
            returned = true
        }
        task.join()
        assertTrue(task.isCancelled)
        assertFalse(returned)
    }

    @Test fun incrementalCompressionNeverRewritesEarlyRelationships() = runBlocking {
        val early = Message(id = "early", content = "林岚是周行的姐姐，已经归还银钥匙。")
        val first = HistoryCompressor.compress(CompressionSlice(listOf(early), early.id, ContextEngine.sourceHash(listOf(early))), 8192, "自定义要求") {
            compactReply(it, "## 人物关系\n林岚是周行的姐姐。\n## 已发生剧情\n已归还银钥匙。", finishReason = "stop")
        }
        val later = Message(id = "later", content = "今天来到港口。")
        val inputs = mutableListOf<String>()
        val next = HistoryCompressor.compress(CompressionSlice(listOf(later), later.id, "prefix", first.summaryText), 8192, "自定义要求") { input ->
            inputs += input
            compactReply(input, "## 已发生剧情\n今天来到港口。", finishReason = "end_turn")
        }
        assertEquals(first.summaryText, org.json.JSONObject(inputs.single()).getString("previousSummary"))
        assertFalse(compactSourceText(inputs.single()).contains("林岚"))
        val chat = Chat(messages = listOf(early, later), knowledgeNotes = first.notes + next.notes)
        assertEquals(first.notes.first().text, chat.knowledgeNotes.first().text)
        assertTrue(KnowledgeVault.search(chat, "林岚和周行什么关系").any { it.text.contains("姐姐") })
    }

    @Test fun truncatedOrFilteredSummaryCannotReplaceHistory() = runBlocking {
        for (reason in listOf("length", "max_tokens", "content_filter", "tool_calls", "")) {
            val failure = runCatching {
                HistoryCompressor.compress(CompressionSlice(listOf(Message(content = "历史")), "m", "hash"), 8192, "") {
                    CompletionResult(content = "半截摘要", finishReason = reason)
                }
            }
            assertTrue("must reject $reason", failure.isFailure)
        }
    }

    @Test fun longMessagesAreChunkedWithoutDroppingAnyText() = runBlocking {
        val original = "林岚周行银钥匙".repeat(2500)
        val message = Message(id = "long", content = original)
        val seen = mutableListOf<String>()
        val result = HistoryCompressor.compress(CompressionSlice(listOf(message), message.id, "prefix"), 4096, "要求") {
            seen += compactSourceText(it)
            compactReply(it, "分段事实", finishReason = "stop")
        }
        assertTrue(seen.size > 1)
        assertEquals(ContextEngine.summarySource(listOf(message)).single(), seen.joinToString(""))
        assertEquals(seen.size, result.requestCount)
    }

    @Test fun recallCanFindFactsMissingFromOldSummariesInRawHistory() {
        val source = Message(id = "source-7", role = "assistant", content = "三年前林岚在雨夜把铜钥匙交给周行，之后两人立下互不背叛的约定。")
        val later = Message(id = "latest", role = "user", content = "他们之间是什么约定？")
        val chat = Chat(messages = listOf(source, later), summaries = listOf(Summary(id = "s", text = "当前在雨夜。", throughMessageId = source.id,
            sourceHash = ContextEngine.sourceHash(listOf(source)))), activeSummaryId = "s")
        val hit = KnowledgeVault.search(chat, "林岚 周行 约定").first()
        assertEquals("message:source-7", hit.id)
        assertTrue(hit.text.contains("互不背叛"))
        assertEquals(source.content, org.json.JSONObject(KnowledgeVault.read(chat, hit.id)).getString("text").substringAfter("content="))
        val built = ContextEngine.build(AppData(), chat)
        assertFalse("past facts stay local until explicitly retrieved", built.messagesJson.contains("互不背叛"))
        assertTrue("unsupported models must not invent tool access", built.messagesJson.contains("当前模型未启用历史检索工具"))
        assertTrue("unrelated terms do not return arbitrary messages", KnowledgeVault.search(chat, "火星飞船").isEmpty())
    }

    @Test fun knowledgeVaultSurvivesAppBackupAndWritesReadableObsidianMarkdown() {
        val source = Message(id = "src", content = "林岚是周行的姐姐。")
        val note = KnowledgeNote(text = "## 人物关系\n林岚是周行的姐姐。", sourceMessageIds = listOf(source.id), sourceHash = ContextEngine.sourceHash(listOf(source)))
        val chat = Chat(title = "南方旅程", messages = listOf(source), knowledgeNotes = listOf(note), summaries = listOf(Summary(id = "s", text = "有人启程。", throughMessageId = source.id, sourceHash = ContextEngine.sourceHash(listOf(source)))), activeSummaryId = "s")
        val state = DataCodec.decode(DataCodec.encode(AppData(chats = listOf(chat))))
        assertEquals(chat.knowledgeNotes, state.chats.single().knowledgeNotes)
        val root = kotlin.io.path.createTempDirectory("vault-export").toFile()
        val zipFile = java.io.File(root, "vault.zip")
        try {
            VaultExport.write(chat, zipFile)
            java.util.zip.ZipFile(zipFile).use { zip ->
                val index = zip.getInputStream(zip.getEntry("index.md")).readBytes().toString(Charsets.UTF_8)
                val exportedNote = zip.getInputStream(zip.getEntry("notes/${VaultExport.token(note.id)}.md")).readBytes().toString(Charsets.UTF_8)
                assertTrue(index.contains("[[conversation|完整会话]]"))
                assertTrue(index.contains("[[notes/${VaultExport.token(note.id)}|分段 1]]"))
                assertTrue(exportedNote.contains("[[sources/${VaultExport.token(source.id)}|核对原文"))
                assertTrue(VaultExport.conversation(chat).contains("林岚是周行的姐姐。"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun oldConversationJsonWithoutKnowledgeFieldStillLoads() {
        val legacy = """{"schemaVersion":1,"providers":[{"id":"p","kind":"venice","baseUrl":"https://api.venice.ai"}],"chats":[{"id":"c","messages":[{"id":"m","role":"user","content":"保留原文"}]}],"presets":[{"id":"default"}]}"""
        assertTrue(DataCodec.decode(legacy).chats.single().knowledgeNotes.isEmpty())
    }
}
