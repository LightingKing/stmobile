package app.pockettavern.engine

import app.pockettavern.data.BuiltinSkills
import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.SkillRecord
import app.pockettavern.provider.ProviderClient
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies how a selected Skill reaches the model and how its reference files are read back. */
class SkillInvocationTest {
    private val referenceSkill = SkillRecord(
        id = "builtin-writing-ref",
        name = "参考型",
        description = "带引用文件的内置 Skill",
        content = "正文：需要时读取 references/guide.md",
        files = mapOf("SKILL.md" to "---\nname: ref\ndescription: d\n---\n正文", "LICENSE" to "MIT", "references/guide.md" to "指南全文"),
        enabled = true,
        source = "GitHub精选 · 小说写作"
    )
    private val otherSkill = SkillRecord(
        id = "builtin-writing-other",
        name = "未选",
        description = "不应发送",
        content = "未选 Skill 的正文",
        files = mapOf("SKILL.md" to "x", "references/secret.md" to "未选引用"),
        enabled = true
    )

    private fun data(selected: List<String>, tools: Boolean = true): Pair<AppData, Chat> {
        val provider = ProviderConfig(id = "p", models = listOf(ModelInfo(id = "m", type = "text", contextTokens = 200_000, supportsTools = tools)))
        val chat = Chat(id = "c", providerId = "p", modelId = "m", skillIds = selected, messages = listOf(Message(role = "user", content = "开始")))
        return AppData(providers = listOf(provider), chats = listOf(chat), skills = listOf(referenceSkill, otherSkill)) to chat
    }

