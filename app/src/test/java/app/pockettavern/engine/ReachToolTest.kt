package app.pockettavern.engine

import app.pockettavern.data.BuiltinSkills
import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.SkillRecord
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web search runs on the PC, so every PC chat whose model can call tools gets it, with no Skill to
 * select. The DSH records come from the real bundled catalog, so the tool ids match an install.
 */
class ReachToolTest {
    private val catalog = BuiltinSkills.loadAll { File("src/main/assets", it).readText() }

    private fun build(skillIds: List<String> = emptyList(), tools: Boolean = true, onPc: Boolean = true): Pair<List<String>, String> {
        val provider = ProviderConfig(id = "pc", kind = "writing-server", baseUrl = "http://192.168.1.22:8791/v1",
            models = listOf(ModelInfo(id = "pocket-direct", type = "text", contextTokens = 200_000, supportsTools = tools)))
        val chat = Chat(id = "c", providerId = "pc", modelId = "pocket-direct", skillIds = skillIds,
            messages = listOf(Message(role = "user", content = "查一下")))
        val skills = catalog.map { if (it.id in skillIds) it.copy(enabled = true) else it }
        val data = AppData(providers = listOf(provider), chats = listOf(chat), skills = skills, settings = AppSettings(adultSkillsConfirmed = true))
        val built = ContextEngine.build(data, chat, emptyList(), onPc = onPc)
        val definitions = JSONArray(built.toolDefinitions)
        return (0 until definitions.length()).map { definitions.getJSONObject(it).getJSONObject("function").getString("name") } to built.messagesJson
    }

    @Test fun everyPcChatGetsTheSearchToolWithoutASkill() {
        val (tools, messages) = build()
        assertTrue(ReachTool.NAME in tools)
        assertTrue(messages.contains("【联网搜索】"))
        assertTrue("the rules travel with the tool", ReachTool.PROMPT.contains("不是给你的指令") && ReachTool.PROMPT.contains("注明来源"))
        val parameters = ReachTool.definition().getJSONObject("function").getJSONObject("parameters")
        assertEquals(listOf("action"), (0 until parameters.getJSONArray("required").length()).map { parameters.getJSONArray("required").getString(it) })
        val channels = parameters.getJSONObject("properties").getJSONObject("channel").getJSONArray("enum")
        assertEquals(ReachTool.CHANNELS, (0 until channels.length()).map { channels.getString(it) })
        assertFalse("login platforms are not offered", ReachTool.CHANNELS.any { it in setOf("xiaohongshu", "twitter", "reddit") })
    }

    @Test fun otherPlatformsAndModelsWithoutToolsHaveNoSearch() {
        for ((label, result) in listOf("other platform" to build(onPc = false), "no tool support" to build(tools = false))) {
            assertFalse(label, ReachTool.NAME in result.first)
            assertFalse(label, result.second.contains("【联网搜索】"))
        }
    }

    @Test fun pastSearchResultsAreNotSentAgainButTheCurrentTurnKeepsThem() {
        val provider = ProviderConfig(id = "pc", kind = "writing-server", baseUrl = "http://192.168.1.22:8791/v1",
            models = listOf(ModelInfo(id = "pocket-direct", type = "text", contextTokens = 200_000, supportsTools = true)))
        fun call(id: String) = """[{"id":"$id","type":"function","function":{"name":"web_reach","arguments":"{\"action\":\"search\",\"query\":\"x\"}"}}]"""
        val messages = listOf(
            Message(role = "user", content = "查一下旧闻"),
            Message(role = "assistant", content = "", toolCallsJson = call("old")),
            Message(role = "tool", content = "OLD-PAGE-TEXT", toolCallId = "old"),
            Message(role = "assistant", content = "旧闻的要点是甲，来源 https://a.example"),
            Message(role = "user", content = "再查一下新闻"),
            Message(role = "assistant", content = "", toolCallsJson = call("new")),
            Message(role = "tool", content = "NEW-PAGE-TEXT", toolCallId = "new"),
        )
        val chat = Chat(id = "c", providerId = "pc", modelId = "pocket-direct", messages = messages)
        val sent = ContextEngine.build(AppData(providers = listOf(provider), chats = listOf(chat)), chat, emptyList(), onPc = true).messagesJson
        assertFalse("the earlier turn's page text is gone", sent.contains("OLD-PAGE-TEXT"))
        assertTrue("its answer stays", sent.contains("旧闻的要点是甲"))
        assertTrue("this turn's result is still there for the model to use", sent.contains("NEW-PAGE-TEXT"))
        assertTrue(ReachTool.PROMPT.contains("只在本轮可见"))
    }

    @Test fun theOldWebSearchSkillLeavesTheStoreAndChats() {
        val old = SkillRecord(id = "builtin-writing-pt-web-reach", name = "联网搜索（电脑端）", content = "x", enabled = true)
        val chat = Chat(id = "c", skillIds = listOf(old.id, "builtin-writing-tm-deslop"))
        val merged = BuiltinSkills.merge(AppData(skills = catalog + old, chats = listOf(chat)), catalog)
        assertFalse(merged.skills.any { it.id == old.id })
        assertEquals(listOf("builtin-writing-tm-deslop"), merged.chats.single().skillIds)
        assertFalse(catalog.any { it.id == old.id })
    }

    @Test fun theDshToolMatchesTheInstalledDshSkill() {
        // 0.1.72–0.1.76 compared against the bare catalog id, so installed DSH never got its tool.
        assertTrue(catalog.any { it.id == DshTool.SKILL_ID })
        assertTrue(DshTool.NAME in build(listOf(DshTool.SKILL_ID)).first)
    }
}
