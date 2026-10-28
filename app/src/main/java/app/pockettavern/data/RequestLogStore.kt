package app.pockettavern.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Small, local, privacy-preserving request diagnostics store.
 *
 * Callers pass an event-shaped JSON object. Only the fields in the explicit
 * allowlists below are copied. This is deliberately not a general JSON log:
 * request/response bodies, prompts, headers, authorization values and unknown
 * objects must never reach disk.
 */
class RequestLogStore(
    directory: File,
    private val appVersion: String,
    private val androidSdk: Int,
) {
    private val lock = Any()
    private val file = File(directory, FILE_NAME)
    private val temporary = File(directory, "$FILE_NAME.tmp")

    init {
        directory.mkdirs()
    }

    /** Adds one filtered event. Invalid JSON and events without an object root are ignored. */
    fun record(eventJson: String) {
        if (eventJson.toByteArray(StandardCharsets.UTF_8).size > MAX_INPUT_BYTES) return
        val filtered = runCatching {
            val parsed = JsonParser.parseString(eventJson)
            if (!parsed.isJsonObject) return
            filterEvent(parsed.asJsonObject)
        }.getOrNull() ?: return

        synchronized(lock) {
            val lines = readValidLinesLocked().toMutableList()
            lines += filtered.toString()
            writeAtomicallyLocked(bound(lines))
        }
    }

    /** Returns the filtered JSONL snapshot. Malformed/stale lines are omitted. */
    fun exportJsonl(): String = synchronized(lock) {
        readValidLinesLocked().joinToString(separator = "\n", postfix = "\n").let {
            if (it == "\n") "" else it
        }
    }

    /** Deletes the current log and any interrupted temporary replacement. */
    fun clear() {
        synchronized(lock) {
            Files.deleteIfExists(file.toPath())
            Files.deleteIfExists(temporary.toPath())
        }
    }

    /**
     * Human-readable summary for support diagnostics. Billing is intentionally
     * reported as unknown: HTTP status and usage metadata cannot prove a settled
     * Venice charge.
     */
    fun latestSummary(): String = synchronized(lock) {
        val events = readObjectsLocked()
        if (events.isEmpty()) {
            return@synchronized "版本 ${safeDisplay(appVersion)} · Android SDK $androidSdk\n暂无诊断日志\n计费状态：未知（日志不能证明 Venice 是否已扣款）"
        }
        val latest = events.last()
        val builder = StringBuilder()
        builder.append("版本 ").append(stringOrUnknown(latest, "app_version", appVersion))
            .append(" · Android SDK ").append(numberOrUnknown(latest, "android_sdk", androidSdk.toLong()))
            .append('\n')
        val traceRequests = events.mapNotNull { stringValue(it, "trace_id")?.takeIf { value -> value.isNotBlank() } }.toSet()
        val terminalRequestsWithoutTrace = events.count { isInferenceTerminal(it) && stringValue(it, "trace_id").isNullOrBlank() }
        val requestCount = if (traceRequests.isNotEmpty()) traceRequests.size else terminalRequestsWithoutTrace
        builder.append("日志事件数：").append(events.size)
            .append(" · 请求数：").append(requestCount)
            .append(" · 开始：").append(dateOrUnknown(latest, "started_at_ms"))
            .append(" · 耗时：").append(durationOrUnknown(latest, "elapsed_ms"))
            .append('\n')
        builder.append("HTTP：").append(numberOrUnknown(latest, "http_status"))
            .append(" · 阶段：").append(stringOrUnknown(latest, "phase"))
            .append(" · 结果：").append(stringOrUnknown(latest, "outcome"))
            .append('\n')
        stringValue(latest, "endpoint_path")?.let { builder.append("查询接口：").append(it).append('\n') }
        events.lastOrNull { stringValue(it, "event") == "context_budget" }?.let { budget ->
            builder.append("最近上下文估算：").append(numberOrUnknown(budget, "context_input_tokens"))
                .append(" · 触发线：").append(numberOrUnknown(budget, "context_trigger_tokens"))
                .append(" · 输入上限：").append(numberOrUnknown(budget, "context_input_budget"))
                .append(" · 原因：").append(stringOrUnknown(budget, "outcome")).append('\n')
            builder.append("Skills：").append(numberOrUnknown(budget, "context_skill_tokens"))
                .append(" · 摘要：").append(numberOrUnknown(budget, "context_summary_tokens"))
                .append(" · 近期对话：").append(numberOrUnknown(budget, "context_recent_tokens")).append('\n')
        }
        val appState = stringValue(latest, "app_state")
        val errorType = stringValue(latest, "error_type")
        if (!appState.isNullOrBlank() || !errorType.isNullOrBlank()) {
            builder.append("应用状态：").append(when (appState) { "foreground" -> "前台"; "background" -> "后台"; else -> "未知" })
            if (!errorType.isNullOrBlank()) builder.append(" · 异常类型：").append(errorType)
            builder.append('\n')
        }
        builder.append("平台：").append(stringOrUnknown(latest, "provider_name"))
            .append(" · purpose：").append(stringOrUnknown(latest, "purpose"))
            .append(" · 模型：").append(stringOrUnknown(latest, "model_id"))
            .append(" · 读取空闲超时：").append(timeoutOrUnknown(latest, "read_timeout_ms", "timeout_ms"))
            .append('\n')
        builder.append("连接超时：").append(timeoutOrUnknown(latest, "connect_timeout_ms"))
            .append(" · 写入超时：").append(timeoutOrUnknown(latest, "write_timeout_ms"))
            .append(" · 整轮上限：").append(timeoutOrUnknown(latest, "call_timeout_ms"))
            .append("（读取空闲与整轮上限分别计时）\n")
        stringValue(latest, "output_budget_field")?.let { field ->
            builder.append("输出预算：")
            if (field == "omitted") builder.append("未发送（跟随平台）")
            else builder.append(field).append('=').append(numberOrUnknown(latest, "max_output_tokens"))
            if (field == "max_completion_tokens") {
                builder.append("（总计；正文上限=").append(numberOrUnknown(latest, "visible_max_output_tokens"))
                    .append("，思考预留=").append(numberOrUnknown(latest, "reasoning_budget_tokens")).append("）")
            }
            builder.append(" · 请求工具数：").append(numberOrUnknown(latest, "request_tool_count")).append('\n')
        }
        stringValue(latest, "requested_reasoning_effort")?.let { requested ->
            builder.append("推理设置：请求=").append(requested)
                .append(" · 目录默认=").append(stringOrUnknown(latest, "default_reasoning_effort"))
                .append(" · 实际参数=").append(stringValue(latest, "reasoning_effort") ?: "未发送").append('\n')
        }
        latest.get("temperature_sent")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.let { sent ->
            builder.append("温度：").append(if (sent.asBoolean) numberOrUnknown(latest, "temperature") else "未发送").append('\n')
        }
        latest.get("venice_system_prompt_included")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.let { included ->
            builder.append("Venice 平台提示：").append(if (included.asBoolean) "开启" else "关闭").append('\n')
        }
        builder.append("首字节：").append(durationOrUnknown(latest, "first_byte_ms"))
            .append(" · 首个增量：").append(durationOrUnknown(latest, "first_delta_ms"))
            .append('\n')
        builder.append("request_id：").append(stringOrUnknown(latest, "request_id"))
            .append(" · response_id：").append(stringOrUnknown(latest, "response_id"))
            .append(" · finish_reason：").append(stringOrUnknown(latest, "finish_reason"))
            .append('\n')
        builder.append("内容字符：").append(numberOrUnknown(latest, "content_chars"))
            .append(" · 推理字符：").append(numberOrUnknown(latest, "reasoning_chars"))
            .append(" · 事件数：").append(numberOrUnknown(latest, "event_count"))
            .append('\n')
        latest.getAsJsonObject("usage")?.let { usage ->
            builder.append("token：prompt=").append(numberOrUnknown(usage, "prompt_tokens"))
                .append(" completion=").append(numberOrUnknown(usage, "completion_tokens"))
                .append(" total=").append(numberOrUnknown(usage, "total_tokens"))
                .append(" reasoning=").append(numberOrNestedUnknown(usage, "reasoning_tokens", "completion_tokens_details", "reasoning_tokens"))
                .append(" cached=").append(numberOrNestedUnknown(usage, "cached_tokens", "prompt_tokens_details", "cached_tokens"))
                .append('\n')
        } ?: builder.append("token：未知\n")
        builder.append("计费状态：未知（HTTP 状态或 usage 不能证明 Venice 是否已结算扣款）")
        builder.toString()
    }

    /**
     * Returns the most recent terminal inference event, already sanitized and
     * safe to attach to a billing lookup. It never returns a prompt or body.
     */
    fun latestRequestJson(): String? = synchronized(lock) {
        readObjectsLocked().asReversed().firstOrNull(::isInferenceTerminal)?.toString()
    }

    private fun filterEvent(source: JsonObject, attachMetadata: Boolean = true): JsonObject {
        val result = JsonObject()
        for (name in STRING_FIELDS) {
            source.get(name)?.let { value ->
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    result.addProperty(name, sanitize(value.asString, stringLimit(name)))
                }
            }
        }
        for (name in INTEGER_FIELDS) {
            if (name == "done_signal") continue
            source.get(name)?.let { value ->
                parseLong(value)?.let { result.addProperty(name, it) }
            }
        }
        source.get("done_signal")?.let { value ->
            if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
                result.addProperty("done_signal", value.asBoolean)
            } else {
                parseLong(value)?.let { result.addProperty("done_signal", it) }
            }
        }
        for (name in BOOLEAN_FIELDS) {
            source.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.let { value ->
                result.addProperty(name, value.asBoolean)
            }
        }
        for (name in DECIMAL_FIELDS) {
            parseDecimal(source.get(name))?.let { result.addProperty(name, it) }
        }
        source.get("usage")?.let { usage ->
            if (usage.isJsonObject) {
                filterUsage(usage.asJsonObject).takeIf { it.size() > 0 }?.let { result.add("usage", it) }
            }
        }
        if (attachMetadata) {
            // These are generated by the app, never accepted from caller input.
            result.addProperty("app_version", sanitize(appVersion, MAX_VERSION_LENGTH))
            result.addProperty("android_sdk", androidSdk)
        } else {
            // Existing entries keep the version in which they were recorded.
            source.get("app_version")?.let { value ->
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    result.addProperty("app_version", sanitize(value.asString, MAX_VERSION_LENGTH))
                }
            }
            source.get("android_sdk")?.let { value ->
                parseLong(value)?.let { result.addProperty("android_sdk", it) }
            }
        }
        return result
    }

    private fun filterUsage(source: JsonObject): JsonObject {
        val result = JsonObject()
        for (name in USAGE_NUMBER_FIELDS) {
            source.get(name)?.let { value ->
                parseDecimal(value)?.let { result.addProperty(name, it) }
            }
        }
        for (name in listOf("cost", "costs")) {
            source.get(name)?.takeIf { it.isJsonObject }?.asJsonObject?.let { costs ->
                val out = JsonObject()
                for (field in listOf("input_tokens", "output_tokens", "total", "usd")) {
                    parseDecimal(costs.get(field))?.let { out.addProperty(field, it) }
                }
                if (out.size() > 0) result.add(name, out)
            }
        }
        source.get("completion_tokens_details")?.let { details ->
            if (details.isJsonObject) {
                val out = JsonObject()
                parseDecimal(details.asJsonObject.get("reasoning_tokens"))?.let { out.addProperty("reasoning_tokens", it) }
                if (out.size() > 0) result.add("completion_tokens_details", out)
            }
        }
        source.get("prompt_tokens_details")?.let { details ->
            if (details.isJsonObject) {
                val out = JsonObject()
                parseDecimal(details.asJsonObject.get("cached_tokens"))?.let { out.addProperty("cached_tokens", it) }
                if (out.size() > 0) result.add("prompt_tokens_details", out)
            }
        }
        return result
    }

    private fun readValidLinesLocked(): List<String> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines(StandardCharsets.UTF_8)
                .mapNotNull { line ->
                    runCatching {
                        val parsed = JsonParser.parseString(line)
                        if (!parsed.isJsonObject) null else filterEvent(parsed.asJsonObject, attachMetadata = false).toString()
                    }.getOrNull()
                }
        }.getOrDefault(emptyList())
    }

    private fun readObjectsLocked(): List<JsonObject> = readValidLinesLocked().mapNotNull {
        runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull()
    }

    private fun bound(lines: List<String>): List<String> {
        val retained = lines.takeLast(MAX_EVENTS).toMutableList()
        while (utf8Size(retained) > MAX_BYTES && retained.isNotEmpty()) retained.removeAt(0)
        return retained
    }

    private fun writeAtomicallyLocked(lines: List<String>) {
        val parent = file.parentFile ?: return
        parent.mkdirs()
        val payload = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
        temporary.writeText(payload, StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun utf8Size(lines: List<String>): Int = lines.sumOf { it.toByteArray(StandardCharsets.UTF_8).size + 1 }

    private fun parseLong(value: JsonElement?): Long? {
        val decimal = parseDecimal(value) ?: return null
        return runCatching { decimal.longValueExact() }.getOrNull()
    }

    private fun parseDecimal(value: JsonElement?): BigDecimal? {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return runCatching { BigDecimal(value.asString) }.getOrNull()
    }

    private fun stringLimit(name: String): Int = when (name) {
        "api_host" -> MAX_HOST_LENGTH
        "trace_id", "request_id", "response_id" -> MAX_ID_LENGTH
        else -> MAX_STRING_LENGTH
    }

    private fun sanitize(value: String, maxLength: Int): String {
        val redacted = value
            .replace(SK_PATTERN, "[REDACTED]")
            .replace(BEARER_PATTERN, "Bearer [REDACTED]")
        return redacted.take(maxLength)
    }

    private fun safeDisplay(value: String): String = sanitize(value, MAX_VERSION_LENGTH).ifBlank { "未知" }

    private fun stringOrUnknown(source: JsonObject, name: String, fallback: String? = null): String {
        val value = stringValue(source, name)
        return value?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }?.let(::safeDisplay) ?: "未知"
    }

    private fun stringValue(source: JsonObject, name: String): String? =
        source.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun isInferenceTerminal(source: JsonObject): Boolean {
        if (stringValue(source, "outcome").isNullOrBlank()) return false
        val event = stringValue(source, "event")?.lowercase(Locale.ROOT).orEmpty()
        return event in TERMINAL_EVENTS ||
            (event.contains("inference") && (event.contains("terminal") || event.contains("finished"))) ||
            (event.isBlank() && stringValue(source, "purpose")?.lowercase(Locale.ROOT) in setOf("chat", "compression"))
    }

    private fun numberOrUnknown(source: JsonObject, name: String, fallback: Long? = null): String {
        val value = parseDecimal(source.get(name))?.stripTrailingZeros()?.toPlainString()
        return value ?: fallback?.toString() ?: "未知"
    }

    private fun numberOrNestedUnknown(source: JsonObject, direct: String, objectName: String, nested: String): String {
        parseDecimal(source.get(direct))?.stripTrailingZeros()?.toPlainString()?.let { return it }
        val nestedObject = source.get(objectName)?.takeIf { it.isJsonObject }?.asJsonObject ?: return "未知"
        return numberOrUnknown(nestedObject, nested)
    }

    private fun dateOrUnknown(source: JsonObject, name: String): String {
        val millis = parseLong(source.get(name)) ?: return "未知"
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getDefault() }
        return format.format(Date(millis))
    }

    private fun durationOrUnknown(source: JsonObject, name: String): String {
        val millis = parseLong(source.get(name)) ?: return "未知"
        return formatDuration(millis)
    }

    private fun timeoutOrUnknown(source: JsonObject, name: String, fallbackName: String? = null): String {
        val millis = parseLong(source.get(name)) ?: fallbackName?.let { parseLong(source.get(it)) } ?: return "未知"
        return if (millis <= 0) "未设置（等待完成或手动停止）" else formatDuration(millis)
    }

    private fun formatDuration(millis: Long): String {
        return if (millis < 1_000) "${millis}ms" else "${"%.2f".format(Locale.ROOT, millis / 1000.0)}s"
    }

    private companion object {
        const val FILE_NAME = "request-diagnostics.jsonl"
        const val MAX_EVENTS = 200
        const val MAX_BYTES = 1 * 1024 * 1024
        const val MAX_INPUT_BYTES = 4 * 1024 * 1024
        const val MAX_STRING_LENGTH = 256
        const val MAX_HOST_LENGTH = 512
        const val MAX_ID_LENGTH = 192
        const val MAX_VERSION_LENGTH = 64
        val STRING_FIELDS = setOf(
            "trace_id", "event", "phase", "request_id", "response_id", "finish_reason", "outcome",
            "provider_kind", "provider_name", "provider_id", "model_id", "api_host", "purpose", "endpoint_path",
            "app_state", "error_type", "output_budget_field", "requested_reasoning_effort", "default_reasoning_effort", "reasoning_effort",
        )
        val INTEGER_FIELDS = setOf(
            "started_at_ms", "elapsed_ms", "http_status", "first_byte_ms", "first_delta_ms",
            "timeout_ms", "read_timeout_ms", "connect_timeout_ms", "write_timeout_ms", "call_timeout_ms", "content_chars", "reasoning_chars", "tool_calls", "event_count",
            "malformed_events", "done_signal", "max_output_tokens", "visible_max_output_tokens", "reasoning_budget_tokens", "input_message_count", "input_chars", "request_tool_count",
            "context_input_tokens", "context_input_budget", "context_trigger_tokens", "context_skill_tokens", "context_summary_tokens",
            "context_recent_tokens", "context_tool_tokens", "context_model_window", "context_daily_limit", "tool_round",
        )
        val BOOLEAN_FIELDS = setOf("temperature_sent", "venice_system_prompt_included", "disable_thinking", "strip_thinking_response", "generation_budget_enabled")
        val DECIMAL_FIELDS = setOf("temperature")
        val USAGE_NUMBER_FIELDS = setOf(
            "prompt_tokens", "completion_tokens", "total_tokens", "input_tokens", "output_tokens",
            "reasoning_tokens", "cached_tokens", "cost", "costs",
        )
        val TERMINAL_EVENTS = setOf("finished", "inference_terminal", "inference_finished", "completion_finished", "terminal")
        val SK_PATTERN = Regex("sk-[A-Za-z0-9_-]*")
        val BEARER_PATTERN = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    }
}
