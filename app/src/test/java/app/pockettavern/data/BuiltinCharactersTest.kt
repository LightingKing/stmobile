package app.pockettavern.data

import app.pockettavern.domain.AppData
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 大肥鱼 ships with the app: she lists under 角色卡, arrives once, and stays deleted if removed. */
class BuiltinCharactersTest {
    private val read: (String) -> String = { File("src/main/assets", it).readText(Charsets.UTF_8) }

    @Test fun dafeiyuLoadsFromThePinnedCard() {
        val entry = BuiltinScenarios.loadCharacters(read).single()
        assertEquals("dafeiyu", entry.id)
        assertEquals("builtin-character-dafeiyu", entry.character.id)
        assertFalse("not listed with the scenarios", entry.character.id.startsWith("builtin-scenario-"))
        assertEquals("大肥鱼", entry.character.name)
        assertEquals("builtin-character-dafeiyu-preset", entry.preset.id)
        assertEquals("大肥鱼 · 日常聊天", entry.preset.name)
        assertTrue(entry.worldEntries.isEmpty())
        val card = entry.character
        assertTrue(card.firstMessage.contains("夜魔女王") && card.firstMessage.contains("契约者"))
        assertTrue("黑猫-style voice", card.systemPrompt.contains("中二病毒舌") && card.systemPrompt.contains("称呼{{user}}为“契约者”"))
        assertTrue("she is an adult AI", card.description.contains("二十岁左右") && card.systemPrompt.contains("不编造自己没经历过的现实经历"))
        assertTrue(card.examples.contains("<START>"))
        assertTrue("the scenario catalog is unchanged", BuiltinScenarios.load(read).single().character.id.startsWith("builtin-scenario-"))
    }

    @Test fun shePairsWithTheLooseCopyRulesAndMergesOnce() {
        val all = BuiltinScenarios.load(read) + BuiltinScenarios.loadCharacters(read)
        val first = BuiltinScenarios.merge(AppData(), all)
        val fish = first.characters.single { it.id == "builtin-character-dafeiyu" }
        assertEquals("builtin-character-dafeiyu-preset", fish.defaultPresetId)
        assertEquals(fish.defaultPresetId, BuiltinScenarios.presetIdForCharacter(fish, first.presets))
        assertTrue("dafeiyu" in first.settings.builtinScenariosSeen)
        val edited = first.copy(characters = first.characters.map { if (it.id == fish.id) it.copy(personality = "用户修改") else it })
        assertEquals("用户修改", BuiltinScenarios.merge(edited, all).characters.single { it.id == fish.id }.personality)
        val deleted = first.copy(characters = first.characters.filterNot { it.id == fish.id })
        assertFalse("a deleted 大肥鱼 does not come back", BuiltinScenarios.merge(deleted, all).characters.any { it.id == fish.id })
    }

    @Test fun tamperedCardIsRejected() {
        val tampered: (String) -> String = { path -> read(path).let { if (path == "characters/dafeiyu.json") it.replace("夜魔女王", "白魔女王") else it } }
        val error = runCatching { BuiltinScenarios.loadCharacters(tampered) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("SHA-256"))
    }
}
