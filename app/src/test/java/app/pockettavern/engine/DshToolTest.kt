package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.SkillRecord
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DSH engine runs on the PC, so only PC chats that selected DSH and can call tools get its tool. */
class DshToolTest {
    private val dsh = SkillRecord(id = DshTool.SKILL_ID, name = "DSH 成人互动", content = "运行时……", adult = true, enabled = true,
        files = mapOf("SKILL.md" to "x", "references/commands.md" to "命令"))

    private fun build(selected: Boolean = true, tools: Boolean = true, onPc: Boolean = true, adultConfirmed: Boolean = true): Pair<List<String>, String> {
        val provider = ProviderConfig(id = "pc", kind = "writing-server", baseUrl = "http://192.168.1.22:8791/v1",
            models = listOf(ModelInfo(id = "pocket-direct", type = "text", contextTokens = 200_000, supportsTools = tools)))
        val chat = Chat(id = "c", providerId = "pc", modelId = "pocket-direct", skillIds = if (selected) listOf(dsh.id) else emptyList(),
            messages = listOf(Message(role = "user", content = "开局")))
        val data = AppData(providers = listOf(provider), chats = listOf(chat), skills = listOf(dsh), settings = AppSettings(adultSkillsConfirmed = adultConfirmed))
        val built = ContextEngine.build(data, chat, emptyList(), onPc = onPc)
        val tools = JSONArray(built.toolDefinitions)
        return (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") } to built.messagesJson
    }

    @Test fun aPcChatWithDshSelectedGetsTheEngineToolAndTheShellNote() {
        val (tools, messages) = build()
        assertTrue(DshTool.NAME in tools)
        assertTrue(messages.contains("【DSH 引擎】"))
        val definition = DshTool.definition().getJSONObject("function")
        assertEquals(listOf("command"), (0 until definition.getJSONObject("parameters").getJSONArray("required").length()).map { definition.getJSONObject("parameters").getJSONArray("required").getString(it) })
        assertFalse("developer checks are not offered", DshTool.COMMANDS.contains("smoke") || DshTool.COMMANDS.contains("verify-content"))
    }

    @Test fun withoutThePcToolsTheSkillOrAdultConsentThereIsNoEngine() {
        for ((label, result) in listOf(
            "other platform" to build(onPc = false),
            "no tool support" to build(tools = false),
            "DSH not selected" to build(selected = false),
            "adult skills not confirmed" to build(adultConfirmed = false),
        )) {
            assertFalse(label, DshTool.NAME in result.first)
            assertFalse(label, result.second.contains("【DSH 引擎】"))
        }
    }
}
