package app.pockettavern.engine

import app.pockettavern.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryCoverageTest {
    @Test fun serializedSourceMetadataAndEscapesStayWithinTheActualWindow() = runBlocking {
        val messages = (1..20).map { Message(id = "message-$it", content = "\\\"\n原文🌙".repeat(400)) }
        val seen = mutableListOf<String>()
        val window = 8192
        val result = HistoryCompressor.compress(MemoryRebuilder.snapshot(Chat(messages = messages)), window, "") { input ->
            seen += input
            assertTrue(ContextEngine.estimateTokens(HistoryCompressor.GUIDE + input) + HistoryCompressor.outputLimit(window) + HistoryCompressor.safetyReserve(window) <= window)
            compactReply(input, "当前仍在港口。")
        }
        assertTrue(seen.size > 1)
        assertEquals(messages.joinToString("") { HistorySource.memoryText(it) }, seen.joinToString("") { compactSourceText(it) })
        assertEquals(messages.map { it.id }, result.coveredSourceMessageIds)
    }

    @Test fun aMillionTokenWindowProcessesNineHundredThousandSourceTokensInOneRequest() = runBlocking {
        val message = Message(id = "book", content = "往".repeat(600_000))
        val inputs = mutableListOf<String>()
        val result = HistoryCompressor.compress(MemoryRebuilder.snapshot(Chat(messages = listOf(message))), 1_000_000, "") {
            inputs += it
            compactReply(it, "当前状态：已抵达港口。", text = "林岚和周行约定在港口见面。")
        }
        assertEquals("A fitting source must not be split into fixed 8192-token requests", 1, inputs.size)
        assertEquals(HistorySource.memoryText(message), compactSourceText(inputs.single()))
        assertEquals(1, result.requestCount)
        assertEquals("当前状态：已抵达港口。", result.summaryText)
        assertEquals(1, result.notes.count { it.kind == "archive" })
        assertEquals(1, result.notes.count { it.kind == "fact" })
    }

    @Test fun overviewKeepsEarlyRelationshipAnchorsAndTheLatestStateWithinItsBudget() {
        val early = KnowledgeNote(text = "## 人物关系\n林岚是周行的姐姐。\n## 已发生剧情\n银钥匙已经归还。")
        val latest = KnowledgeNote(text = "## 人物关系\n队员仍在一起。\n## 已发生剧情\n" + "他们讨论港口路线。".repeat(1600) + "\n## 当前状态\n车队已经驶离港口。")
        val overview = HistoryCompressor.overview(listOf(early, latest))
        assertTrue("Recent long notes must not evict all early relationships", overview.contains("林岚是周行的姐姐"))
        assertTrue("The ending state is more useful than a cut-off prefix of the latest note", overview.contains("车队已经驶离港口"))
        assertTrue(ContextEngine.estimateTokens(overview) <= 2400)
        assertTrue(overview.contains("导览"))
    }

    @Test fun evenVeryManyNotesProduceABoundedOverviewWithoutPretendingToBeComplete() {
        val notes = (1..200).map { KnowledgeNote(text = "## 人物关系\n人物$it 记得约定。\n## 当前状态\n队伍$it 已出发。") }
        val overview = HistoryCompressor.overview(notes)
        assertTrue(ContextEngine.estimateTokens(overview) <= 2400)
        assertTrue(overview.contains("200"))
        assertTrue(overview.contains("人物1"))
        assertTrue(overview.contains("队伍200 已出发"))
        assertTrue(overview.contains("原文"))
    }

    @Test fun numberedOrUnformattedCurrentStateStillKeepsTheEndOfTheLatestNote() {
        for (heading in listOf("4. 当前状态", "四、当前状态", "### **4. 当前状态**", "")) {
            val note = KnowledgeNote(text = "过去的行程记录。".repeat(1600) + "\n$heading\n目前已经在港口登船。")
            val guide = HistoryCompressor.overview(listOf(note))
            assertTrue(heading, guide.contains("目前已经在港口登船"))
            assertTrue(ContextEngine.estimateTokens(guide) <= 2400)
        }
    }

    @Test fun editedOrBranchedAwayFactsCannotReenterANewOverview() {
        val original = Message(id = "old", content = "旧仓库已被烧毁。")
        val latest = Message(id = "new", content = "队伍停在仓库门口。")
        val staleNote = KnowledgeNote(text = original.content, sourceMessageIds = listOf(original.id), sourceHash = ContextEngine.sourceHash(listOf(original)))
        val latestNote = KnowledgeNote(text = latest.content, sourceMessageIds = listOf(latest.id), sourceHash = ContextEngine.sourceHash(listOf(latest)))
        for (messages in listOf(listOf(original.copy(content = "旧仓库完好无损。"), latest), listOf(latest))) {
            val chat = Chat(messages = messages, knowledgeNotes = listOf(staleNote, latestNote))
            val overview = HistoryCompressor.overview(chat)
            assertFalse(overview.contains("烧毁"))
            assertTrue(overview.contains("队伍停在仓库门口"))
            assertEquals(listOf(staleNote, latestNote), chat.knowledgeNotes)
        }
    }
}
