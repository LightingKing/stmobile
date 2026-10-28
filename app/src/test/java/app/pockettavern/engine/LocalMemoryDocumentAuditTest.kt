package app.pockettavern.engine

import app.pockettavern.data.Exchange
import app.pockettavern.domain.*
import app.pockettavern.ui.MemoryBrowser
import app.pockettavern.ui.MemoryEntryKind
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** Opt-in offline checks. Private documents and the search term never enter repository fixtures or output. */
class LocalMemoryDocumentAuditTest {
    @Test fun completeLocalDocumentsRemainRetrievableInsideNineteenMessageContainers() = runBlocking {
        val input = inputs()
        input.documents.forEachIndexed { index, text ->
            val attachment = Attachment(
                id = "archive-attachment", name = "audit.md", mime = "text/markdown",
                dataUrl = "data:text/markdown;base64," + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
            )
            val source = Message(id = "archive", role = "user", attachments = listOf(attachment))
            val messages = listOf(source) + (1..18).map { number ->
                Message(id = "followup-$number", role = if (number % 2 == 0) "user" else "assistant", content = "Audit follow-up $number")
            }
            assertEquals(19, messages.size)
            val chat = Chat(id = "audit-chat", providerId = PROVIDER, modelId = MODEL, messages = messages)
            audit(chat, input.query, text.length, "attachment", index + 1)
        }
    }

    @Test fun formallyImportedLocalDocumentsRemainRetrievableAfterRebuild() = runBlocking {
        val input = inputs()
        input.documents.forEachIndexed { index, text ->
            val chat = runCatching { Exchange.chat(text, PROVIDER, MODEL) }
                .getOrElse { throw AssertionError("Local document could not be imported as conversation messages") }
            assertTrue("Formal import must produce multiple messages", chat.messages.size > 1)
            assertTrue("Formal import must retain the requested source fact", chat.messages.any { HistorySource.searchableText(it).contains(input.query, true) })
            audit(chat, input.query, text.length, "messages", index + 1)
        }
    }

    private suspend fun audit(chat: Chat, query: String, documentChars: Int, mode: String, ordinal: Int) {
        val snapshot = MemoryRebuilder.snapshot(chat)
        val observed = MessageDigest.getInstance("SHA-256")
        var calls = 0
        var sourceChars = 0
        var largestSegment = 0
        val completed = HistoryCompressor.compress(snapshot, 1_000_000, "") { source ->
            calls++
            val raw = compactSourceText(source)
            sourceChars += raw.length
            val tokens = ContextEngine.estimateTokens(source)
            largestSegment = maxOf(largestSegment, tokens)
            assertTrue("A compact request exceeded the actual model budget", tokens + ContextEngine.estimateTokens(HistoryCompressor.GUIDE) + HistoryCompressor.outputLimit(1_000_000) + HistoryCompressor.safetyReserve(1_000_000) <= 1_000_000)
            observed.update(raw.toByteArray(Charsets.UTF_8))
            compactReply(source, "OFFLINE_AUDIT_NOTE_$calls")
        }
        val originalSource = chat.messages.filterNot { it.contextExcluded }.joinToString("") { HistorySource.memoryText(it) }
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(originalSource.toByteArray(Charsets.UTF_8))
        assertTrue("The exact ordered source must reach the mocked summarizer without loss", expectedDigest.contentEquals(observed.digest()))
        assertEquals(originalSource.length, sourceChars)
        assertTrue("Mock summaries must deliberately omit the requested source fact", completed.notes.none { it.text.contains(query, true) })
        assertFalse("The guide must also omit the requested source fact in this fixture", completed.summaryText.contains(query, true))

        val rebuilt = MemoryRebuilder.commit(chat, snapshot, completed, MODEL)
        assertTrue("Rebuild must preserve the original message list", rebuilt.messages === chat.messages)
        assertTrue("Rebuild must preserve all original message fields", ContextEngine.sourceHash(rebuilt.messages) == snapshot.sourceHash)
        assertTrue("Rebuild must preserve the current conversation", rebuilt.id == chat.id)
        assertEquals(calls, completed.requestCount)
        assertEquals(calls + 1, KnowledgeVault.validNotes(rebuilt).size)
        val currentSummary = rebuilt.summaries.single { it.id == rebuilt.activeSummaryId }
        assertTrue("The reader must validate the full source prefix", MemoryBrowser.source(rebuilt, currentSummary).valid)

        val hits = KnowledgeVault.search(rebuilt, query)
        assertTrue("Raw-history retrieval must recover the omitted source fact", hits.any { it.id.startsWith("message:") && it.text.contains(query, true) })
        val index = MemoryBrowser.index(rebuilt)
        assertTrue("The guide alone must not appear to contain the omitted source fact", MemoryBrowser.search(index, query, section = 0).isEmpty())
        val readerHits = MemoryBrowser.search(index, query)
        assertTrue("Global memory search must find an original source", readerHits.any { it.entry.kind == MemoryEntryKind.ORIGINAL && it.excerpt.contains(query, true) })

        val model = ModelInfo(id = MODEL, contextTokens = 1_000_000, supportsTools = false)
        val data = AppData(providers = listOf(ProviderConfig(id = PROVIDER, models = listOf(model))))
        val withQuestion = rebuilt.copy(messages = rebuilt.messages + Message(id = "audit-question", content = query))
        val built = ContextEngine.build(data, withQuestion)
        assertEquals("[]", built.toolDefinitions)
        val outgoing = JSONArray(built.messagesJson)
        val recalled = (0 until outgoing.length()).map { outgoing.getJSONObject(it) }.filter {
            it.optString("role") == "system" && it.optString("content").startsWith("【会话知识库召回】")
        }
        assertTrue("On-demand memory never silently injects historical search results", recalled.isEmpty())
        assertTrue("Unsupported models must receive an honest capability notice", built.messagesJson.contains("当前模型未启用历史检索工具"))
        println("LocalMemoryAudit mode=$mode document=$ordinal chars=$documentChars messages=${chat.messages.size} segments=$calls maxSegmentTokens=$largestSegment vaultHits=${hits.size} readerHits=${readerHits.size} recallBlocks=${recalled.size}")
    }

    private data class Inputs(val documents: List<String>, val query: String)

    private fun inputs(): Inputs {
        val primary = System.getenv("POCKET_MEMORY_AUDIT_DOCUMENT").orEmpty()
        val query = System.getenv("POCKET_MEMORY_AUDIT_QUERY").orEmpty().trim()
        assumeTrue("Local document audit is opt-in", primary.isNotBlank() && query.isNotBlank())
        val paths = listOf(primary, System.getenv("POCKET_MEMORY_AUDIT_CLEANED_DOCUMENT").orEmpty()).filter { it.isNotBlank() }.distinct()
        val documents = paths.map { path ->
            runCatching { File(path).readText(Charsets.UTF_8) }
                .getOrElse { throw AssertionError("An audit document could not be read") }
        }
        assertTrue("Each audit document must contain the requested source fact", documents.all { it.contains(query, true) })
        return Inputs(documents, query)
    }

    private companion object {
        const val PROVIDER = "offline-audit-provider"
        const val MODEL = "offline-audit-model"
    }
}
