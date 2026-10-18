package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Attachment
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.CompressionSlice
import app.pockettavern.domain.ContextBuild
import app.pockettavern.domain.Message
import app.pockettavern.domain.Memory
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.Preset
import app.pockettavern.domain.Summary
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

/** Builds the exact OpenAI-compatible message list used for the next request. */
object ContextEngine {
    private const val DEFAULT_CONTEXT = 32_768
    private const val SAFETY_FLOOR = 512

    fun build(data: AppData, chat: Chat): ContextBuild {
        val preset = data.presets.firstOrNull { it.id == chat.presetId }
            ?: data.presets.firstOrNull()
            ?: Preset(id = chat.presetId)
        val character = data.characters.firstOrNull { it.id == chat.characterId }
        val model = data.providers.firstOrNull { it.id == chat.providerId }
            ?.models?.firstOrNull { it.id == chat.modelId }
        val messages = mutableListOf<JSONObject>()
        fun addSystem(text: String) {
            if (text.isNotBlank()) messages += JSONObject().put("role", "system").put("content", text)
        }

        addSystem(preset.systemPrompt)
        addSystem(characterPrompt(character, data.settings.userPersona))

        val latestUserText = chat.messages.asReversed().firstOrNull { it.role == "user" }?.content.orEmpty()
        val memories = data.memories.filter { it.enabled && memoryApplies(it, chat, character) }
        if (memories.isNotEmpty()) addSystem("【已确认记忆】\n" + memories.joinToString("\n") { "- ${it.text}" })

        val world = data.worldEntries.filter {
            it.enabled && (it.characterId.isBlank() || it.characterId == chat.characterId) &&
                (it.always || it.keywords.any { keyword -> keyword.isNotBlank() && latestUserText.contains(keyword, ignoreCase = true) })
        }.sortedByDescending { it.priority }
        if (world.isNotEmpty()) addSystem("【世界书资料】\n" + world.joinToString("\n") { "- ${it.name}: ${it.content}" })

        val skills = data.skills.filter { it.enabled && it.id in chat.skillIds }
        skills.forEach { addSystem("【Skill: ${it.name}】\n${it.content}") }
        val granted = skills.flatMap { it.permissions }.toSet()
        if ("network" in granted && data.providers.isNotEmpty()) {
            val providers = data.providers.joinToString("\n") { provider ->
                "- id=${provider.id}; name=${provider.name}; baseUrl=${provider.baseUrl}"
            }
            addSystem("【可用 API 配置（不含密钥）】\n$providers")
        }
        val toolDefinitions = if (model?.supportsTools == true) ToolRuntime.definitionsForPermissions(granted) else "[]"

        val validSummary = findValidSummary(chat)
        val cutoff = validSummary?.let { chat.messages.indexOfFirst { message -> message.id == it.throughMessageId } } ?: -1
        if (validSummary != null && validSummary.text.isNotBlank()) addSystem("【历史摘要】\n${validSummary.text}")

        chat.messages.drop(cutoff + 1).forEach { message -> messages += messageJson(message, model) }
        val messagesJson = JSONArray().apply { messages.forEach { put(it) } }.toString()
        val estimated = estimateMessagesTokens(messages) + if (toolDefinitions == "[]") 0 else estimateTokens(toolDefinitions)
        val modelWindow = model?.contextTokens?.takeIf { it > 0 } ?: DEFAULT_CONTEXT
        val contextWindow = data.settings.contextOverride.takeIf { it > 0 }?.let { minOf(it, modelWindow) } ?: modelWindow
        val reserve = maxOf(SAFETY_FLOOR, (contextWindow * 0.05).toInt())
        val budget = (contextWindow - preset.maxOutput.coerceAtLeast(0) - reserve).coerceAtLeast(1)
        val threshold = data.settings.compressionThreshold.coerceIn(0.1, 1.0)
        val needsCompression = estimated > budget || estimated >= (budget * threshold).toInt()
        val preview = buildPreview(previewMessagesJson(messagesJson), toolDefinitions, estimated, budget, validSummary, skills.size)
        return ContextBuild(messagesJson, estimated, budget, needsCompression, preview)
    }

