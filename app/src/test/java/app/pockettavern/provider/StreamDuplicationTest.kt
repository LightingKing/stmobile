package app.pockettavern.provider

import app.pockettavern.domain.CompletionResult
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.StreamDelta
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/** Local fixtures for "one large passage shows up two or three times in a single reply". */
class StreamDuplicationTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) } }
    @After fun tearDown() { server.shutdown() }

    private val passage = "雪从庄园的屋檐上滑落，顾砚舟把温度计的读数抄进本子，又抬头看了一眼还在发灰的天。".repeat(8)

    private fun provider(): ProviderConfig =
        ProviderConfig(kind = "venice", baseUrl = server.url("/api/v1").newBuilder().host("127.0.0.1").build().toString())

    private fun sse(vararg events: String): String = events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun delta(content: String, index: Int? = null): String {
        val choice = JSONObject().put("delta", JSONObject().put("content", content))
        if (index != null) choice.put("index", index)
        return JSONObject().put("choices", JSONArray().put(choice)).toString()
    }

    private fun snapshot(content: String): String = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", content)).put("finish_reason", "stop"))).toString()

    private fun run(body: String): Pair<CompletionResult, StreamDelta> = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        var ui = StreamDelta()
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "deepseek-v4-1-flash", "[]", Preset()) { ui = it.appendTo(ui) }
        result to ui
    }

    private fun copies(text: String): Int = Regex(Regex.escape(passage)).findAll(text).count()

    private fun assertOneCopy(run: Pair<CompletionResult, StreamDelta>) {
        assertEquals("saved reply", 1, copies(run.first.content))
        assertEquals("streaming bubble", 1, copies(run.second.content))
        assertEquals(run.first.content, run.second.content)
    }

    @Test fun largePassageInDeltasThenIdenticalSnapshotStaysOneCopy() {
        val thirds = passage.chunked(passage.length / 3 + 1)
        assertOneCopy(run(sse(*thirds.map { delta(it) }.toTypedArray(), snapshot(passage))))
    }

    @Test fun snapshotWithoutLeadingWhitespaceReplacesInsteadOfAddingASecondCopy() {
        assertOneCopy(run(sse(delta("\n\n"), delta(passage), snapshot(passage))))
    }

    @Test fun snapshotThatIsAPrefixOfStreamedTextIsIgnored() {
        assertOneCopy(run(sse(delta(passage), delta("后来"), snapshot(passage))))
    }

    @Test fun inlineThinkingFollowedByFullSnapshotKeepsOneCopy() {
        val result = run(sse(delta("<think>先写雪</think>"), delta(passage), snapshot("<think>先写雪</think>$passage")))
        assertOneCopy(result)
        assertEquals("先写雪", result.first.reasoning)
    }

    @Test fun chunksForOtherChoicesAreNotAppendedToTheVisibleReply() {
        val result = run(sse(
            delta(passage, index = 0),
            delta(passage, index = 1),
            JSONObject().put("choices", JSONArray()
                .put(JSONObject().put("index", 1).put("delta", JSONObject().put("content", passage)))
                .put(JSONObject().put("index", 0).put("delta", JSONObject().put("content", "。")).put("finish_reason", "stop"))).toString()
        ))
        assertOneCopy(result)
        assertEquals("$passage。", result.first.content)
        assertEquals("stop", result.first.finishReason)
    }
}
