package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.Sticker
import app.pockettavern.domain.StickerPack
import app.pockettavern.engine.StickerGate
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Character avatars and each character's own sticker packs. */
class CharacterAvatarsTest {
    private val read: (String) -> String = { File("src/main/assets", it).readText(Charsets.UTF_8) }
    private val bytes: (String) -> ByteArray = { File("src/main/assets", it).readBytes() }

    @Test fun onlyBundledOrStoredAvatarsAreAccepted() {
        assertEquals("asset:characters/dafeiyu-avatar.png", CharacterAvatars.normalize(" asset:characters/dafeiyu-avatar.png "))
        assertEquals("file:avatars/abc-1.png", CharacterAvatars.normalize("file:avatars/abc-1.png"))
        for (bad in listOf("asset:../secrets.png", "file:avatars/../state.json", "file:/sdcard/x.png", "https://example.com/a.png", "asset:scenarios/x.png", null)) {
            assertEquals(bad.toString(), "", CharacterAvatars.normalize(bad))
        }
        val files = Files.createTempDirectory("avatars").toFile()
        assertEquals(File(files, "avatars/a.png").canonicalFile, CharacterAvatars.file(files, "file:avatars/a.png"))
        assertNull(CharacterAvatars.file(files, "asset:characters/dafeiyu-avatar.png"))
        assertEquals("characters/kuroneko-avatar.png", CharacterAvatars.assetPath("asset:characters/kuroneko-avatar.png"))
        assertTrue(CharacterAvatars.newFileName("builtin/../x", 5).matches(Regex("[A-Za-z0-9._-]+-5\\.png")))
    }

    @Test fun builtInsCarryPinnedAvatarsAndTheirOwnPacks() {
        val characters = BuiltinScenarios.loadCharacters(read, bytes)
        val fish = characters.single { it.id == "dafeiyu" }.character
        val cat = characters.single { it.id == "kuroneko" }.character
        assertEquals("asset:characters/dafeiyu-avatar.png", fish.avatar)
        assertEquals("asset:characters/kuroneko-avatar.png", cat.avatar)
        assertEquals(listOf("大肥鱼（dsh-meme）", "大肥鱼表情（dafeifish-codex）"), fish.stickerPacks)
        assertEquals(listOf("黑猫"), cat.stickerPacks)
        assertTrue("黑猫 stays an everyday-chat character", cat.systemPrompt.contains("不写露骨的性内容"))
        val swapped: (String) -> ByteArray = { path -> if (path.endsWith("kuroneko-avatar.png")) bytes("characters/dafeiyu-avatar.png") else bytes(path) }
        assertTrue(runCatching { BuiltinScenarios.loadCharacters(read, swapped) }.exceptionOrNull()?.message.orEmpty().contains("头像 SHA-256"))
    }

    @Test fun olderInstallsGetAvatarAndPacksOnceAndUserChoicesStay() {
        val catalog = BuiltinScenarios.loadCharacters(read, bytes)
        val cat = catalog.single { it.id == "kuroneko" }
        // 0.1.82: the same card without the everyday-chat line, no avatar, no packs.
        val old = cat.character.copy(avatar = "", stickerPacks = emptyList(), systemPrompt = cat.character.systemPrompt.substringBefore("这是日常聊天角色"))
        assertTrue(BuiltinScenarios.contentHash(old) in cat.replaces)
        val phone = AppData(characters = listOf(old)).let { it.copy(settings = it.settings.copy(builtinScenariosSeen = listOf("dafeiyu", "kuroneko"))) }
        val upgraded = BuiltinScenarios.merge(phone, catalog)
        val now = upgraded.characters.single()
        assertEquals("asset:characters/kuroneko-avatar.png", now.avatar)
        assertEquals(listOf("黑猫"), now.stickerPacks)
        assertTrue(now.systemPrompt.contains("不写露骨的性内容"))
        // The user then removes the avatar and picks other packs: the next start leaves both alone.
        val changed = upgraded.copy(characters = listOf(now.copy(avatar = "", stickerPacks = emptyList())))
        BuiltinScenarios.merge(changed, catalog).characters.single().let { assertEquals("", it.avatar); assertTrue(it.stickerPacks.isEmpty()) }
        // An edited card keeps its text but still gets the picture it never had.
        val edited = phone.copy(characters = listOf(old.copy(personality = "我改过")))
        BuiltinScenarios.merge(edited, catalog).characters.single().let { assertEquals("我改过", it.personality); assertEquals("asset:characters/kuroneko-avatar.png", it.avatar) }
    }

