package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.Memory
import app.pockettavern.domain.SkillRecord
import app.pockettavern.domain.Summary
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Attachment
import app.pockettavern.domain.ModelInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    @Test fun emptyAssistantAttemptsDoNotPolluteNextRequestButPartialRepliesAndToolsRemain() {
        val toolJson = "[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]"
        val messages = listOf(
            Message(content = "first"),
            Message(role = "assistant"),
            Message(role = "assistant", status = "error"),
            Message(role = "assistant", status = "stopped", reasoning = "unfinished thought"),
            Message(role = "assistant", status = "error", content = "partial answer"),
            Message(role = "assistant", toolCallsJson = toolJson),
            Message(role = "tool", content = "result", toolCallId = "call-1"),
            Message(content = "next")
        )
        val built = JSONArray(ContextEngine.build(AppData(), Chat(messages = messages)).messagesJson)
        val assistants = (0 until built.length()).map(built::getJSONObject).filter { it.optString("role") == "assistant" }
        assertEquals(2, assistants.size)
        assertTrue(assistants.any { it.optString("content") == "partial answer" })
        assertTrue(assistants.any { it.optJSONArray("tool_calls")?.length() == 1 })
        assertEquals(8, messages.size)
    }

    @Test fun everyEarlierAssistantPassageIsSentBackExactlyOnce() {
        val passage = "雪从庄园的屋檐上滑落，顾砚舟把温度计的读数抄进本子。".repeat(6)
        val toolJson = "[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"skill_read\",\"arguments\":\"{}\"}}]"
        val messages = listOf(
            Message(content = "开始"),
            Message(role = "assistant", content = passage, reasoning = "先写雪"),
            Message(content = "继续"),
            Message(role = "assistant", content = "第二段开头", toolCallsJson = toolJson),
            Message(role = "tool", content = "引用文件原文", toolCallId = "call-1"),
            Message(role = "assistant", content = "第二段写完"),
            Message(content = "再继续")
        )
        val json = ContextEngine.build(AppData(), Chat(messages = messages)).messagesJson
        assertEquals(1, Regex(Regex.escape(passage)).findAll(json).count())
        assertEquals(1, Regex("第二段开头").findAll(json).count())
        assertEquals(1, Regex("第二段写完").findAll(json).count())
        assertEquals(1, Regex("先写雪").findAll(json).count())
    }

    @Test fun storyLoreUsesRecentNpcProseWithoutLeakingIntoOtherCharacters() {
        val character = app.pockettavern.domain.Character(id = "story", name = "story")
        val entry = app.pockettavern.domain.WorldEntry(
            name = "管家", content = "NPC_CANON", keywords = listOf("顾砚舟"),
            characterId = character.id, searchRange = 1000
        )
        val data = AppData(characters = listOf(character), worldEntries = listOf(
            entry,
            entry.copy(id = "legacy", content = "LEGACY_ONLY_LATEST_USER", searchRange = 0),
            entry.copy(id = "foreign", content = "OTHER_STORY_SECRET", characterId = "other", always = true),
            entry.copy(id = "disabled", content = "DISABLED_RULE", enabled = false)
        ))
        val chat = Chat(characterId = character.id, messages = listOf(
            Message(role = "assistant", content = "顾砚舟在门边等候。"),
            Message(role = "user", content = "问他今天的安排。")
        ))
        val built = ContextEngine.build(data, chat).messagesJson
        assertTrue(built.contains("NPC_CANON"))
        assertFalse(built.contains("LEGACY_ONLY_LATEST_USER"))
        assertFalse(built.contains("OTHER_STORY_SECRET"))
        assertFalse(built.contains("DISABLED_RULE"))
        assertFalse(ContextEngine.build(data, chat.copy(characterId = "unrelated")).messagesJson.contains("NPC_CANON"))
    }

    @Test fun storyLoreScanIsBoundedAndDoesNotActivateFromToolResults() {
        val entry = app.pockettavern.domain.WorldEntry(content = "BOUNDED_LORE", keywords = listOf("顾砚舟"), searchRange = 20)
        val data = AppData(worldEntries = listOf(entry))
        val chat = Chat(messages = listOf(
            Message(role = "assistant", content = "顾砚舟"),
            Message(role = "user", content = "庭院".repeat(30)),
            Message(role = "tool", content = "顾砚舟")
        ))
        assertFalse(ContextEngine.build(data, chat).messagesJson.contains("BOUNDED_LORE"))
        val spoken = chat.copy(messages = chat.messages + Message(role = "user", content = "叫顾砚舟来。"))
        assertTrue(ContextEngine.build(data, spoken).messagesJson.contains("BOUNDED_LORE"))
    }

    @Test fun memorySearchIsolatedToChatUnlessGlobal() = runBlocking {
        val first = Chat(id = "first", skillIds = listOf("memory"), messages = listOf(Message(role = "user", content = "hi")))
        val second = Chat(id = "second")
        val skill = SkillRecord(id = "memory", name = "memory", permissions = listOf("memory"))
        val data = AppData(skills = listOf(skill), memories = listOf(
            Memory(text = "first-only", scope = "chat", targetId = first.id),
            Memory(text = "second-only", scope = "chat", targetId = second.id),
            Memory(text = "global-fact", scope = "global")
        ))
        val runtime = ToolRuntime(createTempDir(prefix = "pocket-engine"))
        val result = runtime.execute("memory_search", "{\"query\":\"only\"}", listOf(skill), data, first)
        assertTrue(result.content.contains("first-only"))
        assertFalse(result.content.contains("second-only"))
    }

    @Test fun summaryMustMatchExactSourceAndCompressionKeepsToolGroup() {
        val user1 = Message(id = "u1", role = "user", content = "one")
        val call = Message(id = "a1", role = "assistant", toolCallsJson = "[{\"id\":\"call-1\"}]")
        val tool = Message(id = "t1", role = "tool", toolCallId = "call-1", content = "result")
        val user2 = Message(id = "u2", role = "user", content = "two")
        val assistant2 = Message(id = "a2", role = "assistant", content = "done")
        val messages = listOf(user1, call, tool, user2, assistant2)
        val chat = Chat(id = "c", messages = messages, summaries = listOf(Summary(id = "s", text = "old", throughMessageId = "t1", sourceHash = ContextEngine.sourceHash(messages.take(3)))), activeSummaryId = "s")
        assertTrue(ContextEngine.build(AppData(), chat).messagesJson.contains("old"))
        val edited = chat.copy(messages = messages.map { if (it.id == "u1") it.copy(content = "edited") else it })
        assertFalse(ContextEngine.build(AppData(), edited).messagesJson.contains("old"))
        val slice = ContextEngine.compressionSlice(AppData(settings = AppSettings(keepRecentTurns = 1)), chat.copy(summaries = emptyList()))
        assertEquals(listOf("u1", "a1", "t1"), slice!!.messages.map { it.id })
    }

    @Test fun blankScopedMemoryDoesNotCrossChatsAndOnlyActiveSummaryIsUsed() {
        val source = Message(id = "m1", role = "user", content = "hello")
        val summary = Summary(id = "old", text = "old summary", throughMessageId = source.id, sourceHash = ContextEngine.sourceHash(listOf(source)))
        val data = AppData(memories = listOf(Memory(text = "scoped", scope = "chat", targetId = "")))
        val chat = Chat(id = "chat", messages = listOf(source), summaries = listOf(summary), activeSummaryId = "")
        val json = ContextEngine.build(data, chat).messagesJson
        assertFalse(json.contains("scoped"))
        assertFalse(json.contains("old summary"))
    }

    @Test fun contextOverrideAndAttachmentBudgetAreExplicit() {
        val model = ModelInfo(id = "m", contextTokens = 4096, supportsVision = true)
        val provider = app.pockettavern.domain.ProviderConfig(id = "p", models = listOf(model))
        val message = Message(role = "user", content = "看图", attachments = listOf(Attachment(mime = "image/png", dataUrl = "data:image/png;base64," + "a".repeat(10000))))
        val chat = Chat(providerId = "p", modelId = "m", messages = listOf(message))
        val build = ContextEngine.build(AppData(providers = listOf(provider), settings = AppSettings(contextOverride = 1024)), chat)
        assertEquals(512, build.budget)
        assertTrue(build.preview.contains("messages JSON"))
        assertTrue(build.messagesJson.contains("image_url"))
        assertTrue(build.messagesJson.contains("data:image/png;base64,"))
        assertFalse(build.preview.contains("data:image/png;base64,"))
        assertTrue(build.preview.contains("二进制附件已省略"))
        assertTrue(build.preview.contains("bytes="))
    }

    @Test fun veniceContextBudgetReservesReasoningOutsideVisibleOutputTarget() {
        val model = ModelInfo(id = "reasoning", contextTokens = 4096)
        val provider = app.pockettavern.domain.ProviderConfig(id = "venice", kind = "venice", models = listOf(model))
        val preset = app.pockettavern.domain.Preset(maxOutput = 512, reasoningEffort = "low", reasoningBudget = 256, generationBudgetEnabled = true)
        val chat = Chat(providerId = "venice", modelId = "reasoning", presetId = preset.id, messages = listOf(Message(content = "继续")))
        val data = AppData(providers = listOf(provider), presets = listOf(preset), settings = AppSettings(contextOverride = 4096))
        val withReserve = ContextEngine.build(data, chat)
        val withoutReserve = ContextEngine.build(data.copy(providers = listOf(provider.copy(kind = "openai"))), chat)
        assertEquals(2816, withReserve.budget)
        assertEquals(3072, withoutReserve.budget)
    }

    @Test fun plainTextAttachmentDataIsNotUrlDecodedAndDeletedPresetUsesFirstPreset() {
        val preset = app.pockettavern.domain.Preset(id = "kept", systemPrompt = "FIRST PRESET")
        val message = Message(role = "user", content = "x", attachments = listOf(
            Attachment(name = "plain.txt", mime = "text/plain", dataUrl = "plain,a+b"),
            Attachment(name = "encoded.txt", mime = "text/plain", dataUrl = "data:text/plain,a%2Bb%2Cc")
        ))
        val build = ContextEngine.build(AppData(presets = listOf(preset)), Chat(presetId = "deleted", messages = listOf(message)))
        assertTrue(build.messagesJson.contains("plain,a+b"))
        assertTrue(build.messagesJson.contains("a+b,c"))
        assertTrue(build.messagesJson.contains("FIRST PRESET"))
    }

    @Test fun summarySourceRetainsTextAndToolArgumentsWithoutBinaryPayloads() {
        val message = Message(
            role = "assistant",
            toolCallsJson = "[{\"id\":\"call-1\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}}]",
            attachments = listOf(
                Attachment(name = "note.txt", mime = "text/plain", dataUrl = "data:text/plain,keep%2Cthis"),
                Attachment(name = "photo.png", mime = "image/png", dataUrl = "data:image/png;base64,IMAGE_SECRET"),
                Attachment(name = "voice.wav", mime = "audio/wav", dataUrl = "data:audio/wav;base64,AUDIO_SECRET")
            )
        )
        val source = ContextEngine.summarySource(listOf(message)).single()
        assertTrue(source.contains("keep,this"))
        assertTrue(source.contains("lookup"))
        assertTrue(source.contains("q"))
        assertTrue(source.contains("photo.png"))
        assertTrue(source.contains("voice.wav"))
        assertFalse(source.contains("IMAGE_SECRET"))
        assertFalse(source.contains("AUDIO_SECRET"))
    }

    @Test fun veniceModelSpecAudioCapabilityKeepsAudioAttachment() {
        val model = ModelInfo(
            id = "audio-chat",
            rawJson = "{\"model_spec\":{\"capabilities\":{\"supportsAudioInput\":true}}}"
        )
        val provider = app.pockettavern.domain.ProviderConfig(id = "venice", models = listOf(model))
        val message = Message(
            role = "user",
            attachments = listOf(Attachment(name = "voice.wav", mime = "audio/wav", dataUrl = "data:audio/wav;base64,AAAA"))
        )
        val build = ContextEngine.build(
            AppData(providers = listOf(provider)),
            Chat(providerId = "venice", modelId = "audio-chat", messages = listOf(message))
        )
        assertTrue(build.messagesJson.contains("input_audio"))
        assertFalse(build.messagesJson.contains("不支持音频"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zipTraversalIsRejected() {
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("../SKILL.md")); zip.write("---\nname: bad\ndescription: bad\n---\n".toByteArray()); zip.closeEntry()
        } }.toByteArray()
        SkillImporter.importFile("bad.zip", bytes)
    }

    @Test fun importedSkillIsDisabledAndDoesNotGrantRequestedPermission() {
        val raw = "---\nname: sample\ndescription: |\n  multiline skill\npermissions:\n  - network\n---\nbody"
        val skill = SkillImporter.importFile("SKILL.md", raw.toByteArray()).single()
        assertFalse(skill.enabled)
        assertTrue(skill.permissions.isEmpty())
        assertTrue(skill.warnings.any { it.contains("network") })
    }

    @Test fun definitionsAndPathPermissionAreBounded() = runBlocking {
        val definitions = JSONArray(ToolRuntime(java.io.File(".")).definitions(listOf("files.read")))
        val names = (0 until definitions.length()).map { definitions.getJSONObject(it).getJSONObject("function").getString("name") }
        assertTrue("workspace_read" in names)
        assertFalse("workspace_write" in names)
        val skill = SkillRecord(id = "read", permissions = listOf("files.read"))
        val runtime = ToolRuntime(createTempDir(prefix = "pocket-engine"))
        val denied = runtime.execute("workspace_read", "{\"path\":\"../outside.txt\"}", listOf(skill), AppData(skills = listOf(skill)), Chat(id = "c", skillIds = listOf(skill.id)))
        assertTrue(denied.content.contains("失败") || denied.content.contains("超出"))
    }

    @Test fun emptyChatSelectionCannotWriteEvenWhenSkillIsEnabled() = runBlocking {
        val workspace = createTempDir(prefix = "pocket-engine")
        val skill = SkillRecord(id = "writer", enabled = true, permissions = listOf("files.write"))
        val target = File(workspace, "should-not-exist.txt")
        val result = ToolRuntime(workspace).execute(
            "workspace_write", "{\"path\":\"should-not-exist.txt\",\"content\":\"secret\"}",
            listOf(skill), AppData(skills = listOf(skill)), Chat(id = "c", skillIds = emptyList())
        )
        assertTrue(result.content.contains("权限被拒绝"))
        assertFalse(target.exists())
    }

    @Test fun providerApiRejectsDestinationSwitchBeforeCredentialLookup() = runBlocking {
        var lookedUp = false
        val provider = app.pockettavern.domain.ProviderConfig(id = "api", kind = "openai-compatible", baseUrl = "https://api.example.test/v1")
        val skill = SkillRecord(id = "net", enabled = true, permissions = listOf("network"))
        val runtime = ToolRuntime(createTempDir(prefix = "pocket-engine")) {
            lookedUp = true
            "super-secret-key"
        }
        val result = runtime.execute(
            "http_request", "{\"providerId\":\"api\",\"path\":\"../evil\"}", listOf(skill),
            AppData(providers = listOf(provider)), Chat(id = "c", skillIds = listOf(skill.id))
        )
        assertFalse(lookedUp)
        assertFalse(result.content.contains("super-secret-key"))
        val scopeResult = runtime.execute(
            "http_request", "{\"providerId\":\"api\",\"path\":\"chat/completions\"}", listOf(skill),
            AppData(providers = listOf(provider)), Chat(id = "c", providerId = "other", skillIds = listOf(skill.id))
        )
        assertTrue(scopeResult.content.contains("当前会话"))
        assertFalse(lookedUp)
    }

    @Test fun modelCannotPromoteMemoryToGlobal() = runBlocking {
        val skill = SkillRecord(id = "memory", enabled = true, permissions = listOf("memory"))
        val result = ToolRuntime(createTempDir(prefix = "pocket-engine")).execute(
            "memory_save", "{\"text\":\"fact\",\"scope\":\"global\"}", listOf(skill), AppData(), Chat(id = "chat", skillIds = listOf(skill.id))
        )
        assertEquals("chat", result.memories!!.single().scope)
        assertEquals("chat", result.memories.single().targetId)
        assertTrue(result.content.contains("手动提升"))
    }
}
