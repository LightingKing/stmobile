package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Sticker
import app.pockettavern.domain.StickerPack
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StickerDataTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)
    private val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2)

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()

    private fun text(value: String) = value.toByteArray(Charsets.UTF_8)

    @Test fun dafeifishMarkdownIndexGivesNamesDescriptionsAndKeywords() {
        val memes = """
            # 大肥鱼表情图库
            | ID | 描述与文案 | 关键词 | 使用语境 | 图片路径 |
            | --- | --- | --- | --- | --- |
            | hello | 挥手打招呼 | 你好 收到 开场 | 轻松问好、确认 | assets/memes/generated-v1/hello.png |
            | rice | 戴锅盔吃白米饭；开饭！ | 白米饭 干饭 | 饭点、投喂 | assets/memes/generated-v1/rice.png |
        """.trimIndent()
        val manifest = """{"id":"dafeiyu","name":"大肥鱼表情","files":[{"id":"hello","path":"assets/memes/generated-v1/hello.png"},{"id":"rice","path":"assets/memes/generated-v1/rice.png"}]}"""
        val pack = StickerImporter.read("dafeifish-codex-main.zip", zip(
            "dafeifish-codex-main/README.md" to text("| 命令 | 效果 |\n| --- | --- |\n| 正经点 | 暂停 |"),
            "dafeifish-codex-main/dafeiyu/references/memes.md" to text(memes),
            "dafeifish-codex-main/dafeiyu/assets/manifest.json" to text(manifest),
            "dafeifish-codex-main/dafeiyu/assets/memes/generated-v1/hello.png" to png,
            "dafeifish-codex-main/dafeiyu/assets/memes/generated-v1/rice.png" to png,
            "dafeifish-codex-main/docs/banner.png" to png,
        ))
        assertEquals(listOf("hello", "rice"), pack.items.map { it.key })
        assertTrue(pack.items.first().description.contains("挥手打招呼"))
        assertEquals(listOf("你好", "收到", "开场"), pack.items.first().tags)
        assertEquals("大肥鱼表情", pack.name)
    }

    @Test fun dshMemeRepoUsesTheLargestManifestFolderAndFolderNamesForGenericFiles() {
        val pack = StickerImporter.read("dsh-meme-main.zip", zip(
            "dsh-meme-main/docs/dafeiyu/manifest.json" to text("""{"name":"文档样例","license":"personal"}"""),
            "dsh-meme-main/docs/dafeiyu/bixin.jpg" to jpg,
            "dsh-meme-main/memes/dafeiyu-001/manifest.json" to text("""{"id":"dafeiyu-001","name":"大肥鱼","license":"personal"}"""),
            "dsh-meme-main/memes/dafeiyu-001/memes/happy/01.png" to png,
            "dsh-meme-main/memes/dafeiyu-001/memes/sad/02.png" to png,
            "dsh-meme-main/memes/dafeiyu-001/memes/sad/03.png" to png,
            "dsh-meme-main/docs/hero-banner.jpg" to jpg,
        ))
        assertEquals("大肥鱼", pack.name)
        assertEquals("personal", pack.license)
        assertEquals(listOf("happy", "sad", "sad2"), pack.items.map { it.key })
        assertEquals(listOf("sad"), pack.items[1].tags)
    }

    @Test fun ownStickersJsonIsPreferredAndTakenKeysStayUnique() {
        val index = """{"name":"我的包","license":"CC0","stickers":[{"file":"a.png","name":"smile","tags":["微笑"],"description":"轻松"},{"file":"img/b.png","name":"[开心]:耶"}]}"""
        val pack = StickerImporter.read("x.zip", zip("pack/stickers.json" to text(index), "pack/a.png" to png, "pack/img/b.png" to png, "pack/c.png" to png),
            takenKeys = setOf("smile"))
        assertEquals(listOf("smile2", "开心 耶"), pack.items.map { it.key })
        assertEquals("我的包", pack.name)
        assertEquals(listOf("微笑"), pack.items.first().tags)
    }

    @Test fun plainImageZipUsesFileNamesAndChecksSignaturesNotExtensions() {
        val pack = StickerImporter.read("表情.zip", zip(
            "ganfan.jpg" to jpg, "bixin.png" to png, "fake.png" to text("not an image"), "notes.txt" to text("x"),
            "__MACOSX/._ganfan.jpg" to jpg, ".hidden/a.png" to png
        ))
        assertEquals(listOf("ganfan", "bixin"), pack.items.map { it.key })
        assertEquals(listOf("jpg", "png"), pack.items.map { it.extension })
        assertEquals("表情", pack.name)
        assertEquals("未声明", pack.license)
    }

    @Test fun unsafeOrEmptyPacksAreRejectedAndOversizedImagesSkipped() {
        try { StickerImporter.read("evil.zip", zip("../evil.png" to png)); fail("path traversal must fail") } catch (_: IllegalArgumentException) { }
        try { StickerImporter.read("empty.zip", zip("readme.md" to text("# hi"))); fail("no images must fail") } catch (_: IllegalArgumentException) { }
        val big = png + ByteArray((StickerImporter.MAX_IMAGE_BYTES + 1).toInt())
        val pack = StickerImporter.read("p.zip", zip("big.png" to big, "ok.png" to png))
        assertEquals(listOf("ok"), pack.items.map { it.key })
    }

    @Test fun storeWritesReadsAndDeletesInsideItsFolderOnly() {
        val dir = Files.createTempDirectory("stickers").toFile()
        val saved = StickerStore.save(dir, StickerImporter.read("p.zip", zip("smile.png" to png, "think.jpg" to jpg)), "p.zip")
        assertEquals(2, saved.stickers.size)
        assertTrue(saved.stickers.all { StickerStore.file(dir, it)?.readBytes()?.isNotEmpty() == true })
        assertTrue(saved.stickers.last().file.endsWith(".jpg"))
        assertNull(StickerStore.file(dir, Sticker(key = "x", file = "../secret.png")))
        assertFalse(StickerStore.isSafe("a/b/c.png"))
        StickerStore.delete(dir, saved.id)
        assertTrue(saved.stickers.all { StickerStore.file(dir, it) == null })
        StickerStore.delete(dir, "..")
        assertTrue(dir.exists())
    }

    @Test fun phonePacksBecomePcUploadsWithFreeNamesAndNoMissingImages() {
        val dir = Files.createTempDirectory("stickers-move").toFile()
        val saved = StickerStore.save(dir, StickerImporter.read("p.zip", zip("smile.png" to png, "think.jpg" to jpg)), "p.zip")
        val withGhost = saved.copy(stickers = saved.stickers + Sticker(key = "ghost", file = "${saved.id}/999.png"))
        val upload = StickerStore.toUpload(dir, withGhost, takenKeys = setOf("smile", "smile2"))
        assertEquals(listOf("smile3", "think"), upload.items.map { it.key })
        assertEquals(listOf("png", "jpg"), upload.items.map { it.extension })
        assertTrue(upload.items.first().bytes.contentEquals(png))
        assertEquals(saved.name, upload.name)
    }

    @Test fun codecKeepsStickerDataAndOldStateGetsDefaults() {
        val pack = StickerPack(id = "p1", name = "包", license = "personal", stickers = listOf(Sticker(key = "smile", name = "smile", file = "p1/001.png")))
        val data = AppData(chats = listOf(Chat(id = "c", stickerFrequency = "standard")), stickerPacks = listOf(pack))
        val restored = DataCodec.decode(DataCodec.encode(data))
        assertEquals(listOf(pack), restored.stickerPacks)
        assertEquals("standard", restored.chats.single().stickerFrequency)
        val legacy = DataCodec.encode(AppData(chats = listOf(Chat(id = "c")))).replace(",\"stickerPacks\":[]", "").replace(",\"stickerFrequency\":\"off\"", "")
        assertFalse(legacy.contains("stickerPacks"))
        val old = DataCodec.decode(legacy)
        assertTrue(old.stickerPacks.isEmpty())
        assertEquals("off", old.chats.single().stickerFrequency)
        val unsafe = DataCodec.gson.toJson(data.copy(stickerPacks = listOf(pack.copy(stickers = listOf(Sticker(key = "x", file = "../x.png"))))))
        try { DataCodec.decode(unsafe); fail("unsafe sticker path must fail") } catch (_: IllegalArgumentException) { }
    }

    @Test fun backupCarriesStickerImagesAndDropsMissingOnes() {
        val source = Files.createTempDirectory("sticker-src").toFile()
        val pack = StickerStore.save(source, StickerImporter.read("p.zip", zip("smile.png" to png, "think.png" to png)), "p.zip")
        val withGhost = pack.copy(stickers = pack.stickers + Sticker(key = "ghost", file = "${pack.id}/999.png"))
        val archive = Files.createTempFile("backup", ".zip").toFile()
        BackupArchive.write(AppData(stickerPacks = listOf(withGhost)), source, archive)
        val target = Files.createTempDirectory("sticker-dst").toFile()
        val restored = BackupArchive.restore(archive.readBytes(), target, Files.createTempDirectory("cache").toFile())
        assertEquals(listOf("smile", "think"), restored.stickerPacks.single().stickers.map { it.key })
        assertTrue(restored.stickerPacks.single().stickers.all { StickerStore.file(target, it)?.readBytes()?.contentEquals(png) == true })
    }
}
