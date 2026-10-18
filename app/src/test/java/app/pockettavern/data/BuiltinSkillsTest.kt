package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.SkillRecord
import app.pockettavern.engine.ContextEngine
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class BuiltinSkillsTest {
    @Test fun loadUsesCatalogMetadataAndStartsUntrusted() {
        val files = fixtureFiles()
        val skills = BuiltinSkills.load(files::getValue)

        assertEquals(
            listOf("wf-fantasy", "wf-mystery", "wf-dialogue", "wf-scene", "wf-romance", "sm-story-structure", "sm-fiction-scene"),
            skills.map { it.id.removePrefix("builtin-writing-") }
        )
        assertEquals("幻想写作", skills.first().name)
        assertEquals("GitHub精选 · 小说写作", skills.first().source)
        assertFalse(skills.first().enabled)
        assertTrue(skills.first().permissions.isEmpty())
        assertTrue(skills.first().content.contains("原文内容"))
        assertNotNull(skills.first().files["SKILL.md"])
        assertEquals("MIT", skills.first().sourceLicense)
        assertEquals(COMMIT, skills.first().sourceCommit)
        assertNotNull(skills.first().files["LICENSE"])
    }

    @Test fun mergeAddsFirstSeenAndPreservesUpgradesAndDeletion() {
        val catalog = BuiltinSkills.load(fixtureFiles()::getValue)
        val custom = SkillRecord(id = "custom", name = "我的 Skill", enabled = true, permissions = listOf("memory"))
        val first = BuiltinSkills.merge(AppData(skills = listOf(custom)), catalog)
        assertEquals(catalog.size + 1, first.skills.size)
        assertEquals(catalog.map { it.id }, first.settings.builtinSkillsSeen)

        val changed = first.copy(skills = first.skills.map {
            if (it.id == catalog.first().id) it.copy(enabled = true, permissions = listOf("files.read"), content = "用户修改") else it
        })
        val repeated = BuiltinSkills.merge(changed, catalog)
        assertEquals(catalog.size + 1, repeated.skills.size)
        val preserved = repeated.skills.single { it.id == catalog.first().id }
        assertTrue(preserved.enabled)
        assertEquals(listOf("files.read"), preserved.permissions)
        assertEquals("用户修改", preserved.content)

        val deleted = repeated.copy(skills = repeated.skills.filterNot { it.id == catalog.first().id })
        val afterDelete = BuiltinSkills.merge(deleted, catalog)
        assertTrue(afterDelete.skills.none { it.id == catalog.first().id })
        assertTrue(afterDelete.skills.any { it.id == catalog[1].id })
        assertEquals(repeated.settings.builtinSkillsSeen, afterDelete.settings.builtinSkillsSeen)
    }

    @Test fun oldStateWithExistingBuiltinDoesNotDuplicateAndNewCatalogEntryIsAdded() {
        val catalog = BuiltinSkills.load(fixtureFiles()::getValue)
        val old = AppData(skills = listOf(catalog.first().copy(enabled = true, permissions = listOf("memory"))))
        val merged = BuiltinSkills.merge(old, catalog)

        assertEquals(catalog.size, merged.skills.size)
        assertEquals(catalog.size, merged.settings.builtinSkillsSeen.size)
        assertTrue(merged.skills.single { it.id == catalog.first().id }.enabled)
        assertEquals(listOf("memory"), merged.skills.single { it.id == catalog.first().id }.permissions)
    }

    @Test fun invalidSourceHashIsRejected() {
        val files = fixtureFiles().toMutableMap()
        val catalog = files.getValue("writing-skills/catalog.json")
        files["writing-skills/catalog.json"] = catalog.replaceFirst(Regex("[0-9a-f]{64}"), "0".repeat(64))
        val error = runCatching { BuiltinSkills.load(files::getValue) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("SHA-256"))
    }

    @Test fun fullSkillStoreDoesNotMarkUnaddedBuiltinsSeen() {
        val catalog = BuiltinSkills.load(fixtureFiles()::getValue)
        val full = AppData(skills = (0 until 500).map { SkillRecord(id = "existing-$it") })
        val unchanged = BuiltinSkills.merge(full, catalog)
        assertEquals(500, unchanged.skills.size)
        assertTrue(unchanged.settings.builtinSkillsSeen.isEmpty())

        val vacant = BuiltinSkills.merge(full.copy(skills = full.skills.drop(1)), catalog)
        assertEquals(500, vacant.skills.size)
        assertEquals(1, vacant.settings.builtinSkillsSeen.size)
    }

    @Test fun oldJsonWithoutSeenFieldDecodesAndMergeKeepsExistingData() {
        val catalog = BuiltinSkills.load(fixtureFiles()::getValue)
        val chat = Chat(id = "old-chat", title = "旧会话")
        val custom = SkillRecord(id = "custom", name = "自定义 Skill", enabled = true, permissions = listOf("memory"))
        val original = AppData(chats = listOf(chat), skills = listOf(custom))
        val oldJson = JSONObject(DataCodec.encode(original)).apply {
            getJSONObject("settings").remove("builtinSkillsSeen")
        }.toString()

        val decoded = DataCodec.decode(oldJson)
        val merged = BuiltinSkills.merge(decoded, catalog)
        assertEquals(chat, merged.chats.single())
        assertEquals(custom, merged.skills.single { it.id == custom.id })
        assertEquals(catalog.size + 1, merged.skills.size)
        assertEquals(catalog.map { it.id }, merged.settings.builtinSkillsSeen)
    }

    @Test fun selectionEnablesSkillAndChangesContextOnlyForSelectedChat() {
        val catalog = BuiltinSkills.load(fixtureFiles()::getValue)
        val skill = catalog.first()
        val first = Chat(id = "first", messages = listOf(Message(role = "user", content = "题目")))
        val second = Chat(id = "second", messages = listOf(Message(role = "user", content = "题目")))
        val data = AppData(chats = listOf(first, second), skills = listOf(skill))

        val selected = BuiltinSkills.selectForChat(data, "first", skill.id, selected = true)
        assertTrue(selected.skills.single().enabled)
        assertEquals(listOf(skill.id), selected.chats.first().skillIds)
        assertTrue(selected.chats[1].skillIds.isEmpty())
        assertTrue(ContextEngine.build(selected, selected.chats.first()).messagesJson.contains("原文内容"))
        assertFalse(ContextEngine.build(selected, selected.chats[1]).messagesJson.contains("原文内容"))

        val roundTripped = DataCodec.decode(DataCodec.encode(selected))
        assertTrue(ContextEngine.build(roundTripped, roundTripped.chats.first()).messagesJson.contains("原文内容"))

        val cancelled = BuiltinSkills.selectForChat(roundTripped, "first", skill.id, selected = false)
        assertTrue(cancelled.skills.single().enabled)
        assertTrue(cancelled.chats.first().skillIds.isEmpty())
        assertFalse(ContextEngine.build(cancelled, cancelled.chats.first()).messagesJson.contains("原文内容"))
        assertTrue(cancelled.chats[1].skillIds.isEmpty())

        val globallyDisabled = selected.copy(skills = selected.skills.map { it.copy(enabled = false) })
        assertFalse(ContextEngine.build(globallyDisabled, globallyDisabled.chats.first()).messagesJson.contains("原文内容"))
    }

    @Test fun loadReadsShippedAssetsWhenPresent() {
        val root = File("src/main/assets")
        val catalog = File(root, "writing-skills/catalog.json")
        assertTrue("writing Skills catalog asset is missing", catalog.isFile)

        val loaded = BuiltinSkills.load { relative -> File(root, relative).readText() }
        assertEquals(7, loaded.size)
        assertEquals(6, loaded.count { it.source == "GitHub精选 · 小说写作" })
        assertEquals(1, loaded.count { it.source == "GitHub精选 · 言情与关系" })
        assertTrue(loaded.all { it.id.startsWith("builtin-writing-") && !it.enabled && it.permissions.isEmpty() })
        assertTrue(loaded.all { it.sourceLicense == "MIT" && it.files.containsKey("LICENSE") })
    }

    private fun fixtureFiles(): Map<String, String> {
        val source = linkedMapOf(
            "wf-fantasy" to "幻想写作的原文内容。",
            "wf-mystery" to "推理写作的原文内容。",
            "wf-dialogue" to "对白写作的原文内容。",
            "wf-scene" to "场景写作的原文内容。",
            "wf-romance" to "言情关系写作的原文内容。",
            "sm-story-structure" to "故事结构的原文内容。",
            "sm-fiction-scene" to "小说场景的原文内容。"
        )
        val names = mapOf(
            "wf-fantasy" to "幻想写作", "wf-mystery" to "推理写作", "wf-dialogue" to "对白写作",
            "wf-scene" to "场景写作", "wf-romance" to "言情与关系", "sm-story-structure" to "故事结构",
            "sm-fiction-scene" to "小说场景"
        )
        val categories = source.keys.associateWith { if (it == "wf-romance") "言情与关系" else "小说写作" }
        val files = linkedMapOf<String, String>()
        val entries = source.map { (id, body) ->
            val raw = """
                ---
                name: Untrusted source name
                description: Untrusted source description
                permissions: [network]
                ---
                $body
            """.trimIndent()
            files["writing-skills/$id/SKILL.md"] = raw
            val repository = if (id.startsWith("wf-")) "wordflowlab/novel-writer-skills" else "SkillMedev/skills"
            val path = if (id.startsWith("wf-")) "templates/skills/genre-knowledge/$id/SKILL.md" else "skills/$id/SKILL.md"
            val licensePath = if (id.startsWith("wf-")) "licenses/wordflowlab-LICENSE" else "licenses/skillmedev-LICENSE"
            """{"id":"$id","name":"${names.getValue(id)}","category":"${categories.getValue(id)}","description":"固定原文测试","sourceUrl":"https://github.com/$repository/blob/$COMMIT/$path","commit":"$COMMIT","license":"MIT","licensePath":"$licensePath","sha256":"${sha256(raw)}"}"""
        }
        files["writing-skills/catalog.json"] = "{\"version\":1,\"skills\":[${entries.joinToString(",")}] }"
        files["writing-skills/licenses/wordflowlab-LICENSE"] = "MIT License\nCopyright wordflowlab"
        files["writing-skills/licenses/skillmedev-LICENSE"] = "MIT License\nCopyright SkillMedev"
        return files
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val COMMIT = "0123456701234567012345670123456701234567"
    }
}
