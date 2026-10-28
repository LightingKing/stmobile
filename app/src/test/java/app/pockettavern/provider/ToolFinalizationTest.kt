package app.pockettavern.provider

import app.pockettavern.domain.*
import app.pockettavern.engine.KnowledgeVault
import app.pockettavern.engine.ToolTurnPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class ToolFinalizationTest {
    private lateinit var server: MockWebServer

    @Before fun setup() { server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) } }
    @After fun teardown() { server.shutdown() }

    private fun provider(kind: String) = ProviderConfig(kind = kind,
        baseUrl = server.url("/v1").newBuilder().host("127.0.0.1").build().toString())

    private fun history(): String = """[
        {"role":"system","content":"回答问题。"},
        {"role":"user","content":"钥匙在哪里？"},
        {"role":"assistant","content":"先核对来源。","reasoning_content":"核对已有记录。","tool_calls":[{"id":"lookup-1","type":"function","function":{"name":"history_search","arguments":"{\"query\":\"钥匙\"}"}}]},
        {"role":"tool","tool_call_id":"lookup-1","content":"钥匙已归还给周行。"}
    ]"""

    private fun answer(text: String = "钥匙在周行手里。") = server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)).put("finish_reason", "stop"))).toString()))

    @Test fun forcedOpenAiAndVeniceFinalRequestsDisableToolsButKeepAllPriorToolEvidence() = runBlocking {
        for (kind in listOf("openai-compatible", "venice")) {
            answer()
            val policy = ToolTurnPolicy()
            val input = policy.finalMessages(history())
            val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider(kind), "fixture-key", "model", input,
                Preset(), KnowledgeVault.definitions, finalAnswerOnly = true)
            assertEquals("钥匙在周行手里。", result.content)
            assertEquals("[]", result.toolCallsJson)
            val payload = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("none", payload.getString("tool_choice"))
            assertFalse(payload.has("tools"))
            val messages = payload.getJSONArray("messages")
            assertEquals(5, messages.length())
            assertEquals(policy.finalInstruction, messages.getJSONObject(1).getString("content"))
            assertEquals("assistant", messages.getJSONObject(3).getString("role"))
            assertEquals("lookup-1", messages.getJSONObject(3).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertEquals("tool", messages.getJSONObject(4).getString("role"))
            assertEquals("lookup-1", messages.getJSONObject(4).getString("tool_call_id"))
            assertEquals("钥匙已归还给周行。", messages.getJSONObject(4).getString("content"))
        }
    }

    @Test fun forcedAnthropicFinalOmitsUnsupportedChoiceFieldsAndPreservesToolUseResultPair() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"content":[{"type":"text","text":"钥匙在周行手里。"}],"stop_reason":"end_turn"}"""))
        val policy = ToolTurnPolicy()
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider("anthropic"), "fixture-key", "model",
            policy.finalMessages(history()), Preset(), KnowledgeVault.definitions, finalAnswerOnly = true)
        assertEquals("钥匙在周行手里。", result.content)
        val payload = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(payload.has("tools"))
        assertFalse(payload.has("tool_choice"))
        assertTrue(payload.getString("system").contains(policy.finalInstruction))
        val messages = payload.getJSONArray("messages")
        assertEquals(3, messages.length())
        val assistantBlocks = messages.getJSONObject(1).getJSONArray("content")
        val use = (0 until assistantBlocks.length()).map(assistantBlocks::getJSONObject).single { it.getString("type") == "tool_use" }
        assertEquals("lookup-1", use.getString("id"))
        val toolResult = messages.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", toolResult.getString("type"))
        assertEquals("lookup-1", toolResult.getString("tool_use_id"))
        assertEquals("钥匙已归还给周行。", toolResult.getString("content"))
    }

    @Test fun ordinaryToolEnabledRequestKeepsExistingProtocolDefaults() = runBlocking {
        answer()
        ProviderClient(allowInsecureHttpForTests = true).chat(provider("openai-compatible"), "fixture-key", "model", history(), Preset(), KnowledgeVault.definitions) { }
        val payload = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(2, payload.getJSONArray("tools").length())
        assertFalse(payload.has("tool_choice"))
    }

    @Test fun finalAnswerStreamsThroughTheExistingTrailingLambda() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"choices":[{"delta":{"content":"钥匙在"}}]}

            data: {"choices":[{"delta":{"content":"周行手里。"},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()))
        val chunks = mutableListOf<String>()
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider("venice"), "fixture-key", "model",
            ToolTurnPolicy().finalMessages(history()), Preset(), KnowledgeVault.definitions, finalAnswerOnly = true) { chunks += it.content }
        assertEquals("钥匙在周行手里。", result.content)
        assertEquals(result.content, chunks.joinToString(""))
        assertEquals("none", JSONObject(server.takeRequest().body.readUtf8()).getString("tool_choice"))
    }

    @Test fun oneAllowedToolRoundStillGetsAnExtraFinalProseRequest() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{
            "choices":[{"message":{"content":"","tool_calls":[{"id":"lookup-1","type":"function","function":{"name":"history_search","arguments":"{\"query\":\"钥匙\"}"}}]},"finish_reason":"tool_calls"}]
        }"""))
        answer()
        val client = ProviderClient(allowInsecureHttpForTests = true)
        val policy = ToolTurnPolicy()
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "钥匙在哪里？"))
        assertFalse(policy.finalAnswerRequired(0, 1))
        val first = client.chat(provider("venice"), "fixture-key", "model", messages.toString(), Preset(), KnowledgeVault.definitions)
        val calls = JSONArray(first.toolCallsJson)
        val call = calls.getJSONObject(0)
        val arguments = call.getJSONObject("function").getString("arguments")
        assertTrue(policy.beforeCall("history_search", arguments).execute)
        val chat = Chat(messages = listOf(Message(id = "source", role = "assistant", content = "林岚把钥匙交还给周行。"), Message(content = "钥匙在哪里？")))
        val evidence = KnowledgeVault.toolResponse(chat, "history_search", arguments, 1200)
        policy.recordResult("history_search", arguments, evidence)
        policy.completedRound(listOf("history_search"))
        messages.put(JSONObject().put("role", "assistant").put("content", first.content).put("tool_calls", calls))
            .put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", evidence))
        assertTrue(policy.finalAnswerRequired(1, 1))
        val final = client.chat(provider("venice"), "fixture-key", "model", policy.finalMessages(messages.toString()), Preset(), KnowledgeVault.definitions, finalAnswerOnly = true)
        assertTrue(final.content.isNotBlank())
        assertEquals("[]", final.toolCallsJson)
        assertEquals(2, server.requestCount)
        assertTrue(JSONObject(server.takeRequest().body.readUtf8()).has("tools"))
        val second = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("none", second.getString("tool_choice"))
        assertTrue(second.getJSONArray("messages").toString().contains("钥匙交还给周行"))
    }

    @Test fun providerIgnoringFinalRestrictionIsReturnedToCallerWithoutAutomaticExecutionOrRetry() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{
            "choices":[{"message":{"content":"","tool_calls":[{"id":"unwanted","type":"function","function":{"name":"history_read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]
        }"""))
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider("venice"), "fixture-key", "model",
            ToolTurnPolicy().finalMessages(history()), Preset(), KnowledgeVault.definitions, finalAnswerOnly = true)
        assertEquals("unwanted", JSONArray(result.toolCallsJson).getJSONObject(0).getString("id"))
        assertEquals(1, server.requestCount)
        assertEquals("none", JSONObject(server.takeRequest().body.readUtf8()).getString("tool_choice"))
    }
}
