package app.pockettavern.provider

import app.pockettavern.domain.CompletionResult
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.StreamDelta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private fun newProviderHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(120, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/** A small, protocol-neutral error type used by the UI to display actionable failures. */
class ProviderException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause) {
    enum class Kind { INVALID_URL, HTTP, IN_BAND, AUTH, RATE_LIMIT, BILLING, CONTEXT, NETWORK, CANCELLED, UNSUPPORTED }
}

/** HTTP transport for the first-party and OpenAI-compatible provider protocols. */
class ProviderClient(
    client: OkHttpClient = newProviderHttpClient(),
    private val allowInsecureHttpForTests: Boolean = false
) {
    private val client: OkHttpClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val activeCall = AtomicReference<Call?>(null)

    suspend fun models(provider: ProviderConfig, key: String): List<ModelInfo> = withContext(Dispatchers.IO) {
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
            val response = execute(request)
            try {
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw httpError(response.code, body, key)
                val parsed = parseModelsPage(body)
                all += parsed.models
                after = if (parsed.hasMore) parsed.afterId else null
            } finally {
                response.close()
                activeCall.set(null)
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
        onDelta: (StreamDelta) -> Unit = {}
    ): CompletionResult = withContext(Dispatchers.IO) {
        if (modelId.isBlank()) throw ProviderException(ProviderException.Kind.UNSUPPORTED, "未选择模型")
        val base = checkedBaseUrl(provider)
        val kind = provider.kind.lowercase(Locale.ROOT)
        val inputMessages = parseArray(messagesJson, "messages")
        val payload = if (kind == "anthropic") {
            anthropicPayload(modelId, inputMessages, preset, toolsJson)
        } else {
            openAiPayload(modelId, inputMessages, preset, toolsJson)
        }
        val endpoint = if (kind == "anthropic") "messages" else "chat/completions"
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments(endpoint).build())
            .applyHeaders(kind, key)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        val response = execute(request)
        try {
            response.use {
                if (!it.isSuccessful) {
                    val errorBody = readBody(it)
                    throw httpError(it.code, errorBody, key)
                }
                val contentType = it.header("Content-Type").orEmpty()
                if (contentType.contains("application/json", ignoreCase = true)) {
                    val body = it.body?.string().orEmpty()
                    return@use parseNonStreaming(kind, body, it.header("x-request-id"))
                }
                val source = it.body?.source() ?: throw ProviderException(ProviderException.Kind.NETWORK, "响应没有内容")
                val stream = SseAccumulator(kind, onDelta)
                try {
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        stream.acceptLine(line)
                    }
                    stream.finish()
                    stream.result(it.header("x-request-id"))
                } catch (e: ProviderException) {
                    throw e
                } catch (e: IOException) {
                    if (e.message?.contains("Canceled", ignoreCase = true) == true) {
                        throw ProviderException(ProviderException.Kind.CANCELLED, "请求已取消", e)
                    }
                    throw ProviderException(ProviderException.Kind.NETWORK, "网络连接失败", e)
                }
            }
        } finally {
            activeCall.set(null)
        }
    }

    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    private fun execute(request: Request) : okhttp3.Response {
        val call = client.newCall(request)
        if (!activeCall.compareAndSet(null, call)) {
            call.cancel()
            throw ProviderException(ProviderException.Kind.CANCELLED, "已有请求正在执行")
        }
        return try {
            call.execute()
        } catch (e: IOException) {
            activeCall.compareAndSet(call, null)
            if (call.isCanceled()) throw ProviderException(ProviderException.Kind.CANCELLED, "请求已取消", e)
            throw ProviderException(ProviderException.Kind.NETWORK, "网络连接失败", e)
        }
    }

    private fun checkedBaseUrl(provider: ProviderConfig): HttpUrl {
        val raw = provider.baseUrl.trim()
        val url = raw.toHttpUrlOrNull() ?: throw ProviderException(ProviderException.Kind.INVALID_URL, "API 地址无效")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw ProviderException(ProviderException.Kind.INVALID_URL, "API 地址不能包含账号信息")
        }
        if (url.scheme != "https" && !(allowInsecureHttpForTests && url.scheme == "http" && isLocal(url.host))) {
            throw ProviderException(ProviderException.Kind.INVALID_URL, "API 地址必须使用 HTTPS")
        }
        return url
    }

    private fun isLocal(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1"

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
        return result.take(MAX_ERROR_BYTES)
    }

    private data class ModelsPage(val models: List<ModelInfo>, val hasMore: Boolean, val afterId: String?)

    private fun parseModels(body: String): List<ModelInfo> = parseModelsPage(body).models

    private fun parseModelsPage(body: String): ModelsPage {
        val root = try { JSONObject(body) } catch (e: JSONException) { throw ProviderException(ProviderException.Kind.HTTP, "模型目录格式无效", e) }
        val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        val result = buildList(data.length()) {
            for (index in 0 until data.length()) {
                val model = data.optJSONObject(index) ?: continue
                val id = model.optString("id", model.optString("name"))
                if (id.isBlank()) continue
                val spec = model.optJSONObject("model_spec") ?: model.optJSONObject("spec")
                val caps = model.optJSONObject("capabilities") ?: spec?.optJSONObject("capabilities")
                val type = model.optString("type", spec?.optString("type", "text") ?: "text").ifBlank { "text" }
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
                add(ModelInfo(id, spec?.optString("name", model.optString("name", id)) ?: model.optString("name", id), type, context, tools, vision, reasoning, offline, model.toString()))
            }
        }
        val hasMore = root.optBoolean("has_more", root.optBoolean("hasMore", false))
        val afterId = root.optString("last_id", root.optString("after_id", root.optString("afterId", ""))).takeIf { it.isNotBlank() }
        return ModelsPage(result, hasMore, afterId)
    }

    private fun parseNonStreaming(kind: String, body: String, requestId: String?): CompletionResult {
        val json = try { JSONObject(body) } catch (e: Exception) { throw ProviderException(ProviderException.Kind.HTTP, "响应格式无效", e) }
        if (json.has("error")) throw ProviderException(ProviderException.Kind.IN_BAND, json.optJSONObject("error")?.optString("message") ?: json.optString("error"))
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
            return CompletionResult(content.toString(), reasoning.toString(), tools.toString(), JSONObject().put("request_id", requestId ?: JSONObject.NULL).put("raw", json).toString())
        }
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
        val message = choice.optJSONObject("message") ?: choice.optJSONObject("delta") ?: JSONObject()
        val content = message.optString("content", "")
        val reasoning = message.optString("reasoning_content", message.optString("reasoning", ""))
        val tools = message.optJSONArray("tool_calls") ?: JSONArray()
        return CompletionResult(content, reasoning, tools.toString(), JSONObject().put("request_id", requestId ?: JSONObject.NULL).put("raw", json).toString())
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
            .put("temperature", preset.temperature).put("max_tokens", preset.maxOutput)
        val tools = parseArrayOrNull(toolsJson)
        if (tools != null && tools.length() > 0) payload.put("tools", tools)
        return payload
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

    private fun firstInt(obj: JSONObject, vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
        if (!obj.has(name)) null else obj.optInt(name, -1).takeIf { it > 0 }
    }

    private fun firstBool(obj: JSONObject, vararg names: String): Boolean? = names.firstNotNullOfOrNull { name -> if (obj.has(name)) obj.optBoolean(name) else null }

    private class SseAccumulator(private val kind: String, private val onDelta: (StreamDelta) -> Unit) {
        private val dataLines = ArrayList<String>()
        private val content = StringBuilder()
        private val reasoning = StringBuilder()
        private val toolCalls = LinkedHashMap<Int, ToolCallState>()
        private var rawEvents = 0
        private var doneSignal = false
        private var rawLast = "{}"

        fun acceptLine(line: String) {
            when {
                line.startsWith("data:") -> dataLines += line.substringAfter("data:").trimStart()
                line.isEmpty() -> flush()
                line.startsWith(":") -> Unit
            }
        }

        fun finish() {
            flush()
            if (!doneSignal) throw ProviderException(ProviderException.Kind.NETWORK, "流式响应提前结束")
        }

        private fun flush() {
            if (dataLines.isEmpty()) return
            val data = dataLines.joinToString("\n").also { dataLines.clear() }
            if (data == "[DONE]") { doneSignal = true; return }
            val json = try { JSONObject(data) } catch (_: Exception) { return }
            rawEvents++
            rawLast = json.toString()
            if (kind == "anthropic") parseAnthropic(json) else parseOpenAi(json)
        }

        private fun parseOpenAi(json: JSONObject) {
            if (json.has("error")) throw inBand(json.optJSONObject("error")?.optString("message") ?: json.optString("error"))
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
            val delta = choice.optJSONObject("delta") ?: JSONObject()
            val text = delta.optString("content", "")
            val think = delta.optString("reasoning_content", delta.optString("reasoning", ""))
            if (text.isNotEmpty()) { content.append(text); onDelta(StreamDelta(content = text)) }
            if (think.isNotEmpty()) { reasoning.append(think); onDelta(StreamDelta(reasoning = think)) }
            val calls = delta.optJSONArray("tool_calls") ?: return
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val index = call.optInt("index", i)
                val state = toolCalls.getOrPut(index) { ToolCallState() }
                // Providers may emit id/type only in the first chunk and send null or
                // empty placeholders in later chunks. Never erase a useful value.
                call.opt("id")?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }?.let { state.id = it }
                call.opt("type")?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }?.let { state.type = it }
                val function = call.optJSONObject("function")
                if (function != null) {
                    if (function.has("name")) state.name.append(function.optString("name"))
                    if (function.has("arguments")) state.arguments.append(function.optString("arguments"))
                }
            }
        }

        private fun parseAnthropic(json: JSONObject) {
            if (json.optString("type") == "error") throw inBand(json.optJSONObject("error")?.optString("message") ?: "Anthropic 流中错误")
            when (json.optString("type")) {
                "message_stop" -> doneSignal = true
                "content_block_start" -> {
                    val block = json.optJSONObject("content_block") ?: return
                    if (block.optString("type") == "tool_use") {
                        val state = toolCalls.getOrPut(json.optInt("index", toolCalls.size)) { ToolCallState() }
                        state.id = block.optString("id")
                        state.type = "function"
                        state.name.append(block.optString("name"))
                    }
                }
                "content_block_delta" -> {
                    val delta = json.optJSONObject("delta") ?: return
                    when (delta.optString("type")) {
                        "text_delta" -> {
                            val text = delta.optString("text")
                            content.append(text); if (text.isNotEmpty()) onDelta(StreamDelta(content = text))
                        }
                        "thinking_delta" -> {
                            val text = delta.optString("thinking", "")
                            reasoning.append(text); if (text.isNotEmpty()) onDelta(StreamDelta(reasoning = text))
                        }
                        // Signatures authenticate a thinking block for replay. They are
                        // not user-facing reasoning text and StreamDelta has no signature field.
                        "signature_delta" -> Unit
                        "input_json_delta" -> toolCalls.getOrPut(json.optInt("index", toolCalls.size)) { ToolCallState() }.arguments.append(delta.optString("partial_json"))
                    }
                }
            }
        }

        fun result(requestId: String?): CompletionResult {
            val calls = JSONArray()
            for ((index, state) in toolCalls.toSortedMap()) {
                val id = state.id.ifBlank { "call_$index" }
                calls.put(JSONObject().put("index", index).put("id", id).put("type", state.type)
                    .put("function", JSONObject().put("name", state.name.toString()).put("arguments", state.arguments.toString())))
            }
            return CompletionResult(content.toString(), reasoning.toString(), calls.toString(), JSONObject().put("events", rawEvents).put("last", rawLast).put("request_id", requestId ?: JSONObject.NULL).toString())
        }

        private fun inBand(message: String): ProviderException = ProviderException(ProviderException.Kind.IN_BAND, "流中错误: ${message.take(500)}")
        private data class ToolCallState(var id: String = "", var type: String = "function", val name: StringBuilder = StringBuilder(), val arguments: StringBuilder = StringBuilder())
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val MAX_ERROR_BYTES = 16_384
        const val MAX_MODEL_PAGES = 100
    }
}
