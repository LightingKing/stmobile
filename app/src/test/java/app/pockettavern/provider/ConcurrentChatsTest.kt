package app.pockettavern.provider

import app.pockettavern.domain.CompletionResult
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Several chats generate at once: every chat task has its own client on one shared connection
 * pool, exactly as the controller builds them. Their requests overlap, and stopping one chat
 * leaves the other's reply untouched.
 */
class ConcurrentChatsTest {
    private lateinit var server: MockWebServer
    private val shared = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) } }
    @After fun tearDown() { server.shutdown() }

    private fun provider(): ProviderConfig =
        ProviderConfig(kind = "venice", baseUrl = server.url("/api/v1").newBuilder().host("127.0.0.1").build().toString())

    private fun chatClient() = ProviderClient(client = shared, allowInsecureHttpForTests = true)

    private fun sse(text: String): String {
        val delta = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text)))).toString()
        return "data: $delta\n\ndata: [DONE]\n\n"
    }

    private fun messages(text: String) = JSONArray().put(JSONObject().put("role", "user").put("content", text)).toString()

    /** Each request is answered with the name its own message asked for. */
    private fun echo(request: RecordedRequest): String =
        JSONObject(request.body.readUtf8()).getJSONArray("messages").getJSONObject(0).getString("content")

    @Test fun twoChatsAreAnsweredAtTheSameTime() = runBlocking {
        // Neither request is answered until both have arrived: a client that ran them one after
        // the other would leave the first waiting here until the latch gives up.
        val bothArrived = CountDownLatch(2)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val name = echo(request)
                bothArrived.countDown()
                val overlapped = bothArrived.await(10, TimeUnit.SECONDS)
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(if (overlapped) "给$name" + "的回复" else "没有同时到达"))
            }
        }
        val first = async(Dispatchers.Default) { chatClient().chat(provider(), "key", "model", messages("黑猫"), Preset()) }
        val second = async(Dispatchers.Default) { chatClient().chat(provider(), "key", "model", messages("大肥鱼"), Preset()) }
        assertEquals("给黑猫的回复", first.await().content)
        assertEquals("给大肥鱼的回复", second.await().content)
    }

    @Test fun stoppingOneChatLeavesTheOtherReplying() = runBlocking {
        val stopped = CountDownLatch(1)
        val testOver = CountDownLatch(1)
        val bothArrived = CountDownLatch(2)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val name = echo(request)
                bothArrived.countDown()
                // The chat that keeps going is answered only after the other one was stopped;
                // the stopped one would only ever be answered once the test is over.
                (if (name == "继续") stopped else testOver).await(10, TimeUnit.SECONDS)
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(if (name == "继续") "没有被打断" else "太晚了"))
            }
        }
        val stopping = chatClient()
        val keeping = chatClient()
        val halted = async(Dispatchers.Default) { runCatching { stopping.chat(provider(), "key", "model", messages("停止"), Preset()) } }
        val kept = async(Dispatchers.Default) { keeping.chat(provider(), "key", "model", messages("继续"), Preset()) }
        assertTrue(bothArrived.await(10, TimeUnit.SECONDS))
        stopping.cancel()
        stopped.countDown()
        val failure = halted.await().exceptionOrNull()
        assertTrue("the stopped chat ends as cancelled, not as a reply", failure is ProviderException && failure.kind == ProviderException.Kind.CANCELLED)
        val reply: CompletionResult = try { kept.await() } finally { testOver.countDown() }
        assertEquals("没有被打断", reply.content)
    }

    @Test fun oneClientStillHandlesOneRequestAtATime() = runBlocking {
        // Why each chat needs its own client: a second request on the same one is refused.
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(10, TimeUnit.SECONDS)
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse("第一条"))
            }
        }
        val client = chatClient()
        val first = async(Dispatchers.Default) { client.chat(provider(), "key", "model", messages("一"), Preset()) }
        assertTrue(arrived.await(10, TimeUnit.SECONDS))
        try {
            client.chat(provider(), "key", "model", messages("二"), Preset())
            fail("a second request on the same client must be refused")
        } catch (error: ProviderException) {
            assertEquals(ProviderException.Kind.CANCELLED, error.kind)
            assertTrue(error.message.orEmpty().contains("已有请求正在执行"))
        } finally { release.countDown() }
        assertEquals("第一条", first.await().content)
    }
}
