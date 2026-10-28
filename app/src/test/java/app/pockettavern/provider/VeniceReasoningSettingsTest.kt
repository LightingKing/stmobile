package app.pockettavern.provider

import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
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

class VeniceReasoningSettingsTest {
    private lateinit var server: MockWebServer
    private val target = "deepseek-v4-1-flash-1017"

    @Before fun setup() { server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) } }
    @After fun teardown() { server.shutdown() }

    private fun model(id: String = target, max: Int = 8192, default: String = "high"): ModelInfo {
        val caps = JSONObject().put("supportsReasoningEffort", true)
            .put("reasoningEffortOptions", JSONArray(listOf("none", "low", "high", "max")))
            .put("defaultReasoningEffort", default)
        return ModelInfo(id = id, supportsTools = true, supportsReasoning = true,
            rawJson = JSONObject().put("model_spec", JSONObject().put("capabilities", caps).put("maxCompletionTokens", max)).toString())
    }

    private fun provider(kind: String = "venice", models: List<ModelInfo> = listOf(model())) =
        ProviderConfig(kind = kind, baseUrl = server.url("/api/v1").newBuilder().host("127.0.0.1").build().toString(), models = models)

    private fun answer() { server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"choices":[{"message":{"content":"林岚仍在港口。"},"finish_reason":"stop"}]}""")) }

    @Test fun explicitThinkingUsesVeniceBudgetAndKeepsHistoricalReasoningForToolReplay() = runBlocking {
        answer()
        val logs = mutableListOf<String>()
        val history = """[
            {"role":"assistant","content":"上一轮正文","reasoning_content":"上一轮推理"},
            {"role":"user","content":"查一下钥匙的位置。"},
            {"role":"assistant","content":"","reasoning_content":"本轮工具推理","tool_calls":[{"id":"lookup-1","type":"function","function":{"name":"history_search","arguments":"{}"}}]},
            {"role":"tool","tool_call_id":"lookup-1","content":"钥匙在周行手里。"}
        ]"""
        val tools = """[{"type":"function","function":{"name":"history_search","parameters":{"type":"object"}}}]"""
        ProviderClient(allowInsecureHttpForTests = true, onDiagnostic = { logs += it }).chat(provider(), "test-only-key", target,
            history, Preset(maxOutput = 4096, temperature = 0.9, reasoningEffort = "high", includeVeniceSystemPrompt = false, generationBudgetEnabled = true), tools)
        val payload = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(4096, payload.getInt("max_completion_tokens"))
        assertFalse(payload.has("max_tokens"))
        assertFalse(payload.has("temperature"))
        assertEquals("high", payload.getString("reasoning_effort"))
        val venice = payload.getJSONObject("venice_parameters")
        assertFalse(venice.getBoolean("include_venice_system_prompt"))
        assertFalse(venice.getBoolean("disable_thinking"))
        assertFalse(venice.getBoolean("strip_thinking_response"))
        assertTrue(payload.getJSONObject("stream_options").getBoolean("include_usage"))
        assertEquals("上一轮推理", payload.getJSONArray("messages").getJSONObject(0).getString("reasoning_content"))
        assertEquals("本轮工具推理", payload.getJSONArray("messages").getJSONObject(2).getString("reasoning_content"))
        val terminal = JSONObject(logs.last())
        assertEquals("max_completion_tokens", terminal.getString("output_budget_field"))
        assertEquals("high", terminal.getString("requested_reasoning_effort"))
        assertEquals("high", terminal.getString("default_reasoning_effort"))
        assertFalse(terminal.getBoolean("temperature_sent"))
        assertEquals(1, terminal.getInt("request_tool_count"))
        assertFalse(terminal.getBoolean("venice_system_prompt_included"))
        assertFalse(logs.joinToString().contains("上一轮推理"))
        assertFalse(logs.joinToString().contains("test-only-key"))
    }

    @Test fun platformDefaultIsNotOverriddenAndKnownThinkingDefaultOmitsTemperature() = runBlocking {
        answer()
        ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", target, "[]", Preset())
        val payload = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(payload.has("max_tokens"))
        assertFalse(payload.has("max_completion_tokens"))
        assertFalse(payload.has("reasoning_effort"))
        assertFalse(payload.has("temperature"))
        assertFalse(payload.getJSONObject("venice_parameters").has("disable_thinking"))
        assertTrue(payload.getJSONObject("venice_parameters").getBoolean("include_venice_system_prompt"))
        assertFalse(payload.getJSONObject("venice_parameters").getBoolean("strip_thinking_response"))
    }

    @Test fun noneDisablesThinkingAndRetainsSamplingTemperature() = runBlocking {
        answer()
        ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", target, "[]",
            Preset(reasoningEffort = "none", temperature = 1.1))
        val payload = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("none", payload.getString("reasoning_effort"))
        assertTrue(payload.getJSONObject("venice_parameters").getBoolean("disable_thinking"))
        assertEquals(1.1, payload.getDouble("temperature"), 0.0)
    }

    @Test fun unknownOrNoneDefaultAndOtherVeniceModelsKeepTemperature() = runBlocking {
        val cases = listOf(
            provider(models = emptyList()) to target,
            provider(models = listOf(model(default = "none"))) to target,
            provider(models = listOf(model(id = "another-model"))) to "another-model"
        )
        for ((configured, id) in cases) {
            answer()
            ProviderClient(allowInsecureHttpForTests = true).chat(configured, "key", id, "[]", Preset(temperature = 0.7))
            val payload = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(0.7, payload.getDouble("temperature"), 0.0)
        }
    }

    @Test fun nonVeniceOpenAiRequestsKeepTheirOriginalFieldsEvenForTheSameModelName() = runBlocking {
        answer()
        ProviderClient(allowInsecureHttpForTests = true).chat(provider("openai", emptyList()), "key", target, "[]",
            Preset(maxOutput = 4096, temperature = 0.6, reasoningEffort = "high", includeVeniceSystemPrompt = false, generationBudgetEnabled = true))
        val payload = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(4096, payload.getInt("max_tokens"))
        assertEquals(0.6, payload.getDouble("temperature"), 0.0)
        assertFalse(payload.has("max_completion_tokens"))
        assertFalse(payload.has("venice_parameters"))
        assertFalse(payload.has("reasoning_effort"))
    }

    @Test fun nonVeniceAnthropicRequestsRetainTheirOriginalBudgetProtocol() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"content":[{"type":"text","text":"已确认。"}],"stop_reason":"end_turn"}"""))
        ProviderClient(allowInsecureHttpForTests = true).chat(provider("anthropic", emptyList()), "key", "model", "[]",
            Preset(maxOutput = 4096, reasoningEffort = "none", includeVeniceSystemPrompt = false, generationBudgetEnabled = true))
        val payload = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(4096, payload.getInt("max_tokens"))
        assertFalse(payload.has("max_completion_tokens"))
        assertFalse(payload.has("venice_parameters"))
        assertFalse(payload.has("reasoning_effort"))
    }

    @Test fun unsupportedEffortMissingDirectoryAndExcessBudgetFailBeforeAnyHttpRequest() = runBlocking {
        val attempts = listOf(
            provider() to Preset(reasoningEffort = "medium"),
            provider(models = emptyList()) to Preset(reasoningEffort = "none"),
            provider(models = listOf(ModelInfo(id = target, supportsReasoning = true))) to Preset(reasoningEffort = "high"),
            provider() to Preset(maxOutput = 8193, generationBudgetEnabled = true)
        )
        for ((configured, preset) in attempts) {
            val failure = runCatching {
                ProviderClient(allowInsecureHttpForTests = true).chat(configured, "key", target, "[]", preset)
            }.exceptionOrNull()
            assertTrue(failure is ProviderException)
            assertEquals(ProviderException.Kind.UNSUPPORTED, (failure as ProviderException).kind)
            assertTrue(failure.message.orEmpty().contains("尚未发送请求"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun advertisedMaximumIsAllowedWithoutSilentBudgetChanges() = runBlocking {
        answer()
        ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", target, "[]", Preset(maxOutput = 8192, generationBudgetEnabled = true))
        assertEquals(8192, JSONObject(server.takeRequest().body.readUtf8()).getInt("max_completion_tokens"))
    }

    @Test fun visibleOutputAndReasoningReserveUseOneVeniceServerBudgetAndAreLoggedSeparately() = runBlocking {
        answer()
        val logs = mutableListOf<String>()
        ProviderClient(allowInsecureHttpForTests = true, onDiagnostic = { logs += it }).chat(
            provider(), "key", target, "[]",
            Preset(maxOutput = 4096, reasoningBudget = 2048, reasoningEffort = "high", generationBudgetEnabled = true)
        )
        val payload = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(6144, payload.getInt("max_completion_tokens"))
        val terminal = JSONObject(logs.last())
        assertEquals(4096, terminal.getInt("visible_max_output_tokens"))
        assertEquals(2048, terminal.getInt("reasoning_budget_tokens"))
        assertEquals(6144, terminal.getInt("max_output_tokens"))
    }

    @Test fun noneDoesNotAddAReasoningReserveToVeniceBudget() = runBlocking {
        answer()
        ProviderClient(allowInsecureHttpForTests = true).chat(
            provider(), "key", target, "[]",
            Preset(maxOutput = 4096, reasoningBudget = 2048, reasoningEffort = "none", generationBudgetEnabled = true)
        )
        assertEquals(4096, JSONObject(server.takeRequest().body.readUtf8()).getInt("max_completion_tokens"))
    }

    @Test fun disabledVeniceBudgetIgnoresSavedNumbersEvenAboveTheCatalogMaximum() = runBlocking {
        answer()
        ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", target, "[]",
            Preset(maxOutput = 100_000, reasoningBudget = 100_000, reasoningEffort = "high", generationBudgetEnabled = false))
        val payload = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(payload.has("max_tokens"))
        assertFalse(payload.has("max_completion_tokens"))
        assertEquals("high", payload.getString("reasoning_effort"))
        assertFalse(payload.getJSONObject("venice_parameters").getBoolean("disable_thinking"))
    }

    @Test fun openAiBudgetIsAbsentByDefaultAndCanBeEnabledThenDisabledAgain() = runBlocking {
        val saved = Preset(maxOutput = 4096, reasoningBudget = 2048)
        assertFalse(saved.generationBudgetEnabled)
        for (enabled in listOf(false, true, false)) {
            answer()
            ProviderClient(allowInsecureHttpForTests = true).chat(provider("openai", emptyList()), "key", target, "[]",
                saved.copy(generationBudgetEnabled = enabled))
            val payload = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(payload.has("max_completion_tokens"))
            if (enabled) assertEquals(4096, payload.getInt("max_tokens")) else assertFalse(payload.has("max_tokens"))
        }
        assertEquals(4096, saved.maxOutput)
        assertEquals(2048, saved.reasoningBudget)
    }

    @Test fun anthropicStillSendsItsRequiredMaxTokensWhenOptionalBudgetIsDisabled() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"content":[{"type":"text","text":"已确认。"}],"stop_reason":"end_turn"}"""))
        ProviderClient(allowInsecureHttpForTests = true).chat(provider("anthropic", emptyList()), "key", "model", "[]",
            Preset(maxOutput = 4096, generationBudgetEnabled = false))
        val payload = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(4096, payload.getInt("max_tokens"))
        assertFalse(payload.has("max_completion_tokens"))
    }

    @Test fun capabilityParserRequiresExplicitBooleanSupportAndKnownOrderedOptions() {
        val raw = """{"model_spec":{"maxCompletionTokens":131072,"capabilities":{"supportsReasoningEffort":true,"reasoningEffortOptions":["none","future","high","none","max",3],"defaultReasoningEffort":"high"}}}"""
        val parsed = VeniceModelCapabilities.from(ModelInfo(rawJson = raw))
        assertEquals(listOf("none", "high", "max"), parsed.reasoningEffortOptions)
        assertEquals("high", parsed.defaultReasoningEffort)
        assertEquals(131072, parsed.maxCompletionTokens ?: 0)
        for (invalid in listOf("{}", "not-json", raw.replace("true", "\"true\""))) {
            assertTrue(VeniceModelCapabilities.from(ModelInfo(rawJson = invalid)).reasoningEffortOptions.isEmpty())
            assertNull(VeniceModelCapabilities.from(ModelInfo(rawJson = invalid)).defaultReasoningEffort)
        }
        assertEquals(VeniceModelCapabilities(), VeniceModelCapabilities.from(null))
        assertNull(VeniceModelCapabilities.from(ModelInfo(rawJson = raw.replace("131072", "1.5"))).maxCompletionTokens)
        assertNull(VeniceModelCapabilities.from(ModelInfo(rawJson = raw.replace("\"defaultReasoningEffort\":\"high\"", "\"defaultReasoningEffort\":\"low\""))).defaultReasoningEffort)
    }
}
