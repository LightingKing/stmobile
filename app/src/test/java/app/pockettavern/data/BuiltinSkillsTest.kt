package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.SkillRecord
import app.pockettavern.engine.ContextEngine
import app.pockettavern.engine.ToolRuntime
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class BuiltinSkillsTest {
    @Test fun missingAndExplicitRestorePreserveInstalledDataAndStayDisabled() {
        val catalog = BuiltinSkills.load(fixtureFiles()::getValue)
        val removed = catalog.first()
        val edited = catalog[1].copy(name = "我的编辑", enabled = true, permissions = listOf("files.read"))
        val chat = Chat(id = "c", skillIds = listOf(edited.id))
        val saved = AppData(skills = listOf(edited), chats = listOf(chat),
            settings = AppSettings(builtinSkillsSeen = catalog.map { it.id }, builtinSkillsRevision = 4))
        assertEquals(catalog.filterNot { it.id == edited.id }, BuiltinSkills.missing(saved, catalog))
        val restored = BuiltinSkills.restore(saved, catalog, removed.id)
        assertEquals(removed.copy(enabled = false, permissions = emptyList()), restored.skills.last())
        assertEquals(edited, restored.skills.first())
        assertEquals(saved.chats, restored.chats)
        assertEquals(saved.settings, restored.settings)
        assertFalse(BuiltinSkills.missing(restored, catalog).any { it.id == removed.id })
        assertEquals(restored, BuiltinSkills.restore(restored, catalog, removed.id))
        assertEquals(restored, BuiltinSkills.merge(restored, listOf(removed, edited)))
    }

    @Test fun restoreEnforcesCapacityKnownIdAndSeenMarker() {
        val catalog = BuiltinSkills.load(fixtureFiles()::getValue)
        val full = AppData(skills = (0 until 500).map { SkillRecord(id = "local-$it") })
        assertTrue(runCatching { BuiltinSkills.restore(full, catalog, catalog.first().id) }.isFailure)
        assertTrue(runCatching { BuiltinSkills.restore(AppData(), catalog, "unknown") }.isFailure)
        val restored = BuiltinSkills.restore(AppData(), catalog, catalog.first().id)
        assertEquals(listOf(catalog.first().id), restored.settings.builtinSkillsSeen)
        assertFalse(restored.skills.single().enabled)
        assertTrue(restored.skills.single().permissions.isEmpty())
    }

    @Test fun revisionFourRepairsOnlyDeepNovelResourcesAndKeepsOtherEdits() {
        val novel = SkillRecord(id = "builtin-writing-dn-deep-novel", source = "GitHub精选 · 成人向",
            files = mapOf("assets/template/config/instruction.md" to "new-marker"))
        val other = SkillRecord(id = "builtin-writing-other", source = "GitHub精选 · 小说写作")
        val oldNovel = novel.copy(files = emptyMap(), enabled = true, permissions = listOf("files.read"))
        val editedOther = other.copy(content = "用户编辑")
        val saved = AppData(skills = listOf(oldNovel, editedOther),
            settings = AppSettings(builtinSkillsSeen = listOf(novel.id, other.id), builtinSkillsRevision = 1))
        val merged = BuiltinSkills.merge(saved, listOf(novel, other))
        assertEquals(novel.copy(enabled = true, permissions = listOf("files.read")), merged.skills.first())
        assertEquals(editedOther, merged.skills.last())
        assertEquals(4, merged.settings.builtinSkillsRevision)
        assertEquals(merged, BuiltinSkills.merge(merged, listOf(novel, other)))
    }

    @Test fun loadUsesCatalogMetadataAndStartsUntrusted() {
        val files = fixtureFiles()
        val skills = BuiltinSkills.load(files::getValue)

        assertEquals(
            listOf("fx-fantasy", "fx-mystery", "fx-dialogue", "fx-scene", "fx-romance", "fy-story-structure", "fy-fiction-scene"),
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

    @Test fun installedGitHubSkillsRefreshVerbatimAndChineseMetadataOnlyOnce() {
        val catalog = BuiltinSkills.load(fixtureFiles()::getValue)
        val builtin = catalog.first()
        val old = builtin.copy(
            name = "Old name", description = "Old description", content = "旧删节正文",
            files = mapOf("SKILL.md" to "旧删节正文", "old.md" to "旧引用"),
            source = "旧来源", warnings = listOf("旧导入提示"), sourceUrl = "", sourceCommit = "", sourceLicense = "旧许可证",
            enabled = true, permissions = listOf("files.read", "memory")
        )
        val custom = SkillRecord(id = "custom", name = "自定义", content = "用户正文")
        val firstParty = SkillRecord(id = "builtin-writing-pt-custom", name = "应用 Skill", content = "用户修改的自编正文", source = "${BuiltinSkills.FIRST_PARTY_SOURCE} · 小说写作")
        val chat = Chat(id = "c", skillIds = listOf(builtin.id, custom.id, firstParty.id))
        val saved = AppData(
            chats = listOf(chat), skills = listOf(old, custom, firstParty),
            settings = AppSettings(builtinSkillsSeen = catalog.map { it.id } + firstParty.id)
        )
        val merged = BuiltinSkills.merge(saved, catalog + firstParty.copy(content = "应用出厂正文"))

        assertEquals(builtin.copy(enabled = old.enabled, permissions = old.permissions), merged.skills.single { it.id == builtin.id })
        assertEquals(custom, merged.skills.single { it.id == custom.id })
        assertEquals(firstParty, merged.skills.single { it.id == firstParty.id })
        assertEquals(chat, merged.chats.single())
        assertFalse("previously deleted Skill must stay deleted", merged.skills.any { it.id == catalog[1].id })
        assertEquals(4, merged.settings.builtinSkillsRevision)

        val edited = merged.copy(skills = merged.skills.map {
            if (it.id == builtin.id) it.copy(name = "用户名称", description = "用户说明", content = "升级后用户修改", files = mapOf("SKILL.md" to "升级后用户修改")) else it
        })
        val roundTripped = DataCodec.decode(DataCodec.encode(edited))
        assertEquals(4, roundTripped.settings.builtinSkillsRevision)
        assertEquals(roundTripped, BuiltinSkills.merge(roundTripped, catalog + firstParty))
    }

    @Test fun fullSkillStoreRefreshesInstalledGitHubButDoesNotMarkUnaddedEntriesSeen() {
        val catalog = BuiltinSkills.load(fixtureFiles()::getValue)
        val old = catalog.first().copy(content = "旧正文", files = mapOf("SKILL.md" to "旧正文"), enabled = true)
        val full = AppData(skills = listOf(old) + (1 until 500).map { SkillRecord(id = "existing-$it") })
        val merged = BuiltinSkills.merge(full, catalog)

        assertEquals(500, merged.skills.size)
        assertEquals(catalog.first().copy(enabled = true), merged.skills.first())
        assertEquals(listOf(old.id), merged.settings.builtinSkillsSeen)
        assertEquals(4, merged.settings.builtinSkillsRevision)

        val vacant = BuiltinSkills.merge(merged.copy(skills = merged.skills.dropLast(1)), catalog)
        assertEquals(500, vacant.skills.size)
        assertEquals(catalog.take(2).map { it.id }, vacant.settings.builtinSkillsSeen)
    }

    @Test fun firstPartyOnlyMergeDoesNotConsumeGitHubRevision() {
        val firstParty = SkillRecord(id = "builtin-writing-pt-custom", source = "${BuiltinSkills.FIRST_PARTY_SOURCE} · 小说写作")
        val first = BuiltinSkills.merge(AppData(), listOf(firstParty))
        assertEquals(0, first.settings.builtinSkillsRevision)

        val catalog = BuiltinSkills.load(fixtureFiles()::getValue)
        val old = catalog.first().copy(content = "旧正文")
        val merged = BuiltinSkills.merge(first.copy(skills = first.skills + old), catalog)
        assertEquals(catalog.first(), merged.skills.single { it.id == old.id })
        assertEquals(4, merged.settings.builtinSkillsRevision)
    }

    @Test fun newerCatalogRevisionIsNotDowngradedOrRefreshed() {
        val catalog = BuiltinSkills.load(fixtureFiles()::getValue)
        val edited = catalog.first().copy(content = "未来版本编辑")
        val saved = AppData(
            skills = listOf(edited),
            settings = AppSettings(builtinSkillsSeen = catalog.map { it.id }, builtinSkillsRevision = 7)
        )
        assertEquals(saved, BuiltinSkills.merge(saved, catalog))
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
            getJSONObject("settings").remove("builtinSkillsRevision")
        }.toString()

        val decoded = DataCodec.decode(oldJson)
        assertEquals(0, decoded.settings.builtinSkillsRevision)
        val merged = BuiltinSkills.merge(decoded, catalog)
        assertEquals(chat, merged.chats.single())
        assertEquals(custom, merged.skills.single { it.id == custom.id })
        assertEquals(catalog.size + 1, merged.skills.size)
        assertEquals(catalog.map { it.id }, merged.settings.builtinSkillsSeen)
        assertEquals(4, merged.settings.builtinSkillsRevision)
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
        assertEquals(25, loaded.size)
        assertEquals(9, loaded.count { it.source == "GitHub精选 · 小说写作" })
        assertEquals(7, loaded.count { it.source == "GitHub精选 · 角色扮演" })
        assertEquals(9, loaded.count { it.source == "GitHub精选 · 成人向" })
        assertTrue(loaded.all { it.id.startsWith("builtin-writing-") && !it.enabled && it.permissions.isEmpty() })
        assertTrue(loaded.all { it.sourceLicense in setOf("MIT", "GPL-3.0", "AGPL-3.0", "CC-BY-4.0", BuiltinSkills.PERSONAL_USE_LICENSE) && it.files.containsKey("LICENSE") })
        assertEquals(4, loaded.count { it.sourceLicense == BuiltinSkills.PERSONAL_USE_LICENSE })
        assertEquals(listOf("at-adult-tension-original", "dn-deep-novel", "dp-chapter-adult-original", "ec-erotica-craft", "fe-fictional-erotica", "hn-continuity", "hn-scene-writing", "hn-worldbuilding", "va-velvet-ascent"),
            loaded.filter { it.adult }.map { it.id.removePrefix("builtin-writing-") }.sorted())
    }

    @Test fun shippedReferenceFilesResolveFromAssetsAndStayOutOfState() {
        val root = File("src/main/assets")
        val reader = { relative: String -> File(root, relative).readText() }
        val loaded = BuiltinSkills.load(reader)
        var resolvedChars = 0L
        var references = 0
        loaded.forEach { skill ->
            ToolRuntime.referencePaths(skill).forEach { path ->
                assertTrue("${skill.id}/$path should be an asset marker", skill.files.getValue(path).startsWith(BuiltinSkills.ASSET_PREFIX))
                val text = BuiltinSkills.readResource(reader, skill, path)
                assertNotNull("${skill.id}/$path", text)
                resolvedChars += text!!.length
                references++
            }
        }
        assertEquals(325, references)
        val stateChars = DataCodec.encode(AppData(skills = loaded)).length
        assertTrue("reference text must not be copied into persisted state ($stateChars vs $resolvedChars)", stateChars < resolvedChars)
        val sepia = loaded.single { it.id == "builtin-writing-se-sepia" }
        assertTrue(BuiltinSkills.readResource(reader, sepia, "references/languages/zh.md").orEmpty().isNotBlank())
    }

    @Test fun webnovelSkillsShipPinnedWithMitLicenseAndReferences() {
        val root = File("src/main/assets")
        val reader = { relative: String -> File(root, relative).readText() }
        val loaded = BuiltinSkills.load(reader).associateBy { it.id.removePrefix("builtin-writing-") }
        val expected = mapOf(
            "tm-deslop" to listOf("references/anti-ai-checklist.md", "references/ai-detector.md", "references/human-texture.md", "references/sentence-rhythm.md", "references/show-emotion.md"),
            "tm-dialogue" to listOf("references/dialogue-library.md", "references/project-memory.md"),
            "tm-continuity" to listOf("references/continuity-check.md", "references/human-texture.md", "references/project-memory.md")
        )
        expected.forEach { (id, references) ->
            val skill = loaded.getValue(id)
            assertEquals("GitHub精选 · 小说写作", skill.source)
            assertEquals("MIT", skill.sourceLicense)
            assertEquals(TANCE_COMMIT, skill.sourceCommit)
            assertTrue(skill.sourceUrl.startsWith("https://github.com/tance-mang/chinese-webnovel-skills/blob/$TANCE_COMMIT/skills/"))
            assertFalse(skill.enabled || skill.adult)
            assertTrue(skill.permissions.isEmpty())
            assertTrue(skill.files.getValue("LICENSE").contains("Copyright (c) 2026 tance-mang"))
            assertEquals(references.sorted(), ToolRuntime.referencePaths(skill).sorted())
            references.forEach { assertTrue("$id/$it", BuiltinSkills.readResource(reader, skill, it).orEmpty().isNotBlank()) }
        }
    }

    @Test fun skillPromptsGetNoAppendedNote() {
        val root = File("src/main/assets")
        val loaded = BuiltinSkills.load { File(root, it).readText() }
        val chat = Chat(id = "c", messages = listOf(Message(role = "user", content = "开始")))
        var data = AppData(chats = listOf(chat), skills = loaded)
        listOf("tm-deslop", "tm-dialogue", "tm-continuity", "dp-character-style", "tt-sp-roleplay").forEach {
            data = BuiltinSkills.selectForChat(data, "c", "builtin-writing-$it", true)
        }
        val messages = org.json.JSONArray(ContextEngine.build(data, data.chats.single()).messagesJson)
        val skillPrompts = (0 until messages.length()).map { messages.getJSONObject(it) }
            .filter { it.getString("role") == "system" && it.getString("content").startsWith("【Skill: ") }
            .map { it.getString("content") }
        assertEquals(5, skillPrompts.size)
        skillPrompts.forEach { assertFalse(it.take(40), it.contains("【避免重复】")) }
    }

    @Test fun replyNoRepeatLineIsSentExactlyOncePerRequest() {
        val expected = "【避免重复回复】同一条回复里不要把同一段话或同一段落输出两三遍，也不要再写一遍本条回复前面已经写过的大段内容。"
        assertEquals(expected, ContextEngine.REPLY_NO_REPEAT)
        val root = File("src/main/assets")
        val loaded = BuiltinSkills.load { File(root, it).readText() }
        val history = listOf(
            Message(role = "user", content = "开始"),
            Message(role = "assistant", content = "庄园大门在雪里打开。"),
            Message(role = "user", content = "继续")
        )
        val chat = Chat(id = "c", messages = history)
        val none = AppData(chats = listOf(chat), skills = loaded)
        var all = none
        loaded.filterNot { it.adult }.forEach { all = BuiltinSkills.selectForChat(all, "c", it.id, true) }
        listOf(none, all).forEach { data ->
            val messages = org.json.JSONArray(ContextEngine.build(data, data.chats.single()).messagesJson)
            val contents = (0 until messages.length()).map { messages.getJSONObject(it) }
            assertEquals(1, contents.count { it.optString("content") == expected })
            assertEquals(1, Regex("【避免重复回复】").findAll(messages.toString()).count())
            val line = contents.indexOfFirst { it.optString("content") == expected }
            assertEquals("system", contents[line].getString("role"))
            assertTrue("sent after every skill prompt", contents.drop(line + 1).none { it.optString("content").startsWith("【Skill: ") })
            // 0.1.66: it closes the stable prompt; only turn-varying system context sits between it and the history.
            val after = contents.drop(line + 1)
            val firstChat = after.indexOfFirst { it.getString("role") != "system" }
            assertTrue(after.take(firstChat).all { it.getString("role") == "system" })
            // 0.2.4: the latest message also carries the Skill note when Skills are selected; the history itself is unchanged.
            assertEquals("chat history follows unchanged", history.map { it.content },
                after.drop(firstChat).map { it.getString("content").substringBefore("\n\n" + app.pockettavern.engine.SkillReminder.HEADING) })
        }
    }

    @Test fun tamperedReferenceAssetAndForeignMarkersAreRejected() {
        val root = File("src/main/assets")
        val loaded = BuiltinSkills.load { File(root, it).readText() }
        val skill = loaded.single { it.id == "builtin-writing-tt-sp-roleplay" }
        val tampered = runCatching { BuiltinSkills.readResource({ "篡改内容" }, skill, "example.md") }.exceptionOrNull()
        assertTrue(tampered?.message.orEmpty().contains("SHA-256"))
        val foreign = SkillRecord(id = "custom", files = mapOf("example.md" to skill.files.getValue("example.md")))
        assertTrue(runCatching { BuiltinSkills.readResource({ File(root, it).readText() }, foreign, "example.md") }.isFailure)
    }

    @Test fun adultFlagMustMatchAdultCategory() {
        val files = fixtureFiles().toMutableMap()
        files["writing-skills/catalog.json"] = files.getValue("writing-skills/catalog.json").replaceFirst("\"category\":\"小说写作\"", "\"category\":\"成人向\"")
        val error = runCatching { BuiltinSkills.load(files::getValue) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("成人向"))
    }

    @Test fun adultSkillCannotBeSelectedOrSentUntilConfirmed() {
        val adult = SkillRecord(id = "builtin-writing-adult", name = "成人", content = "成人正文", enabled = true, adult = true)
        val chat = Chat(id = "c", messages = listOf(Message(role = "user", content = "开始")))
        val data = AppData(chats = listOf(chat), skills = listOf(adult))
        assertTrue(runCatching { BuiltinSkills.selectForChat(data, "c", adult.id, true) }.exceptionOrNull()?.message.orEmpty().contains("18"))

        val forced = data.copy(chats = listOf(chat.copy(skillIds = listOf(adult.id))))
        assertFalse(ContextEngine.build(forced, forced.chats.single()).messagesJson.contains("成人正文"))

        val confirmed = data.copy(settings = data.settings.copy(adultSkillsConfirmed = true))
        val selected = BuiltinSkills.selectForChat(confirmed, "c", adult.id, true)
        val messages = ContextEngine.build(selected, selected.chats.single()).messagesJson
        assertTrue(messages.contains("成人正文"))
        assertTrue(messages.contains("成人向内容边界"))
        assertTrue(messages.contains("未成年"))
    }

    private fun fixtureFiles(): Map<String, String> {
        val source = linkedMapOf(
            "fx-fantasy" to "幻想写作的原文内容。",
            "fx-mystery" to "推理写作的原文内容。",
            "fx-dialogue" to "对白写作的原文内容。",
            "fx-scene" to "场景写作的原文内容。",
            "fx-romance" to "言情关系写作的原文内容。",
            "fy-story-structure" to "故事结构的原文内容。",
            "fy-fiction-scene" to "小说场景的原文内容。"
        )
        val names = mapOf(
            "fx-fantasy" to "幻想写作", "fx-mystery" to "推理写作", "fx-dialogue" to "对白写作",
            "fx-scene" to "场景写作", "fx-romance" to "言情与关系", "fy-story-structure" to "故事结构",
            "fy-fiction-scene" to "小说场景"
        )
        val categories = source.keys.associateWith { if (it == "fx-romance") "言情与关系" else "小说写作" }
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
            val repository = if (id.startsWith("fx-")) "wordflowlab/novel-writer-skills" else "SkillMedev/skills"
            val path = if (id.startsWith("fx-")) "templates/skills/genre-knowledge/$id/SKILL.md" else "skills/$id/SKILL.md"
            val licensePath = if (id.startsWith("fx-")) "licenses/wordflowlab-LICENSE" else "licenses/skillmedev-LICENSE"
            """{"id":"$id","name":"${names.getValue(id)}","category":"${categories.getValue(id)}","description":"固定原文测试","sourceUrl":"https://github.com/$repository/blob/$COMMIT/$path","commit":"$COMMIT","license":"MIT","licensePath":"$licensePath","sha256":"${sha256(raw)}","gitBlob":"${"a".repeat(40)}"}"""
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
        private const val TANCE_COMMIT = "ecf552f6930e769d8bbf17818ad3d5a864a7a70b"
    }
}
