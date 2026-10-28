package app.pockettavern.engine

import app.pockettavern.data.DataCodec
import app.pockettavern.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompressionScopeTest {
    private val messages = (1..8).map { Message(id = "m$it", content = "原文私密哨兵 $it") }
    private val base = Summary(id = "base", text = "旧摘要私密哨兵", throughMessageId = "m4",
        sourceHash = ContextEngine.sourceHash(messages.take(4)))

    @Test fun incrementalRangeSurvivesSaveLoadAndDiagnosticsWithoutExportingContent() {
        val chat = Chat(messages = messages, summaries = listOf(base), activeSummaryId = base.id)
        val slice = CompressionSlice(messages.subList(4, 6), "m6", ContextEngine.sourceHash(messages.take(6)), base.text)
        val record = CompressionScope.record(chat, slice).copy(segmentCount = 11)
        assertEquals(base.id, record.baseSummaryId)
        assertEquals("m4", record.reusedThroughMessageId)
        assertEquals("m5", record.firstMessageId)
        assertEquals("m6", record.lastMessageId)
        assertEquals(2, record.messageCount)
        val summary = Summary(id = "next", text = "新摘要私密哨兵", throughMessageId = "m6", sourceHash = slice.sourceHash, compression = record)
        val saved = DataCodec.decode(DataCodec.encode(AppData(chats = listOf(chat.copy(
            summaries = chat.summaries + summary, activeSummaryId = summary.id
        ))))).chats.single()
        assertEquals(record, saved.summaries.last().compression)
        val scope = CompressionScope.describe(saved, record)
        assertTrue(scope.contains("截至第 4 条"))
        assertTrue(scope.contains("原文第 5–6 条"))
        assertTrue(scope.contains("本次新增 11 段记忆"))
        val report = JSONObject(MemoryDiagnostics.report(saved, null))
        assertFalse(report.toString().contains("私密哨兵"))
        val detail = report.getJSONArray("summaries").getJSONObject(1).getJSONObject("compression")
        assertEquals(4, detail.getInt("startIndex"))
        assertEquals(5, detail.getInt("endIndex"))
        assertEquals(2, detail.getInt("messageCount"))
        assertEquals(11, detail.getInt("segmentCount"))
    }

    @Test fun fallbackReportsReusedBoundaryAndExplainsWhySomeSourcesAreReprocessed() {
        val second = Summary(id = "second", text = "第二版摘要", throughMessageId = "m6",
            sourceHash = ContextEngine.sourceHash(messages.take(6)))
        val edited = messages.map { if (it.id == "m5") it.copy(content = "编辑过的第五条") else it }
        val chat = Chat(messages = edited, summaries = listOf(base, second), activeSummaryId = second.id)
        val slice = CompressionSlice(edited.subList(4, 7), "m7", ContextEngine.sourceHash(edited.take(7)), base.text)
        val record = CompressionScope.record(chat, slice)
        assertEquals("FALLBACK_TO_EARLIER", record.reason)
        assertEquals(base.id, record.baseSummaryId)
        assertTrue(CompressionScope.describe(chat, record).contains("沿用更早的有效摘要（截至第 4 条）"))
        val report = JSONObject(MemoryDiagnostics.report(chat, null))
        assertEquals(second.id, report.getString("activeSummaryId"))
        assertEquals(base.id, report.getString("effectiveSummaryId"))
    }

    @Test fun legacyAndPartialMetadataLoadSafelyWithoutInventingAnIncrementalRange() {
        val encoded = JSONObject(DataCodec.encode(AppData(chats = listOf(Chat(summaries = listOf(base))))))
        val summary = encoded.getJSONArray("chats").getJSONObject(0).getJSONArray("summaries").getJSONObject(0)
        summary.remove("compression")
        assertNull(DataCodec.decode(encoded.toString()).chats.single().summaries.single().compression)
        summary.put("compression", JSONObject().put("segmentCount", 3))
        val normalized = DataCodec.decode(encoded.toString()).chats.single().summaries.single().compression!!
        assertEquals("", normalized.firstMessageId)
        assertEquals(3, normalized.segmentCount)
        assertTrue(CompressionScope.describe(Chat(), normalized).contains("范围已变动"))
    }
}
