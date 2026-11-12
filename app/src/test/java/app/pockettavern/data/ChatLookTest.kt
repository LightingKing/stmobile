package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.ChatLook
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.2.2: each chat can have a background; characters can carry a default; the user can have a picture. */
class ChatLookTest {
    @Test fun onlyBuiltInScenesAndStoredPicturesAreAccepted() {
        assertEquals("builtin:night", ChatBackgrounds.normalize(" builtin:night "))
        assertEquals("file:backgrounds/bg-1.jpg", ChatBackgrounds.normalize("file:backgrounds/bg-1.jpg"))
        for (bad in listOf("builtin:space", "file:backgrounds/../state.jpg", "file:avatars/me.png", "file:backgrounds/x.png", "https://example.com/a.jpg", "", null))
            assertEquals(bad.toString(), "", ChatBackgrounds.normalize(bad))
        assertEquals("sea", ChatBackgrounds.builtin("builtin:sea"))
        assertNull(ChatBackgrounds.builtin("file:backgrounds/bg-1.jpg"))
        assertEquals(listOf("night", "lamp", "sea"), ChatBackgrounds.BUILTIN.map { it.first })
    }

    @Test fun aLookIsCleanedAndNoBackgroundMeansNoLook() {
        assertEquals(ChatLook("builtin:lamp", ChatLook.MAX_DIM, ChatLook.MAX_BLUR, 0), ChatBackgrounds.normalizeLook(ChatLook("builtin:lamp", dim = 300, blur = 9, bubbleClear = -5)))
        assertNull(ChatBackgrounds.normalizeLook(ChatLook("not-a-background")))
        assertNull(ChatBackgrounds.normalizeLook(null))
        assertEquals(0.85f, ChatLook(bubbleClear = 15).bubbleAlpha, 0.001f)
        assertEquals("cards never go fully clear", 0.4f, ChatLook(bubbleClear = 99).bubbleAlpha, 0.001f)
    }

    @Test fun storedPicturesStayInsideTheBackgroundsFolder() {
        val files = Files.createTempDirectory("backgrounds").toFile()
        try {
            val file = ChatBackgrounds.file(files, "file:backgrounds/bg-7.jpg")
            assertEquals(File(files, "backgrounds/bg-7.jpg").canonicalFile, file)
            assertNull(ChatBackgrounds.file(files, "builtin:night"))
            assertNull(ChatBackgrounds.file(files, "file:backgrounds/../state.json"))
        } finally { files.deleteRecursively() }
    }

    @Test fun blurLevelsDecodeSmallerPictures() {
        assertEquals(listOf(ChatBackgrounds.MAX_SIDE, 160, 72, 32), (0..3).map(ChatBackgrounds::decodeWidth))
        assertEquals(8, ChatBackgrounds.sampleSize(1440, 160))
        assertEquals(1, ChatBackgrounds.sampleSize(1000, 1440))
        assertTrue(ChatBackgrounds.decodeWidth(1) > ChatBackgrounds.decodeWidth(3))
    }

    @Test fun onlyPicturesInUseAreKept() {
        val data = AppData(chats = listOf(Chat(look = ChatLook("file:backgrounds/a.jpg")), Chat(look = ChatLook("builtin:night")), Chat()),
            characters = listOf(Character(look = ChatLook("file:backgrounds/b.jpg"))))
        assertEquals(setOf("a.jpg", "b.jpg"), ChatBackgrounds.referenced(data))
    }

