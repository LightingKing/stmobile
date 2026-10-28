package app.pockettavern.engine

import app.pockettavern.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KnowledgeVaultToolTest {
    @Test fun historyToolsReadOnlySearchCurrentChatWithoutGrantingSkillPermissions() = runBlocking {
        val source = Message(id = "old", role = "assistant", content = "顾遥把蓝色药盒交给许渡，并答应天亮前回来。")
        val latest = Message(id = "new", role = "user", content = "顾遥答应了什么？")
        val chat = Chat(id = "current", messages = listOf(source, latest))
        val runtime = ToolRuntime(kotlin.io.path.createTempDirectory("history-tool").toFile())
        val found = runtime.execute("history_search", """{"query":"顾遥答应什么"}""", emptyList(), AppData(), chat)
        val item = JSONArray(found.content).getJSONObject(0)
        assertEquals("message:old", item.getString("id"))
        assertTrue(item.getString("text").contains("天亮前回来"))
        val read = runtime.execute("history_read", JSONObject().put("id", item.getString("id")).put("offset", item.getInt("offset")).toString(), emptyList(), AppData(), chat)
        assertTrue(read.content.contains("蓝色药盒"))
        val other = runtime.execute("history_search", """{"query":"顾遥答应什么"}""", emptyList(), AppData(), Chat(id = "other"))
        assertEquals("[]", other.content)
    }
}
