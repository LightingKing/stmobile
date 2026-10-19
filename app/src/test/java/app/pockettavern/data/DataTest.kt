package app.pockettavern.data

import app.pockettavern.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DataTest {
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
        assertEquals(listOf("user","assistant","tool"),imported.messages.map { it.role })
        assertEquals("t",imported.messages.last().toolCallId)
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
