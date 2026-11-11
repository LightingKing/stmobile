package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 角色卡助手: the card tool exists only in its own chat, and saves only complete, fictional cards. */
class CharacterCardToolTest {
    private val pc = ProviderConfig(id = "pc", kind = "writing-server", baseUrl = "https://pt.example.com/v1",
        models = listOf(ModelInfo(id = "pocket-direct", type = "text", supportsTools = true)))

    private fun tools(task: String, onPc: Boolean = true): Pair<List<String>, String> {
        val chat = Chat(id = "c", providerId = "pc", modelId = "pocket-direct", task = task, messages = listOf(Message(role = "user", content = "看这个视频做个角色")))
        val built = ContextEngine.build(AppData(providers = listOf(pc), chats = listOf(chat)), chat, emptyList(), onPc = onPc)
        val array = JSONArray(built.toolDefinitions)
        return (0 until array.length()).map { array.getJSONObject(it).getJSONObject("function").getString("name") } to built.messagesJson
    }

    @Test fun theAssistantChatGetsTheCardToolAndVideoAccess() {
        val (names, messages) = tools(CharacterCardTool.TASK)
        assertTrue(CharacterCardTool.NAME in names)
        assertTrue("it can watch videos on the PC", ReachTool.NAME in names)
        assertTrue(messages.contains("【角色卡助手】"))
        assertTrue("real people are refused", CharacterCardTool.PROMPT.contains("不给现实中可识别的真人") && CharacterCardTool.PROMPT.contains("必须是成年人"))
        assertTrue("saving waits for the user", CharacterCardTool.PROMPT.contains("明确同意保存后"))
        val (ordinary, plain) = tools("")
        assertFalse(CharacterCardTool.NAME in ordinary)
        assertFalse(plain.contains("【角色卡助手】"))
    }

    @Test fun onlyCompleteCardsWithinLimitsAreSaved() {
        val card = CharacterCardTool.character(JSONObject().put("name", "林小满").put("description", "原创角色，大学生，爱拍视频").put("first_message", "嗨！")).getOrThrow()
        assertEquals("林小满", card.name)
        assertEquals("嗨！", card.firstMessage)
        assertTrue(CharacterCardTool.character(JSONObject().put("name", "无简介")).isFailure)
        assertTrue(CharacterCardTool.character(JSONObject().put("description", "无名")).isFailure)
        assertTrue(CharacterCardTool.character(JSONObject().put("name", "x".repeat(61)).put("description", "d")).isFailure)
        val required = CharacterCardTool.definition().getJSONObject("function").getJSONObject("parameters").getJSONArray("required")
        assertEquals(listOf("name", "description"), (0 until required.length()).map { required.getString(it) })
    }
}