    @Test fun looksAndMyAvatarSurviveSavingAndOldDataHasNone() {
        val data = AppData(chats = listOf(Chat(id = "c", look = ChatLook("builtin:sea", 50, 2, 30))),
            characters = listOf(Character(id = "h", name = "黑猫", look = ChatLook("file:backgrounds/bg-1.jpg"))),
            settings = AppSettings(userAvatar = "file:avatars/me-1.png", showUserAvatar = true))
        val decoded = DataCodec.decode(DataCodec.encode(data))
        assertEquals(ChatLook("builtin:sea", 50, 2, 30), decoded.chats.single().look)
        assertEquals("file:backgrounds/bg-1.jpg", decoded.characters.single().look?.background)
        assertEquals("file:avatars/me-1.png", decoded.settings.userAvatar)
        assertTrue(decoded.settings.showUserAvatar)
        // A file written by 0.2.1 has none of these fields.
        val old = DataCodec.encode(AppData(chats = listOf(Chat(id = "c")))).replace(",\"look\":null", "")
        assertNull(DataCodec.decode(old).chats.single().look)
        // Hand-edited or damaged values are dropped, not trusted.
        val damaged = DataCodec.encode(data).replace("builtin:sea", "builtin:../../x").replace("file:avatars/me-1.png", "file:../me.png")
        assertNull(DataCodec.decode(damaged).chats.single().look)
        assertEquals("", DataCodec.decode(damaged).settings.userAvatar)
    }

    @Test fun backupsCarryChosenPicturesAndMyAvatar() {
        val source = Files.createTempDirectory("look-src").toFile()
        val target = Files.createTempDirectory("look-dst").toFile()
        val cache = Files.createTempDirectory("look-cache").toFile()
        try {
            File(source, "backgrounds").mkdirs(); File(source, "avatars").mkdirs()
            File(source, "backgrounds/bg-1.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(source, "backgrounds/unused.jpg").writeBytes(byteArrayOf(9))
            File(source, "avatars/me-1.png").writeBytes(byteArrayOf(4, 5))
            val data = AppData(chats = listOf(Chat(id = "c", look = ChatLook("file:backgrounds/bg-1.jpg")), Chat(id = "d", look = ChatLook("builtin:night"))),
                characters = listOf(Character(id = "h", look = ChatLook("file:backgrounds/bg-1.jpg"))),
                settings = AppSettings(userAvatar = "file:avatars/me-1.png", showUserAvatar = true))
            val archive = File(cache, "backup.zip")
            BackupArchive.write(data, source, archive)
            val names = ZipFile(archive).use { zip -> zip.entries().toList().map { it.name } }
            assertTrue(names.contains("backgrounds/bg-1.jpg") && names.contains("avatars/me-1.png"))
            assertFalse("pictures nothing uses stay out of backups", names.contains("backgrounds/unused.jpg"))
            val restored = BackupArchive.restore(archive.readBytes(), target, cache)
            assertTrue(File(target, "backgrounds/bg-1.jpg").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
            assertTrue(File(target, "avatars/me-1.png").isFile)
            assertEquals("file:backgrounds/bg-1.jpg", restored.chats.first { it.id == "c" }.look?.background)
            assertEquals("builtin:night", restored.chats.first { it.id == "d" }.look?.background)
            assertNotNull(restored.characters.single().look)
            assertEquals("file:avatars/me-1.png", restored.settings.userAvatar)
            assertTrue(restored.settings.showUserAvatar)
        } finally { listOf(source, target, cache).forEach { it.deleteRecursively() } }
    }

    @Test fun aPictureMissingFromABackupFallsBackToNone() {
        val source = Files.createTempDirectory("look-src").toFile()
        val target = Files.createTempDirectory("look-dst").toFile()
        val cache = Files.createTempDirectory("look-cache").toFile()
        try {
            val data = AppData(chats = listOf(Chat(id = "c", look = ChatLook("file:backgrounds/gone.jpg")), Chat(id = "d", look = ChatLook("builtin:lamp"))),
                settings = AppSettings(userAvatar = "file:avatars/gone.png", showUserAvatar = true))
            val archive = File(cache, "backup.zip")
            BackupArchive.write(data, source, archive)
            val restored = BackupArchive.restore(archive.readBytes(), target, cache)
            assertNull(restored.chats.first { it.id == "c" }.look)
            assertEquals("builtin:lamp", restored.chats.first { it.id == "d" }.look?.background)
            assertEquals("", restored.settings.userAvatar)
            assertFalse(restored.settings.showUserAvatar)
        } finally { listOf(source, target, cache).forEach { it.deleteRecursively() } }
    }
}
