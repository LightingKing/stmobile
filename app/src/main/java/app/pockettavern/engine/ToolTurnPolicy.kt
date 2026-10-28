package app.pockettavern.engine

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/** In-memory guard for one generation; it neither searches nor persists user data. */
class ToolTurnPolicy(
    private val memoryCallLimit: Int = 6,
    private val memoryRoundLimit: Int = 3,
    private val noProgressLimit: Int = 2
) {
    data class Decision(val execute: Boolean, val response: String? = null)

    var shouldFinalize: Boolean = false
        private set
    var reason: String = ""
        private set

    private val attempted = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()
    private val evidence = mutableSetOf<String>()
    private var memoryCalls = 0
    private var memoryRounds = 0
    private var consecutiveNoProgress = 0

    init {
        require(memoryCallLimit > 0 && memoryRoundLimit > 0 && noProgressLimit > 0)
    }

    /** Call for every requested tool, including subsequent calls in the same batch. */
    fun beforeCall(name: String, argumentsJson: String): Decision {
        if (shouldFinalize) return blocked("tool_turn_finished")
        if (!isMemoryTool(name)) return Decision(execute = true)
        if (memoryCalls >= memoryCallLimit || memoryRounds >= memoryRoundLimit) {
            finish("memory_limit")
            return blocked("memory_limit")
        }
        val key = key(name, argumentsJson)
        if (!attempted.add(key)) {
            finish("duplicate_memory_call")
            return blocked("duplicate_memory_call")
        }
        memoryCalls++
        pending += key
        return Decision(execute = true)
    }

    /** Record the actual result only for a tool that beforeCall allowed to execute. */
    fun recordResult(name: String, argumentsJson: String, response: String) {
        if (!isMemoryTool(name) || !pending.remove(key(name, argumentsJson))) return
        val value = runCatching { JSONTokener(response).nextValue() }.getOrNull()
        val result = value as? JSONObject
        if (result?.optString("error") == "budget_exhausted") {
            finish("memory_budget_exhausted")
            return
        }
        var added = false
        if (result?.has("error") != true) {
            if (name == "history_search") {
                val matches = (value as? JSONArray) ?: result?.optJSONArray("matches")
                if (matches != null) for (index in 0 until matches.length()) {
                    val hit = matches.optJSONObject(index) ?: continue
                    val id = hit.optString("id")
                    if (id.isNotBlank() && hit.optString("text").isNotBlank()) {
                        added = evidence.add("search:$id:${hit.optInt("offset", 0).coerceAtLeast(0)}") || added
                    }
                }
            } else if (result != null) {
                val id = result.optString("id")
                val text = result.optString("text")
                if (id.isNotBlank() && text.isNotBlank()) {
                    added = evidence.add("read:$id:${result.optInt("offset", 0).coerceAtLeast(0)}:${text.length}")
                }
            }
        }
        consecutiveNoProgress = if (added) 0 else consecutiveNoProgress + 1
        when {
            consecutiveNoProgress >= noProgressLimit -> finish("no_new_memory")
            memoryCalls >= memoryCallLimit -> finish("memory_limit")
        }
    }

    /** One model response may contain several tools; count it as one retrieval round. */
    fun completedRound(names: List<String>) {
        if (names.any(::isMemoryTool)) {
            memoryRounds++
            if (memoryRounds >= memoryRoundLimit) finish("memory_limit")
        }
    }

    /** The configured limit counts tool-enabled requests; one extra answer is always allowed. */
    fun finalAnswerRequired(step: Int, maxToolSteps: Int): Boolean =
        shouldFinalize || step >= maxToolSteps.coerceAtLeast(0)

    /** Add a final-mode instruction before dialogue without interrupting tool-call/result adjacency. */
    fun finalMessages(messagesJson: String): String {
        val original = JSONArray(messagesJson)
        val result = JSONArray()
        var added = false
        for (index in 0 until original.length()) {
            val message = original.getJSONObject(index)
            if (!added && message.optString("role") != "system") {
                result.put(JSONObject().put("role", "system").put("content", finalInstruction))
                added = true
            }
            result.put(message)
        }
        if (!added) result.put(JSONObject().put("role", "system").put("content", finalInstruction))
        return result.toString()
    }

    /** Used only on the final extra request, with provider tool execution disabled. */
    val finalInstruction: String
        get() = "【本轮工具检索已结束】请现在直接回答用户最新要求，依据当前摘要、近期对话和本轮已有工具结果。" +
            "不得再调用工具或继续输出查询计划，不要声称还在后台查询，也不要展示工具 JSON。" +
            "如果用户要续写，就从当前场景衔接正文；若需要的过去细节仍缺证据，明确保留未知，不编造成已经发生的事实。"

    private fun finish(value: String) {
        if (!shouldFinalize) reason = value
        shouldFinalize = true
    }

    private fun blocked(code: String): Decision = Decision(execute = false, response = JSONObject()
        .put("error", code).put("hint", "本轮查询已停止，请根据已有结果直接回答；缺失事实保留未知。").toString())

    private fun key(name: String, argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return "$name:${argumentsJson.trim()}"
        return when (name) {
            "history_search" -> "$name:" + args.optString("query").trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
            "history_read" -> "$name:${args.optString("id").trim()}:${args.optInt("offset", 0).coerceAtLeast(0)}"
            else -> name
        }
    }

    companion object {
        fun isMemoryTool(name: String): Boolean = name == "history_search" || name == "history_read"
    }
}
