package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.Summary
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryFallbackTest {
    private val data = AppData(settings = AppSettings(keepRecentTurns = 1))
    private val messages = listOf("A", "B", "C").flatMap { span ->
        listOf(
            Message(id = "$span-user", role = "user", content = "$span 的提问", createdAt = 1),
            Message(id = "$span-assistant", role = "assistant", content = "$span 的原文事实", createdAt = 2)
        )
    } + Message(id = "current", role = "user", content = "继续", createdAt = 3)
    private val first = summary("first", 2, "第一份摘要仅覆盖 A")
    private val second = summary("second", 4, "第二份摘要覆盖 A 和 B")

    private fun summary(id: String, count: Int, text: String = "摘要 $id") = Summary(
        id = id, text = text, throughMessageId = messages[count - 1].id,
        sourceHash = ContextEngine.sourceHash(messages.take(count)), createdAt = count.toLong()
    )

    private fun chat() = Chat(
        messages = messages, summaries = listOf(first, second), activeSummaryId = second.id
    )

    private fun edit(chat: Chat, id: String) = chat.copy(messages = chat.messages.map {
        if (it.id == id) it.copy(content = "修正后的事实") else it
    })

    @Test fun thirdCompressionReusesSecondSummaryAndOnlyReadsC() {
        val current = chat()
        val resolution = ContextEngine.resolveSummary(current)
        assertEquals(second, resolution.selected)
        assertEquals(second, resolution.effective)
        assertEquals(SummaryResolutionReason.ACTIVE, resolution.reason)
        val slice = ContextEngine.compressionSlice(data, current)!!
        assertEquals(listOf("C-user", "C-assistant"), slice.messages.map { it.id })
        assertEquals(second.text, slice.previousSummary)
    }

    @Test fun editingBReusesAAndOnlyCompressesBAndC() {
        val current = edit(chat(), "B-assistant")
        val resolution = ContextEngine.resolveSummary(current)
        assertEquals(second, resolution.selected)
        assertEquals(first, resolution.effective)
        assertEquals(SummaryResolutionReason.FALLBACK_TO_EARLIER, resolution.reason)
        val slice = ContextEngine.compressionSlice(data, current)!!
        assertEquals(listOf("B-user", "B-assistant", "C-user", "C-assistant"), slice.messages.map { it.id })
        assertEquals(first.text, slice.previousSummary)
        assertEquals(ContextEngine.sourceHash(current.messages.take(6)), slice.sourceHash)
        val sent = ContextEngine.build(data, current).messagesJson
        assertTrue(sent.contains(first.text))
        assertFalse(sent.contains(second.text))
        // Recall may legitimately retrieve an old fact as a system excerpt; it must
        // not be replayed as live dialogue or selected for compression a second time.
        val sentMessages = JSONArray(sent)
        assertFalse((0 until sentMessages.length()).map(sentMessages::getJSONObject)
            .any { it.optString("role") != "system" && it.optString("content") == "A 的原文事实" })
        assertEquals("resolving does not overwrite the user's stored selection", second.id, current.activeSummaryId)
    }

    @Test fun editingAMakesEveryPreviousSummaryInvalid() {
        val current = edit(chat(), "A-assistant")
        val resolution = ContextEngine.resolveSummary(current)
        assertNull(resolution.effective)
        assertEquals(SummaryResolutionReason.SOURCE_CHANGED, resolution.reason)
        assertEquals(messages.take(6).map { it.id }, ContextEngine.compressionSlice(data, current)!!.messages.map { it.id })
        val sent = ContextEngine.build(data, current).messagesJson
        assertFalse(sent.contains(first.text))
        assertFalse(sent.contains(second.text))
        assertTrue(sent.contains("修正后的事实"))
    }

    @Test fun explicitlyDisablingSummariesDoesNotRestoreAnyOldOne() {
        val current = chat().copy(activeSummaryId = "")
        val resolution = ContextEngine.resolveSummary(current)
        assertNull(resolution.selected)
        assertNull(resolution.effective)
        assertEquals(SummaryResolutionReason.DISABLED, resolution.reason)
        assertEquals(messages.take(6).map { it.id }, ContextEngine.compressionSlice(data, current)!!.messages.map { it.id })
    }

    @Test fun manuallySelectingFirstSummaryDoesNotAdvanceToSecond() {
        val current = chat().copy(activeSummaryId = first.id)
        val resolution = ContextEngine.resolveSummary(current)
        assertEquals(first, resolution.effective)
        assertEquals(SummaryResolutionReason.ACTIVE, resolution.reason)
        assertEquals(listOf("B-user", "B-assistant", "C-user", "C-assistant"),
            ContextEngine.compressionSlice(data, current)!!.messages.map { it.id })
    }

    @Test fun fallbackNeverUsesAnUnselectedLaterSummaryEvenWhenItsCoverageIsEarlier() {
        val laterSnapshotOfA = first.copy(id = "created-later", text = "用户尚未选择的后续摘要")
        val current = edit(chat().copy(summaries = listOf(second, laterSnapshotOfA)), "B-assistant")
        val resolution = ContextEngine.resolveSummary(current)
        assertNull(resolution.effective)
        assertEquals(SummaryResolutionReason.SOURCE_CHANGED, resolution.reason)
    }

    @Test fun fallbackCannotAdvanceBeyondSelectedCoverage() {
        // A newer source hash may be valid, but it must not supersede an explicit rollback.
        val current = edit(chat(), "B-assistant")
        val farther = Summary(id = "farther", text = "覆盖更远的有效摘要", throughMessageId = "C-assistant",
            sourceHash = ContextEngine.sourceHash(current.messages.take(6)))
        val rollback = current.copy(summaries = listOf(farther, first, second))
        assertEquals(first, ContextEngine.resolveSummary(rollback).effective)
    }

    @Test fun missingSelectedIdDoesNotGuessWhichSummaryWasChosen() {
        val resolution = ContextEngine.resolveSummary(chat().copy(activeSummaryId = "missing"))
        assertNull(resolution.selected)
        assertNull(resolution.effective)
        assertEquals(SummaryResolutionReason.SELECTED_MISSING, resolution.reason)
    }

    @Test fun missingSelectedBoundaryDoesNotGuessTheFallbackRange() {
        val current = chat().copy(messages = messages.filterNot { it.id == second.throughMessageId })
        val resolution = ContextEngine.resolveSummary(current)
        assertEquals(second, resolution.selected)
        assertNull(resolution.effective)
        assertEquals(SummaryResolutionReason.BOUNDARY_MISSING, resolution.reason)
    }
}
