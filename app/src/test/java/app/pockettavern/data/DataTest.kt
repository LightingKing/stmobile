package app.pockettavern.data

import app.pockettavern.domain.*
import app.pockettavern.engine.ContextEngine
import app.pockettavern.engine.ToolRuntime
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DataTest {

    @Test fun writingServerProviderSurvivesBackupRoundTrip() {
        val provider = ProviderConfig(
            id = "writer-gateway",
            name = "本机写作服务",
            kind = "writing-server",
            baseUrl = "https://writer.example.test/v1",
            models = listOf(ModelInfo(id = "pocket-writer", name = "Pocket Writer", type = "text"))
        )
        val restored = DataCodec.decode(DataCodec.encode(AppData(providers = listOf(provider))))
        assertEquals(provider, restored.providers.single())
    }
    @Test fun lanWritingServerOverHttpSurvivesStateReload() {
        // The editor accepts http on RFC1918 literals for writing-server; the
        // persisted state must load again or every later write is blocked.
        val provider = ProviderConfig(id = "lan-writer", name = "电脑写作服务", kind = "writing-server", baseUrl = "http://192.168.1.22:8791/v1")
        val restored = DataCodec.decode(DataCodec.encode(AppData(providers = listOf(provider))))
        assertEquals(provider, restored.providers.single())
    }
    @Test fun httpProviderOutsideLanWritingServerPolicyRejected() {
        listOf(
            ProviderConfig(id = "lan-openai", name = "LAN OpenAI", kind = "openai", baseUrl = "http://192.168.1.22:8791/v1"),
            ProviderConfig(id = "public-writer", name = "Public writer", kind = "writing-server", baseUrl = "http://8.8.8.8:8791/v1"),
            ProviderConfig(id = "named-writer", name = "Named writer", kind = "writing-server", baseUrl = "http://writer.lan:8791/v1"),
        ).forEach { provider ->
            val json = DataCodec.gson.toJson(AppData(providers = listOf(provider)))
            try { DataCodec.decode(json); fail("expected ${provider.baseUrl} (${provider.kind}) to be rejected") } catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun storyImportsKeepPlayerPersonaAndRecentProseSearchRange() {
        val card = """{"data":{"name":"story","user_persona":"用户控制主角","character_book":{"entries":[{"name":"NPC","content":"facts","keys":["管家"],"searchRange":2400}]}}}"""
        val character = Exchange.character(card.toByteArray()).copy(defaultPresetId = "story-preset")
        assertEquals("用户控制主角", Exchange.character(Exchange.characterJson(character).toByteArray()).userPersona)
        assertEquals("story-preset", Exchange.character(Exchange.characterJson(character).toByteArray()).defaultPresetId)
        val world = org.json.JSONObject(card).getJSONObject("data").getJSONObject("character_book")
        val entry = Exchange.worldbook(world.toString(), character.id).single()
        assertEquals(2400, entry.searchRange)
        assertEquals(character.id, entry.characterId)
        assertEquals(listOf("管家"), entry.keywords)
    }

    @Test fun oldCachedResourceTypesBecomeSelectableWithoutRefreshing() {
        val chatModel = ModelInfo(id = "cached-chat", type = "model", contextTokens = 131072, supportsTools = true,
            rawJson = """{"id":"cached-chat","type":"model","pricing":{"input":0.4}}""")
        val nestedImage = ModelInfo(id = "cached-image", type = "model", rawJson = """{"type":"model","spec":{"type":"image"}}""")
        val config = ProviderConfig(id = "coffee", name = "冷咖啡", kind = "openai", baseUrl = "https://coffee.example/v1",
            models = listOf(chatModel, nestedImage, ModelInfo(id = "video", type = "video")))
        val source = AppData(providers = listOf(config), chats = listOf(Chat(providerId = config.id, modelId = chatModel.id)))
        val restored = DataCodec.decode(DataCodec.encode(source))
        val selectable = ModelCatalog.filter(restored.providers, type = "text", providerId = config.id)
        assertEquals(listOf(chatModel.copy(type = "text")), selectable.map { it.second })
        assertEquals(listOf("text", "image", "video"), restored.providers.single().models.map { it.type })
        assertEquals(source.chats, restored.chats)
        assertEquals(restored, DataCodec.decode(DataCodec.encode(restored)))
    }

    @Test fun backupRoundTripKeepsRolesAndSummary() {
        val chat=Chat(messages=listOf(Message(content="你好"),Message(role="assistant",content="你好。")),summaries=listOf(Summary(text="摘要")))
        val source=AppData(chats=listOf(chat),memories=listOf(Memory(text="用户偏好中文",scope="global")))
        val result=DataCodec.decode(DataCodec.encode(source))
        assertEquals(source,result)
        assertFalse(DataCodec.encode(source).contains("apiKey"))
    }

    @Test fun scenarioPanelsRoundTripAndLegacyChatsGetEmptyDefaults() {
        val chat = Chat(
            id = "scenario-chat",
            scenarioState = ScenarioState(
                location = "栖云庄园卧室", time = "2026-11-01 09:00", systemLevel = "L1",
                resources = listOf(ScenarioResource(name = "文明点", value = "100万", unit = "点")),
                quests = listOf(ScenarioQuest(title = "核对库存", status = "未开始"))
            ),
            scenarioRoster = listOf(ScenarioPerson(name = "顾砚舟", role = "庄园管家", relationship = "已确认雇佣关系"))
        )
        val restored = DataCodec.decode(DataCodec.encode(AppData(chats = listOf(chat)))).chats.single()
        assertEquals(chat, restored)

        val migrated = DataCodec.decode(com.google.gson.JsonParser.parseString(DataCodec.encode(AppData(chats = listOf(chat)))).asJsonObject.apply {
            getAsJsonArray("chats").first().asJsonObject.remove("scenarioState")
            getAsJsonArray("chats").first().asJsonObject.remove("scenarioRoster")
        }.toString()).chats.single()
        assertEquals(ScenarioState(), migrated.scenarioState)
        assertTrue(migrated.scenarioRoster.isEmpty())
    }

    @Test fun skillInjectionOrderSurvivesStateRoundTrip() {
        val first = SkillRecord(id = "first", name = "基础规则", content = "FIRST_BODY", enabled = true)
        val second = SkillRecord(id = "second", name = "具体风格", content = "SECOND_BODY", enabled = true)
        val chat = Chat(id = "ordered", skillIds = listOf(second.id, first.id), messages = listOf(Message(content = "继续")))

        val restored = DataCodec.decode(DataCodec.encode(AppData(chats = listOf(chat), skills = listOf(first, second))))

        assertEquals(listOf(second.id, first.id), restored.chats.single().skillIds)
        assertEquals(listOf(second.id, first.id), ToolRuntime.skillsInOrder(restored.skills, restored.chats.single().skillIds).map { it.id })
        val request = ContextEngine.build(restored, restored.chats.single()).messagesJson
        assertTrue(request.indexOf("SECOND_BODY") < request.indexOf("FIRST_BODY"))
    }

    @Test fun duplicateSkillIdsAreMigratedWithoutDroppingSkillsOrSelections() {
        val first = SkillRecord(id = "same", name = "第一份", content = "FIRST", enabled = true)
        val duplicate = SkillRecord(id = "same", name = "第二份", content = "SECOND", enabled = true)
        val reservedSuffix = SkillRecord(id = "same-duplicate-2", name = "原有后缀", content = "RESERVED", enabled = true)
        val source = AppData(
            chats = listOf(Chat(id = "legacy", skillIds = listOf("same", "same-duplicate-2"))),
            skills = listOf(first, duplicate, reservedSuffix)
        )

        val restored = DataCodec.decode(DataCodec.encode(source))
        val ids = restored.skills.map { it.id }
        assertEquals(3, ids.distinct().size)
        assertEquals(listOf("FIRST", "SECOND", "RESERVED"), restored.skills.map { it.content })
        assertEquals(
            listOf("FIRST", "SECOND", "RESERVED"),
            ToolRuntime.skillsInOrder(restored.skills, restored.chats.single().skillIds).map { it.content }
        )
        assertEquals(restored, DataCodec.decode(DataCodec.encode(restored)))
    }

    @Test fun duplicateSkillMigrationDoesNotClaimDanglingChatIds() {
        val source = AppData(
            chats = listOf(
                Chat(id = "selected", skillIds = listOf("same")),
                Chat(id = "dangling", skillIds = listOf("same-duplicate-2"))
            ),
            skills = listOf(
                SkillRecord(id = "same", content = "FIRST", enabled = true),
                SkillRecord(id = "same", content = "SECOND", enabled = true)
            )
        )

        val restored = DataCodec.decode(DataCodec.encode(source))

        assertEquals(listOf("same", "same-duplicate-3"), restored.skills.map { it.id })
        assertEquals(listOf("same", "same-duplicate-3"), restored.chats.first().skillIds)
        assertTrue(ToolRuntime.skillsInOrder(restored.skills, restored.chats.last().skillIds).isEmpty())
    }

    @Test fun stateFingerprintIsStableAcrossPersistenceAndChangesWithConversationData() {
        val source = AppData(chats = listOf(Chat(id = "story", messages = listOf(Message(id = "u", content = "原始剧情")))))
        val restored = DataCodec.decode(DataCodec.encode(source))

        assertEquals(DataCodec.fingerprint(source), DataCodec.fingerprint(restored))
        assertNotEquals(DataCodec.fingerprint(source), DataCodec.fingerprint(restored.copy(
            chats = restored.chats.map { it.copy(messages = it.messages + Message(id = "a", role = "assistant", content = "新剧情")) }
        )))
    }

    @Test fun legacyBackupDiagnosticsRoundTripWithoutSchemaBump() {
        val source = DataCodec.encode(AppData(chats = listOf(Chat(messages = listOf(
            Message(role = "user", content = "hello"),
            Message(role = "assistant", content = "partial", status = "error")
        )))))
        val root = com.google.gson.JsonParser.parseString(source).asJsonObject
        val message = root.getAsJsonArray("chats").first().asJsonObject
            .getAsJsonArray("messages").get(1).asJsonObject
        message.addProperty("errorMessage", "模型返回空回复")
        message.addProperty("finishReason", "length")
        message.addProperty("requestId", "req-test-123")

        val restored = DataCodec.decode(root.toString())
        val diagnostics = restored.chats.single().messages[1]
        assertEquals("模型返回空回复", diagnostics.errorMessage)
        assertEquals("length", diagnostics.finishReason)
        assertEquals("req-test-123", diagnostics.requestId)

        val encoded = DataCodec.encode(restored)
        assertTrue(encoded.contains("\"errorMessage\":\"模型返回空回复\""))
        assertTrue(encoded.contains("\"finishReason\":\"length\""))
        assertTrue(encoded.contains("\"requestId\":\"req-test-123\""))
    }

    @Test fun omittedLegacyMessageFieldsAreNormalizedBeforeComposeReadsThem() {
        val root = com.google.gson.JsonParser.parseString(DataCodec.encode(AppData(
            chats = listOf(Chat(id = "legacy", title = "旧会话", messages = listOf(Message(id = "assistant-1", role = "assistant", content = "回复"))))
        ))).asJsonObject
        val message = root.getAsJsonArray("chats").first().asJsonObject.getAsJsonArray("messages").first().asJsonObject
        // Older exports omitted newer fields. Gson otherwise leaves Kotlin
        // non-null properties as runtime nulls, which can crash on redraw.
        listOf("content", "reasoning", "toolCallsJson", "status", "attachments", "errorMessage", "finishReason", "requestId", "intent", "generationId", "revisionOf", "qualityNotice", "skillSnapshotJson").forEach(message::remove)

        val restored = DataCodec.decode(root.toString())
        val normalized = restored.chats.single().messages.single()
        assertEquals("", normalized.content)
        assertEquals("complete", normalized.status)
        assertEquals("[]", normalized.toolCallsJson)
        assertEquals(emptyList<Attachment>(), normalized.attachments)
        assertEquals(restored, DataCodec.decode(DataCodec.encode(restored)))
    }

    @Test fun futureBackupVersionRejected() {
        try { DataCodec.decode("{\"schemaVersion\":999}"); fail("expected invalid version") } catch(_:IllegalArgumentException) { }
    }
    @Test fun explicitNullRejected() {
        try { DataCodec.decode("{\"schemaVersion\":1,\"providers\":null}"); fail("expected null rejection") } catch(_:IllegalArgumentException) { }
    }
    @Test fun stCharacterPreservesForeignFields() {
        val bytes="""{"spec":"chara_card_v2","data":{"name":"测试角色","description":"描述","first_mes":"你好","extensions":{"vendor":"value"}}}""".toByteArray()
        val c=Exchange.character(bytes)
        assertEquals("测试角色",c.name)
        assertTrue(Exchange.characterJson(c.copy(description="修改" )).contains("vendor"))
        assertEquals("修改",Exchange.character(Exchange.characterJson(c.copy(description="修改")).toByteArray()).description)
    }
    @Test fun stJsonlSkipsHeaderAndRetainsTools() {
        val source=Chat(messages=listOf(Message(content="测试"),Message(role="assistant",content="",toolCallsJson="[{\"id\":\"t\",\"type\":\"function\",\"function\":{\"name\":\"memory_search\",\"arguments\":\"{}\"}}]"),Message(role="tool",content="结果",toolCallId="t")))
        val imported=Exchange.chat(Exchange.chatJsonl(source),"venice","m")
        assertEquals(source.title, imported.title)
        assertEquals(listOf("user","assistant","tool"),imported.messages.map { it.role })
        assertEquals("t",imported.messages.last().toolCallId)
    }

    @Test fun stJsonlRoundTripRetainsMessageDiagnostics() {
        val source = Chat(messages = listOf(Message(
            role = "assistant",
            content = "部分回复",
            status = "error",
            errorMessage = "HTTP 502",
            finishReason = "length",
            requestId = "req-jsonl"
        )))
        val imported = Exchange.chat(Exchange.chatJsonl(source), "venice", "m")
        val message = imported.messages.single()
        assertEquals("error", message.status)
        assertEquals("HTTP 502", message.errorMessage)
        assertEquals("length", message.finishReason)
        assertEquals("req-jsonl", message.requestId)
    }

    @Test fun chatJsonlRoundTripKeepsScenarioPanelData() {
        val source = Chat(
            title = "剧本",
            scenarioState = ScenarioState(resources = listOf(ScenarioResource(name = "文明点", value = "42"))),
            scenarioRoster = listOf(ScenarioPerson(name = "顾砚舟", role = "管家")),
            messages = listOf(Message(role = "user", content = "查看状态"))
        )
        val restored = Exchange.chat(Exchange.chatJsonl(source), "venice", "model")
        assertEquals(source.scenarioState, restored.scenarioState)
        assertEquals(source.scenarioRoster, restored.scenarioRoster)
    }

    @Test fun markdownConversationImportKeepsDialogueAndDropsExportNoise() {
        val markdown = """# 风雪故事

会话 ID：external-session

## 当前摘要

<ds_safety>自动分类文字</ds_safety>

## 1 · user

我走出房间。

## 2 · assistant

顾砚舟向我点头。

### 推理记录

内部规划内容不应进入会话上下文。

## 3 · assistant

### 推理记录

只有推理、没有可见回复的空轮次。

## 4 · tool

内部工具输出。

## 5 · user

附件：map.png（image/png）

## 6 · assistant

错误：HTTP 502
""".trimIndent()

        val imported = Exchange.chat(markdown, "provider", "model")

        assertEquals("风雪故事", imported.title)
        assertEquals(listOf("user", "assistant", "user"), imported.messages.map { it.role })
        assertEquals("我走出房间。", imported.messages[0].content)
        assertEquals("顾砚舟向我点头。", imported.messages[1].content)
        assertTrue(imported.messages[2].content.contains("map.png"))
        assertFalse(imported.messages.joinToString { it.content }.contains("内部规划"))
        assertFalse(imported.messages.joinToString { it.content }.contains("自动分类"))
        assertFalse(imported.messages.joinToString { it.content }.contains("内部工具"))
    }

    @Test fun markdownConversationExportOmitsHiddenReasoningAndInternalMessages() {
        val chat = Chat(
            title = "清洁导出",
            messages = listOf(
                Message(content = "用户正文"),
                Message(role = "assistant", content = "助手正文", reasoning = "私有推理文本", toolCallsJson = "[{\"id\":\"t\"}]", errorMessage = "内部错误"),
                Message(role = "assistant", content = "", reasoning = "空轮次思考"),
                Message(role = "tool", content = "工具返回值")
            )
        )

        val markdown = VaultExport.conversation(chat)
        val imported = Exchange.chat(markdown, "provider", "model")

        assertTrue(markdown.contains("用户正文"))
        assertTrue(markdown.contains("助手正文"))
        assertFalse(markdown.contains("推理记录"))
        assertFalse(markdown.contains("私有推理文本"))
        assertFalse(markdown.contains("工具调用"))
        assertFalse(markdown.contains("工具返回值"))
        assertFalse(markdown.contains("内部错误"))
        assertEquals(listOf("用户正文", "助手正文"), imported.messages.map { it.content })
    }

    @Test fun presetRoundTripKeepsSummaryConfiguration() {
        val source = Preset(
            id = "preset-1",
            name = "长对话",
            systemPrompt = "系统规则",
            temperature = 0.35,
            maxOutput = 4096,
            summaryPrompt = "摘要规则",
            summaryProviderId = "provider-1",
            summaryModelId = "summary-model"
        )

        assertEquals(source, Exchange.preset(Exchange.presetJson(source)))
    }

    @Test fun backupRoundTripRestoresMediaAndWorkspace() {
        val root = createTempDir(prefix = "pocket-backup")
        val filesDir = File(root, "files").apply { mkdirs() }
        val mediaFile = File(filesDir, "media/sample.bin").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4)) }
        File(filesDir, "workspace/notes/hello.txt").apply { parentFile!!.mkdirs(); writeText("workspace content") }
        val source = AppData(mediaJobs = listOf(MediaJob(providerId = "p", modelId = "m", filePath = mediaFile.absolutePath)))
        val archive = File(root, "backup.zip")
        BackupArchive.write(source, filesDir, archive)

        val restoredFiles = File(root, "restored-files")
        val restored = BackupArchive.restore(archive.readBytes(), restoredFiles, File(root, "cache").apply { mkdirs() })

        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), File(restored.mediaJobs.single().filePath).readBytes().toList())
        assertEquals("workspace content", File(restoredFiles, "workspace/notes/hello.txt").readText())
    }

    @Test fun backupRestoreRejectsTraversalBeforeWritingOutsideRoots() {
        val root = createTempDir(prefix = "pocket-backup-traversal")
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val outside = File(root, "outside.txt")
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("state.json"))
                zip.write(DataCodec.encode(AppData()).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("workspace/../outside.txt"))
                zip.write("must not be written".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        try {
            BackupArchive.restore(bytes, filesDir, cacheDir)
            fail("expected traversal rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("路径不安全"))
        }
        assertFalse(outside.exists())
    }
}
