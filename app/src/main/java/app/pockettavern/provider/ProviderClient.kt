package app.pockettavern.provider

import app.pockettavern.domain.CompletionResult
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ModelTypes
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.StreamDelta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private fun newProviderHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/** A small, protocol-neutral error type used by the UI to display actionable failures. */
class ProviderException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
    val finishReason: String = "",
    val requestId: String = "",
    val partial: CompletionResult? = null
) : IOException(message, cause) {
    enum class Kind { INVALID_URL, HTTP, IN_BAND, AUTH, RATE_LIMIT, BILLING, CONTEXT, NETWORK, TIMEOUT, CANCELLED, CONTENT_FILTER, EMPTY_RESPONSE, UNSUPPORTED }
}

/** HTTP transport for the first-party and OpenAI-compatible provider protocols. */
class ProviderClient(
    client: OkHttpClient = newProviderHttpClient(),
    private val allowInsecureHttpForTests: Boolean = false,
    private val onDiagnostic: (String) -> Unit = {},
    private val timeoutMillis: Long = 0L,
    private val callTimeoutMillis: Long = 0L
) {
    init { require(timeoutMillis >= 0) { "timeoutMillis must not be negative" } }
    init { require(callTimeoutMillis >= 0) { "callTimeoutMillis must not be negative" } }
    private val client: OkHttpClient = client.newBuilder()
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        // Chat waits for the provider's end signal or an explicit cancellation.
        // Thinking can be long or silent; neither elapsed time nor received
        // tokens may discard an already paid generation. Compact opts in to
        // its own finite timeouts through the constructor.
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        // Every request on a fresh connection. Node servers (the PC writing server too) close an idle
        // connection after 5 s, OkHttp re-checks a pooled one only after 10 s, and a paid request is
        // never retried: reusing one failed messages sent 5-10 s after the previous reply. The extra
        // handshake is small next to a model's reply time.
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    // Catalog retrieval is independent from waiting for model generation.
    private val modelsClient = this.client.newBuilder()
        .readTimeout(timeoutMillis.takeIf { it > 0 } ?: 120_000L, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMillis.takeIf { it > 0 } ?: 180_000L, TimeUnit.MILLISECONDS)
        .build()
    private val activeCall = AtomicReference<Call?>(null)
    /** Kept separate from activeCall: OkHttp may report a cancelled socket after the reference is cleared. */
    private val cancelRequested = AtomicBoolean(false)
    private val connectTimeoutMillis = this.client.connectTimeoutMillis.toLong()
    private val writeTimeoutMillis = this.client.writeTimeoutMillis.toLong()

    suspend fun models(provider: ProviderConfig, key: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        cancelRequested.set(false)
        val base = checkedBaseUrl(provider)
        val kind = provider.kind.lowercase(Locale.ROOT)
        val all = ArrayList<ModelInfo>()
        var after: String? = null
        var page = 0
        do {
            val url = base.newBuilder().addPathSegment("models").apply {
                if (kind == "venice") addQueryParameter("type", "all")
                if (!after.isNullOrBlank()) addQueryParameter("after_id", after)
            }.build()
            val request = Request.Builder().url(url).applyHeaders(kind, key).get().build()
            val response = execute(request, modelsClient)
            try {
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw httpError(response.code, body, key)
                val parsed = parseModelsPage(body, kind)
                all += parsed.models
                after = if (parsed.hasMore) parsed.afterId else null
            } finally {
                response.close()
                activeCall.set(null)
                cancelRequested.set(false)
            }
            page++
        } while (!after.isNullOrBlank() && page < MAX_MODEL_PAGES)
        all
    }

    suspend fun chat(
        provider: ProviderConfig,
        key: String,
        modelId: String,
        messagesJson: String,
        preset: Preset,
        toolsJson: String = "[]",
        metadata: Map<String, String> = emptyMap(),
        finalAnswerOnly: Boolean = false,
        onDelta: (StreamDelta) -> Unit = {}
    ): CompletionResult = withContext(Dispatchers.IO) {
        cancelRequested.set(false)
        if (modelId.isBlank()) throw ProviderException(ProviderException.Kind.UNSUPPORTED, "未选择模型")
        val base = checkedBaseUrl(provider)
        val kind = provider.kind.lowercase(Locale.ROOT)
        val inputMessages = parseArray(messagesJson, "messages")
        val veniceCapabilities = if (kind == "venice") VeniceModelCapabilities.from(provider.models.firstOrNull { it.id == modelId }) else null
        val budgetSent = preset.generationBudgetEnabled || kind == "anthropic"
        val reasoningBudget = if (budgetSent && kind == "venice" && preset.reasoningEffort != "none") preset.reasoningBudget else 0
        val totalOutputBudget = if (!budgetSent) 0L else if (kind == "venice") veniceCapabilities!!.totalCompletionBudget(preset) else preset.maxOutput.toLong()
        if (budgetSent && totalOutputBudget !in 1..Int.MAX_VALUE.toLong()) {
            throw ProviderException(ProviderException.Kind.UNSUPPORTED,
                "生成总预算超出可用范围，请降低正文上限或思考预留；尚未发送请求")
        }
        val trace = DiagnosticTrace(
            provider,
            base,
            modelId,
            kind,
            if (budgetSent) preset.maxOutput else 0,
            reasoningBudget,
            totalOutputBudget.toInt(),
            inputMessages.length(),
            messagesJson.length,
            timeoutMillis,
            connectTimeoutMillis,
            writeTimeoutMillis,
            callTimeoutMillis,
            key,
            onDiagnostic
        )
        trace.requestedSettings(preset, veniceCapabilities)
        trace.emit("started", "request")
        var outcome = "error"
        var completed: CompletionResult? = null
        try {
            val availableTools = if (finalAnswerOnly) "[]" else toolsJson
            val payload = if (kind == "anthropic") {
                // Prior tool_use/tool_result blocks stay paired in the input;
                // omitting tools is the compatible way to end further tool use.
                anthropicPayload(modelId, inputMessages, preset, availableTools)
            } else {
                openAiPayload(modelId, inputMessages, preset, availableTools).also {
                    if (finalAnswerOnly) it.put("tool_choice", "none")
                    if (kind == "writing-server" && metadata.isNotEmpty()) {
                        it.put("metadata", JSONObject().apply {
                            metadata.forEach { (name, value) -> put(name, value) }
                        })
                    }
                    if (kind == "venice") {
                        applyVeniceSettings(it, modelId, preset, veniceCapabilities!!)
                        it.put("stream_options", JSONObject().put("include_usage", true))
                    }
                }
            }
            trace.payloadSettings(payload)
            val endpoint = if (kind == "anthropic") "messages" else "chat/completions"
            val request = Request.Builder()
                .url(base.newBuilder().addPathSegments(endpoint).build())
                .applyHeaders(kind, key)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            val response = execute(request)
            trace.headers(response.code, response.header("x-request-id"))
            try {
                if (!response.isSuccessful) {
                    val errorBody = readBody(response)
                    throw httpError(response.code, errorBody, key)
                }
                val contentType = response.header("Content-Type").orEmpty()
                completed = if (contentType.contains("application/json", ignoreCase = true)) {
                    val bodySource = response.body?.source()
                        ?: throw ProviderException(ProviderException.Kind.NETWORK, "响应没有内容")
                    val hasBodyByte = bodySource.request(1)
                    if (hasBodyByte) trace.firstByte()
                    val body = bodySource.readUtf8()
                    trace.jsonDecoded(body)
                    parseNonStreaming(kind, body, response.header("x-request-id"), key)
                } else {
                    val source = response.body?.source() ?: throw ProviderException(ProviderException.Kind.NETWORK, "响应没有内容")
                    val stream = SseAccumulator(kind, onDelta, key) { trace.firstDelta() }
                    try {
                        if (source.request(1)) trace.firstByte()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            trace.firstByte()
                            if (stream.acceptLine(line)) break
                        }
                        if (cancelRequested.get()) {
                            val partial = stream.result(response.header("x-request-id"))
                            throw ProviderException(
                                ProviderException.Kind.CANCELLED,
                                "请求已取消",
                                finishReason = partial.finishReason,
                                requestId = partial.requestId,
                                partial = partial
                            )
                        }
                        stream.finish()
                        stream.result(response.header("x-request-id"))
                    } catch (error: ProviderException) {
                        val partial = stream.result(response.header("x-request-id"))
                        throw ProviderException(
                            error.kind,
                            error.message.orEmpty(),
                            error.cause,
                            finishReason = error.finishReason.ifBlank { partial.finishReason },
                            requestId = error.requestId.ifBlank { partial.requestId },
                            // Rebuild from the accumulator so a header request ID is retained even
                            // when finish() or an in-band error created its own partial result.
                            partial = partial
                        )
                    } catch (error: SocketTimeoutException) {
                        val partial = stream.result(response.header("x-request-id"))
                        throw ProviderException(
                            ProviderException.Kind.TIMEOUT,
                            "请求超时",
                            error,
                            finishReason = partial.finishReason,
                            requestId = partial.requestId,
                            partial = partial
                        )
                    } catch (error: IOException) {
                        val partial = stream.result(response.header("x-request-id"))
                        val cancelled = cancelRequested.get() || error.message.orEmpty().contains("Canceled", ignoreCase = true)
                        val timedOut = !cancelled && error is InterruptedIOException && error.message.orEmpty().contains("timeout", ignoreCase = true)
                        throw ProviderException(
                            when {
                                cancelled -> ProviderException.Kind.CANCELLED
                                timedOut -> ProviderException.Kind.TIMEOUT
                                else -> ProviderException.Kind.NETWORK
                            },
                            when {
                                cancelled -> "请求已取消"
                                timedOut -> "请求超时"
                                else -> "网络连接失败"
                            },
                            error,
                            finishReason = partial.finishReason,
                            requestId = partial.requestId,
                            partial = partial
                        )
                    } finally {
                        trace.streamMetrics(stream)
                    }
                }
            } finally {
                response.close()
            }
            trace.result(completed!!)
            completed = validateResult(completed!!)
            outcome = "complete"
            completed!!
        } catch (error: CancellationException) {
            outcome = "cancelled"
            throw error
        } catch (error: ProviderException) {
            outcome = when (error.kind) {
                ProviderException.Kind.CONTENT_FILTER -> "content_filter"
                ProviderException.Kind.EMPTY_RESPONSE -> "empty_response"
                ProviderException.Kind.TIMEOUT -> "timeout"
                ProviderException.Kind.CANCELLED -> "cancelled"
                else -> if (error.message.orEmpty().contains("提前结束")) "incomplete_stream" else "error"
            }
            trace.failure(error)
            throw if (error.requestId.isBlank() && trace.currentRequestId().isNotBlank()) {
                ProviderException(error.kind, error.message.orEmpty(), error,
                    finishReason=error.finishReason, requestId=trace.currentRequestId(), partial=error.partial)
            } else error
        } catch (error: SocketTimeoutException) {
            outcome = "timeout"
            val wrapped = ProviderException(ProviderException.Kind.TIMEOUT, "请求超时", error, requestId = trace.currentRequestId())
            trace.failure(wrapped)
            throw wrapped
        } catch (error: IOException) {
            val cancelled = cancelRequested.get() || error.message.orEmpty().contains("Canceled", ignoreCase = true)
            val timedOut = !cancelled && error is InterruptedIOException && error.message.orEmpty().contains("timeout", ignoreCase = true)
            outcome = when {
                cancelled -> "cancelled"
                timedOut -> "timeout"
                else -> "error"
            }
            val wrapped = ProviderException(
                when {
                    cancelled -> ProviderException.Kind.CANCELLED
                    timedOut -> ProviderException.Kind.TIMEOUT
                    else -> ProviderException.Kind.NETWORK
                },
                when {
                    cancelled -> "请求已取消"
                    timedOut -> "请求超时"
                    else -> "网络连接失败"
                },
                error,
                requestId = trace.currentRequestId()
            )
            trace.failure(wrapped)
            throw wrapped
        } finally {
            activeCall.set(null)
            cancelRequested.set(false)
            trace.emitFinished(outcome, completed)
        }
    }

    fun cancel() {
        cancelRequested.set(true)
        activeCall.get()?.cancel()
    }

    private fun execute(request: Request, transport: OkHttpClient = client) : okhttp3.Response {
        val call = transport.newCall(request)
        if (!activeCall.compareAndSet(null, call)) {
            call.cancel()
            throw ProviderException(ProviderException.Kind.CANCELLED, "已有请求正在执行")
        }
        return try {
            call.execute()
        } catch (e: SocketTimeoutException) {
            activeCall.compareAndSet(call, null)
            throw ProviderException(ProviderException.Kind.TIMEOUT, "请求超时", e)
        } catch (e: IOException) {
            activeCall.compareAndSet(call, null)
            if (call.isCanceled() || cancelRequested.get()) throw ProviderException(ProviderException.Kind.CANCELLED, "请求已取消", e)
            if (e is InterruptedIOException && e.message.orEmpty().contains("timeout", ignoreCase = true)) {
                throw ProviderException(ProviderException.Kind.TIMEOUT, "请求超时", e)
            }
            throw ProviderException(ProviderException.Kind.NETWORK, "网络连接失败", e)
        }
    }

    private fun checkedBaseUrl(provider: ProviderConfig): HttpUrl {
        return try {
            ProviderUrlPolicy.parse(provider, allowInsecureHttpForTests)
        } catch (error: IllegalArgumentException) {
            throw ProviderException(ProviderException.Kind.INVALID_URL, error.message ?: "API 地址无效")
        }
    }

    private fun Request.Builder.applyHeaders(kind: String, key: String): Request.Builder {
        val cleanKey = key.trim()
        if (cleanKey.isBlank()) throw ProviderException(ProviderException.Kind.AUTH, "请先配置 API key")
        return when (kind) {
            "anthropic" -> header("x-api-key", cleanKey).header("anthropic-version", "2023-06-01")
            else -> header("Authorization", "Bearer $cleanKey")
        }.header("Accept", "text/event-stream, application/json")
            .header("Content-Type", "application/json")
    }

    private fun readBody(response: okhttp3.Response): String = response.body?.string().orEmpty().take(MAX_ERROR_BYTES)

    private fun httpError(code: Int, body: String, key: String): ProviderException {
        val safe = redact(body, key)
        val kind = when (code) {
            401, 403 -> ProviderException.Kind.AUTH
            402 -> ProviderException.Kind.BILLING
            429 -> ProviderException.Kind.RATE_LIMIT
            400, 413 -> if (safe.contains("context", true) || safe.contains("token", true)) ProviderException.Kind.CONTEXT else ProviderException.Kind.HTTP
            else -> ProviderException.Kind.HTTP
        }
        return ProviderException(kind, "HTTP $code${if (safe.isBlank()) "" else ": $safe"}")
    }

    private fun redact(value: String, key: String): String {
        var result = value.replace(Regex("(?i)(api[-_ ]?key|authorization|x-api-key)(\\s*[:=]\\s*)[^,\\s}]+"), "$1$2[REDACTED]")
        if (key.isNotBlank()) result = result.replace(key, "[REDACTED]")
        result = result.replace(Regex("(?i)\\bsk-[A-Za-z0-9_-]{8,}\\b"), "[REDACTED]")
        return result.take(MAX_ERROR_BYTES)
    }

    private data class ModelsPage(val models: List<ModelInfo>, val hasMore: Boolean, val afterId: String?)

    private fun parseModelsPage(body: String, providerKind: String): ModelsPage {
        val root = try { JSONObject(body) } catch (e: JSONException) { throw ProviderException(ProviderException.Kind.HTTP, "模型目录格式无效", e) }
        val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        val result = buildList(data.length()) {
            for (index in 0 until data.length()) {
                val model = data.optJSONObject(index) ?: continue
                val id = model.optString("id", model.optString("name"))
                if (id.isBlank()) continue
                val spec = model.optJSONObject("model_spec") ?: model.optJSONObject("spec")
                val caps = model.optJSONObject("capabilities") ?: spec?.optJSONObject("capabilities")
                val type = model.optString("type", "")
                val context = firstInt(model, "context_tokens", "context_length", "max_context_tokens", "contextTokens")
                    ?: spec?.let { firstInt(it, "availableContextTokens", "context_tokens", "context_length", "max_context_tokens") }
                    ?: 32768
                val tools = firstBool(model, "supports_tools", "supportsTools", "tool_calling")
                    ?: caps?.let { firstBool(it, "supportsFunctionCalling", "supportsTools", "tools", "tool_calling", "function_calling") } ?: false
                val vision = firstBool(model, "supports_vision", "supportsVision", "vision")
                    ?: caps?.let { firstBool(it, "supportsVision", "vision", "image_input") } ?: false
                val reasoning = firstBool(model, "supports_reasoning", "supportsReasoning", "reasoning")
                    ?: caps?.let { firstBool(it, "supportsReasoning", "reasoning", "thinking") } ?: false
                val offline = firstBool(model, "offline") ?: spec?.let { firstBool(it, "offline") } ?: false
                val name = spec?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: model.optString("name").takeIf { it.isNotBlank() }
                    ?: model.optString("display_name").takeIf { it.isNotBlank() } ?: id
                add(ModelTypes.normalize(ModelInfo(id, name, type, context, tools, vision, reasoning, offline, model.toString()), providerKind))
            }
        }
        val hasMore = root.optBoolean("has_more", root.optBoolean("hasMore", false))
        val afterId = root.optString("last_id", root.optString("after_id", root.optString("afterId", ""))).takeIf { it.isNotBlank() }
        return ModelsPage(result, hasMore, afterId)
    }

    private fun parseNonStreaming(kind: String, body: String, requestId: String?, key: String): CompletionResult {
        val json = try { JSONObject(body) } catch (e: Exception) { throw ProviderException(ProviderException.Kind.HTTP, "响应格式无效", e) }
        val resolvedRequestId = requestId?.takeIf { it.isNotBlank() }
            ?: json.optString("request_id", json.optString("requestId", "")).takeIf { it.isNotBlank() }.orEmpty()
        if (json.has("error")) {
            val message = json.optJSONObject("error")?.optString("message") ?: json.optString("error")
            throw ProviderException(ProviderException.Kind.IN_BAND, redact(message, key), requestId = resolvedRequestId)
        }
        if (kind == "anthropic") {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val tools = JSONArray()
            val blocks = json.optJSONArray("content") ?: JSONArray()
            for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> content.append(block.optString("text"))
                    "thinking" -> reasoning.append(block.optString("thinking"))
                    "tool_use" -> tools.put(JSONObject().put("index", i).put("id", block.optString("id"))
                        .put("type", "function").put("function", JSONObject().put("name", block.optString("name")).put("arguments", block.optJSONObject("input")?.toString() ?: "{}")))
                }
            }
            val finish = json.optString("stop_reason", json.optString("finish_reason", ""))
            return CompletionResult(content.toString(), reasoning.toString(), tools.toString(), JSONObject().put("request_id", resolvedRequestId.ifBlank { JSONObject.NULL }).put("raw", json).toString(), finish, resolvedRequestId)
        }
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
        val message = choice.optJSONObject("message") ?: choice.optJSONObject("delta") ?: JSONObject()
        val (visible, thought) = contentParts(message.opt("content"))
        val (body, inline) = InlineReasoningSplitter.split(visible)
        val content = body.ifBlank { extractText(message.opt("refusal")) }
        val reasoning = reasoningText(message) + thought + inline
        val tools = message.optJSONArray("tool_calls") ?: JSONArray()
        val finish = choice.optString("finish_reason", json.optString("finish_reason", ""))
        return CompletionResult(content, reasoning, tools.toString(), JSONObject().put("request_id", resolvedRequestId.ifBlank { JSONObject.NULL }).put("raw", json).toString(), finish, resolvedRequestId)
    }

    private fun openAiPayload(modelId: String, input: JSONArray, preset: Preset, toolsJson: String): JSONObject {
        val messages = JSONArray()
        for (i in 0 until input.length()) {
            val original = input.optJSONObject(i)
            if (original == null) {
                messages.put(input.opt(i))
                continue
            }
            messages.put(openAiMessage(original))
        }
        val payload = JSONObject().put("model", modelId).put("messages", messages).put("stream", true)
            .put("temperature", preset.temperature)
        if (preset.generationBudgetEnabled) payload.put("max_tokens", preset.maxOutput)
        val tools = parseArrayOrNull(toolsJson)
        if (tools != null && tools.length() > 0) payload.put("tools", tools)
        return payload
    }

    private fun applyVeniceSettings(payload: JSONObject, modelId: String, preset: Preset, capabilities: VeniceModelCapabilities) {
        val requested = preset.reasoningEffort
        if (requested != "default" && requested !in capabilities.reasoningEffortOptions) {
            throw ProviderException(ProviderException.Kind.UNSUPPORTED,
                "当前模型目录未确认支持所选推理强度，请刷新 Venice 模型目录并重新选择；尚未发送请求")
        }
        // A disabled custom budget omits both aliases entirely; zero is not
        // sent as a made-up unlimited value. The provider chooses its default.
        payload.remove("max_tokens")
        if (preset.generationBudgetEnabled) {
            val reasoningBudget = if (preset.reasoningEffort == "none") 0 else preset.reasoningBudget
            val totalBudget = capabilities.totalCompletionBudget(preset)
            capabilities.maxCompletionTokens?.let { maximum ->
                if (totalBudget > maximum.toLong()) throw ProviderException(ProviderException.Kind.UNSUPPORTED,
                    "正文上限 ${preset.maxOutput} + 思考预留 ${reasoningBudget} = 总生成预算 ${totalBudget}，超过当前 Venice 模型目录上限 $maximum；请降低其中一项后重试，尚未发送请求")
            }
            payload.put("max_completion_tokens", totalBudget.toInt())
        }
        val venice = JSONObject().put("include_venice_system_prompt", preset.includeVeniceSystemPrompt)
            .put("strip_thinking_response", false)
        if (requested != "default") {
            payload.put("reasoning_effort", requested)
            venice.put("disable_thinking", requested == "none")
        }
        payload.put("venice_parameters", venice)
        val effectiveEffort = if (requested == "default") capabilities.defaultReasoningEffort else requested
        if (modelId == "deepseek-v4-1-flash-1017" && effectiveEffort != null && effectiveEffort != "none") {
            // This snapshot's thinking mode ignores sampling temperature. Keep it in
            // non-thinking/unknown modes and for every other model or provider.
            payload.remove("temperature")
        }
    }

    private fun anthropicPayload(modelId: String, input: JSONArray, preset: Preset, toolsJson: String): JSONObject {
        val payload = JSONObject().put("model", modelId).put("max_tokens", preset.maxOutput).put("stream", true)
        val messages = JSONArray()
        val systemParts = ArrayList<String>()
        for (i in 0 until input.length()) {
            val raw = input.optJSONObject(i) ?: continue
            val role = raw.optString("role", "user")
            if (role == "system") {
                textFromContent(raw.opt("content"))?.takeIf { it.isNotBlank() }?.let(systemParts::add)
                continue
            }
            val message = JSONObject().put("role", if (role == "assistant") "assistant" else "user")
            message.put("content", if (role == "tool" || raw.optString("tool_call_id", raw.optString("toolCallId", "")).isNotBlank()) anthropicToolResult(raw) else anthropicContent(raw))
            messages.put(message)
        }
        if (systemParts.isNotEmpty()) payload.put("system", systemParts.joinToString("\n\n"))
        payload.put("messages", messages)
        val tools = parseArrayOrNull(toolsJson)
        if (tools != null && tools.length() > 0) payload.put("tools", normalizeAnthropicTools(tools))
        return payload
    }

    private fun anthropicToolResult(message: JSONObject): JSONArray {
        val block = JSONObject().put("type", "tool_result")
            .put("tool_use_id", message.optString("tool_call_id", message.optString("toolCallId")))
        val content = message.opt("content")
        if (content is JSONArray) {
            block.put("content", normalizeAnthropicBlocks(content))
        } else {
            block.put("content", message.optString("content", ""))
        }
        return JSONArray().put(block)
    }

    private fun anthropicContent(message: JSONObject): Any {
        val content = message.opt("content")
        if (content is JSONArray) return normalizeAnthropicBlocks(content)
        val text = message.optString("content", "")
        val blocks = JSONArray()
        blocks.put(JSONObject().put("type", "text").put("text", text))
        val calls = toolCallsFrom(message)
        if (calls != null) for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val function = call.optJSONObject("function")
            val input = try { JSONObject(function?.optString("arguments", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            blocks.put(JSONObject().put("type", "tool_use").put("id", call.optString("id")).put("name", function?.optString("name", "") ?: "").put("input", input))
        }
        return blocks
    }

    /** Convert the OpenAI-shaped blocks emitted by ContextEngine to Anthropic Messages blocks. */
    private fun normalizeAnthropicBlocks(input: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until input.length()) {
            val block = input.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> result.put(JSONObject().put("type", "text").put("text", block.optString("text", "")))
                "image_url" -> anthropicImageBlock(block)?.let(result::put)
                "input_audio" -> result.put(JSONObject().put("type", "text").put("text", "【音频附件：Anthropic Messages API 不支持音频内容块】"))
                "thinking" -> {
                    val thinking = block.optString("thinking", "")
                    val signature = block.optString("signature", "")
                    if (thinking.isNotBlank() && signature.isNotBlank()) {
                        result.put(JSONObject().put("type", "thinking").put("thinking", thinking).put("signature", signature))
                    }
                }
                "tool_use" -> {
                    val normalized = JSONObject().put("type", "tool_use")
                        .put("id", block.optString("id"))
                        .put("name", block.optString("name"))
                        .put("input", block.optJSONObject("input") ?: JSONObject())
                    result.put(normalized)
                }
                "tool_result" -> {
                    result.put(JSONObject().put("type", "tool_result")
                        .put("tool_use_id", block.optString("tool_use_id", block.optString("toolCallId")))
                        .put("content", block.opt("content") ?: ""))
                }
                "document" -> result.put(block)
            }
        }
        return result
    }

    private fun anthropicImageBlock(block: JSONObject): JSONObject? {
        val image = block.opt("image_url")
        val url = when (image) {
            is JSONObject -> image.optString("url", "")
            is String -> image
            else -> ""
        }
        if (url.isBlank()) return null
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma <= 5) return null
            val metadata = url.substring(5, comma)
            val mediaType = metadata.substringBefore(';').ifBlank { "image/png" }
            return JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64").put("media_type", mediaType).put("data", url.substring(comma + 1)))
        }
        return if (url.startsWith("https://")) {
            JSONObject().put("type", "image").put("source", JSONObject().put("type", "url").put("url", url))
        } else null
    }

    private fun openAiMessage(original: JSONObject): JSONObject {
        val message = JSONObject().put("role", original.optString("role", "user"))
        if (original.has("content")) message.put("content", original.opt("content"))
        if (original.has("name")) message.put("name", original.opt("name"))
        val calls = toolCallsFrom(original)
        if (calls != null) message.put("tool_calls", calls)
        val toolCallId = original.optString("tool_call_id", original.optString("toolCallId", ""))
        if (toolCallId.isNotBlank()) message.put("tool_call_id", toolCallId)
        val reasoning = original.optString("reasoning_content", original.optString("reasoning", ""))
        if (reasoning.isNotBlank()) message.put("reasoning_content", reasoning)
        return message
    }

    private fun toolCallsFrom(message: JSONObject): JSONArray? {
        val value = when {
            message.has("tool_calls") -> message.opt("tool_calls")
            message.has("tool_calls_json") -> message.opt("tool_calls_json")
            message.has("toolCallsJson") -> message.opt("toolCallsJson")
            else -> null
        }
        return when (value) {
            is JSONArray -> value
            is String -> parseArrayOrNull(value)
            else -> null
        }
    }

    private fun textFromContent(value: Any?): String? {
        if (value is String) return value
        if (value is JSONArray) {
            val parts = ArrayList<String>()
            for (i in 0 until value.length()) {
                val block = value.optJSONObject(i) ?: continue
                block.optString("text", "").takeIf { it.isNotBlank() }?.let(parts::add)
            }
            return parts.joinToString("\n")
        }
        return null
    }

    private fun normalizeAnthropicTools(tools: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until tools.length()) {
            val item = tools.optJSONObject(i) ?: continue
            val function = item.optJSONObject("function")
            if (function != null) {
                result.put(JSONObject().put("name", function.optString("name")).put("description", function.optString("description"))
                    .put("input_schema", function.optJSONObject("parameters") ?: JSONObject()))
            } else result.put(item)
        }
        return result
    }

    private fun parseArray(text: String, name: String): JSONArray = parseArrayOrNull(text)
        ?: throw ProviderException(ProviderException.Kind.UNSUPPORTED, "$name 格式无效")

    private fun parseArrayOrNull(text: String): JSONArray? = try { JSONArray(text) } catch (_: Exception) { null }

    private fun extractText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                val item = value.opt(i)
                when (item) {
                    is JSONObject -> {
                        val text = extractText(item.opt("text"))
                            .ifBlank { extractText(item.opt("output_text")) }
                        if (text.isNotBlank()) append(text)
                    }
                    else -> append(extractText(item))
                }
            }
        }
        is JSONObject -> extractText(value.opt("text")).ifBlank { extractText(value.opt("content")) }
        else -> ""
    }

    /** Splits content parts into visible text and parts flagged as model thoughts. */
    private fun contentParts(value: Any?): Pair<String, String> {
        if (value !is JSONArray) return extractText(value) to ""
        val text = StringBuilder()
        val thought = StringBuilder()
        for (i in 0 until value.length()) {
            val item = value.opt(i)
            if (item is JSONObject && (item.optBoolean("thought") || item.optString("type") in THOUGHT_PARTS)) {
                thought.append(extractText(item.opt("thinking")).ifBlank { extractText(item.opt("text")) }.ifBlank { extractText(item.opt("reasoning")) })
            } else if (item is JSONObject) {
                text.append(extractText(item.opt("text")).ifBlank { extractText(item.opt("output_text")) })
            } else text.append(extractText(item))
        }
        return text.toString() to thought.toString()
    }

    /** Plain reasoning fields win; `reasoning_details` usually repeats them and is only a fallback. */
    private fun reasoningText(source: JSONObject): String {
        for (key in listOf("reasoning_content", "reasoning")) {
            (source.opt(key) as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val details = source.optJSONArray("reasoning_details") ?: return ""
        return buildString {
            for (i in 0 until details.length()) {
                val detail = details.optJSONObject(i) ?: continue
                when (detail.optString("type")) {
                    "reasoning.text" -> append(detail.optString("text"))
                    "reasoning.summary" -> append(detail.optString("summary"))
                }
            }
        }
    }

    private fun validateResult(result: CompletionResult): CompletionResult {
        // A filtered response may contain a partial reply or tool call. Keep it
        // for diagnostics, but never mark it complete or execute those tools.
        if (result.finishReason.trim().replace('-', '_').equals("content_filter", ignoreCase = true)) {
            throw ProviderException(
                ProviderException.Kind.CONTENT_FILTER,
                if (result.content.isNotBlank()) "平台或上游已过滤后续内容，已保留部分回复"
                else "平台或上游已过滤回复，未收到正文",
                finishReason = result.finishReason,
                requestId = result.requestId,
                partial = result
            )
        }
        val validTools = runCatching { JSONArray(result.toolCallsJson).length() > 0 }.getOrDefault(false)
        if (result.content.isNotBlank() || validTools) return result
        if (result.reasoning.isNotBlank()) {
            throw ProviderException(
                ProviderException.Kind.EMPTY_RESPONSE,
                "模型只返回思考过程，未收到回复正文",
                finishReason = result.finishReason,
                requestId = result.requestId,
                partial = result
            )
        }
        throw ProviderException(
            ProviderException.Kind.EMPTY_RESPONSE,
            "模型返回空回复",
            finishReason = result.finishReason,
            requestId = result.requestId,
            partial = result
        )
    }

    private fun firstInt(obj: JSONObject, vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
        if (!obj.has(name)) null else obj.optInt(name, -1).takeIf { it > 0 }
    }

    private fun firstBool(obj: JSONObject, vararg names: String): Boolean? = names.firstNotNullOfOrNull { name -> if (obj.has(name)) obj.optBoolean(name) else null }

    private fun numericUsage(source: JSONObject): JSONObject {
        val result = JSONObject()
        val topLevel = setOf("prompt_tokens", "completion_tokens", "total_tokens", "input_tokens", "output_tokens", "reasoning_tokens", "cached_tokens", "cost", "costs")
        for (key in topLevel) {
            val value = source.opt(key)
            when (value) {
                is Number -> result.put(key, value)
                is JSONObject -> if (key in setOf("cost", "costs")) result.put(key, numericObject(value))
            }
        }
        listOf("completion_tokens_details", "prompt_tokens_details").forEach { key ->
            source.optJSONObject(key)?.let { details ->
                val allowed = if (key == "completion_tokens_details") setOf("reasoning_tokens") else setOf("cached_tokens")
                val selected = JSONObject()
                allowed.forEach { field -> if (details.opt(field) is Number) selected.put(field, details.opt(field)) }
                if (selected.length() > 0) result.put(key, selected)
            }
        }
        return result
    }

    private fun numericObject(source: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in setOf("input_tokens", "output_tokens", "total", "usd")) {
            val value = source.opt(key)
            if (value is Number) result.put(key, value)
        }
        return result
    }

    private data class StreamStats(
        val eventCount: Int,
        val malformedEvents: Int,
        val doneSignal: Boolean,
        val finishReason: String,
        val requestId: String,
        val responseId: String,
        val usage: JSONObject?,
        val contentChars: Int,
        val reasoningChars: Int,
        val toolCalls: Int
    )

    private data class ToolCallState(
        var id: String = "",
        var type: String = "function",
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    )

    private inner class DiagnosticTrace(
        private val provider: ProviderConfig,
        private val base: HttpUrl,
        private val modelId: String,
        private val kind: String,
        private val visibleOutputTokens: Int,
        private val reasoningBudgetTokens: Int,
        private val maxOutputTokens: Int,
        private val inputMessageCount: Int,
        private val inputChars: Int,
        private val readTimeoutMillis: Long,
        private val connectTimeoutMillis: Long,
        private val writeTimeoutMillis: Long,
        private val callTimeoutMillis: Long,
        private val key: String,
        private val callback: (String) -> Unit
    ) {
        private val startedAt = System.currentTimeMillis()
        private val traceId = UUID.randomUUID().toString()
        private var phase = "request"
        private var httpStatus = 0
        private var firstByteMs = -1L
        private var firstDeltaMs = -1L
        private var requestId = ""
        private var responseId = ""
        private var outcome = ""
        private var finishReason = ""
        private var contentChars = 0
        private var reasoningChars = 0
        private var toolCalls = 0
        private var eventCount = 0
        private var malformedEvents = 0
        private var doneSignal = false
        private var usage: JSONObject? = null
        private var errorType = ""
        private val requestSettings = JSONObject()

        fun requestedSettings(preset: Preset, capabilities: VeniceModelCapabilities?) {
            requestSettings.put("visible_max_output_tokens", visibleOutputTokens)
                .put("reasoning_budget_tokens", if (kind == "venice") reasoningBudgetTokens else 0)
                .put("generation_budget_enabled", preset.generationBudgetEnabled)
            if (kind != "venice") return
            requestSettings.put("requested_reasoning_effort", safe(preset.reasoningEffort))
            capabilities?.defaultReasoningEffort?.let { requestSettings.put("default_reasoning_effort", it) }
        }

        /** Copy only known scalar settings; never log the payload, messages, or tools themselves. */
        fun payloadSettings(payload: JSONObject) {
            val outputField = when {
                payload.has("max_completion_tokens") -> "max_completion_tokens"
                payload.has("max_tokens") -> "max_tokens"
                else -> "omitted"
            }
            requestSettings.put("output_budget_field", outputField)
                .put("temperature_sent", payload.has("temperature"))
                .put("request_tool_count", payload.optJSONArray("tools")?.length() ?: 0)
            (payload.opt("temperature") as? Number)?.let { requestSettings.put("temperature", it) }
            (payload.opt("reasoning_effort") as? String)?.let { requestSettings.put("reasoning_effort", safe(it)) }
            payload.optJSONObject("venice_parameters")?.let { venice ->
                requestSettings.put("venice_system_prompt_included", venice.optBoolean("include_venice_system_prompt"))
                if (venice.has("disable_thinking")) requestSettings.put("disable_thinking", venice.optBoolean("disable_thinking"))
                requestSettings.put("strip_thinking_response", venice.optBoolean("strip_thinking_response"))
            }
        }

        fun emit(event: String, nextPhase: String) {
            phase = nextPhase
            val now = System.currentTimeMillis()
            val json = JSONObject()
                .put("trace_id", traceId)
                .put("event", event)
                .put("started_at_ms", startedAt)
                .put("elapsed_ms", (now - startedAt).coerceAtLeast(0))
                .put("phase", phase)
                .put("http_status", httpStatus)
                .put("request_id", safe(requestId))
                .put("response_id", safe(responseId))
                .put("finish_reason", safe(finishReason))
                .put("outcome", outcome)
                // timeout_ms is retained for older consumers and means the read idle timeout.
                .put("timeout_ms", readTimeoutMillis)
                .put("read_timeout_ms", readTimeoutMillis)
                .put("connect_timeout_ms", connectTimeoutMillis)
                .put("write_timeout_ms", writeTimeoutMillis)
                .put("call_timeout_ms", callTimeoutMillis)
                .put("content_chars", contentChars)
                .put("reasoning_chars", reasoningChars)
                .put("tool_calls", toolCalls)
                .put("event_count", eventCount)
                .put("malformed_events", malformedEvents)
                .put("done_signal", doneSignal)
                .put("provider_kind", safe(kind))
                .put("provider_name", safe(provider.name))
                .put("provider_id", safe(provider.id))
                .put("model_id", safe(modelId))
                .put("api_host", safe(base.host))
                .put("max_output_tokens", maxOutputTokens)
                .put("input_message_count", inputMessageCount)
                .put("input_chars", inputChars)
            requestSettings.keys().forEach { name -> json.put(name, requestSettings.get(name)) }
            if (firstByteMs >= 0) json.put("first_byte_ms", firstByteMs)
            if (firstDeltaMs >= 0) json.put("first_delta_ms", firstDeltaMs)
            usage?.takeIf { it.length() > 0 }?.let { json.put("usage", it) }
            if (errorType.isNotBlank()) json.put("error_type", errorType)
            safeDiagnostic(json)
        }

        fun headers(status: Int, headerRequestId: String?) {
            httpStatus = status
            requestId = headerRequestId.orEmpty()
            emit("headers", "response_headers")
        }

        fun firstByte() {
            if (firstByteMs < 0L) firstByteMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
            if (phase == "response_headers") phase = "stream"
        }

        fun firstDelta() {
            firstByte()
            if (firstDeltaMs < 0L) {
                firstDeltaMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
                emit("first_delta", "stream")
            } else phase = "stream"
        }

        fun jsonDecoded(body: String) {
            phase = "json_decode"
            runCatching { JSONObject(body) }.getOrNull()?.let { root ->
                usage = root.optJSONObject("usage")?.let(::numericUsage)
                responseId = root.optString("id", "")
                if (requestId.isBlank()) requestId = root.optString("request_id", root.optString("requestId", ""))
                val choice = root.optJSONArray("choices")?.optJSONObject(0)
                finishReason = choice?.optString("finish_reason", "").orEmpty().ifBlank { root.optString("finish_reason", "") }
            }
        }

        fun streamMetrics(stream: SseAccumulator) {
            val stats = stream.stats()
            eventCount = stats.eventCount
            malformedEvents = stats.malformedEvents
            doneSignal = stats.doneSignal
            finishReason = stats.finishReason
            usage = stats.usage
            contentChars = stats.contentChars
            reasoningChars = stats.reasoningChars
            toolCalls = stats.toolCalls
            if (requestId.isBlank()) requestId = stats.requestId
            responseId = stats.responseId
        }

        fun result(result: CompletionResult) {
            contentChars = result.content.length
            reasoningChars = result.reasoning.length
            toolCalls = runCatching { JSONArray(result.toolCallsJson).length() }.getOrDefault(0)
            finishReason = result.finishReason
            if (requestId.isBlank()) requestId = result.requestId
        }

        fun failure(error: ProviderException) {
            // Class name only: exception messages can echo hosts or response text.
            errorType = error.cause?.javaClass?.simpleName ?: error.kind.name.lowercase()
            finishReason = error.finishReason.ifBlank { finishReason }
            requestId = error.requestId.ifBlank { requestId }
            error.partial?.let(::result)
        }

        fun currentRequestId(): String = requestId

        fun emitFinished(outcome: String, result: CompletionResult?) {
            this.outcome = outcome
            result?.let(::result)
            emit("finished", phase)
        }

        private fun safe(value: String): String = redact(value, key).take(240)
        private fun safeDiagnostic(json: JSONObject) { runCatching { callback(json.toString()) } }
    }

    private inner class SseAccumulator(
        private val kind: String,
        private val onDelta: (StreamDelta) -> Unit,
        private val key: String,
        private val onFirstDelta: () -> Unit
    ) {
        private val dataLines = ArrayList<String>()
        private val content = StringBuilder()
        private val reasoning = StringBuilder()
        private val toolCalls = LinkedHashMap<Int, ToolCallState>()
        private var rawEvents = 0
        private var malformedEvents = 0
        private var doneSignal = false
        private var rawLast = "{}"
        private var finishReason = ""
        private var requestId = ""
        private var responseId = ""
        private var usage: JSONObject? = null
        private var emittedDelta = false
        private val inlineReasoning = InlineReasoningSplitter()

        fun acceptLine(line: String): Boolean {
            if (doneSignal) return true
            return when {
                line.startsWith("data:") -> {
                    dataLines += line.substringAfter("data:").trimStart()
                    false
                }
                line.isEmpty() -> flush()
                line.startsWith(":") -> false
                else -> false
            }
        }

        fun finish() {
            flush()
            drainInlineReasoning()
            if (!doneSignal) throw ProviderException(ProviderException.Kind.NETWORK, "流式响应提前结束", partial = result(null))
        }

        fun stats(): StreamStats = StreamStats(rawEvents, malformedEvents, doneSignal, finishReason, requestId, responseId, usage, content.length, reasoning.length, toolCalls.size)

        private fun flush(): Boolean {
            if (dataLines.isEmpty()) return doneSignal
            val data = dataLines.joinToString("\n").also { dataLines.clear() }
            if (data == "[DONE]") {
                doneSignal = true
                return true
            }
            val json = try { JSONObject(data) } catch (_: Exception) {
                malformedEvents++
                return false
            }
            rawEvents++
            rawLast = json.toString()
            if (kind == "anthropic") parseAnthropic(json) else parseOpenAi(json)
            return doneSignal
        }

        private fun markDelta() {
            if (!emittedDelta) {
                emittedDelta = true
                onFirstDelta()
            }
        }

        private fun appendContent(text: String, snapshot: Boolean = false) {
            if (text.isEmpty()) return
            if (snapshot) {
                val current = content.toString()
                if (current.isNotBlank() && text.startsWith(current)) {
                    content.clear(); content.append(text)
                    val suffix = text.removePrefix(current)
                    if (suffix.isNotEmpty()) onDelta(StreamDelta(content = suffix))
                    markDelta()
                    return
                }
                if (current == text || current.startsWith(text)) return
                content.clear(); content.append(text)
                onDelta(StreamDelta(content = text, replaceContent = true))
                markDelta()
                return
            }
            content.append(text)
            onDelta(StreamDelta(content = text))
            markDelta()
        }

        private fun appendReasoning(text: String, snapshot: Boolean = false) {
            if (text.isEmpty()) return
            val added = when {
                !snapshot || reasoning.isEmpty() -> text
                text.startsWith(reasoning) -> text.substring(reasoning.length)
                else -> ""
            }
            if (added.isEmpty()) return
            reasoning.append(added)
            onDelta(StreamDelta(reasoning = added))
            markDelta()
        }

        private fun feedContent(text: String) {
            if (text.isEmpty()) return
            val (visible, thought) = inlineReasoning.feed(text)
            appendReasoning(thought)
            appendContent(visible)
        }

        private fun drainInlineReasoning() {
            val (visible, thought) = inlineReasoning.finish()
            appendReasoning(thought)
            appendContent(visible)
        }

        private fun parseOpenAi(json: JSONObject) {
            if (json.has("error")) throw inBand(json.optJSONObject("error")?.optString("message") ?: json.optString("error"))
            responseId = json.optString("id", responseId)
            if (requestId.isBlank()) requestId = json.optString("request_id", json.optString("requestId", ""))
            json.optJSONObject("usage")?.let { usage = numericUsage(it) }
            // Chunks for other choices would otherwise be appended to the one visible reply.
            val choices = json.optJSONArray("choices") ?: return
            val choice = (0 until choices.length()).mapNotNull { choices.optJSONObject(it) }
                .firstOrNull { it.optInt("index", 0) == 0 } ?: return
            choice.optString("finish_reason").takeIf { it.isNotBlank() }?.let { finishReason = it }
            val delta = choice.optJSONObject("delta")
            val message = choice.optJSONObject("message")
            val source = delta ?: message ?: JSONObject()
            val snapshot = delta == null && message != null
            val (visible, thought) = contentParts(source.opt("content"))
            if (snapshot) {
                val (body, inline) = InlineReasoningSplitter.split(visible)
                inlineReasoning.finish()
                appendReasoning(reasoningText(source) + thought + inline, true)
                appendContent(body, true)
            } else {
                appendReasoning(reasoningText(source) + thought)
                feedContent(visible)
            }
            appendContent(extractText(source.opt("refusal")), snapshot)
            parseToolCalls(source.optJSONArray("tool_calls"))
        }

        private fun parseToolCalls(calls: JSONArray?) {
            if (calls == null) return
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val index = call.optInt("index", i)
                val state = toolCalls.getOrPut(index) { ToolCallState() }
                call.opt("id")?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }?.let { state.id = it }
                call.opt("type")?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }?.let { state.type = it }
                val function = call.optJSONObject("function")
                if (function != null) {
                    if (function.has("name")) state.name.append(function.optString("name"))
                    if (function.has("arguments")) state.arguments.append(function.optString("arguments"))
                }
                markDelta()
            }
        }

        private fun parseAnthropic(json: JSONObject) {
            if (json.optString("type") == "error") throw inBand(json.optJSONObject("error")?.optString("message") ?: "Anthropic 流中错误")
            if (json.has("message")) json.optJSONObject("message")?.optString("id")?.takeIf { it.isNotBlank() }?.let { responseId = it }
            if (json.has("usage")) json.optJSONObject("usage")?.let { usage = numericUsage(it) }
            when (json.optString("type")) {
                "message_stop" -> doneSignal = true
                "message_delta" -> json.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotBlank() }?.let { finishReason = it }
                "content_block_start" -> {
                    val block = json.optJSONObject("content_block") ?: return
                    if (block.optString("type") == "tool_use") {
                        val state = toolCalls.getOrPut(json.optInt("index", toolCalls.size)) { ToolCallState() }
                        state.id = block.optString("id")
                        state.type = "function"
                        state.name.append(block.optString("name"))
                        markDelta()
                    }
                }
                "content_block_delta" -> {
                    val delta = json.optJSONObject("delta") ?: return
                    when (delta.optString("type")) {
                        "text_delta" -> appendContent(delta.optString("text"))
                        "thinking_delta" -> appendReasoning(delta.optString("thinking", ""))
                        "signature_delta" -> Unit
                        "input_json_delta" -> {
                            toolCalls.getOrPut(json.optInt("index", toolCalls.size)) { ToolCallState() }.arguments.append(delta.optString("partial_json"))
                            markDelta()
                        }
                    }
                }
            }
        }

        fun result(requestId: String?): CompletionResult {
            drainInlineReasoning()
            val calls = JSONArray()
            for ((index, state) in toolCalls.toSortedMap()) {
                val id = state.id.ifBlank { "call_$index" }
                calls.put(JSONObject().put("index", index).put("id", id).put("type", state.type)
                    .put("function", JSONObject().put("name", state.name.toString()).put("arguments", state.arguments.toString())))
            }
            val resolved = requestId?.takeIf { it.isNotBlank() } ?: this.requestId
            return CompletionResult(content.toString(), reasoning.toString(), calls.toString(), JSONObject().put("events", rawEvents).put("last", rawLast).put("request_id", resolved.ifBlank { JSONObject.NULL }).toString(), finishReason, resolved)
        }

        private fun inBand(message: String): ProviderException = ProviderException(ProviderException.Kind.IN_BAND, "流中错误: ${redact(message, key).take(500)}")
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val MAX_ERROR_BYTES = 16_384
        const val MAX_MODEL_PAGES = 100
        val THOUGHT_PARTS = setOf("thinking", "reasoning", "redacted_thinking")
    }
}
