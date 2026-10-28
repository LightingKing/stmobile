package app.pockettavern.provider

import app.pockettavern.domain.CompletionResult
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.StreamDelta
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/** Local fixtures for the reasoning shapes OpenAI-compatible providers (Venice, Gemini, OpenRouter) emit. */
class ReasoningShapesTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) } }
    @After fun tearDown() { server.shutdown() }

    private fun provider(): ProviderConfig =
        ProviderConfig(kind = "venice", baseUrl = server.url("/api/v1").newBuilder().host("127.0.0.1").build().toString())

    private fun sse(vararg events: String): String = events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun delta(content: String): String = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", content)))).toString()

    /** Returns the final result and what the chat bubble showed while streaming. */
    private fun run(body: String, contentType: String = "text/event-stream", messages: String = "[]"): Pair<CompletionResult, StreamDelta> = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", contentType).setBody(body))
        var ui = StreamDelta()
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "gemini-4-argon", messages, Preset()) { ui = it.appendTo(ui) }
        result to ui
    }

    private fun assertBody(expectedContent: String, expectedReasoning: String, run: Pair<CompletionResult, StreamDelta>) {
        assertEquals("final content", expectedContent, run.first.content)
        assertEquals("final reasoning", expectedReasoning, run.first.reasoning)
        assertEquals("streamed bubble content", expectedContent, run.second.content)
        assertEquals("streamed reasoning", expectedReasoning, run.second.reasoning)
    }

    @Test fun separateReasoningContentDeltasStayOutOfBody() {
        assertBody("正文", "先想一下", run(sse(
            """{"choices":[{"delta":{"reasoning_content":"先想"}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"一下"}}]}""",
            """{"choices":[{"delta":{"content":"正文"},"finish_reason":"stop"}]}"""
        )))
    }

    @Test fun veniceReasoningFieldStaysOutOfBody() {
        assertBody("正文", "思考", run(sse(
            """{"choices":[{"delta":{"reasoning":"思考"}}]}""",
            """{"choices":[{"delta":{"content":"正文"},"finish_reason":"stop"}]}"""
        )))
    }

    @Test fun reasoningDetailsAreFoldedWithoutDuplicatingPlainReasoning() {
        assertBody("答复", "计划再想", run(sse(
            """{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"计划"}]}}]}""",
            """{"choices":[{"delta":{"reasoning":"再想","reasoning_details":[{"type":"reasoning.text","text":"再想"}]}}]}""",
            """{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.encrypted","data":"opaque"}]}}]}""",
            """{"choices":[{"delta":{"content":"答复"},"finish_reason":"stop"}]}"""
        )))
    }

    @Test fun inlineThinkTagsSplitAcrossChunksMoveToReasoning() {
        assertBody("她转身离开。", "草稿：她会转身", run(sse(
            delta("<thi"), delta("nk>草稿：她会"), delta("转身</th"), delta("ink>\n\n她转身离开。")
        )))
    }

    @Test fun geminiThoughtAndThinkingTagsAfterLeadingWhitespaceMoveToReasoning() {
        assertBody("Answer", "plan", run(sse(delta("\n<thought>plan</thought>"), delta("Answer"))))
        assertBody("答", "想", run(sse(delta("<thinking>想</thinking>答"))))
    }

    @Test fun thoughtFlaggedContentPartsGoToReasoning() {
        assertBody("正文", "内部推理再想r", run(sse(
            """{"choices":[{"delta":{"content":[{"type":"text","text":"内部推理","thought":true},{"type":"thinking","thinking":"再想"},{"type":"reasoning","text":"r"},{"type":"text","text":"正文"}]}}]}"""
        )))
    }

    @Test fun finalMessageSnapshotAfterDeltasDoesNotDuplicate() {
        assertBody("你好 世界", "", run(sse(
            delta("你好"), delta(" 世界"),
            """{"choices":[{"message":{"content":"你好 世界"},"finish_reason":"stop"}]}"""
        )))
    }

    @Test fun finalSnapshotRepeatingStreamedReasoningDoesNotDuplicateIt() {
        assertBody("答", "想好了", run(sse(
            """{"choices":[{"delta":{"reasoning_content":"想好"}}]}""",
            """{"choices":[{"message":{"reasoning_content":"想好了","content":"答"},"finish_reason":"stop"}]}"""
        )))
    }

    @Test fun divergentSnapshotReplacesStreamedTextInsteadOfAppending() {
        assertBody("Hi world", "", run(sse(
            delta("Hello wor"),
            """{"choices":[{"message":{"content":"Hi world"},"finish_reason":"stop"}]}"""
        )))
    }

    @Test fun snapshotWithInlineThinkAfterStreamedThinkDoesNotDuplicate() {
        assertBody("Hello world", "t", run(sse(
            delta("<think>t</think>"), delta("Hello"),
            """{"choices":[{"message":{"content":"<think>t</think>Hello world"},"finish_reason":"stop"}]}"""
        )))
    }

    @Test fun midReplyAngleTagsStayInBody() {
        assertBody("她低声说：<think>是暗号</think>", "", run(sse(delta("她低声说："), delta("<think>是暗号</think>"))))
    }

    @Test fun unclosedThinkAtEndIsReasoningNotBody() {
        try {
            run(sse(delta("<think>只想到"), delta("一半")))
            fail("reasoning-only replies must not complete as a visible answer")
        } catch (error: ProviderException) {
            assertEquals(ProviderException.Kind.EMPTY_RESPONSE, error.kind)
            assertEquals("", error.partial?.content)
            assertEquals("只想到一半", error.partial?.reasoning)
        }
    }

    @Test fun incompleteTagPrefixWithoutTagStaysInBody() {
        assertBody("<thi", "", run(sse(delta("<thi"))))
    }

    @Test fun nonStreamingInlineThinkAndReasoningDetailsStayOutOfBody() {
        val (result, _) = run(
            """{"choices":[{"message":{"content":"<think>plan</think>答","reasoning_details":[{"type":"reasoning.summary","summary":"总结"}]},"finish_reason":"stop"}]}""",
            contentType = "application/json"
        )
        assertEquals("答", result.content)
        assertTrue(result.reasoning, result.reasoning.contains("总结") && result.reasoning.contains("plan"))
    }

    @Test fun priorReasoningIsNeverResentAsAssistantContent() {
        run(sse(delta("ok")), messages = """[{"role":"assistant","content":"正文","reasoning_content":"思考"},{"role":"user","content":"继续"}]""")
        val sent = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(0)
        assertEquals("正文", sent.getString("content"))
    }
}
