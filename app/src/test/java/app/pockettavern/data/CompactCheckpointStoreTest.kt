package app.pockettavern.data

import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.engine.CompactCheckpoint
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompactCheckpointStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val checkpoint = CompactCheckpoint(fingerprint = "source-fingerprint", nextMessageIndex = 3,
        summaryText = "当前状态", notes = listOf(KnowledgeNote(text = "旧关系→新事件→新关系")), requestCount = 1)

    @Test fun restartLoadsOnlyMatchingConversationOperationAndModelSettings() {
        val directory = temporary.newFolder("compact")
        CompactCheckpointStore(directory).save("../chat-A", "compression", "provider/model/settings", checkpoint)
        val reopened = CompactCheckpointStore(directory)
        assertEquals(checkpoint, reopened.load("../chat-A", "compression", "provider/model/settings"))
        assertNull(reopened.load("chat-B", "compression", "provider/model/settings"))
        assertNull(reopened.load("../chat-A", "memory_rebuild", "provider/model/settings"))
        assertNull(reopened.load("../chat-A", "compression", "provider/changed-model/settings"))
        assertEquals(1, directory.listFiles()!!.size)
        assertEquals(directory.canonicalFile, directory.listFiles()!!.single().canonicalFile.parentFile)
    }

    @Test fun corruptCheckpointCannotSkipSourceAndClearingDoesNotDeleteOtherChats() {
        val directory = temporary.newFolder("compact")
        val store = CompactCheckpointStore(directory)
        store.save("a", "compression", "same", checkpoint)
        val file = directory.listFiles()!!.single()
        val root = JSONObject(file.readText())
        root.put("checkpoint", root.getString("checkpoint").replace("当前状态", "changed"))
        file.writeText(root.toString())
        assertNull(store.load("a", "compression", "same"))
        store.save("a", "compression", "same", checkpoint)
        store.save("b", "compression", "same", checkpoint)
        store.clear("a", "compression")
        assertNull(store.load("a", "compression", "same"))
        assertEquals(checkpoint, store.load("b", "compression", "same"))
    }
}
