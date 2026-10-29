package app.pockettavern.engine

import app.pockettavern.data.BuiltinSkills
import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.SkillRecord
import app.pockettavern.domain.WorldEntry
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.66: what selected Skills cost per request and where they sit in the prompt. */
class SkillInjectionTest {
    private val root = File("src/main/assets")
    private val bundled = BuiltinSkills.load { File(root, it).readText() }

    private fun chatData(skillIds: List<String>, tools: Boolean, skills: List<SkillRecord> = bundled, messages: List<Message> = listOf(Message(role = "user", content = "开始")),
                         world: List<WorldEntry> = emptyList()): Pair<AppData, Chat> {
        val provider = ProviderConfig(id = "p", models = listOf(ModelInfo(id = "m", type = "text", contextTokens = 200_000, supportsTools = tools)))
        val chat = Chat(id = "c", providerId = "p", modelId = "m", presetId = "preset", messages = messages)
        var data = AppData(providers = listOf(provider), chats = listOf(chat), skills = skills, worldEntries = world,
            presets = listOf(Preset(id = "preset", systemPrompt = "【预设】按设定写作。")), settings = AppSettings(adultSkillsConfirmed = true))
        skillIds.forEach { data = BuiltinSkills.selectForChat(data, "c", it, true) }
        return data to data.chats.single()
    }

    private fun contents(data: AppData, chat: Chat): List<Pair<String, String>> {
        val messages = JSONArray(ContextEngine.build(data, chat).messagesJson)
        return (0 until messages.length()).map { messages.getJSONObject(it) }.map { it.getString("role") to it.optString("content") }
    }

    @Test fun largeReferenceTreeIsSummarisedPerFolderAndSmallFoldersStayListed() {
        val dsh = bundled.single { it.id == "builtin-writing-at-adult-tension-original" }
        val (data, chat) = chatData(listOf(dsh.id), tools = true)
        val prompt = contents(data, chat).single { it.second.startsWith("【Skill: ${dsh.name}】") }.second
        val paths = ToolRuntime.referencePaths(dsh)
        assertEquals(221, paths.size)
        // Only the reference section: the Skill body may itself mention content paths.
        val list = prompt.substringAfter("【Skill 引用文件】").lines()
        val contentFiles = paths.filter { it.startsWith("content/") }
        assertTrue(list.contains("- content/（${contentFiles.size} 个文件；path 填 \"content\" 可列出）"))
        contentFiles.forEach { assertFalse(it, list.contains("- $it")) }
        paths.filter { it.startsWith("references/") }.forEach { assertTrue(it, list.contains("- $it")) }
        // The 221-path list used to cost about as much as a whole extra Skill.
        assertTrue(ContextEngine.skillPromptTokens(dsh, toolsSupported = true) < ContextEngine.estimateTokens("【Skill: ${dsh.name}】\n${dsh.content}") + 400)
    }

    @Test fun ordinarySkillsStillListEveryReferencePath() {
        val sepia = bundled.single { it.id == "builtin-writing-se-sepia" }
        val outline = ContextEngine.referenceOutline(ToolRuntime.referencePaths(sepia))
        ToolRuntime.referencePaths(sepia).forEach { assertTrue(it, outline.contains("- $it")) }
    }

    @Test fun withoutToolsNoReferenceListIsSentAndTheChatModeNoteSaysSo() {
        val sepia = bundled.single { it.id == "builtin-writing-se-sepia" }
        val (data, chat) = chatData(listOf(sepia.id), tools = false)
        val system = contents(data, chat).filter { it.first == "system" }.joinToString("\n") { it.second }
        assertFalse(system.contains("【Skill 引用文件】"))
        assertTrue(system.contains("当前模型不能读取引用文件"))
        assertTrue(ContextEngine.skillPromptTokens(sepia, toolsSupported = false) < ContextEngine.skillPromptTokens(sepia, toolsSupported = true))
    }

