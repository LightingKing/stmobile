package app.pockettavern

import app.pockettavern.domain.*
import app.pockettavern.engine.ContextEngine
import app.pockettavern.ui.MemoryBrowser
import org.junit.Assert.*
import org.junit.Test

class StructuredMemoryBrowserTest {
    @Test fun categoryAndAliasSearchKeepsExactSourceNavigation() {
        val message = Message(id = "source", content = "钥匙已交还。")
        val note = KnowledgeNote(id = "note", title = "钥匙归还", kind = "event", entity = "person:林宁", aliases = listOf("小林"), text = message.content,
            sourceMessageIds = listOf(message.id), sourceHash = ContextEngine.sourceHash(listOf(message)))
        val chat = Chat(messages = listOf(message), knowledgeNotes = listOf(note))
        val index = MemoryBrowser.index(chat)
        val match = MemoryBrowser.search(index, "小林", section = 1, category = "event").single()
        assertEquals("钥匙归还", match.entry.title)
        assertEquals("1:note", match.entry.key)
        assertTrue(match.entry.source!!.invoke().valid)
        assertEquals(message.id, match.entry.source!!.invoke().messages.single().message.id)
        assertTrue(MemoryBrowser.search(index, "小林", section = 1, category = "person").isEmpty())
        assertEquals("1:note", MemoryBrowser.search(index, "林宁").single().entry.key)
    }

    @Test fun compactSummaryAndLegacyNotesRemainDistinct() {
        val message = Message(id = "source", content = "正在钟楼。")
        val summary = Summary(id = "current", text = message.content, throughMessageId = message.id,
            sourceHash = ContextEngine.sourceHash(listOf(message)), format = "compact-v1")
        val chat = Chat(messages = listOf(message), summaries = listOf(summary), activeSummaryId = summary.id,
            knowledgeNotes = listOf(KnowledgeNote(id = "legacy", text = "旧分段")))
        val index = MemoryBrowser.index(chat)
        assertEquals("当前续聊摘要", index.resolve("0:current")!!.title)
        assertTrue(index.resolve("0:current")!!.compact)
        assertEquals("分段记忆 1", index.resolve("1:legacy")!!.title)
        assertEquals("legacy", index.resolve("1:legacy")!!.category)
    }
}
