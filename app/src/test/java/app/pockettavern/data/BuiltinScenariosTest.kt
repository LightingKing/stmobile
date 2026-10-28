package app.pockettavern.data

import app.pockettavern.domain.AppData
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinScenariosTest {
    @Test
    fun shippedStoryLoadsWithOriginalOpeningAndFitsDefaultContext() {
        val assets = java.io.File("src/main/assets")
        val entry = BuiltinScenarios.load { java.io.File(assets, it).readText(Charsets.UTF_8) }.single()
        assertEquals(60, entry.worldEntries.size)
        assertEquals(4, entry.worldEntries.count { it.always })
        assertTrue(entry.worldEntries.all { it.enabled && it.searchRange in 1000..2400 })
        assertEquals(1037, entry.character.firstMessage.length)
        assertEquals(724, entry.preset.systemPrompt.length)
        assertTrue(entry.character.firstMessage.contains("2026年11月1日"))
        assertTrue(entry.character.firstMessage.trimEnd().endsWith("故事将从这一刻继续。"))
        val model = app.pockettavern.domain.ModelInfo(id = "chat", contextTokens = 32768)
        val oldChat = app.pockettavern.domain.Chat(id = "existing", messages = listOf(app.pockettavern.domain.Message(content = "OLD_CHAT_ONLY")))
        val before = AppData(chats = listOf(oldChat), providers = listOf(app.pockettavern.domain.ProviderConfig(id = "p", models = listOf(model))))
        val seeded = BuiltinScenarios.merge(before, listOf(entry))
        assertEquals(before.providers, seeded.providers)
        assertEquals(before.chats, seeded.chats)
        assertEquals(before.skills, seeded.skills)
        val character = seeded.characters.single()
        val chat = app.pockettavern.domain.Chat(
            providerId = "p", modelId = "chat", characterId = character.id,
            presetId = BuiltinScenarios.presetIdForCharacter(character, seeded.presets),
            messages = listOf(app.pockettavern.domain.Message(role = "assistant", content = character.firstMessage),
                app.pockettavern.domain.Message(content = "零号，解释一下空间的遥收条件。"))
        )
        val context = app.pockettavern.engine.ContextEngine.build(seeded, chat)
        assertTrue(context.messagesJson.contains("用户独自扮演谷若尘"))
        assertTrue(context.messagesJson.contains("空间异能：初始操作与资源规则"))
        assertTrue(context.estimatedTokens < context.budget)
        assertTrue(!context.messagesJson.contains("OLD_CHAT_ONLY"))
        assertEquals(seeded, DataCodec.decode(DataCodec.encode(seeded)))
        println("Shipped scenario: ${context.estimatedTokens}/${context.budget} estimated context tokens; world entries=${entry.worldEntries.size}")
    }

    @Test
    fun loadParsesCardPresetAndBoundedWorldbookSearchRange() {
        val card = cardJson()
        val catalog = catalogJson(card)
        val files = mapOf("scenarios/catalog.json" to catalog, "scenarios/frost-manor.json" to card)
        val entry = BuiltinScenarios.load { path -> files[path] ?: error("missing $path") }.single()

        assertEquals("frost-manor", entry.id)
        assertEquals("builtin-scenario-frost-manor", entry.character.id)
        assertEquals("builtin-scenario-frost-manor-preset", entry.preset.id)
        assertEquals("用中文总结这段互动剧情。", entry.preset.summaryPrompt)
        assertEquals("builtin-scenario-frost-manor", entry.worldEntries.single().characterId)
        assertEquals(1000, entry.worldEntries.single().searchRange)
    }

    @Test
    fun mergeIsIdempotentAndPreservesEditsAndDeletion() {
        val entry = sampleEntry()
        val first = BuiltinScenarios.merge(AppData(), listOf(entry))
        assertEquals(listOf("frost-manor"), first.settings.builtinScenariosSeen)
        assertEquals(entry.preset.id, first.characters.single().defaultPresetId)

        val editedCharacter = first.characters.single().copy(description = "用户修改")
        val edited = first.copy(characters = listOf(editedCharacter))
        val mergedAgain = BuiltinScenarios.merge(edited, listOf(entry))
        assertEquals("用户修改", mergedAgain.characters.single().description)
        assertEquals(edited, mergedAgain)

        val deleted = first.copy(characters = emptyList())
        val restored = BuiltinScenarios.merge(deleted, listOf(entry))
        assertTrue(restored.characters.isEmpty())
        assertTrue(restored.presets.any { it.id == entry.preset.id })
    }

    @Test
    fun characterPresetUsesScenarioPresetThenRoleplayFallback() {
        val entry = sampleEntry()
        val merged = BuiltinScenarios.merge(AppData(), listOf(entry))
        val character = merged.characters.single()
        assertEquals(entry.preset.id, BuiltinScenarios.presetIdForCharacter(character, merged.presets))
        assertEquals("roleplay", BuiltinScenarios.presetIdForCharacter(character.copy(defaultPresetId = "missing"), merged.presets))
        assertEquals("default", BuiltinScenarios.presetIdForCharacter(null, merged.presets))
    }

    @Test
    fun oldStateWithoutScenarioMarkerRemainsReadable() {
        val source = JSONObject(DataCodec.encode(AppData()))
        source.getJSONObject("settings").remove("builtinScenariosSeen")
        val restored = DataCodec.decode(source.toString())
        assertTrue(restored.settings.builtinScenariosSeen.orEmpty().isEmpty())
    }

    private fun sampleEntry(): BuiltinScenarios.Entry = BuiltinScenarios.load { path ->
        val card = cardJson()
        when (path) {
            "scenarios/catalog.json" -> catalogJson(card)
            "scenarios/frost-manor.json" -> card
            else -> error("missing $path")
        }
    }.single()

    private fun cardJson(): String {
        val entry = JSONObject()
            .put("comment", "门厅")
            .put("content", "暴雪封住了庄园")
            .put("key", JSONArray(listOf("暴雪")))
            .put("constant", false)
            .put("searchRange", 1000)
        return JSONObject()
            .put("spec", "chara_card_v2")
            .put("data", JSONObject()
                .put("name", "旧名称")
                .put("description", "卡片描述")
                .put("scenario", "庄园门厅")
                .put("first_mes", "你推开了门。")
                .put("character_book", JSONObject().put("entries", JSONArray().put(entry))))
            .toString()
    }

    private fun catalogJson(card: String): String {
        val preset = JSONObject()
            .put("name", "庄园预设")
            .put("systemPrompt", "保持悬疑叙事。")
            .put("temperature", 0.7)
            .put("maxOutput", 1024)
            .put("summaryPrompt", "用中文总结这段互动剧情。")
        return JSONObject()
            .put("version", 1)
            .put("scenarios", JSONArray().put(JSONObject()
                .put("id", "frost-manor")
                .put("name", "霜雪庄园")
                .put("description", "一场暴雪中的庄园剧本")
                .put("path", "scenarios/frost-manor.json")
                .put("sha256", sha256(card))
                .put("preset", preset)))
            .toString()
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