    fun compressionSlice(data: AppData, chat: Chat): CompressionSlice? {
        val keepTurns = data.settings.keepRecentTurns.coerceAtLeast(0)
        val groups = turnGroups(chat.messages)
        if (groups.size <= keepTurns || groups.isEmpty()) return null
        var cutAt = groups.dropLast(keepTurns).flatten()
        // A summary boundary may never cut through an assistant tool-call group.
        // If a provider left a call incomplete, leave that call in the live tail.
        val brokenIndex = groups.dropLast(keepTurns).indexOfFirst { group -> !isCompleteToolGroup(group) }
        if (brokenIndex >= 0) cutAt = groups.take(brokenIndex).flatten()
        if (cutAt.isEmpty()) return null
        val last = cutAt.last()
        return CompressionSlice(cutAt, last.id, sourceHash(cutAt))
    }

    /** Hashes the complete ordered source messages; changing any field invalidates a summary. */
    fun sourceHash(messages: List<Message>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        messages.forEach { message ->
            val json = messageCanonicalJson(message).toString()
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Deliberately conservative fallback in the absence of a model-specific tokenizer. */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var units = 0.0
        text.codePoints().forEach { codePoint ->
            units += when {
                isCjk(codePoint) -> 1.5
                codePoint <= 0x7f -> if (codePoint == 9 || codePoint == 10 || codePoint == 13 || codePoint == 32) 0.25 else 0.5
                else -> 1.0
            }
        }
        return maxOf(1, kotlin.math.ceil(units).toInt())
    }

    /**
     * Returns bounded, human-readable source chunks for the summary model.
     * Text attachments and tool arguments remain available; binary payloads do not.
     */
    fun summarySource(messages: List<Message>): List<String> = messages.map { message ->
        val attachmentText = message.attachments.joinToString("; ") { attachment ->
            when {
                attachment.isText() -> "text(name=${attachment.name},mime=${attachment.mime}): ${if (attachment.dataUrl.isNotBlank()) dataUrlText(attachment.dataUrl) else "[path=${attachment.path}]"}"
                attachment.isImage() -> "image(name=${attachment.name},mime=${attachment.mime})"
                attachment.isAudio() -> "audio(name=${attachment.name},mime=${attachment.mime})"
                else -> "attachment(name=${attachment.name},mime=${attachment.mime})"
            }
        }
        buildString {
            append("id=").append(message.id).append(" role=").append(message.role)
            append(" content=").append(message.content)
            if (message.reasoning.isNotBlank()) append(" reasoning_content=").append(message.reasoning)
            if (message.toolCallsJson.isNotBlank() && message.toolCallsJson != "[]") append(" tool_calls=").append(message.toolCallsJson)
            if (message.toolCallId.isNotBlank()) append(" tool_call_id=").append(message.toolCallId)
            if (attachmentText.isNotBlank()) append(" attachments=").append(attachmentText)
        }
    }

    private fun characterPrompt(character: Character?, globalPersona: String): String {
        if (character == null && globalPersona.isBlank()) return ""
        val parts = mutableListOf<String>()
        character?.let {
            if (it.name.isNotBlank()) parts += "角色：${it.name}"
            if (it.description.isNotBlank()) parts += "描述：${it.description}"
            if (it.personality.isNotBlank()) parts += "性格：${it.personality}"
            if (it.scenario.isNotBlank()) parts += "场景：${it.scenario}"
            if (it.examples.isNotBlank()) parts += "示例对话：${it.examples}"
            if (it.systemPrompt.isNotBlank()) parts += it.systemPrompt
            if (it.userPersona.isNotBlank()) parts += "用户人设：${it.userPersona}"
        }
        if (globalPersona.isNotBlank()) parts += "全局用户人设：$globalPersona"
        return parts.joinToString("\n")
    }

    private fun memoryApplies(memory: Memory, chat: Chat, character: Character?): Boolean {
        return when (memory.scope.lowercase()) {
            "global" -> true
            "character" -> memory.targetId.isNotBlank() && memory.targetId == character?.id
            "chat" -> memory.targetId.isNotBlank() && memory.targetId == chat.id
            else -> false
        }
    }

    private fun findValidSummary(chat: Chat): Summary? {
        if (chat.activeSummaryId.isBlank()) return null
        val selected = chat.summaries.firstOrNull { it.id == chat.activeSummaryId } ?: return null
        val end = chat.messages.indexOfFirst { it.id == selected.throughMessageId }
        return selected.takeIf { end >= 0 && sourceHash(chat.messages.take(end + 1)) == it.sourceHash }
    }

    private fun messageJson(message: Message, model: ModelInfo?): JSONObject {
        val json = JSONObject().put("role", message.role).put("content", contentFor(message, model))
        if (message.reasoning.isNotBlank()) json.put("reasoning_content", message.reasoning)
        if (message.toolCallsJson.isNotBlank() && message.toolCallsJson != "[]") {
            json.put("tool_calls_json", message.toolCallsJson)
            runCatching { json.put("tool_calls", JSONArray(message.toolCallsJson)) }
        }
        if (message.toolCallId.isNotBlank()) json.put("tool_call_id", message.toolCallId)
        return json
    }

    private fun contentFor(message: Message, model: ModelInfo?): Any {
        if (message.attachments.isEmpty()) return message.content
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", message.content))
        message.attachments.forEach { attachment ->
            when {
                attachment.isImage() && model?.supportsVision == true && attachment.dataUrl.isNotBlank() ->
                    content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", attachment.dataUrl)))
                attachment.isAudio() && supportsAudioInput(model) && attachment.dataUrl.isNotBlank() ->
                    content.put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
                        .put("data", dataUrlPayload(attachment.dataUrl)).put("format", audioFormat(attachment.mime))))
                attachment.isText() && attachment.dataUrl.isNotBlank() ->
                    content.put(JSONObject().put("type", "text").put("text", "【文本附件：${attachment.name}】\n${dataUrlText(attachment.dataUrl)}"))
                else -> content.put(JSONObject().put("type", "text").put("text", attachmentNotice(attachment, model)))
            }
        }
        return content
    }

    private fun Attachment.isImage(): Boolean = mime.lowercase().startsWith("image/")
    private fun Attachment.isAudio(): Boolean = mime.lowercase().startsWith("audio/")
    private fun Attachment.isText(): Boolean = mime.lowercase().startsWith("text/") || mime.lowercase() in setOf("application/json", "application/xml", "application/csv")

    private fun supportsAudioInput(model: ModelInfo?): Boolean {
        if (model == null) return false
        return runCatching {
            val raw = JSONObject(model.rawJson)
            raw.optBoolean("supportsAudioInput") ||
                raw.optJSONObject("capabilities")?.optBoolean("supportsAudioInput") == true ||
                raw.optJSONObject("model_spec")?.optJSONObject("capabilities")?.optBoolean("supportsAudioInput") == true ||
                raw.optJSONArray("input_modalities")?.let { modalities ->
                (0 until modalities.length()).any { modalities.optString(it).equals("audio", true) }
            } == true
        }.getOrDefault(false)
    }

    private fun dataUrlPayload(dataUrl: String): String = dataUrl.substringAfter(",", dataUrl)
    private fun dataUrlText(dataUrl: String): String {
        if (!dataUrl.startsWith("data:", true)) return dataUrl
        return runCatching {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) return@runCatching dataUrl
            val metadata = dataUrl.substring(0, comma)
            val payload = dataUrl.substring(comma + 1)
            if (metadata.contains(";base64", true)) {
                String(java.util.Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)
            } else URLDecoder.decode(payload, StandardCharsets.UTF_8.name())
        }.getOrDefault("[文本附件内容无法解码]")
    }
    private fun audioFormat(mime: String): String = mime.substringAfter('/', "wav").substringBefore(';').ifBlank { "wav" }
    private fun attachmentNotice(attachment: Attachment, model: ModelInfo?): String = when {
        attachment.isImage() && model?.supportsVision != true -> "[附件未发送：当前模型不支持视觉输入：${attachment.name}]"
        attachment.isAudio() && !supportsAudioInput(model) -> "[附件未发送：当前模型不支持音频输入：${attachment.name}]"
        attachment.dataUrl.isBlank() && attachment.path.isNotBlank() -> "[附件未加载：路径需要用户授权读取：${attachment.name}]"
        else -> "[附件未发送：格式不受支持：${attachment.name}]"
    }

    private fun messageCanonicalJson(message: Message): JSONObject = JSONObject()
        .put("id", message.id).put("role", message.role).put("content", message.content)
        .put("reasoning", message.reasoning).put("toolCallsJson", message.toolCallsJson)
        .put("toolCallId", message.toolCallId).put("status", message.status).put("createdAt", message.createdAt)
        .put("attachments", JSONArray().apply {
            message.attachments.forEach { attachment ->
                put(JSONObject().put("id", attachment.id).put("name", attachment.name).put("mime", attachment.mime)
                    .put("path", attachment.path).put("dataUrl", attachment.dataUrl))
            }
        })

    private fun buildPreview(messagesJson: String, toolDefinitions: String, estimated: Int, budget: Int, summary: Summary?, skills: Int): String {
        val summaryPart = if (summary == null) "无有效摘要" else "摘要至 ${summary.throughMessageId}"
        return "$summaryPart；Skills $skills 个；估算 $estimated/$budget tokens\n实际 messages JSON：$messagesJson\n工具定义 JSON：$toolDefinitions"
    }

    private fun previewMessagesJson(messagesJson: String): String = runCatching {
        val messages = JSONArray(messagesJson)
        for (messageIndex in 0 until messages.length()) {
            val message = messages.optJSONObject(messageIndex) ?: continue
            val content = message.optJSONArray("content") ?: continue
            for (contentIndex in 0 until content.length()) {
                val item = content.optJSONObject(contentIndex) ?: continue
                when (item.optString("type")) {
                    "image_url" -> {
                        val image = item.optJSONObject("image_url") ?: continue
                        val url = image.optString("url")
                        image.put("url", binaryPreviewMarker(url, "image"))
                    }
                    "input_audio" -> {
                        val audio = item.optJSONObject("input_audio") ?: continue
                        val data = audio.optString("data")
                        audio.put("data", binaryPreviewMarker(data, "audio"))
                    }
                }
            }
        }
        messages.toString()
    }.getOrDefault(messagesJson)

    private fun binaryPreviewMarker(value: String, kind: String): String {
        val metadata = value.substringBefore(',').takeIf { it.startsWith("data:", true) } ?: "payload"
        return "[二进制附件已省略 kind=$kind bytes=${value.toByteArray(StandardCharsets.UTF_8).size} metadata=$metadata]"
    }

    private fun estimateMessagesTokens(messages: List<JSONObject>): Int {
        var total = 0
        messages.forEach { message ->
            val copy = JSONObject(message.toString())
            val content = copy.opt("content")
            var imageCount = 0
            var audioCount = 0
            if (content is JSONArray) {
                val safeContent = JSONArray()
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index)
                    when (item?.optString("type")) {
                        "image_url" -> { imageCount++; safeContent.put("[image input]") }
                        "input_audio" -> { audioCount++; safeContent.put("[audio input]") }
                        else -> safeContent.put(item ?: content.opt(index))
                    }
                }
                copy.put("content", safeContent)
            }
            total += estimateTokens(copy.toString()) + imageCount * IMAGE_TOKEN_ALLOWANCE + audioCount * AUDIO_TOKEN_ALLOWANCE
        }
        return total
    }

    private fun isCjk(codePoint: Int): Boolean =
        codePoint in 0x2E80..0x9FFF || codePoint in 0xF900..0xFAFF || codePoint in 0x20000..0x3134F

    private const val IMAGE_TOKEN_ALLOWANCE = 768
    private const val AUDIO_TOKEN_ALLOWANCE = 1536

    /** Groups a conversation at user boundaries while keeping assistant tool calls/results together. */
    private fun turnGroups(messages: List<Message>): List<List<Message>> {
        if (messages.isEmpty()) return emptyList()
        val atomic = mutableListOf<List<Message>>()
        var index = 0
        while (index < messages.size) {
            val current = messages[index]
            if (current.role == "assistant") {
                val ids = toolCallIds(current.toolCallsJson)
                if (ids.isNotEmpty()) {
                    val group = mutableListOf(current)
                    var cursor = index + 1
                    while (cursor < messages.size && messages[cursor].role == "tool" && messages[cursor].toolCallId in ids) {
                        group += messages[cursor++]
                    }
                    atomic += group
                    index = cursor
                    continue
                }
            }
            atomic += listOf(current)
            index++
        }
        val turns = mutableListOf<MutableList<Message>>()
        atomic.forEach { atom ->
            if (turns.isEmpty() || atom.any { it.role == "user" }) turns += atom.toMutableList()
            else turns.last().addAll(atom)
        }
        return turns
    }

    private fun toolCallIds(raw: String): Set<String> = runCatching {
        val result = mutableSetOf<String>()
        val array = JSONArray(raw)
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            if (item is JSONObject) item.optString("id").takeIf { it.isNotBlank() }?.let(result::add)
        }
        result
    }.getOrDefault(emptySet())

    private fun isCompleteToolGroup(group: List<Message>): Boolean {
        return group.filter { it.role == "assistant" }.all { assistant ->
            val ids = toolCallIds(assistant.toolCallsJson)
            ids.isEmpty() || ids.all { id -> group.any { it.role == "tool" && it.toolCallId == id } }
        }
    }
}
