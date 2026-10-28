package app.pockettavern.data

import app.pockettavern.domain.*
import app.pockettavern.engine.ContextEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class ExchangeWritingStateTest {
    private fun writingChat(): Chat {
        val old = Message(id = "old", role = "assistant", content = "林岚独自走进旧仓库。", createdAt = 10,
            contextExcluded = true, generationId = "generation-old")
        val correction = Message(id = "correction", content = "更正：林岚仍与周行同行。", createdAt = 20, intent = "correction")
        val rewrite = Message(id = "rewrite", role = "assistant", content = "林岚在仓库门口等周行。", createdAt = 30,
            intent = "rewrite", generationId = "generation-new", revisionOf = old.id,
            qualityNotice = "请核对是否重复旧稿；备注 --> 仍然只是文本。",
            skillSnapshotJson = JSONObject().put("count", 1).put("name", "叙事校对 --> 示例").toString())
        return Chat(title = "港口旅程", messages = listOf(old, correction, rewrite),
            continuityNotes = "地点：港口\n在场：林岚、周行\n笔记中的 --> 和 <script> 都是普通文字。")
    }

    @Test fun jsonlRoundTripPreservesDraftIdentityCorrectionsSnapshotsAndContinuity() {
        val source = writingChat()
        val jsonl = Exchange.chatJsonl(source)
        val imported = Exchange.chat(jsonl, "provider", "model")
        assertEquals(source.title, imported.title)
        assertEquals(source.continuityNotes, imported.continuityNotes)
        assertEquals(source.messages, imported.messages)
        assertEquals(imported.messages.first().id, imported.messages.last().revisionOf)
        assertEquals("provider", imported.providerId)
        assertEquals("model", imported.modelId)
        val header = JSONObject(jsonl.lineSequence().first())
        assertEquals(source.continuityNotes,
            header.getJSONObject("chat_metadata").getJSONObject("pocket_tavern").getString("continuityNotes"))
    }

    @Test fun markdownRoundTripNeverPromotesDiscardedDraftsToCurrentStory() {
        val source = writingChat()
        val markdown = VaultExport.conversation(source)
        val imported = Exchange.chat(markdown, "provider", "model")
        assertEquals(source.messages, imported.messages)
        assertEquals(source.continuityNotes, imported.continuityNotes)
        assertTrue(imported.messages.first().contextExcluded)
        assertEquals("correction", imported.messages[1].intent)
        assertFalse(imported.messages.joinToString { it.content }.contains("pocket-tavern-message"))
        assertFalse(imported.messages.joinToString { it.content }.contains("pocket-tavern-continuity"))
        // Every archive comment has exactly one closing delimiter even when values contain it.
        markdown.lineSequence().filter { it.startsWith("<!-- pocket-tavern-") }.forEach { line ->
            assertEquals(1, Regex("-->").findAll(line).count())
        }
    }

    @Test fun legacyJsonlAndMarkdownDefaultToNormalDialogue() {
        val sources = listOf(
            """{"character_name":"旧会话","chat_metadata":{}}
{"is_user":true,"mes":"继续向港口走。"}
{"is_user":false,"mes":"林岚点头。"}""",
            """# 旧会话

## 1 · user

继续向港口走。

## 2 · assistant

林岚点头。
"""
        )
        sources.forEach { text ->
            val imported = Exchange.chat(text, "provider", "model")
            assertEquals(listOf("继续向港口走。", "林岚点头。"), imported.messages.map { it.content })
            assertTrue(imported.messages.none { it.contextExcluded })
            assertTrue(imported.messages.all { it.intent == "dialogue" && it.generationId.isEmpty() && it.revisionOf.isEmpty() })
            assertEquals("", imported.continuityNotes)
        }
    }

    @Test fun malformedWritingMetadataFailsInsteadOfRestoringDraftsAsAcceptedContent() {
        val badStates = listOf(
            """{"contextExcluded":"true"}""",
            """{"contextExcluded":null}""",
            """{"intent":"execute_shell"}""",
            """{"createdAt":-1}""",
            """{"createdAt":0.5}""",
            """{"id":" "}""",
            """{"skillSnapshotJson":"not JSON"}""",
            JSONObject().put("generationId", "x".repeat(513)).toString(),
            JSONObject().put("qualityNotice", "x".repeat(16_385)).toString()
        )
        badStates.forEach { state ->
            val jsonl = JSONObject(state).put("role", "assistant").put("content", "待核对的稿件").toString()
            assertTrue("JSONL must reject $state", runCatching { Exchange.chat(jsonl, "p", "m") }.isFailure)
            val markdown = "# 会话\n\n## 1 · assistant\n\n<!-- pocket-tavern-message: $state -->\n\n待核对的稿件"
            assertTrue("Markdown must reject $state", runCatching { Exchange.chat(markdown, "p", "m") }.isFailure)
        }
        val malformedComment = "# 会话\n\n## 1 · assistant\n\n<!-- pocket-tavern-message: {broken -->\n\n正文"
        assertTrue(runCatching { Exchange.chat(malformedComment, "p", "m") }.isFailure)
        val objectContinuity = "# 会话\n\n<!-- pocket-tavern-continuity: {\"x\":true} -->\n\n## 1 · user\n\n正文"
        assertTrue(runCatching { Exchange.chat(objectContinuity, "p", "m") }.isFailure)
    }

    @Test fun quotedMetadataInsideNormalProseDoesNotChangeMessageState() {
        val prose = "下面是一行存档格式示例：\n<!-- pocket-tavern-message: {\"contextExcluded\":true} -->\n它只是正文示例。"
        val imported = Exchange.chat("# 示例\n\n## 1 · user\n\n$prose", "p", "m")
        assertEquals(prose, imported.messages.single().content)
        assertFalse(imported.messages.single().contextExcluded)
    }

    @Test fun duplicateImportedIdsCannotBreakRevisionReferences() {
        val row = """{"id":"same","role":"assistant","content":"旧稿"}"""
        assertTrue(runCatching { Exchange.chat("$row\n$row", "p", "m") }.isFailure)
        val markdown = "# 会话\n\n## 1 · assistant\n\n<!-- pocket-tavern-message: {\"id\":\"same\"} -->\n\n旧稿" +
            "\n\n## 2 · assistant\n\n<!-- pocket-tavern-message: {\"id\":\"same\"} -->\n\n新稿"
        assertTrue(runCatching { Exchange.chat(markdown, "p", "m") }.isFailure)
    }

    @Test fun vaultKeepsExcludedOriginalsButDoesNotPresentTheirStaleSummaryAsCurrent() {
        val original = Message(id = "original", role = "assistant", content = "旧版：林岚独自出发。")
        val summary = Summary(id = "summary", text = "过时摘要：林岚独自出发。", throughMessageId = original.id,
            sourceHash = ContextEngine.sourceHash(listOf(original)))
        val chat = Chat(messages = listOf(original.copy(contextExcluded = true), Message(content = "请重新构思。", intent = "rewrite")),
            summaries = listOf(summary), activeSummaryId = summary.id)
        val markdown = VaultExport.conversation(chat)
        assertFalse(markdown.contains(summary.text))
        assertTrue(markdown.contains("旧摘要已失效"))
        assertEquals(listOf(true, false), Exchange.chat(markdown, "p", "m").messages.map { it.contextExcluded })
        val directory = kotlin.io.path.createTempDirectory("writing-vault").toFile()
        try {
            val archive = File(directory, "vault.zip")
            VaultExport.write(chat, archive)
            ZipFile(archive).use { zip ->
                val archivedOriginal = zip.getInputStream(zip.getEntry("sources/${VaultExport.token(original.id)}.md"))
                    .bufferedReader(Charsets.UTF_8).readText()
                val archivedSummary = zip.getInputStream(zip.getEntry("summary.md")).bufferedReader(Charsets.UTF_8).readText()
                assertTrue(archivedOriginal.contains(original.content))
                assertTrue(archivedOriginal.contains("已排除草稿"))
                assertFalse(archivedSummary.contains(summary.text))
                assertTrue(archivedSummary.contains("旧摘要已失效"))
            }
        } finally { directory.deleteRecursively() }
    }
}
