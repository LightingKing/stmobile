package app.pockettavern.provider

import app.pockettavern.domain.MediaJob
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.nio.file.Files
import java.util.Base64

class MediaClientTest {
    private lateinit var server: MockWebServer
    private lateinit var out: java.io.File

    @Before fun setUp() {
        server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
        out = Files.createTempDirectory("pocket-media-test").toFile()
    }

    @After fun tearDown() {
        server.shutdown()
        out.deleteRecursively()
    }

    private fun provider(): ProviderConfig {
        val url = server.url("/api/v1").newBuilder().host("127.0.0.1").build()
        assertEquals("MockWebServer URL must use HTTP for test injection", "http", url.scheme)
        assertEquals("MockWebServer URL must use loopback host", "127.0.0.1", url.host)
        return ProviderConfig(id = "venice", baseUrl = url.toString())
    }

    @Test fun imageJsonBase64IsSavedInsideOutputDirectory() = runBlocking {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("images", org.json.JSONArray().put(encoded)).toString()))
        val job = MediaClient(allowInsecureHttpForTests = true).execute(provider(), "key", ModelInfo(id = "image-model", type = "image"), "{\"prompt\":\"a cat\"}", out)
        assertEquals("complete", job.status)
        assertTrue(job.filePath.startsWith(out.canonicalPath))
        assertTrue(job.filePath.endsWith(".png"))
        assertEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).toList(), java.io.File(job.filePath).readBytes().toList())
        assertTrue(!job.resultJson.contains(encoded))
        assertTrue(!JSONObject(job.resultJson).has("images"))
        val request = server.takeRequest()
        assertEquals("/api/v1/image/generate", request.requestUrl?.encodedPath)
        assertEquals("image-model", JSONObject(request.body.readUtf8()).getString("model"))
    }

    @Test fun upscaleUsesDocumentedBodyWithoutModel() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-result"))
        val job = MediaClient(allowInsecureHttpForTests = true).execute(
            provider(), "key", ModelInfo(id = "upscaler", type = "upscale"),
            "{\"image\":\"aW1hZ2U=\",\"scale\":2,\"creativity\":0.01}", out
        )
        assertEquals("complete", job.status)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(!body.has("model"))
        assertEquals(2, body.getInt("scale"))
        assertEquals(0.01, body.getDouble("creativity"), 0.0001)
    }

    @Test fun embeddingVectorsAndPlainTextAreNotWrittenAsMediaFiles() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}],\"request_id\":\"YQ==\",\"message\":\"ok\"}"
        ))
        val job = MediaClient(allowInsecureHttpForTests = true).execute(
            provider(), "key", ModelInfo(id = "embed-model", type = "embedding"), "{\"input\":\"hello\"}", out
        )
        assertEquals("complete", job.status)
        assertEquals("", job.filePath)
        assertEquals(0, out.listFiles().orEmpty().size)
    }

    @Test fun largeEmbeddingJsonIsSavedWholeAndJobResultStaysValid() = runBlocking {
        val vector = org.json.JSONArray()
        repeat(5000) { vector.put(it.toDouble() / 1000.0) }
        val responseBody = JSONObject().put("data", org.json.JSONArray().put(JSONObject().put("embedding", vector))).put("model", "embed-model").toString()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(responseBody))
        val job = MediaClient(allowInsecureHttpForTests = true).execute(
            provider(), "key", ModelInfo(id = "embed-model", type = "embedding"), "{\"input\":\"hello\"}", out
        )
        assertEquals("complete", job.status)
        assertTrue(job.filePath.endsWith(".json"))
        val overview = JSONObject(job.resultJson)
        assertTrue(overview.getBoolean("truncated"))
        assertEquals(responseBody, java.io.File(job.filePath).readText())
    }

    @Test fun decisionUsesStateAndQuestionsSchema() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            "{\"decisions\":{\"is_urgent\":true}}"
        ))
        val job = MediaClient(allowInsecureHttpForTests = true).execute(
            provider(), "key", ModelInfo(id = "decision-model", type = "decision"),
            "{\"state\":\"Please handle this now\",\"questions\":{\"is_urgent\":{\"type\":\"noul\",\"instructions\":\"Does this message convey urgency?\"}}}", out
        )
        assertEquals("complete", job.status)
        val request = server.takeRequest()
        assertEquals("/api/v1/decisions", request.requestUrl?.encodedPath)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("Please handle this now", body.getString("state"))
        assertTrue(body.getJSONObject("questions").has("is_urgent"))
    }

    @Test fun queueActionSendsOnceAndRetrieveKeepsQueueId() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"queue_id\":\"q-1\",\"status\":\"QUEUED\"}"))
        server.enqueue(MockResponse().setBody("{\"status\":\"PROCESSING\"}"))
        val client = MediaClient(allowInsecureHttpForTests = true)
        val job = client.execute(provider(), "key", ModelInfo(id = "video-model", type = "video"), "{\"_action\":\"queue\",\"duration\":\"1s\"}", out)
        assertEquals("q-1", job.queueId)
        assertEquals("queued", job.status)
        val polled = client.retrieve(provider(), "key", job, out)
        assertEquals("pending", polled.status)
        assertEquals("q-1", polled.queueId)
        assertEquals("/api/v1/video/queue", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/api/v1/video/retrieve", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test fun binaryRetrieveMarksPreviousProcessingJobComplete() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"queue_id\":\"q-bin\",\"status\":\"QUEUED\"}"))
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/flac").setBody("fLaC-complete-media"))
        val client = MediaClient(allowInsecureHttpForTests = true)
        val queued = client.execute(
            provider(), "key", ModelInfo(id = "music-model", type = "music"),
            "{\"_action\":\"queue\",\"duration_seconds\":1}", out
        )
        val completed = client.retrieve(provider(), "key", queued, out)
        assertEquals("complete", completed.status)
        assertTrue(completed.filePath.endsWith(".flac"))
        assertEquals("COMPLETED", JSONObject(completed.resultJson).getString("status"))
    }

    @Test fun musicQuoteFiltersGenerationFieldsAndDerivesCharacterCount() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"quote_id\":\"quote-1\",\"total\":0.01}"))
        val client = MediaClient(allowInsecureHttpForTests = true)
        val job = client.execute(
            provider(), "key", ModelInfo(id = "music-model", type = "music"),
            "{\"_action\":\"quote\",\"duration_seconds\":12,\"prompt\":\"three words\",\"private_field\":\"drop\"}", out
        )
        assertEquals("quoted", job.status)
        val request = server.takeRequest()
        assertEquals("/api/v1/audio/quote", request.requestUrl?.encodedPath)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("music-model", body.getString("model"))
        assertEquals(12, body.getInt("duration_seconds"))
        assertEquals("three words".length, body.getInt("character_count"))
        assertTrue(!body.has("prompt"))
        assertTrue(!body.has("private_field"))
    }

    @Test fun retrievePreservesQueuedDownloadUrlAndDoesNotForwardKey() = runBlocking {
        val downloadUrl = server.url("/signed/video").newBuilder().host("127.0.0.1").build().toString()
        server.enqueue(MockResponse().setBody("{\"queue_id\":\"q-2\",\"status\":\"QUEUED\",\"download_url\":\"$downloadUrl\"}"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"status\":\"COMPLETED\"}"))
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("video-bytes"))
        val client = MediaClient(allowInsecureHttpForTests = true)
        val queued = client.execute(provider(), "secret-key", ModelInfo(id = "video-model", type = "video"), "{\"_action\":\"queue\",\"duration\":\"1s\"}", out)
        val completed = client.retrieve(provider(), "secret-key", queued, out)
        assertEquals("complete", completed.status)
        assertTrue(completed.filePath.endsWith(".mp4"))
        server.takeRequest()
        server.takeRequest()
        val downloadRequest = server.takeRequest()
        assertEquals("/signed/video", downloadRequest.requestUrl?.encodedPath)
        assertEquals(null, downloadRequest.getHeader("Authorization"))
    }
}
