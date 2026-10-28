package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.Summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the auto-compression step in AppController.generate with a local summarizer. */
class CompressionCadenceTest {
    private val world = "【世界书】极寒末世，庄园地下有三号仓库，燃料按周配给。".repeat(140)
    private val reply = "雪压断了东侧的电线，顾砚舟带着手电去配电房，一路数着结冰的台阶。".repeat(20)
    private val data = AppData(
        providers = listOf(ProviderConfig(id = "p", models = listOf(ModelInfo(id = "m", type = "text", contextTokens = 20_000)))),
        presets = listOf(Preset(id = "default", systemPrompt = world))
    )

    private var summaryCalls = 0

    /** Same order as generate(): build, compress once if the engine asks for it, then send. */
    private fun send(chat: Chat, text: String): Chat {
        var current = chat.copy(messages = chat.messages + Message(role = "user", content = text))
        val context = ContextEngine.build(data, current)
        val slice = if (context.needsCompression) ContextEngine.compressionSlice(data, current) else null
        if (slice != null) {
            summaryCalls++
            val summary = Summary(text = "摘要第${summaryCalls}版：庄园、仓库、配给。", throughMessageId = slice.throughMessageId, sourceHash = slice.sourceHash)
            current = current.copy(summaries = current.summaries + summary, activeSummaryId = summary.id)
        }
        val sent = ContextEngine.build(data, current)
        assertTrue("request must fit: ${sent.estimatedTokens}/${sent.budget}", sent.estimatedTokens <= sent.budget)
        return current.copy(messages = current.messages + Message(role = "assistant", content = reply))
    }

    private fun trigger(chat: Chat): Int {
        val built = ContextEngine.build(data, chat)
        return (built.budget * data.settings.compressionThreshold).toInt()
    }

    private fun overBudgetChat(): Chat {
        val turns = (1..14).flatMap { listOf(Message(role = "user", content = "第${it}轮"), Message(role = "assistant", content = reply)) }
        val chat = Chat(id = "c", providerId = "p", modelId = "m", messages = turns)
        val built = ContextEngine.build(data, chat)
        assertTrue("fixture starts over budget", built.estimatedTokens > built.budget)
        return chat
    }

    @Test fun emergencyCompressionCanRecoverAnOversizedImportedTranscriptWithOnlyOneCurrentTurn() {
        val model = ModelInfo(id = "large", type = "text", contextTokens = 16_384)
        val provider = ProviderConfig(id = "p", models = listOf(model))
        val emergencyData = AppData(
            providers = listOf(provider),
            presets = listOf(Preset(id = "default", systemPrompt = "保持人物关系和剧情连续。")),
            settings = AppSettings(keepRecentTurns = 8)
        )
        val imported = Message(
            id = "imported-transcript", role = "user",
            content = "原始会话记录：林澈和沈越是兄妹，他们已经在北城重逢。" + "旧剧情资料".repeat(12_000)
        )
        val current = Message(id = "current-question", role = "user", content = "继续查看北城重逢时发生的事")
        val original = Chat(id = "c", providerId = "p", modelId = model.id, messages = listOf(imported, current))

        val before = ContextEngine.build(emergencyData, original)
        assertTrue("fixture must be over budget", before.estimatedTokens > before.budget)
        assertTrue("an oversized import should expose an automatic recovery slice", before.needsCompression)
        val slice = before.compression!!
        assertEquals(listOf(imported.id), slice.messages.map { it.id })
        assertEquals(imported.id, slice.throughMessageId)

        val summary = Summary(
            text = "林澈和沈越是兄妹，已经在北城重逢。",
            throughMessageId = slice.throughMessageId,
            sourceHash = slice.sourceHash
        )
        val recovered = original.copy(summaries = listOf(summary), activeSummaryId = summary.id)
        val after = ContextEngine.build(emergencyData, recovered)

        assertTrue("compacted request must fit: ${after.estimatedTokens}/${after.budget}", after.estimatedTokens <= after.budget)
        assertTrue(after.messagesJson.contains(current.content))
        assertTrue(after.messagesJson.contains(summary.text))
        assertFalse(after.messagesJson.contains("旧剧情资料".repeat(100)))
        assertEquals("the complete imported source remains available locally", imported.content, recovered.messages.first().content)
    }

    @Test fun oneSummaryThenNormalSendsUntilTheContextFillsAgain() {
        var chat = send(overBudgetChat(), "继续")
        assertEquals("first over-budget send summarizes once", 1, summaryCalls)

        var quietSends = 0
        while (summaryCalls == 1 && quietSends < 40) {
            val before = ContextEngine.build(data, chat.copy(messages = chat.messages + Message(role = "user", content = "再来")))
            chat = send(chat, "再来")
            if (summaryCalls == 1) {
                assertTrue("a send without compression stays under the trigger line", before.estimatedTokens < trigger(chat))
                quietSends++
            } else {
                assertTrue("the second summary waits until the trigger line is reached", before.estimatedTokens >= trigger(chat))
            }
        }
        println("cadence: sends between first and second summary = $quietSends")
        assertTrue("several sends reuse the first summary, got $quietSends", quietSends >= 3)
        assertEquals("the context filled up again and was summarized exactly once more", 2, summaryCalls)

        chat = send(chat, "然后呢")
        assertEquals("the send right after the second summary does not summarize again", 2, summaryCalls)
    }

    @Test fun laterSummaryOnlyReadsTurnsAfterTheCurrentSummary() {
        val chat = send(overBudgetChat(), "继续")
        val first = chat.summaries.single()
        var grown = chat
        repeat(12) { grown = grown.copy(messages = grown.messages + Message(role = "user", content = "又一轮") + Message(role = "assistant", content = reply)) }
        val next = ContextEngine.compressionSlice(data, grown.copy(messages = grown.messages + Message(role = "user", content = "继续")))!!
        val firstEnd = grown.messages.indexOfFirst { it.id == first.throughMessageId }
        assertTrue("second slice starts after the summarized turns", next.messages.none { m -> grown.messages.indexOf(m) <= firstEnd })
        assertEquals(first.text, next.previousSummary)
        val input = ContextEngine.summaryInput(next)
        assertFalse("immutable earlier notes are never fed through another lossy rewrite", input.joinToString("\n").contains(first.text))
        assertEquals(next.messages.size, input.size)
    }

    @Test fun summaryOutlivesRegenerateAndTailEditsButNotSourceEdits() {
        val chat = send(overBudgetChat(), "继续")
        val summary = chat.summaries.single()
        val lastUser = chat.messages.indexOfLast { it.role == "user" }
        val regenerated = chat.copy(messages = chat.messages.take(lastUser + 1))
        assertTrue(ContextEngine.build(data, regenerated).messagesJson.contains(summary.text))
        assertEquals("regenerating does not trigger another summary", false, ContextEngine.build(data, regenerated).needsCompression)

        val tailEdit = chat.copy(messages = chat.messages.mapIndexed { i, m -> if (i == lastUser) m.copy(content = "改过的最后一句") else m })
        assertTrue(ContextEngine.build(data, tailEdit).messagesJson.contains(summary.text))

        val sourceEdit = chat.copy(messages = chat.messages.mapIndexed { i, m -> if (i == 0) m.copy(content = "改过的第一轮") else m })
        assertTrue(!ContextEngine.build(data, sourceEdit).messagesJson.contains(summary.text))
    }
}
