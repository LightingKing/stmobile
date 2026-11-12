package app.pockettavern.provider

import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * Node servers, the PC writing server among them, close an idle connection after 5 seconds.
 * OkHttp only re-checks a pooled connection after 10 seconds idle, and the app never retries a
 * paid request, so a message sent 5–10 s after the previous reply failed with "网络连接失败".
 * Found in the 0.2.3 group-chat check: every other quick message failed.
 */
class StaleConnectionTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) } }
    @After fun tearDown() { server.shutdown() }

    private fun provider() = ProviderConfig(kind = "venice", baseUrl = server.url("/api/v1").newBuilder().host("127.0.0.1").build().toString())

    private fun reply(text: String): MockResponse {
        val delta = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text)))).toString()
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $delta\n\ndata: [DONE]\n\n")
    }

    @Test fun aConnectionTheServerClosedIsNotReusedForTheNextMessage() = runBlocking {
        // The server hangs up right after the first reply, as an idle Node server does.
        server.enqueue(reply("第一条").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        server.enqueue(reply("第二条"))
        val client = ProviderClient(allowInsecureHttpForTests = true)
        assertEquals("第一条", client.chat(provider(), "key", "model", "[]", Preset()).content)
        Thread.sleep(300)
        assertEquals("第二条", client.chat(provider(), "key", "model", "[]", Preset()).content)
        assertEquals("each message on a connection of its own", 2, server.requestCount)
    }
}
