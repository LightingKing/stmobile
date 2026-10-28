package app.pockettavern.engine

import app.pockettavern.domain.CompletionResult
import org.json.JSONArray
import org.json.JSONObject

/** A protocol-correct fake model; it deliberately does not perform semantic summarization. */
internal fun compactReply(input: String, summary: String, text: String = summary, finishReason: String = "stop"): CompletionResult {
    val sources = JSONObject(input).getJSONArray("sources")
    val ids = (0 until sources.length()).map { sources.getJSONObject(it).getString("id") }.distinct()
    val memories = JSONArray()
    if (text.isNotBlank()) memories.put(JSONObject().put("title", "测试事实").put("kind", "fact")
        .put("entity", "").put("aliases", JSONArray()).put("text", text).put("sourceMessageIds", JSONArray(ids)))
    return CompletionResult(content = JSONObject().put("summary", summary).put("memories", memories).toString(), finishReason = finishReason)
}

internal fun compactSourceText(input: String): String {
    val sources = JSONObject(input).getJSONArray("sources")
    return (0 until sources.length()).joinToString("") { sources.getJSONObject(it).getString("text") }
}

internal fun compactSourceIds(input: String): List<String> {
    val sources = JSONObject(input).getJSONArray("sources")
    return (0 until sources.length()).map { sources.getJSONObject(it).getString("id") }.distinct()
}
