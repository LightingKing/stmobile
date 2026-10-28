package app.pockettavern.provider

import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ModelCatalog
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class ProviderClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) } }
    @After fun tearDown() { server.shutdown() }

    private fun provider(kind: String = "venice"): ProviderConfig {
        val url = server.url("/api/v1").newBuilder().host("127.0.0.1").build()
        assertEquals("MockWebServer URL must use HTTP for test injection", "http", url.scheme)
        assertEquals("MockWebServer URL must use loopback host", "127.0.0.1", url.host)
        return ProviderConfig(kind = kind, baseUrl = url.toString())
    }

    @Test fun veniceModelsUseAllAndPreserveMetadata() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"data":[{"id":"venice-text","name":"Venice Text","type":"text","context_tokens":65536,"capabilities":{"tools":true,"vision":true},"custom":"kept"}]}
        """.trimIndent()))
        val models = ProviderClient(allowInsecureHttpForTests = true).models(provider(), "test-key")
        assertEquals("venice-text", models.single().id)
        assertEquals(65536, models.single().contextTokens)
        assertTrue(models.single().supportsTools)
        assertTrue(models.single().rawJson.contains("custom"))
        assertEquals("all", server.takeRequest().requestUrl?.queryParameter("type"))
    }

    @Test fun veniceNestedModelSpecMapsCapabilitiesAndOffline() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"data":[{"id":"nested-id","type":"text","model_spec":{"name":"Nested Name","availableContextTokens":131072,"offline":true,"capabilities":{"supportsFunctionCalling":true,"supportsVision":true,"supportsReasoning":true},"private_field":"retained"}}]}
        """.trimIndent()))
        val model = ProviderClient(allowInsecureHttpForTests = true).models(provider(), "key").single()
        assertEquals("Nested Name", model.name)
        assertEquals(131072, model.contextTokens)
        assertTrue(model.supportsTools)
        assertTrue(model.supportsVision)
        assertTrue(model.supportsReasoning)
        assertTrue(model.offline)
        assertTrue(model.rawJson.contains("private_field"))
    }

    @Test fun modelPricesRemainDisplayableAfterDirectoryFetchAndStateReload() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"data":[{"id":"priced-model","type":"text","model_spec":{"name":"Priced Model","pricing":{"input":{"usd":0.4},"cache_input":{"usd":0.0001},"output":{"usd":0}}}}]}
        """.trimIndent()))
        val config = provider()
        val models = ProviderClient(allowInsecureHttpForTests = true).models(config, "key")
        // The mock HTTP transport is test-only; persisted provider settings require HTTPS.
        val stored = app.pockettavern.domain.AppData(providers = listOf(config.copy(baseUrl = "https://api.example.test/v1", models = models)))
        val reloaded = app.pockettavern.data.DataCodec.decode(app.pockettavern.data.DataCodec.encode(stored))
        val cachedModel = reloaded.providers.single().models.single()
        assertEquals(models.single().rawJson, cachedModel.rawJson)
        val prices = ModelPricing.describe(cachedModel, config.kind)
        assertEquals(3, prices.lines.size)
        assertTrue(prices.lines.any { it.contains("0.0001") })
        assertTrue(prices.lines.any { it.contains("0.4") })
        assertEquals(ModelPricing.describe(models.single(), config.kind), prices)
    }

    @Test fun fragmentedOpenAiSseNormalizesReasoningAndToolArguments() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("""
                data: {"choices":[{"delta":{"reasoning_content":"think "}}]}

                data: {"choices":[{"delta":{"content":"hello","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\"q\":"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"","type":"","function":{"arguments":"\"x\"}"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":null,"function":{}}]}}]}

                data: [DONE]

            """.trimIndent()))
        val deltas = mutableListOf<String>()
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "test-key", "model", "[]", Preset(systemPrompt = "sys"), "[]") { deltas += it.content + it.reasoning }
        assertEquals("hello", result.content)
        assertEquals("think ", result.reasoning)
        val call = JSONArray(result.toolCallsJson).getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("lookup", call.getJSONObject("function").getString("name"))
        assertEquals("{\"q\":\"x\"}", call.getJSONObject("function").getString("arguments"))
        assertEquals("think hello", deltas.joinToString(""))
        val request = server.takeRequest()
        assertEquals("/api/v1/chat/completions", request.requestUrl?.encodedPath)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals(0, JSONObject(request.body.readUtf8()).getJSONArray("messages").length())
    }

    @Test fun writingServerMetadataIsScopedToWritingServer() = runBlocking {
        repeat(2) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"))
        }
        val metadata = mapOf("pocket_tavern_chat_id" to "chat-42", "pocket_tavern_purpose" to "chat")
        ProviderClient(allowInsecureHttpForTests = true).chat(
            provider("writing-server"), "gateway-key", "pocket-writer", "[]", Preset(),
            metadata = metadata
        )
        val writingPayload = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("chat-42", writingPayload.getJSONObject("metadata").getString("pocket_tavern_chat_id"))
        assertEquals("chat", writingPayload.getJSONObject("metadata").getString("pocket_tavern_purpose"))

        ProviderClient(allowInsecureHttpForTests = true).chat(
            provider("openai"), "openai-key", "model", "[]", Preset(), metadata = metadata
        )
        val ordinaryPayload = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(!ordinaryPayload.has("metadata"))
    }

    @Test fun missingToolIdGetsStableGeneratedId() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"lookup","arguments":"{}"}}]}}]}

            data: [DONE]

        """.trimIndent()))
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "model", "[]", Preset())
        assertEquals("call_0", JSONArray(result.toolCallsJson).getJSONObject(0).getString("id"))
    }

    @Test fun anthropicSignatureDeltaIsNotPresentedAsReasoning() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"visible"}}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"answer"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed-only"}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()))
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider("anthropic"), "key", "model", "[]", Preset())
        assertEquals("visible", result.reasoning)
        assertEquals("answer", result.content)
    }

    @Test fun jsonCompletionFallbackIsAccepted() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"choices\":[{\"message\":{\"content\":\"fallback\"}}]}"))
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "model", "[]", Preset())
        assertEquals("fallback", result.content)
    }

    @Test fun emptySseDoneIsRejectedInsteadOfCompletedAsBlank() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()))
        try {
            ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "model", "[]", Preset())
            throw AssertionError("expected an empty response to be rejected")
        } catch (error: ProviderException) {
            assertTrue(error.message.orEmpty().isNotBlank())
        }
    }

    @Test fun emptyJsonWithoutChoicesIsRejectedInsteadOfCompletedAsBlank() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
        try {
            ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "model", "[]", Preset())
            throw AssertionError("expected a JSON response without choices to be rejected")
        } catch (error: ProviderException) {
            assertTrue(error.message.orEmpty().isNotBlank())
        }
    }

    @Test fun nullJsonContentIsNotRenderedAsLiteralNull() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("{\"choices\":[{\"message\":{\"content\":null}}]}"))
        try {
            val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "model", "[]", Preset())
            assertTrue("literal null must never become assistant text", result.content != "null")
            throw AssertionError("expected null content without tools to be rejected")
        } catch (error: ProviderException) {
            assertTrue(error.message.orEmpty().isNotBlank())
        }
    }

    @Test fun refusalDeltaIsPreservedAsUserVisibleText() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"choices":[{"delta":{"refusal":"请求被模型拒绝"}}]}

            data: [DONE]

        """.trimIndent()))
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "model", "[]", Preset())
        assertTrue(result.content.contains("请求被模型拒绝"))
    }

    @Test fun openAiTextBlockArrayIsFlattenedToText() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"choices":[{"delta":{"content":[{"type":"text","text":"hello "},{"type":"output_text","text":"world"}]}}]}

            data: [DONE]

        """.trimIndent()))
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "model", "[]", Preset())
        assertEquals("hello world", result.content)
    }

    @Test fun emptyFilteredSseIsRejectedInsteadOfCompletedAsBlank() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"id":"filtered-response","choices":[{"delta":{},"finish_reason":"content_filter"}],"usage":{"prompt_tokens":50249,"completion_tokens":1,"total_tokens":50250}}

            data: [DONE]

        """.trimIndent()))
        val logs = mutableListOf<String>()
        try {
            ProviderClient(allowInsecureHttpForTests = true, onDiagnostic = { logs += it })
                .chat(provider(), "key", "model", "[]", Preset())
            throw AssertionError("expected a filtered empty response to be rejected")
        } catch (error: ProviderException) {
            assertEquals("CONTENT_FILTER", error.kind.name)
            assertTrue(error.message.orEmpty().contains("过滤"))
            assertEquals("content_filter", error.finishReason)
            assertEquals("", error.partial?.content)
        }
        val finished = logs.map(::JSONObject).single { it.optString("event") == "finished" }
        assertEquals("content_filter", finished.getString("outcome"))
        assertEquals("filtered-response", finished.getString("response_id"))
        assertTrue(finished.getBoolean("done_signal"))
        assertEquals(50249, finished.getJSONObject("usage").getInt("prompt_tokens"))
        assertEquals(1, server.requestCount)
    }

    @Test fun partialFilteredSseKeepsReceivedTextWithoutReportingSuccess() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setHeader("x-request-id", "filtered-request").setBody("""
                data: {"choices":[{"delta":{"content":"partial text"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"content_filter"}]}

                data: [DONE]

            """.trimIndent()))
        try {
            ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "model", "[]", Preset())
            fail("filtered partial reply must not be reported as complete")
        } catch (error: ProviderException) {
            assertEquals("CONTENT_FILTER", error.kind.name)
            assertEquals("partial text", error.partial?.content)
            assertEquals("filtered-request", error.requestId)
            assertEquals("content_filter", error.finishReason)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun filteredJsonWithToolsAndReasoningIsNotReturnedForExecution() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""
            {"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":"partial reasoning","tool_calls":[{"id":"call-1","type":"function","function":{"name":"write_file","arguments":"{}"}}]},"finish_reason":"content_filter"}]}
        """.trimIndent()))
        try {
            ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "model", "[]", Preset())
            fail("filtered tool call must not be returned for execution")
        } catch (error: ProviderException) {
            assertEquals("CONTENT_FILTER", error.kind.name)
            assertEquals("partial reasoning", error.partial?.reasoning)
            assertEquals("content_filter", error.finishReason)
        }
    }

    @Test fun messageSnapshotsAreSupportedWithoutDuplicatingFullText() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"choices":[{"message":{"content":"hello"}}]}

            data: {"choices":[{"message":{"content":"hello world"},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()))
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "model", "[]", Preset())
        assertEquals("hello world", result.content)
    }

    @Test fun doneSignalStopsParsingTrailingEvents() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"choices":[{"delta":{"content":"before"}}]}

            data: [DONE]

            data: {"choices":[{"delta":{"content":"after"}}]}

        """.trimIndent()))
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "model", "[]", Preset())
        assertEquals("before", result.content)
    }

    @Test fun diagnosticsKeepNumericUsageAndNeverLogKeyOrBody() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setHeader("x-request-id", "header-request")
            .setBody("""
                data: {"id":"chatcmpl-id","choices":[{"delta":{"content":"visible body should never be logged"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8,"cost":"unknown","costs":{"usd":0.12,"note":"unknown"},"completion_tokens_details":{"reasoning_tokens":2},"prompt_tokens_details":{"cached_tokens":1}}}

                data: [DONE]

            """.trimIndent()))
        val logs = mutableListOf<String>()
        val secret = "sk-secret-123456789"
        ProviderClient(allowInsecureHttpForTests = true, onDiagnostic = { logs += it })
            .chat(provider(), secret, "model", "[{\"role\":\"user\",\"content\":\"private prompt\"}]", Preset())
        assertTrue(logs.any { JSONObject(it).optString("event") == "started" })
        assertTrue(logs.any { JSONObject(it).optString("event") == "headers" })
        assertTrue(logs.any { JSONObject(it).optString("event") == "first_delta" })
        val finished = logs.map(::JSONObject).single { it.optString("event") == "finished" }
        assertEquals("chatcmpl-id", finished.getString("response_id"))
        assertEquals("header-request", finished.getString("request_id"))
        assertEquals(3, finished.getJSONObject("usage").getInt("prompt_tokens"))
        assertEquals(0.12, finished.getJSONObject("usage").getJSONObject("costs").getDouble("usd"), 0.0001)
        assertTrue(!finished.getJSONObject("usage").toString().contains("unknown"))
        val all = logs.joinToString("\n")
        assertTrue(!all.contains(secret))
        assertTrue(!all.contains("visible body should never be logged"))
        assertTrue(!all.contains("private prompt"))
    }

    @Test fun readTimeoutIsClassifiedAndEmitsFinishedTimeoutEvent() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"late\"}}]}\n\n")
            .setBodyDelay(500, TimeUnit.MILLISECONDS))
        val logs = mutableListOf<String>()
        try {
            ProviderClient(allowInsecureHttpForTests = true, onDiagnostic = { logs += it }, timeoutMillis = 100)
                .chat(provider(), "key", "model", "[]", Preset())
            fail("expected timeout")
        } catch (error: ProviderException) {
            assertEquals(ProviderException.Kind.TIMEOUT, error.kind)
        }
        val finished = logs.map(::JSONObject).single { it.optString("event") == "finished" }
        assertEquals("timeout", finished.getString("outcome"))
        assertEquals(100, finished.getLong("timeout_ms"))
        assertEquals(100, finished.getLong("read_timeout_ms"))
        assertTrue(finished.getLong("connect_timeout_ms") > 0)
        assertTrue(finished.getLong("write_timeout_ms") > 0)
        assertEquals("SocketTimeoutException", finished.getString("error_type"))
    }

    @Test fun callTimeoutStopsAStreamThatKeepsTheConnectionOpen() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")
            .setBodyDelay(500, TimeUnit.MILLISECONDS))
        val logs = mutableListOf<String>()
        try {
            ProviderClient(
                allowInsecureHttpForTests = true,
                onDiagnostic = { logs += it },
                timeoutMillis = 5_000,
                callTimeoutMillis = 100
            ).chat(provider(), "key", "model", "[]", Preset())
            fail("expected hard call timeout")
        } catch (error: ProviderException) {
            assertEquals(ProviderException.Kind.TIMEOUT, error.kind)
        }
        val finished = logs.map(::JSONObject).single { it.optString("event") == "finished" }
        assertEquals("timeout", finished.getString("outcome"))
        assertEquals(100, finished.getLong("call_timeout_ms"))
    }

    @Test fun networkFailuresRecordOnlyTheExceptionTypeForBackgroundDiagnosis() = runBlocking {
        val unreachable = provider()
        server.shutdown()
        val logs = mutableListOf<String>()
        try {
            ProviderClient(allowInsecureHttpForTests = true, onDiagnostic = { logs += it })
                .chat(unreachable, "sk-secret-123456789", "model", "[]", Preset())
            fail("expected network failure")
        } catch (error: ProviderException) {
            assertEquals(ProviderException.Kind.NETWORK, error.kind)
        }
        val finished = logs.map(::JSONObject).single { it.optString("event") == "finished" }
        assertEquals("error", finished.getString("outcome"))
        assertEquals("ConnectException", finished.getString("error_type"))
        assertTrue(logs.map(::JSONObject).filter { it.optString("event") != "finished" }.none { it.has("error_type") })
    }

    @Test fun completedStreamHasNoErrorType() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
        val logs = mutableListOf<String>()
        ProviderClient(allowInsecureHttpForTests = true, onDiagnostic = { logs += it }).chat(provider(), "key", "model", "[]", Preset())
        val finished = logs.map(::JSONObject).single { it.optString("event") == "finished" }
        assertEquals("complete", finished.getString("outcome"))
        assertTrue(!finished.has("error_type"))
    }

    @Test fun cancellingAfterFirstDeltaKeepsPartialAndIsNotReportedAsNetworkError() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setChunkedBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n" +
                    "data: [DONE]\n\n",
                1
            )
            .throttleBody(1, 100, TimeUnit.MILLISECONDS))
        val logs = mutableListOf<String>()
        lateinit var client: ProviderClient
        var cancelled = false
        client = ProviderClient(allowInsecureHttpForTests = true, onDiagnostic = { logs += it })
        try {
            client.chat(provider(), "key", "model", "[]", Preset()) {
                if (!cancelled) {
                    cancelled = true
                    client.cancel()
                }
            }
            fail("expected cancellation")
        } catch (error: ProviderException) {
            assertEquals(ProviderException.Kind.CANCELLED, error.kind)
            assertEquals("partial", error.partial?.content)
        }
        val finished = logs.map(::JSONObject).single { it.optString("event") == "finished" }
        assertEquals("cancelled", finished.getString("outcome"))
        assertTrue(finished.getInt("content_chars") > 0)
    }

    @Test fun whitespaceSseChunksArePreserved() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"choices":[{"delta":{"content":"hello"}}]}

            data: {"choices":[{"delta":{"content":" "}}]}

            data: {"choices":[{"delta":{"content":"\n"}}]}

            data: {"choices":[{"delta":{"content":"world"}}]}

            data: [DONE]

        """.trimIndent()))
        val result = ProviderClient(allowInsecureHttpForTests = true)
            .chat(provider(), "key", "model", "[]", Preset())
        assertEquals("hello \nworld", result.content)
    }

    @Test fun truncatedSseWithoutDoneIsReported() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"))
        try {
            ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "model", "[]", Preset())
            throw AssertionError("expected truncated stream")
        } catch (e: ProviderException) {
            assertEquals(ProviderException.Kind.NETWORK, e.kind)
            assertTrue(e.message.orEmpty().contains("提前结束"))
        }
    }

    @Test fun anthropicModelsFollowAfterIdPagination() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"m1\"}],\"has_more\":true,\"last_id\":\"cursor-1\"}"))
        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"m2\"}],\"has_more\":false}"))
        val models = ProviderClient(allowInsecureHttpForTests = true).models(provider("anthropic"), "key")
        assertEquals(listOf("m1", "m2"), models.map { it.id })
        server.takeRequest()
        assertEquals("cursor-1", server.takeRequest().requestUrl?.queryParameter("after_id"))
    }

    @Test fun anthropicResourceTypeIsAvailableInChatPicker() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"data":[{"id":"claude-fixture","type":"model","display_name":"Claude Fixture"}]}
        """.trimIndent()))
        val config = provider("anthropic")
        val models = ProviderClient(allowInsecureHttpForTests = true).models(config, "key")
        assertEquals("text", models.single().type)
        assertEquals("Claude Fixture", models.single().name)
        assertEquals(listOf("claude-fixture"), ModelCatalog.filter(listOf(config.copy(models = models)), type = "text", providerId = config.id).map { it.second.id })
    }

    @Test fun compatibleGatewayResourceTypeKeepsExplicitMediaOutOfChat() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"data":[
              {"id":"gateway-chat","type":"model"},
              {"id":"nested-image","type":"model","model_spec":{"type":"image"}},
              {"id":"embedding","type":"embedding"},
              {"id":"unknown","type":"vendor-special"},
              {"id":"offline","type":"model","offline":true}
            ]}
        """.trimIndent()))
        val config = provider("openai")
        val models = ProviderClient(allowInsecureHttpForTests = true).models(config, "key")
        val selectable = ModelCatalog.filter(listOf(config.copy(models = models)), type = "text", providerId = config.id).filter { !it.second.offline }
        assertEquals(listOf("gateway-chat"), selectable.map { it.second.id })
        assertEquals(listOf("text", "image", "embedding", "vendor-special", "text"), models.map { it.type })
        assertEquals("model", JSONObject(models.first().rawJson).getString("type"))
    }

    @Test fun anthropicUsesMessagesHeadersAndContentBlocks() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            event: message_start
            data: {"type":"message_start","message":{"id":"m"}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

            data: [DONE]

        """.trimIndent()))
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider("anthropic"), "anthropic-key", "claude", "[{\"role\":\"system\",\"content\":\"engine rules\"},{\"role\":\"system\",\"content\":\"more rules\"},{\"role\":\"user\",\"content\":\"hi\"}]", Preset(systemPrompt = "ignored because engine already supplied it"), "[]") { }
        assertEquals("ok", result.content)
        val request = server.takeRequest()
        assertEquals("anthropic-key", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("engine rules\n\nmore rules", body.getString("system"))
        assertEquals("hi", body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun anthropicNormalizesMultimodalReplayAndKeepsOnlySignedThinking() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"))

        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "look at this"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:image/png;base64,QUJD")))
            .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
                .put("format", "wav").put("data", "UklGRg==")))
        val assistantContent = JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "unsigned secret"))
            .put(JSONObject().put("type", "thinking").put("thinking", "signed thought").put("signature", "sig-1"))
            .put(JSONObject().put("type", "text").put("text", "use the tool"))
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "first rule"))
            .put(JSONObject().put("role", "system").put("content", "second rule"))
            .put(JSONObject().put("role", "user").put("content", userContent))
            .put(JSONObject().put("role", "assistant").put("content", assistantContent))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", "tool result"))

        ProviderClient(allowInsecureHttpForTests = true).chat(
            provider("anthropic"), "anthropic-key", "claude", messages.toString(), Preset(), "[]"
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("first rule\n\nsecond rule", body.getString("system"))
        val requestMessages = body.getJSONArray("messages")
        assertEquals(3, requestMessages.length())

        val userBlocks = requestMessages.getJSONObject(0).getJSONArray("content")
        assertEquals("text", userBlocks.getJSONObject(0).getString("type"))
        val image = userBlocks.getJSONObject(1)
        assertEquals("image", image.getString("type"))
        assertEquals("base64", image.getJSONObject("source").getString("type"))
        assertEquals("image/png", image.getJSONObject("source").getString("media_type"))
        assertEquals("QUJD", image.getJSONObject("source").getString("data"))
        assertEquals("text", userBlocks.getJSONObject(2).getString("type"))
        assertTrue(userBlocks.getJSONObject(2).getString("text").contains("音频附件"))

        val assistantBlocks = requestMessages.getJSONObject(1).getJSONArray("content")
        assertEquals(2, assistantBlocks.length())
        assertEquals("thinking", assistantBlocks.getJSONObject(0).getString("type"))
        assertEquals("signed thought", assistantBlocks.getJSONObject(0).getString("thinking"))
        assertEquals("sig-1", assistantBlocks.getJSONObject(0).getString("signature"))
        assertEquals("text", assistantBlocks.getJSONObject(1).getString("type"))
        assertTrue(!assistantBlocks.toString().contains("unsigned secret"))

        val resultMessage = requestMessages.getJSONObject(2)
        assertEquals("user", resultMessage.getString("role"))
        val resultBlock = resultMessage.getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", resultBlock.getString("type"))
        assertEquals("call-1", resultBlock.getString("tool_use_id"))
        assertEquals("tool result", resultBlock.getString("content"))
    }

    @Test fun engineToolReplayUsesWireFieldsAndDropsInternalMetadata() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"))
        val messages = "[{\"role\":\"assistant\",\"content\":\"\",\"message_id\":\"internal\",\"reasoning_content\":\"think\",\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"call-1\",\"content\":\"done\"}]"
        ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "key", "model", messages, Preset())
        val requestBody = JSONObject(server.takeRequest().body.readUtf8())
        val assistant = requestBody.getJSONArray("messages").getJSONObject(0)
        assertTrue(assistant.has("tool_calls"))
        assertTrue(assistant.has("reasoning_content"))
        assertTrue(!assistant.has("message_id"))
        assertEquals("call-1", requestBody.getJSONArray("messages").getJSONObject(1).getString("tool_call_id"))
    }

    @Test fun insecureNonLocalhostAndHttpErrorDoNotLeakKey() = runBlocking {
        val invalid = ProviderConfig(baseUrl = "http://example.com/api/v1")
        try {
            ProviderClient(allowInsecureHttpForTests = true).models(invalid, "secret")
            throw AssertionError("expected invalid URL")
        } catch (e: ProviderException) {
            assertEquals(ProviderException.Kind.INVALID_URL, e.kind)
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid api_key=secret"))
        try {
            ProviderClient(allowInsecureHttpForTests = true).models(provider(), "secret")
            throw AssertionError("expected auth error")
        } catch (e: ProviderException) {
            assertEquals(ProviderException.Kind.AUTH, e.kind)
            assertTrue(!e.message.orEmpty().contains("secret"))
        }
    }
}
