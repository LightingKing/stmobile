package app.pockettavern.data

import app.pockettavern.domain.AppData
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 大肥鱼 and 黑猫 ship with the app: they list under 角色卡, arrive once, and stay deleted if removed. */
class BuiltinCharactersTest {
    private val read: (String) -> String = { File("src/main/assets", it).readText(Charsets.UTF_8) }
    private val characters = BuiltinScenarios.loadCharacters(read)
    private val fish = characters.single { it.id == "dafeiyu" }
    private val cat = characters.single { it.id == "kuroneko" }

    @Test fun bothLoadFromPinnedCardsWithTheirOwnVoices() {
        assertEquals(listOf("builtin-character-dafeiyu", "builtin-character-kuroneko"), characters.map { it.character.id })
        assertTrue("not listed with the scenarios", characters.none { it.character.id.startsWith("builtin-scenario-") })
        assertEquals(listOf("大肥鱼 · 日常聊天", "黑猫 · 日常聊天"), characters.map { it.preset.name })
        assertEquals("大肥鱼", fish.character.name)
        assertTrue("community 大肥鱼: 主人 and kaomoji", fish.character.systemPrompt.contains("称呼{{user}}为“主人”") && fish.character.firstMessage.contains("(ﾉ>ω<)ﾉ"))
        assertFalse("no longer 黑猫-flavoured", fish.character.firstMessage.contains("夜魔女王"))
        assertEquals("黑猫", cat.character.name)
        assertTrue(cat.character.systemPrompt.contains("称呼{{user}}为“前辈”") && cat.character.firstMessage.contains("夜之眷属"))
        assertTrue("both are adults", fish.character.description.contains("二十岁左右") && cat.character.description.contains("已经二十岁"))
        assertTrue("the scenario catalog is unchanged", BuiltinScenarios.load(read).single().character.id.startsWith("builtin-scenario-"))
    }

    @Test fun theyMergeOnceAndKeepEditsAndDeletions() {
        val all = BuiltinScenarios.load(read) + characters
        val first = BuiltinScenarios.merge(AppData(), all)
        val installed = first.characters.single { it.id == cat.character.id }
        assertEquals("builtin-character-kuroneko-preset", installed.defaultPresetId)
        assertEquals(installed.defaultPresetId, BuiltinScenarios.presetIdForCharacter(installed, first.presets))
        val edited = first.copy(characters = first.characters.map { if (it.id == cat.character.id) it.copy(personality = "用户修改") else it })
        assertEquals("用户修改", BuiltinScenarios.merge(edited, all).characters.single { it.id == cat.character.id }.personality)
        val deleted = first.copy(characters = first.characters.filterNot { it.id == cat.character.id })
        assertFalse("a deleted built-in does not come back", BuiltinScenarios.merge(deleted, all).characters.any { it.id == cat.character.id })
    }

    @Test fun anUntouched0181FishIsReplacedButAnEditedOneIsKept() {
        // 0.1.81 shipped 大肥鱼 with 黑猫's personality; a phone that installed it has her marked as seen.
        val old = fish.character.copy(
            description = File("src/test/resources/builtin/dafeiyu-0.1.81-description.txt").readText(Charsets.UTF_8),
            personality = File("src/test/resources/builtin/dafeiyu-0.1.81-personality.txt").readText(Charsets.UTF_8),
            scenario = File("src/test/resources/builtin/dafeiyu-0.1.81-scenario.txt").readText(Charsets.UTF_8),
            firstMessage = File("src/test/resources/builtin/dafeiyu-0.1.81-first.txt").readText(Charsets.UTF_8),
            examples = File("src/test/resources/builtin/dafeiyu-0.1.81-examples.txt").readText(Charsets.UTF_8),
            systemPrompt = File("src/test/resources/builtin/dafeiyu-0.1.81-system.txt").readText(Charsets.UTF_8),
            defaultPresetId = "builtin-character-dafeiyu-preset")
        assertTrue(BuiltinScenarios.contentHash(old) in fish.replaces)
        val phone = AppData(characters = listOf(old)).let { it.copy(settings = it.settings.copy(builtinScenariosSeen = listOf("dafeiyu"))) }
        val upgraded = BuiltinScenarios.merge(phone, characters)
        val fishNow = upgraded.characters.single { it.id == "builtin-character-dafeiyu" }
        assertTrue(fishNow.firstMessage.startsWith("主人主人"))
        assertEquals("builtin-character-dafeiyu-preset", fishNow.defaultPresetId)
        assertTrue("黑猫 arrives as a new character", upgraded.characters.any { it.id == "builtin-character-kuroneko" })
        val touched = phone.copy(characters = listOf(old.copy(firstMessage = old.firstMessage + "（我改过）")))
        assertTrue(BuiltinScenarios.merge(touched, characters).characters.single { it.id == "builtin-character-dafeiyu" }.firstMessage.endsWith("（我改过）"))
    }

    @Test fun tamperedCardIsRejected() {
        val tampered: (String) -> String = { path -> read(path).let { if (path == "characters/kuroneko.json") it.replace("夜之眷属", "昼之眷属") else it } }
        val error = runCatching { BuiltinScenarios.loadCharacters(tampered) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("SHA-256"))
    }
}