    @Test fun chatModeNoteIsSentOnceAndOnlyWhenSkillsAreSelected() {
        val (none, noneChat) = chatData(emptyList(), tools = true)
        assertTrue(contents(none, noneChat).none { it.second.startsWith("【Skill 使用方式】") })
        val (two, twoChat) = chatData(listOf("builtin-writing-tm-dialogue", "builtin-writing-tm-continuity"), tools = true)
        val notes = contents(two, twoChat).filter { it.second.startsWith("【Skill 使用方式】") }
        assertEquals(1, notes.size)
        assertTrue(notes.single().second.contains("只采用其中的写作规范"))
    }

    @Test fun stablePromptComesBeforeTurnVaryingContext() {
        val world = WorldEntry(name = "庄园", content = "栖云庄园位于青浦。", always = true)
        val (data, chat) = chatData(listOf("builtin-writing-tm-dialogue"), tools = true, world = listOf(world))
        val texts = contents(data, chat).map { it.second }
        fun at(prefix: String) = texts.indexOfFirst { it.startsWith(prefix) }.also { assertTrue(prefix, it >= 0) }
        val order = listOf(at("【预设】"), at("【Skill 使用方式】"), at("【Skill: "), at("【本轮 Skills 清单"), at("【避免重复回复】"), at("【世界书资料】"), texts.indexOf("开始"))
        assertEquals(order.sorted(), order)
    }

    @Test fun rereadingTheSameSkillFileKeepsOnlyTheNewestCopy() {
        fun read(id: String, path: String) = JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", "skill_read").put("arguments", JSONObject().put("skillId", "builtin-writing-x").put("path", path).toString()))).toString()
        val history = listOf(
            Message(role = "user", content = "第一轮"),
            Message(role = "assistant", content = "", toolCallsJson = read("c1", "references/a.md")),
            Message(role = "tool", toolCallId = "c1", content = "旧的A规则"),
            Message(role = "assistant", content = "第一轮回复"),
            Message(role = "user", content = "第二轮"),
            Message(role = "assistant", content = "", toolCallsJson = read("c2", "./references/a.md")),
            Message(role = "tool", toolCallId = "c2", content = "新的A规则"),
            Message(role = "assistant", content = "", toolCallsJson = read("c3", "references/b.md")),
            Message(role = "tool", toolCallId = "c3", content = "B规则"),
            Message(role = "assistant", content = "第二轮回复"),
            Message(role = "user", content = "第三轮")
        )
        val (data, chat) = chatData(emptyList(), tools = true, skills = emptyList(), messages = history)
        val json = ContextEngine.build(data, chat).messagesJson
        assertFalse(json.contains("旧的A规则"))
        assertFalse(json.contains("\"c1\""))
        assertTrue(json.contains("新的A规则"))
        assertTrue(json.contains("B规则"))
        assertTrue(json.contains("第一轮回复"))
    }

    @Test fun skillReadListsAFolderWhenGivenItsPath() {
        val skill = SkillRecord(id = "builtin-writing-tree", name = "树", content = "正文", enabled = true,
            files = mapOf("SKILL.md" to "正文", "content/a.md" to "A", "content/sub/b.md" to "B", "references/c.md" to "C"))
        val (data, chat) = chatData(listOf(skill.id), tools = true, skills = listOf(skill))
        val runtime = ToolRuntime(Files.createTempDirectory("skill-folder").toFile())
        val listing = kotlinx.coroutines.runBlocking { runtime.execute("skill_read", "{\"skillId\":\"${skill.id}\",\"path\":\"content/\"}", data.skills, data, chat) }.content
        assertTrue(listing.startsWith("目录 content/ 下共 2 个文件"))
        assertTrue(listing.contains("- content/a.md") && listing.contains("- content/sub/b.md"))
        assertFalse(listing.contains("references/c.md"))
        val file = kotlinx.coroutines.runBlocking { runtime.execute("skill_read", "{\"skillId\":\"${skill.id}\",\"path\":\"content/a.md\"}", data.skills, data, chat) }.content
        assertEquals("A", file)
    }
}
