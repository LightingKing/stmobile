package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.GitHubStars
import app.pockettavern.domain.Message
import app.pockettavern.domain.SkillRecord
import app.pockettavern.engine.ContextEngine
import app.pockettavern.ui.skillGuide
import app.pockettavern.ui.starsCardLine
import app.pockettavern.ui.starsDetailLine
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.17: every bundled upstream Skill file is the pinned commit's bytes, unedited. */
class VerbatimSkillsTest {
    private val root = File("src/main/assets")
    private val read: (String) -> String = { File(root, it).readText() }
    private val catalog = JSONObject(read("writing-skills/catalog.json"))
    private val entries = (0 until catalog.getJSONArray("skills").length()).map { catalog.getJSONArray("skills").getJSONObject(it) }
    private val loaded = BuiltinSkills.load(read)

    @Test fun everyPackagedFileHasTheGitBlobIdOfThePinnedUpstreamFile() {
        var checked = 0
        entries.forEach { entry ->
            val id = entry.getString("id")
            assertFalse(id, entry.has("omittedLines") || entry.has("upstreamSha256"))
            val dir = File(root, "writing-skills/$id")
            assertEquals(id, entry.getString("gitBlob"), gitBlob(File(dir, "SKILL.md").readBytes()))
            checked++
            val listed = mutableSetOf("SKILL.md")
            val files = entry.optJSONArray("files")
            if (files != null) for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val path = file.getString("path")
                assertFalse("$id/$path", file.has("omittedLines") || file.has("upstreamSha256"))
                assertEquals("$id/$path", file.getString("gitBlob"), gitBlob(File(dir, path).readBytes()))
                listed += path
                checked++
            }
            val onDisk = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet()
            assertEquals("no unlisted or patched copies in $id", listed, onDisk)
        }
        assertEquals(350, checked)
    }

    @Test fun storedSkillFileIsUpstreamTextAndThePromptBodyIsItsBody() {
        loaded.forEach { skill ->
            val id = skill.id.removePrefix("builtin-writing-")
            val upstream = read("writing-skills/$id/SKILL.md")
            assertEquals(id, upstream, skill.files.getValue("SKILL.md"))
            val normalized = upstream.removePrefix("\uFEFF").replace("\r\n", "\n")
            val wrapped = entries.single { it.getString("id") == id }.optBoolean("wrapFrontmatter", false)
            if (wrapped) assertEquals(id, normalized, skill.content) else assertTrue(id, skill.content.isNotBlank() && normalized.endsWith(skill.content))
        }
        val chat = Chat(id = "c", messages = listOf(Message(role = "user", content = "开始")))
        var data = AppData(chats = listOf(chat), skills = loaded)
        listOf("tm-deslop", "tm-dialogue", "tm-continuity", "dv-roleplay-modes").forEach { data = BuiltinSkills.selectForChat(data, "c", "builtin-writing-$it", true) }
        val messages = org.json.JSONArray(ContextEngine.build(data, data.chats.single()).messagesJson)
        val prompts = (0 until messages.length()).map { messages.getJSONObject(it).getString("content") }.filter { it.startsWith("【Skill: ") }
        assertEquals(4, prompts.size)
        data.skills.filter { it.id in data.chats.single().skillIds }.forEach { skill ->
            val prompt = prompts.single { it.startsWith("【Skill: ${skill.name}】\n") }
            assertTrue(skill.id, prompt.startsWith("【Skill: ${skill.name}】\n${skill.content}"))
            assertFalse("nothing is appended to ${skill.id}", prompt.contains("【避免重复】"))
        }
    }

    @Test fun oldSkillsStayNewSkillsAndFirstPartyArePresent() {
        val ids = loaded.map { it.id.removePrefix("builtin-writing-") }
        assertEquals(OLD_KEPT, ids.take(OLD_KEPT.size))
        assertEquals(NEW, ids.drop(OLD_KEPT.size))
        assertEquals(25, ids.size)
        (WITHDRAWN + BuiltinSkills.RETIRED.map { it.removePrefix("builtin-writing-") }).forEach { assertFalse(it, it in ids) }
        val all = BuiltinSkills.loadAll(read).map { it.id.removePrefix("builtin-writing-") }
        assertEquals(listOf("pt-character-voice", "pt-china-common-sense", "pt-web-reach"), all.drop(25))
    }

    @Test fun skillsRetiredIn0165LeaveTheStoreAndEveryChatSelection() {
        val retired = SkillRecord(id = "builtin-writing-vn-write", name = "章节创作（事件卡）",
            files = mapOf("references/a.md" to "${BuiltinSkills.ASSET_PREFIX}${"0".repeat(64)}"), enabled = true)
        val kept = loaded.single { it.id == "builtin-writing-tm-dialogue" }.copy(enabled = true)
        val imported = SkillRecord(id = "imported-vn-write", name = "我自己导入的同名 Skill")
        val chat = Chat(id = "c", skillIds = listOf(retired.id, kept.id, imported.id))
        val saved = AppData(chats = listOf(chat), skills = listOf(retired, kept, imported),
            settings = app.pockettavern.domain.AppSettings(builtinSkillsSeen = listOf(retired.id, kept.id), builtinSkillsRevision = 4))
        val merged = BuiltinSkills.merge(saved, loaded)
        assertTrue(merged.skills.none { it.id == retired.id })
        assertEquals(listOf(kept.id, imported.id), merged.chats.single().skillIds)
        assertTrue("user imports are untouched", merged.skills.any { it.id == imported.id })
        assertTrue(merged.skills.single { it.id == kept.id }.enabled)
        assertTrue(BuiltinSkills.missing(merged, loaded).none { it.id == retired.id })
        assertEquals(63, BuiltinSkills.RETIRED.size)
        assertTrue(BuiltinSkills.RETIRED.none { id -> loaded.any { it.id == id } })
    }

    @Test fun everyBundledSkillHasChineseBlurbAndDetail() {
        val cjk = Regex("[\\u4e00-\\u9fff]")
        BuiltinSkills.loadAll(read).forEach { skill ->
            assertTrue(skill.id, cjk.containsMatchIn(skill.name) || skill.name.isNotBlank())
            assertTrue(skill.id, cjk.containsMatchIn(skill.description))
            val guide = skillGuide(skill.id)
            assertTrue(skill.id, guide != null && cjk.containsMatchIn(guide.effect))
        }
        NEW.forEach { id ->
            val description = loaded.single { it.id == "builtin-writing-$id" }.description
            if (id == "anti-ai-slop-writing") assertTrue(id, description.startsWith("GitHub 原版"))
            else if (id !in setOf("ss-stop-slop", "se-sepia", "fe-fictional-erotica", "fp-fiction-prose-style", "ec-erotica-craft")) {
                assertTrue(id, description.startsWith("开了以后"))
            }
        }
    }

    @Test fun everyGitHubSkillHasARecordedStarCountAndFirstPartyHasNone() {
        assertEquals("2026-10-29", catalog.getString("starsCheckedAt"))
        val stars = BuiltinSkills.loadStars(read)
        assertEquals(loaded.map { it.id }.toSet(), stars.keys)
        loaded.forEach { skill ->
            val star = stars.getValue(skill.id)
            assertTrue(skill.id, star.stars != null && star.stars!! >= 0)
            assertTrue(skill.id, skill.sourceUrl.startsWith("https://github.com/${star.repo}/blob/"))
        }
        assertEquals(GitHubStars("Nanako0129/sepia", 3537, "2026-10-29"), stars.getValue("builtin-writing-se-sepia"))
        assertNull(stars["builtin-writing-pt-character-voice"])
        assertNull(stars["builtin-writing-pt-china-common-sense"])
        assertEquals("GitHub 仓库 ★ 260", starsCardLine(GitHubStars("ai4rpg/tavern-cards", 260, "2026-10-22")))
        assertEquals("GitHub 仓库星数：260（ai4rpg/tavern-cards，2026-10-22 记录，非实时）", starsDetailLine(GitHubStars("ai4rpg/tavern-cards", 260, "2026-10-22")))
        assertEquals("GitHub 仓库星数未知", starsCardLine(GitHubStars("o/r", null, "2026-10-22")))
        assertEquals("GitHub 仓库星数：未知（o/r，2026-10-22 记录，非实时）", starsDetailLine(GitHubStars("o/r", null, "2026-10-22")))
    }

    @Test fun restoredOriginalEntriesAndTheirReferencesLoadWithoutBodyRewriting() {
        val expected = listOf("dp-chapter-adult-original", "at-adult-tension-original", "ds-roleplay-preset-original")
        expected.forEach { id -> assertTrue(id, loaded.any { it.id == "builtin-writing-$id" }) }
        val deepNovel = loaded.single { it.id == "builtin-writing-dn-deep-novel" }
        val instruction = "assets/template/config/instruction.md"
        assertTrue(deepNovel.files.containsKey(instruction))
        assertTrue(BuiltinSkills.readResource(read, deepNovel, instruction)!!.isNotBlank())
        val dsh = loaded.single { it.id == "builtin-writing-at-adult-tension-original" }
        assertEquals(221, app.pockettavern.engine.ToolRuntime.referencePaths(dsh).size)
        assertTrue(dsh.files.keys.none { it.endsWith(".py") })
        val preset = loaded.single { it.id == "builtin-writing-ds-roleplay-preset-original" }
        assertTrue(preset.sourceUrl.endsWith("/agent.cordis.yml"))
        assertEquals(read("writing-skills/ds-roleplay-preset-original/SKILL.md"), preset.files.getValue("SKILL.md"))
        assertEquals(preset.files.getValue("SKILL.md").replace("\r\n", "\n"), preset.content)
    }

    @Test fun adultSkillsStayHiddenUntilEighteenPlusIsConfirmed() {
        val adult = loaded.filter { it.adult }
        assertEquals(listOf("at-adult-tension-original", "dn-deep-novel", "dp-chapter-adult-original", "ec-erotica-craft", "fe-fictional-erotica", "hn-continuity", "hn-scene-writing", "hn-worldbuilding", "va-velvet-ascent"), adult.map { it.id.removePrefix("builtin-writing-") }.sorted())
        val chat = Chat(id = "c", messages = listOf(Message(role = "user", content = "开始")))
        val data = AppData(chats = listOf(chat), skills = loaded)
        adult.forEach { skill ->
            assertTrue(skill.id, runCatching { BuiltinSkills.selectForChat(data, "c", skill.id, true) }.exceptionOrNull()?.message.orEmpty().contains("18"))
            val forced = data.copy(chats = listOf(chat.copy(skillIds = listOf(skill.id))), skills = data.skills.map { if (it.id == skill.id) it.copy(enabled = true) else it })
            assertFalse(skill.id, ContextEngine.build(forced, forced.chats.single()).messagesJson.contains("【Skill: ${skill.name}】"))
            val confirmed = BuiltinSkills.selectForChat(data.copy(settings = data.settings.copy(adultSkillsConfirmed = true)), "c", skill.id, true)
            val sent = ContextEngine.build(confirmed, confirmed.chats.single()).messagesJson
            assertTrue(skill.id, sent.contains("【Skill: ${skill.name}】") && sent.contains("成人向内容边界"))
        }
        loaded.filterNot { it.adult }.forEach { assertFalse(it.id, it.source.endsWith(BuiltinSkills.ADULT_CATEGORY)) }
    }

    @Test fun trimmedCopiesSavedBy0116AreWithdrawnOrReplaced() {
        val dn = loaded.single { it.id == "builtin-writing-dn-deep-novel" }
        val trimmedDn = dn.copy(files = dn.files + ("SKILL.md" to "删节版"), content = "删节版", enabled = true)
        val dp = SkillRecord(id = "builtin-writing-dp-chapter-adult", name = "成人场景感官写作", adult = true)
        val at = SkillRecord(id = "builtin-writing-at-adult-tension", name = "成人向互动叙事", adult = true)
        val chat = Chat(id = "c", skillIds = listOf(dp.id, at.id, dn.id))
        val saved = AppData(chats = listOf(chat), skills = listOf(trimmedDn, dp, at), settings = app.pockettavern.domain.AppSettings(builtinSkillsSeen = listOf(dn.id, dp.id, at.id)))
        val merged = BuiltinSkills.merge(saved, loaded)
        assertTrue(merged.skills.none { it.id == dp.id || it.id == at.id })
        assertEquals(listOf(dn.id), merged.chats.single().skillIds)
        val replaced = merged.skills.single { it.id == dn.id }
        assertEquals(dn.files.getValue("SKILL.md"), replaced.files.getValue("SKILL.md"))
        assertTrue("user's enabled switch is kept", replaced.enabled)
        assertEquals("not re-added on later launches", merged, BuiltinSkills.merge(merged, loaded))
    }

    @Test fun loaderRejectsDeletionRecordsAndMissingBlobIds() {
        val text = read("writing-skills/catalog.json")
        val withOmission: (String) -> String = { if (it.endsWith("catalog.json")) text.replaceFirst("\"gitBlob\"", "\"omittedLines\": \"1-2\", \"gitBlob\"") else read(it) }
        assertTrue(runCatching { BuiltinSkills.load(withOmission) }.exceptionOrNull()?.message.orEmpty().contains("上游原文"))
        val noBlob: (String) -> String = { if (it.endsWith("catalog.json")) text.replaceFirst(Regex("\"gitBlob\": \"[0-9a-f]{40}\""), "\"gitBlob\": \"\"") else read(it) }
        assertTrue(runCatching { BuiltinSkills.load(noBlob) }.exceptionOrNull()?.message.orEmpty().contains("gitBlob"))
    }

    @Test fun skillsTabOffersTheNewCategories() {
        assertEquals(listOf("小说写作", "言情与关系", "角色扮演", "世界观", "社会常识", "联网工具", "成人向"), BuiltinSkills.CATEGORIES)
        val counts = loaded.groupingBy { BuiltinSkills.bundledCategory(it) }.eachCount()
        assertEquals(mapOf("小说写作" to 9, "角色扮演" to 7, "成人向" to 9), counts)
    }

    private fun gitBlob(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").run {
        update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        val OLD_KEPT = listOf(
            "hn-scene-writing", "hn-worldbuilding", "hn-continuity", "va-velvet-ascent", "fv-chat-doctor", "tt-sp-roleplay",
            "jw-game-facilitator", "tm-deslop", "tm-dialogue", "tm-continuity", "dn-deep-novel"
        )
        val WITHDRAWN = listOf("dp-chapter-adult", "at-adult-tension")
        val NEW = listOf(
            "dv-roleplay-modes", "an-roleplay-sandbox", "os-story-deslop", "dp-chapter-direct", "dp-character-style",
            "dp-chapter-adult-original", "at-adult-tension-original", "ds-roleplay-preset-original", "anti-ai-slop-writing",
            "ss-stop-slop", "se-sepia", "fe-fictional-erotica", "fp-fiction-prose-style", "ec-erotica-craft"
        )
    }
}
