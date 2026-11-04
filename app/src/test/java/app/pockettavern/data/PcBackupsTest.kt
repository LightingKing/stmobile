package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.PcBackupPolicy
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.provider.PcLinkClient
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PcBackupsTest {
    private class MemoryStore : PcLinkStore {
        override var providerId = ""
        override var autoBackup = true
        override var lastBackupAt = 0L
        override var lastAttemptAt = 0L
        override var lastBackupSha = ""
        override var lastBackupSize = 0L
        override var lastError = ""
    }

    private lateinit var server: MockWebServer
    private lateinit var pc: ProviderConfig
    private lateinit var cache: File
    private val store = MemoryStore()
    private var clock = 10L * PcBackupPolicy.INTERVAL_MILLIS
    private val data = AppData(chats = listOf(Chat(id = "c", title = "旧会话", messages = listOf(Message(role = "user", content = "你好")))))

    @Before fun start() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        pc = ProviderConfig(id = "pc", kind = "writing-server", baseUrl = server.url("/v1").newBuilder().host("127.0.0.1").build().toString())
        cache = Files.createTempDirectory("pc-cache").toFile()
    }

    @After fun stop() { server.shutdown() }

    private fun backups() = PcBackups(PcLinkClient(allowInsecureHttpForTests = true), store, Files.createTempDirectory("files").toFile(), cache, "Phone", "0.1.68", now = { clock })

    private fun stored(duplicate: Boolean = false) = MockResponse().setResponseCode(if (duplicate) 200 else 201)
        .setBody("""{"id":"20261104-165000-abcdef","created_at":"2026-11-04T08:50:00.000Z","size":1,"sha256":"x","reason":"auto","duplicate":$duplicate}""")

    @Test fun anUnchangedAutomaticBackupIsNotSentAgain() {
        val pcBackups = backups()
        server.enqueue(stored())
        assertTrue(runBlocking { pcBackups.upload(pc, "t", data, "auto") } is PcBackups.Upload.Stored)
        val first = server.takeRequest().body.readByteArray()
        assertTrue(store.lastBackupSha.isNotBlank())
        clock += PcBackupPolicy.INTERVAL_MILLIS
        assertEquals(PcBackups.Upload.Unchanged, runBlocking { pcBackups.upload(pc, "t", data, "auto") })
        assertEquals(1, server.requestCount)
        assertEquals(clock, store.lastBackupAt)

        // A manual backup always goes out, and the same data gives the same bytes.
        server.enqueue(stored(duplicate = true))
        val manual = runBlocking { pcBackups.upload(pc, "t", data, "manual") } as PcBackups.Upload.Stored
        assertTrue(manual.duplicate)
        assertTrue(server.takeRequest().body.readByteArray().contentEquals(first))

        server.enqueue(stored())
        runBlocking { pcBackups.upload(pc, "t", data.copy(chats = data.chats.map { it.copy(title = "改名了") }), "auto") }
        assertEquals(3, server.requestCount)
        assertEquals(0, File(cache, "pc-backup").listFiles()!!.size)
    }

    @Test fun aFailedBackupIsRecordedAndRetriedLater() {
        val pcBackups = backups()
        server.enqueue(MockResponse().setResponseCode(507).setBody("""{"error":{"message":"满了"}}"""))
        try { runBlocking { pcBackups.upload(pc, "t", data, "auto") } } catch (_: Exception) { }
        assertTrue(store.lastError.contains("磁盘空间不足"))
        assertEquals(clock, store.lastAttemptAt)
        assertEquals(0L, store.lastBackupAt)
        assertFalse("waits before retrying", pcBackups.autoBackupDue())
        clock += PcBackupPolicy.RETRY_MILLIS
        assertTrue(pcBackups.autoBackupDue())
        store.autoBackup = false
        assertFalse(pcBackups.autoBackupDue())
    }

    @Test fun policyRunsAtMostOncePerInterval() {
        val hour = 60L * 60 * 1000
        assertTrue(PcBackupPolicy.due(true, now = 100 * hour, lastSuccessAt = 0, lastAttemptAt = 0))
        assertFalse(PcBackupPolicy.due(true, now = 100 * hour, lastSuccessAt = 99 * hour, lastAttemptAt = 99 * hour))
        assertFalse(PcBackupPolicy.due(false, now = 100 * hour, lastSuccessAt = 0, lastAttemptAt = 0))
        assertFalse(PcBackupPolicy.due(true, now = 100 * hour, lastSuccessAt = 0, lastAttemptAt = 100 * hour - 60_000))
    }

    @Test fun fetchedBackupsRestoreThroughTheNormalArchive() {
        val source = Files.createTempDirectory("src").toFile()
        val archive = File(source, "b.zip")
        BackupArchive.write(data, source, archive)
        val bytes = archive.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        server.enqueue(MockResponse().setHeader("X-Pocket-Sha256", sha).setBody(Buffer().write(bytes)))
        val fetched = runBlocking { backups().fetch(pc, "t", app.pockettavern.domain.PcBackup(id = "20261104-165000-abcdef", sha256 = sha)) }
        val restored = BackupArchive.restore(fetched, Files.createTempDirectory("dst").toFile(), Files.createTempDirectory("tmp").toFile())
        assertEquals("旧会话", restored.chats.single().title)
        assertEquals(0, File(cache, "pc-backup").listFiles()!!.size)
    }
}
