package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.SkillRecord
import app.pockettavern.engine.ContextEngine
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two Skills written for this app: character voice and contemporary mainland China defaults. */
class FirstPartySkillsTest {
    private val root = File("src/main/assets")
    private val read: (String) -> String = { File(root, it).readText() }
    private val ids = listOf("builtin-writing-pt-character-voice", "builtin-writing-pt-china-common-sense")

    @Test fun loadsDisabledNonAdultSkillsLabeledAsWrittenByThisApp() {
        val skills = BuiltinSkills.loadFirstParty(read)
        assertEquals(ids, skills.map { it.id })
        assertEquals(listOf("说话风格符合角色设定", "中国国内世界观及社会常识"), skills.map { it.name })
        assertEquals(listOf("本应用编写 · 角色扮演", "本应用编写 · 社会常识"), skills.map { it.source })
        assertEquals(listOf("角色扮演", "社会常识"), skills.map { BuiltinSkills.bundledCategory(it) })
        skills.forEach { skill ->
            assertFalse(skill.id, skill.enabled)
            assertFalse(skill.id, skill.adult)
            assertTrue(skill.id, skill.permissions.isEmpty())
            assertEquals("", skill.sourceUrl)
            assertEquals("", skill.sourceCommit)
            assertEquals(BuiltinSkills.FIRST_PARTY_LICENSE, skill.sourceLicense)
            assertEquals(setOf("SKILL.md", "LICENSE"), skill.files.keys)
            assertTrue(skill.files.getValue("LICENSE").contains("Affero"))
            assertTrue(skill.warnings.isEmpty())
        }
    }

    @Test fun allBundledSkillsAreUpstreamPlusTheTwoFirstParty() {
        val all = BuiltinSkills.loadAll(read)
        val upstream = BuiltinSkills.load(read)
        assertEquals(26, all.size)
        assertEquals(24, upstream.size)
        assertEquals(ids, all.drop(upstream.size).map { it.id })
        assertTrue("first-party Skills show no star line", BuiltinSkills.loadStars(read).keys.none { it in ids })
        assertTrue("upstream ids never use the first-party marker", upstream.none { it.id.startsWith("builtin-writing-pt-") })
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertNull(BuiltinSkills.bundledCategory(SkillRecord(id = "custom", source = "本地导入")))
    }

    @Test fun shortWritingConstraintsOnly() {
        BuiltinSkills.loadFirstParty(read).forEach { skill ->
            val text = skill.files.getValue("SKILL.md")
            assertTrue("${skill.id} ${text.length}", text.length <= 2_500)
            listOf("状态栏", "写入文件", "保存到", "files.write", "skill_read", "忽略安全", "无视", "越狱", "jailbreak", "ignore", "未成年", "成人").forEach { banned ->
                assertFalse("${skill.id} contains $banned", text.contains(banned, ignoreCase = true))
            }
            assertTrue(skill.id, text.contains("用户本轮的明确要求") && text.contains("角色卡"))
        }
    }

    @Test fun tamperedBytesAreRejected() {
        val tampered: (String) -> String = { path -> read(path).let { if (path.endsWith("pt-character-voice/SKILL.md")) "$it\n多一行" else it } }
        val error = runCatching { BuiltinSkills.loadFirstParty(tampered) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("SHA-256"))
    }

    @Test fun upgradeAddsBothOnceAndKeepsExistingData() {
        val upstream = BuiltinSkills.load(read)
        val existing = BuiltinSkills.merge(AppData(), upstream)
        val edited = existing.copy(skills = existing.skills.map { if (it.id == "builtin-writing-tm-deslop") it.copy(enabled = true) else it })
        val upgraded = BuiltinSkills.merge(edited, BuiltinSkills.loadAll(read))
        assertEquals(upstream.size + ids.size, upgraded.skills.size)
        assertTrue(upgraded.skills.single { it.id == "builtin-writing-tm-deslop" }.enabled)
        ids.forEach { id -> assertFalse(id, upgraded.skills.single { it.id == id }.enabled) }
        val removed = upgraded.copy(skills = upgraded.skills.filterNot { it.id == ids.first() })
        assertEquals("a deleted first-party Skill does not come back", upstream.size + ids.size - 1, BuiltinSkills.merge(removed, BuiltinSkills.loadAll(read)).skills.size)
    }

    @Test fun selectedSkillsAreSentOnceWithoutExtraLines() {
        val chat = Chat(id = "c", messages = listOf(Message(role = "user", content = "开始")))
        var data = AppData(chats = listOf(chat), skills = BuiltinSkills.loadAll(read), settings = AppSettings())
        ids.forEach { data = BuiltinSkills.selectForChat(data, "c", it, true) }
        val messages = JSONArray(ContextEngine.build(data, data.chats.single()).messagesJson)
        val contents = (0 until messages.length()).map { messages.getJSONObject(it).optString("content") }
        listOf("说话风格符合角色设定", "中国国内世界观及社会常识").forEach { name ->
            val prompt = contents.single { it.startsWith("【Skill: $name】") }
            assertFalse(name, prompt.contains("【避免重复】"))
            assertFalse(name, prompt.contains("【Skill 引用文件】"))
        }
        assertEquals(1, contents.count { it == ContextEngine.REPLY_NO_REPEAT })
        assertTrue(contents.none { it.startsWith("【成人向内容边界】") })
    }

    @Test fun writtenForDeepSeekFlashButSentTheSameForEveryModel() {
        val skills = BuiltinSkills.loadFirstParty(read)
        val voice = skills.first().files.getValue("SKILL.md")
        val china = skills.last().files.getValue("SKILL.md")
        listOf("示例对话", "人设", "描述", "不替代角色卡").forEach { assertTrue("voice points at the card: $it", voice.contains(it)) }
        assertTrue("China defaults stay short: ${china.length}", china.length <= 1_000)
        listOf("米兰达", "小费", "911", "先生/小姐").forEach { assertTrue("China skill replaces foreign default $it", china.contains(it)) }
        skills.forEach { assertFalse(it.id, it.files.getValue("SKILL.md").contains("DeepSeek", ignoreCase = true)) }

        fun skillPrompts(modelId: String): List<String> {
            val chat = Chat(id = "c", modelId = modelId, messages = listOf(Message(role = "user", content = "开始")))
            var data = AppData(chats = listOf(chat), skills = BuiltinSkills.loadAll(read), settings = AppSettings())
            ids.forEach { data = BuiltinSkills.selectForChat(data, "c", it, true) }
            val messages = JSONArray(ContextEngine.build(data, data.chats.single()).messagesJson)
            return (0 until messages.length()).map { messages.getJSONObject(it).optString("content") }.filter { it.startsWith("【Skill: ") }
        }
        val flash = skillPrompts("deepseek-v4-1-flash")
        assertEquals(2, flash.size)
        assertEquals(flash, skillPrompts("deepseek-v4-1-flash-1017"))
        assertEquals(flash, skillPrompts("venice-uncensored"))
    }
}