    private fun toolNames(preview: String): List<String> {
        val tools = JSONArray(preview.substringAfter("工具定义 JSON："))
        return (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
    }

    private fun runtime() = ToolRuntime(Files.createTempDirectory("skill-invocation").toFile())

    @Test fun builtinSkillWithReferencesExposesSkillReadWithoutFilePermission() {
        val (data, chat) = data(listOf(referenceSkill.id))
        val names = toolNames(ContextEngine.build(data, chat).preview)
        assertEquals(listOf("skill_read", "skill_list"), names)
    }

    @Test fun systemPromptListsSkillIdAndReferencePaths() {
        val (data, chat) = data(listOf(referenceSkill.id))
        val system = JSONArray(ContextEngine.build(data, chat).messagesJson).let { messages ->
            (0 until messages.length()).map { messages.getJSONObject(it) }.filter { it.getString("role") == "system" }.joinToString("\n") { it.getString("content") }
        }
        assertTrue(system.contains("正文：需要时读取 references/guide.md"))
        assertTrue(system.contains("skillId=builtin-writing-ref"))
        assertTrue(system.contains("- references/guide.md"))
        assertFalse("LICENSE is not a model-facing reference", system.contains("- LICENSE"))
    }

    @Test fun skillReadReturnsReferenceForSelectedBuiltinWithoutFilesPermission() = runBlocking {
        val (data, chat) = data(listOf(referenceSkill.id))
        val result = runtime().execute("skill_read", "{\"skillId\":\"builtin-writing-ref\",\"path\":\"references/guide.md\"}", data.skills, data, chat)
        assertEquals("指南全文", result.content)
    }

    @Test fun skillReadAcceptsSkillNameAndDotSlashPath() = runBlocking {
        val (data, chat) = data(listOf(referenceSkill.id))
        val result = runtime().execute("skill_read", "{\"skillId\":\"参考型\",\"path\":\"./references/guide.md\"}", data.skills, data, chat)
        assertEquals("指南全文", result.content)
    }

    @Test fun migratedDuplicateBuiltinKeepsItsOriginalAssetRoot() = runBlocking {
        val migrated = referenceSkill.copy(
            id = "builtin-writing-ref-duplicate-2",
            files = referenceSkill.files + ("references/guide.md" to BuiltinSkills.ASSET_PREFIX + "a".repeat(64))
        )
        val chat = Chat(id = "c", skillIds = listOf(migrated.id))
        val data = AppData(chats = listOf(chat), skills = listOf(migrated))
        val runtime = ToolRuntime(Files.createTempDirectory("skill-migrated-asset").toFile(), { skill, path ->
            if (skill.id == referenceSkill.id && path == "references/guide.md") "迁移后仍可读取" else error("missing ${skill.id}/$path")
        })

        val result = runtime.execute(
            "skill_read",
            "{\"skillId\":\"${migrated.id}\",\"path\":\"references/guide.md\"}",
            data.skills,
            data,
            chat
        )

        assertEquals("迁移后仍可读取", result.content)
    }

    @Test fun missingReferenceListsAvailableFiles() = runBlocking {
        val (data, chat) = data(listOf(referenceSkill.id))
        val result = runtime().execute("skill_read", "{\"skillId\":\"builtin-writing-ref\",\"path\":\"references/nope.md\"}", data.skills, data, chat)
        assertTrue(result.content, result.content.contains("references/guide.md"))
    }

    @Test fun unselectedSkillIsAbsentFromRequestAndCannotBeRead() = runBlocking {
        val (data, chat) = data(listOf(referenceSkill.id))
        val build = ContextEngine.build(data, chat)
        assertFalse(build.messagesJson.contains("未选 Skill 的正文"))
        assertFalse(build.messagesJson.contains("references/secret.md"))
        val result = runtime().execute("skill_read", "{\"skillId\":\"builtin-writing-other\",\"path\":\"references/secret.md\"}", data.skills, data, chat)
        assertFalse(result.content.contains("未选引用"))
    }

    @Test fun nothingSelectedSendsNoSkillAndOnlyReadOnlySkillList() {
        val (data, chat) = data(emptyList())
        val build = ContextEngine.build(data, chat)
        assertFalse(build.messagesJson.contains("【Skill"))
        assertEquals(listOf("skill_list"), toolNames(build.preview))
    }

    @Test fun perChatSelectionOrderControlsSkillPromptInjectionOrder() {
        val first = referenceSkill.copy(id = "first", name = "基础规则", content = "FIRST_SKILL_BODY")
        val second = otherSkill.copy(id = "second", name = "具体风格", content = "SECOND_SKILL_BODY")
        val provider = ProviderConfig(id = "p", models = listOf(ModelInfo(id = "m", type = "text", contextTokens = 200_000)))
        val chat = Chat(providerId = "p", modelId = "m", skillIds = listOf(second.id, first.id), messages = listOf(Message(role = "user", content = "继续")))
        val data = AppData(providers = listOf(provider), chats = listOf(chat), skills = listOf(first, second))

        val active = ToolRuntime.activeSkills(data.skills, data.settings, chat)
        val request = ContextEngine.build(data, chat).messagesJson

        assertEquals(listOf(second.id, first.id), active.map { it.id })
        assertTrue(request.indexOf("SECOND_SKILL_BODY") < request.indexOf("FIRST_SKILL_BODY"))
    }

    @Test fun importerAcceptsUtf8Bom() {
        val bytes = ("\uFEFF---\r\nname: bom-skill\r\ndescription: 带 BOM 的上游文件\r\n---\r\n正文").toByteArray(Charsets.UTF_8)
        val skill = SkillImporter.importFile("SKILL.md", bytes).single()
        assertEquals("bom-skill", skill.name)
        assertEquals("正文", skill.content)
    }

    @Test fun adultSkillIsNeitherSentNorReadableUntilConfirmed() = runBlocking {
        val adult = referenceSkill.copy(id = "builtin-writing-adult", name = "成人", adult = true)
        val (base, chat) = data(listOf(adult.id))
        val data = base.copy(skills = listOf(adult))
        assertFalse(ContextEngine.build(data, chat).messagesJson.contains("【Skill"))
        assertEquals(listOf("skill_list"), toolNames(ContextEngine.build(data, chat).preview))
        val denied = runtime().execute("skill_read", "{\"skillId\":\"${adult.id}\",\"path\":\"references/guide.md\"}", data.skills, data, chat)
        assertFalse(denied.content.contains("指南全文"))

        val confirmed = data.copy(settings = data.settings.copy(adultSkillsConfirmed = true))
        assertEquals(listOf("skill_read", "skill_list"), toolNames(ContextEngine.build(confirmed, chat).preview))
        assertEquals("指南全文", runtime().execute("skill_read", "{\"skillId\":\"${adult.id}\",\"path\":\"references/guide.md\"}", confirmed.skills, confirmed, chat).content)
    }

    @Test fun shippedSkillReferenceIsReadThroughFixtureToolLoop() = runBlocking {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            val root = File("src/main/assets")
            val reader = { relative: String -> File(root, relative).readText() }
            val catalog = BuiltinSkills.load(reader)
            val selected = catalog.single { it.id == "builtin-writing-tt-sp-roleplay" }.copy(enabled = true)
            val unselected = catalog.single { it.id == "builtin-writing-fv-chat-doctor" }.copy(enabled = true)
            val adultUnconfirmed = catalog.single { it.id == "builtin-writing-hn-scene-writing" }.copy(enabled = true)
            val provider = ProviderConfig(id = "p", baseUrl = server.url("/api/v1").newBuilder().host("127.0.0.1").build().toString(),
                models = listOf(ModelInfo(id = "m", contextTokens = 200_000, supportsTools = true)))
            var chat = Chat(id = "c", providerId = "p", modelId = "m", skillIds = listOf(selected.id, adultUnconfirmed.id),
                messages = listOf(Message(role = "user", content = "照 Skill 的示例开始一段扮演")))
            var data = AppData(providers = listOf(provider), chats = listOf(chat), skills = listOf(selected, unselected, adultUnconfirmed))

            val arguments = JSONObject().put("skillId", selected.id).put("path", "example.md").toString()
            val call = JSONObject().put("index", 0).put("id", "call_skill").put("type", "function")
                .put("function", JSONObject().put("name", "skill_read").put("arguments", arguments))
            val toolChunk = JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("delta", JSONObject().put("tool_calls", JSONArray().put(call))).put("finish_reason", "tool_calls")))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $toolChunk\n\ndata: [DONE]\n\n"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"按示例开始。\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))

            val client = ProviderClient(allowInsecureHttpForTests = true)
            val runtime = ToolRuntime(Files.createTempDirectory("skill-loop").toFile(), { skill, path -> BuiltinSkills.readResource(reader, skill, path) })
            var executed = 0
            for (step in 0 until 4) {
                val built = ContextEngine.build(data, chat)
                assertTrue(built.estimatedTokens <= built.budget)
                val result = client.chat(provider, "fixture-key", chat.modelId, built.messagesJson, Preset(), built.toolDefinitions) { }
                chat = chat.copy(messages = chat.messages + Message(role = "assistant", content = result.content, toolCallsJson = result.toolCallsJson))
                val calls = JSONArray(result.toolCallsJson)
                if (calls.length() == 0) break
                for (index in 0 until calls.length()) {
                    val function = calls.getJSONObject(index).getJSONObject("function")
                    val toolResult = runtime.execute(function.getString("name"), function.optString("arguments", "{}"), data.skills, data, chat)
                    executed++
                    chat = chat.copy(messages = chat.messages + Message(role = "tool", content = toolResult.content, toolCallId = calls.getJSONObject(index).getString("id")))
                }
                data = data.copy(chats = listOf(chat))
            }

            assertEquals(1, executed)
            assertEquals("按示例开始。", chat.messages.last().content)
            val first = server.takeRequest().body.readUtf8()
            val second = server.takeRequest().body.readUtf8()
            val firstJson = JSONObject(first)
            val tools = firstJson.getJSONArray("tools")
            assertEquals("skill_read", tools.getJSONObject(0).getJSONObject("function").getString("name"))
            assertTrue(first.contains("skillId=${selected.id}"))
            assertTrue(first.contains("- example.md"))
            assertFalse("deselected Skill leaked", first.contains(unselected.id) || first.contains("【Skill: ${unselected.name}】"))
            assertFalse("unconfirmed adult Skill leaked", first.contains("【Skill: ${adultUnconfirmed.name}】") || first.contains("成人向内容边界"))
            val expected = reader("writing-skills/tt-sp-roleplay/example.md")
            val messages = JSONObject(second).getJSONArray("messages")
            val toolMessage = (0 until messages.length()).map { messages.getJSONObject(it) }.single { it.optString("role") == "tool" }
            assertEquals("call_skill", toolMessage.getString("tool_call_id"))
            assertEquals(expected, toolMessage.getString("content"))
            assertFalse(second.contains(unselected.id))
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun modelWithoutToolsGetsNoReadToolButIsToldReferencesAreUnavailable() {
        val (data, chat) = data(listOf(referenceSkill.id), tools = false)
        val build = ContextEngine.build(data, chat)
        assertTrue(toolNames(build.preview).isEmpty())
        val messages = JSONArray(build.messagesJson)
        val system = (0 until messages.length()).map { messages.getJSONObject(it) }.filter { it.getString("role") == "system" }.joinToString("\n") { it.optString("content") }
        assertTrue(system.contains("正文：需要时读取 references/guide.md"))
        assertTrue(system.contains("不支持工具调用"))
        assertFalse(JSONObject().put("s", system).toString().contains("指南全文"))
    }
}
