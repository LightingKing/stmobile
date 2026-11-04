package app.pockettavern.provider

import app.pockettavern.domain.PcBackup
import app.pockettavern.domain.ProviderConfig
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PcLinkClientTest {
    private lateinit var server: MockWebServer
    private lateinit var pc: ProviderConfig
    private val client = PcLinkClient(allowInsecureHttpForTests = true)

    @Before fun start() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        pc = ProviderConfig(id = "pc", name = "电脑", kind = "writing-server", baseUrl = server.url("/v1").newBuilder().host("127.0.0.1").build().toString())
    }

    @After fun stop() { server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun failure(block: suspend () -> Unit): PcLinkException = try {
        runBlocking { block() }
        fail("expected PcLinkException"); throw AssertionError()
    } catch (error: PcLinkException) { error }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun statusIsReadWithTheGatewayToken() {
        server.enqueue(json("""{"ok":true,"ready":true,"version":"0.2.0","listen":{"hosts":["127.0.0.1","192.168.1.22"],"port":8791},
            "client_address":"192.168.1.50","upstream":{"configured":true,"host":"api.venice.ai","model":"deepseek-v4"},"review_skill":"ss-stop-slop",
            "active_requests":1,"uptime_seconds":90,"backups":{"count":3,"total_bytes":4096,"keep":20},"features":["backups","live_stream"]}"""))
        val status = runBlocking { client.status(pc, " token ") }
        val request = server.takeRequest()
        assertEquals("/v1/pocket/status", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals("0.2.0", status.version)
        assertTrue(status.ready)
        assertEquals("api.venice.ai", status.upstreamHost)
        assertEquals(listOf("127.0.0.1", "192.168.1.22"), status.listenHosts)
        assertEquals(3, status.backupCount)
        assertEquals(20, status.backupKeep)
        assertTrue("backups" in status.features)
    }

    @Test fun refusalsExplainWhatToDo() {
        server.enqueue(json("""{"error":{"message":"未授权"}}""", 401))
        assertTrue(failure { client.status(pc, "wrong") }.message!!.contains("手机连接.txt"))
        server.enqueue(json("""{"error":{"message":"x"}}""", 429).setHeader("Retry-After", "600"))
        assertTrue(failure { client.status(pc, "wrong") }.message!!.contains("约 10 分钟"))
        server.enqueue(json("""{"error":{"message":"路径不存在"}}""", 404))
        assertTrue(failure { client.backups(pc, "t") }.message!!.contains("版本过旧"))
        server.enqueue(json("""{"error":{"message":"磁盘坏了"}}""", 500))
        assertTrue(failure { client.status(pc, "t") }.message!!.contains("磁盘坏了"))
        server.enqueue(MockResponse().setBody("<html>router login</html>"))
        assertTrue(failure { client.status(pc, "t") }.message!!.contains("不是口袋酒馆写作服务"))
        assertTrue(failure { client.status(pc, " ") }.message!!.contains("Token"))
    }

    @Test fun publicPlainHttpAndUnreachablePcsFailBeforeOrAtConnect() {
        val requests = server.requestCount
        assertTrue(failure { client.status(pc.copy(baseUrl = "http://example.com/v1"), "t") }.message!!.contains("HTTPS"))
        assertEquals(requests, server.requestCount)
        val closed = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val url = closed.url("/v1").newBuilder().host("127.0.0.1").build().toString()
        closed.shutdown()
        assertTrue(failure { client.status(pc.copy(baseUrl = url), "t") }.message!!.contains("连不上电脑"))
    }

    @Test fun uploadSendsTheZipWithLabelsAndReadsTheStoredCopy() {
        val zip = byteArrayOf(0x50, 0x4b, 3, 4, 9, 9, 9)
        val file = Files.createTempFile("pc", ".zip").toFile().apply { writeBytes(zip) }
        server.enqueue(json("""{"id":"20261104-165000-abcdef","created_at":"2026-11-04T08:50:00.000Z","size":7,"sha256":"${sha(zip)}","device":"Xiaomi 14","app_version":"0.1.68 (69)","reason":"auto","duplicate":true}"""))
        val (backup, duplicate) = runBlocking { client.upload(pc, "t", file, "auto", "小米 Xiaomi 14", "0.1.68 (69)") }
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/backups", request.path)
        assertEquals("application/zip", request.getHeader("Content-Type"))
        assertEquals("Xiaomi 14", request.getHeader("X-Pocket-Device"))
        assertEquals("auto", request.getHeader("X-Pocket-Backup-Reason"))
        assertTrue(request.body.readByteArray().contentEquals(zip))
        assertTrue(duplicate)
        assertEquals("20261104-165000-abcdef", backup.id)
        assertEquals(7L, backup.size)
    }

    @Test fun listAndDownloadVerifyTheChecksum() {
        val zip = byteArrayOf(0x50, 0x4b, 3, 4, 1, 2, 3, 4)
        server.enqueue(json("""{"object":"list","data":[{"id":"20261104-165000-abcdef","created_at":"2026-11-04T08:50:00.000Z","size":8,"sha256":"${sha(zip)}","reason":"manual"}]}"""))
        val backup = runBlocking { client.backups(pc, "t") }.single()
        assertEquals("/v1/backups", server.takeRequest().path)

        val target = File(Files.createTempDirectory("pc-dl").toFile(), "b.zip")
        server.enqueue(MockResponse().setHeader("X-Pocket-Sha256", sha(zip)).setBody(Buffer().write(zip)))
        runBlocking { client.download(pc, "t", backup, target) }
        assertEquals("/v1/backups/20261104-165000-abcdef", server.takeRequest().path)
        assertTrue(target.readBytes().contentEquals(zip))

        server.enqueue(MockResponse().setHeader("X-Pocket-Sha256", "0".repeat(64)).setBody(Buffer().write(zip)))
        assertTrue(failure { client.download(pc, "t", backup, target) }.message!!.contains("校验"))
        assertFalse(target.exists())

        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64))))
        assertTrue(failure { client.download(pc, "t", PcBackup(id = backup.id), target, maxBytes = 16) }.message!!.contains("128 MB"))
    }
}
