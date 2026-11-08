package app.pockettavern.engine

import app.pockettavern.data.BuiltinSkills
import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web search runs on the PC, so only PC chats that selected the Skill and can call tools get it.
 * The records come from the real bundled catalog, so the tool ids match what a phone installs.
 */
class ReachToolTest {
    private val catalog = BuiltinSkills.loadAll { File("src/main/assets", it).readText() }

    private fun build(skillId: String, selected: Boolean = true, tools: Boolean = true, onPc: Boolean = true, enabled: Boolean = true): Pair<List<String>, String> {
        val provider = ProviderConfig(id = "pc", kind = "writing-server", baseUrl = "http://192.168.1.22:8791/v1",
            models = listOf(ModelInfo(id = "pocket-direct", type = "text", contextTokens = 200_000, supportsTools = tools)))
        val chat = Chat(id = "c", providerId = "pc", modelId = "pocket-direct", skillIds = if (selected) listOf(skillId) else emptyList(),
            messages = listOf(Message(role = "user", content = "查一下")))
        val skills = catalog.map { if (it.id == skillId) it.copy(enabled = enabled) else it }
        val data = AppData(providers = listOf(provider), chats = listOf(chat), skills = skills, settings = AppSettings(adultSkillsConfirmed = true))
        val built = ContextEngine.build(data, chat, emptyList(), onPc = onPc)
        val definitions = JSONArray(built.toolDefinitions)
        return (0 until definitions.length()).map { definitions.getJSONObject(it).getJSONObject("function").getString("name") } to built.messagesJson
    }

    @Test fun theBundledSkillGivesAPcChatTheSearchTool() {
        val skill = catalog.single { it.id == ReachTool.SKILL_ID }
        assertEquals("联网搜索（电脑端）", skill.name)
        assertEquals("联网工具", BuiltinSkills.bundledCategory(skill))
        val (tools, messages) = build(ReachTool.SKILL_ID)
        assertTrue(ReachTool.NAME in tools)
        assertTrue(messages.contains("【联网搜索】"))
        val parameters = ReachTool.definition().getJSONObject("function").getJSONObject("parameters")
        assertEquals(listOf("action"), (0 until parameters.getJSONArray("required").length()).map { parameters.getJSONArray("required").getString(it) })
        val channels = parameters.getJSONObject("properties").getJSONObject("channel").getJSONArray("enum")
        assertEquals(ReachTool.CHANNELS, (0 until channels.length()).map { channels.getString(it) })
        assertFalse("login platforms are not offered", ReachTool.CHANNELS.any { it in setOf("xiaohongshu", "twitter", "reddit") })
    }

    @Test fun withoutThePcToolsOrTheSkillThereIsNoSearch() {
        for ((label, result) in listOf(
            "other platform" to build(ReachTool.SKILL_ID, onPc = false),
            "no tool support" to build(ReachTool.SKILL_ID, tools = false),
            "not selected" to build(ReachTool.SKILL_ID, selected = false),
            "Skill switched off" to build(ReachTool.SKILL_ID, enabled = false),
        )) {
            assertFalse(label, ReachTool.NAME in result.first)
            assertFalse(label, result.second.contains("【联网搜索】"))
        }
    }

    @Test fun theDshToolMatchesTheInstalledDshSkill() {
        // 0.1.72–0.1.76 compared against the bare catalog id, so installed DSH never got its tool.
        assertTrue(catalog.any { it.id == DshTool.SKILL_ID })
        assertTrue(DshTool.NAME in build(DshTool.SKILL_ID).first)
    }
}