    @Test fun aCharacterSendsOnlyItsOwnPacksWhenThePcHasThem() {
        val pc = ProviderConfig(id = "pc", kind = "writing-server", baseUrl = "https://pt.example.com/v1")
        val packs = listOf(
            StickerPack(id = "a", name = "大肥鱼表情（dafeifish-codex）", stickers = listOf(Sticker(key = "你好", file = "a/001.png"))),
            StickerPack(id = "b", name = "黑猫", stickers = listOf(Sticker(key = "诅咒你", file = "b/001.jpg"))),
            StickerPack(id = "c", name = "熊猫头", stickers = listOf(Sticker(key = "盘它", file = "c/001.jpg"))),
        )
        val cat = Character(id = "cat", name = "黑猫", stickerPacks = listOf("黑猫"))
        val plain = Character(id = "plain", name = "路人")
        fun keys(characterId: String, available: List<StickerPack> = packs) =
            StickerGate.forChat(Chat(id = "c", providerId = "pc", characterId = characterId), listOf(pc), available, listOf(cat, plain)).map { it.key }
        assertEquals(listOf("诅咒你"), keys("cat"))
        assertEquals(listOf("你好", "诅咒你", "盘它"), keys("plain"))
        assertEquals(listOf("你好", "诅咒你", "盘它"), keys(""))
        assertEquals("her pack is switched off or missing: fall back to everything enabled", listOf("你好", "盘它"), keys("cat", packs.map { if (it.name == "黑猫") it.copy(enabled = false) else it }))
    }

    @Test fun avatarAndPacksSurviveSavingAndBackups() {
        val source = Files.createTempDirectory("avatar-src").toFile()
        val picture = byteArrayOf(1, 2, 3, 4)
        File(source, "avatars").mkdirs()
        File(source, "avatars/c-1.png").writeBytes(picture)
        val custom = Character(id = "c", name = "自建", avatar = "file:avatars/c-1.png", stickerPacks = listOf("黑猫", "黑猫", " "))
        val ghost = Character(id = "g", name = "丢图", avatar = "file:avatars/missing.png")
        val builtin = Character(id = "b", name = "内置", avatar = "asset:characters/dafeiyu-avatar.png")
        val data = AppData(characters = listOf(custom, ghost, builtin))
        val decoded = DataCodec.decode(DataCodec.encode(data))
        assertEquals(listOf("黑猫"), decoded.characters.first().stickerPacks)
        val legacy = DataCodec.decode(DataCodec.encode(AppData(characters = listOf(Character(id = "old", name = "旧")))).replace(",\"avatar\":\"\",\"stickerPacks\":[]", ""))
        assertEquals("", legacy.characters.single().avatar)

        val archive = Files.createTempFile("backup", ".zip").toFile()
        BackupArchive.write(data, source, archive)
        val target = Files.createTempDirectory("avatar-dst").toFile()
        val restored = BackupArchive.restore(archive.readBytes(), target, Files.createTempDirectory("cache").toFile())
        assertEquals("file:avatars/c-1.png", restored.characters.single { it.id == "c" }.avatar)
        assertTrue(File(target, "avatars/c-1.png").readBytes().contentEquals(picture))
        assertEquals("a picture missing from the archive is dropped", "", restored.characters.single { it.id == "g" }.avatar)
        assertEquals("asset:characters/dafeiyu-avatar.png", restored.characters.single { it.id == "b" }.avatar)
    }
}
