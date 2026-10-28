package app.pockettavern.data

import app.pockettavern.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.Base64
import java.util.zip.InflaterInputStream

object Exchange {
    fun character(bytes: ByteArray): Character {
        val json = if (bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) pngCharacter(bytes) else bytes.toString(Charsets.UTF_8)
        val root = JSONObject(json.removePrefix("\uFEFF"))
        val data = root.optJSONObject("data") ?: root
        require(data.optString("name").isNotBlank()) { "角色卡缺少名称" }
        return Character(name=data.optString("name"),description=data.optString("description"),personality=data.optString("personality"),scenario=data.optString("scenario"),firstMessage=data.optString("first_mes"),examples=data.optString("mes_example"),systemPrompt=data.optString("system_prompt"),userPersona=data.optString("user_persona"),originalJson=root.toString(),defaultPresetId=data.optJSONObject("extensions")?.optJSONObject("pocket_tavern")?.optString("defaultPresetId").orEmpty())
    }
    private fun pngCharacter(bytes: ByteArray): String {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val signature = ByteArray(8); input.readFully(signature)
        while (input.available() >= 12) {
            val length = input.readInt()
            require(length in 0..(16 * 1024 * 1024) && length <= input.available() - 8) { "PNG 数据损坏" }
            val kind = ByteArray(4); input.readFully(kind)
            val data = ByteArray(length); input.readFully(data); input.readInt()
            val type = kind.toString(Charsets.US_ASCII)
            if (type == "tEXt" || type == "zTXt") {
                val zero = data.indexOf(0.toByte())
                if (zero > 0 && data.copyOfRange(0, zero).toString(Charsets.ISO_8859_1) in listOf("chara", "ccv3")) {
                    val text = if (type == "tEXt") data.copyOfRange(zero + 1, data.size) else {
                        require(zero + 2 <= data.size && data[zero + 1] == 0.toByte()) { "不支持的 PNG 压缩格式" }
                        InflaterInputStream(ByteArrayInputStream(data.copyOfRange(zero + 2, data.size))).use { stream ->
                            val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                            while(true) { val count=stream.read(buffer); if(count<0) break; require(out.size()+count<=4*1024*1024) { "角色卡内容过大" }; out.write(buffer,0,count) }
                            out.toByteArray()
                        }
                    }
                    require(text.size <= 4 * 1024 * 1024) { "角色卡内容过大" }
                    return Base64.getMimeDecoder().decode(text).toString(Charsets.UTF_8)
                }
            }
            if (type == "IEND") break
        }
        error("PNG 内未找到 chara/ccv3 角色卡数据")
    }
    fun characterJson(character: Character): String {
        val root = runCatching { JSONObject(character.originalJson) }.getOrDefault(JSONObject())
        val data = root.optJSONObject("data") ?: JSONObject()
        data.put("name",character.name).put("description",character.description).put("personality",character.personality).put("scenario",character.scenario).put("first_mes",character.firstMessage).put("mes_example",character.examples).put("system_prompt",character.systemPrompt)
        data.put("user_persona", character.userPersona)
        val extensions = data.optJSONObject("extensions") ?: JSONObject()
        val pocket = extensions.optJSONObject("pocket_tavern") ?: JSONObject()
        data.put("extensions", extensions.put("pocket_tavern", pocket.put("defaultPresetId", character.defaultPresetId)))
        return root.put("spec","chara_card_v2").put("spec_version","2.0").put("data",data).toString(2)
    }
    fun worldbook(json: String, characterId: String = ""): List<WorldEntry> {
        val root = JSONObject(json)
        val entries = root.optJSONObject("entries")?.let { obj -> obj.keys().asSequence().map { obj.getJSONObject(it) }.toList() }
            ?: root.optJSONArray("entries")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            ?: error("世界书缺少 entries")
        require(entries.size <= 2000) { "世界书条目过多" }
        return entries.map { item ->
            val keys = item.optJSONArray("key") ?: item.optJSONArray("keys") ?: JSONArray()
            WorldEntry(name=item.optString("comment",item.optString("name","世界书条目")),content=item.optString("content"),keywords=(0 until keys.length()).map { keys.getString(it) },always=item.optBoolean("constant"),enabled=!item.optBoolean("disable"),characterId=characterId,priority=item.optInt("order",item.optInt("insertion_order",0)),searchRange=item.optInt("searchRange",0).coerceIn(0,10_000))
        }
    }

    /** Imports the portable preset fields used by the app and common ST aliases. */
    fun preset(bytes: ByteArray, fallbackName: String = "导入预设"): Preset =
        preset(bytes.toString(Charsets.UTF_8), fallbackName)

    fun preset(json: String, fallbackName: String = "导入预设"): Preset {
        val root = JSONObject(json.removePrefix("\uFEFF"))
        val data = root.optJSONObject("data") ?: root
        val name = data.optString("name", data.optString("title", fallbackName)).ifBlank { fallbackName }
        val id = data.optString("id").takeIf { it.isNotBlank() } ?: newId()
        require(!data.has("reasoningEffort") || data.opt("reasoningEffort") is String) { "预设思考强度必须为字符串" }
        val effort = data.optString("reasoningEffort", "default")
        require(effort in PresetOptions.reasoningEfforts) { "预设思考强度无效" }
        require(!data.has("includeVeniceSystemPrompt") || data.opt("includeVeniceSystemPrompt") is Boolean) { "Venice 平台提示开关必须为布尔值" }
        require(!data.has("reasoningBudget") || data.opt("reasoningBudget") is Number) { "预设思考预留必须为数字" }
        require(!data.has("generationBudgetEnabled") || data.opt("generationBudgetEnabled") is Boolean) { "生成预算开关必须为布尔值" }
        val reasoningBudget = data.optInt("reasoningBudget", data.optInt("reasoning_budget_tokens", data.optInt("thinkingBudget", 0)))
        require(reasoningBudget in 0..131072) { "预设思考预留必须在 0–131072 token 之间" }
        return Preset(
            id = id,
            name = name,
            systemPrompt = data.optString("systemPrompt", data.optString("content", data.optString("system_prompt"))),
            temperature = data.optDouble("temperature", 0.8),
            maxOutput = data.optInt("maxOutput", data.optInt("openai_max_tokens", data.optInt("max_tokens", 2048))),
            summaryPrompt = data.optString("summaryPrompt", data.optString("summary_prompt", DEFAULT_SUMMARY_PROMPT)),
            summaryProviderId = data.optString("summaryProviderId", data.optString("summary_provider_id")),
            summaryModelId = data.optString("summaryModelId", data.optString("summary_model_id")),
            reasoningEffort = effort,
            includeVeniceSystemPrompt = data.optBoolean("includeVeniceSystemPrompt", true),
            reasoningBudget = reasoningBudget,
            generationBudgetEnabled = data.optBoolean("generationBudgetEnabled", false)
        )
    }

    /** Exports every preset field needed for compression and provider selection round-trips. */
    fun presetJson(preset: Preset): String = JSONObject()
        .put("id", preset.id)
        .put("name", preset.name)
        .put("systemPrompt", preset.systemPrompt)
        .put("temperature", preset.temperature)
        .put("maxOutput", preset.maxOutput)
        .put("summaryPrompt", preset.summaryPrompt)
        .put("summaryProviderId", preset.summaryProviderId)
        .put("summaryModelId", preset.summaryModelId)
        .put("reasoningEffort", preset.reasoningEffort)
        .put("includeVeniceSystemPrompt", preset.includeVeniceSystemPrompt)
        .put("reasoningBudget", preset.reasoningBudget)
        .put("generationBudgetEnabled", preset.generationBudgetEnabled)
        .toString(2)

    private val markdownMessageHeading = Regex("(?m)^##\\s+\\d+\\s*·\\s*(user|assistant|system|tool)\\s*$")

    fun chat(source: String, providerId: String, modelId: String): Chat {
        val normalized = source.removePrefix("\uFEFF")
        val firstContent = normalized.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return if (firstContent.startsWith("# ") && markdownMessageHeading.containsMatchIn(normalized)) {
            chatMarkdown(normalized, providerId, modelId)
        } else {
            chatJsonl(normalized, providerId, modelId)
        }
    }

    private fun chatJsonl(jsonl: String, providerId: String, modelId: String): Chat {
        val rows = jsonl.lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
        val title = rows.firstOrNull()?.let { it.optString("character_name", it.optString("title")) }.orEmpty().ifBlank { "导入会话" }
        val archiveMetadata = rows.firstOrNull()?.optJSONObject("chat_metadata")
        val metadata = archiveMetadata?.takeIf { it.has("pocket_tavern") }?.get("pocket_tavern")?.let {
            require(it is JSONObject) { "会话写作元数据格式无效" }
            it
        }
        val continuity = metadata?.let { checkedString(it, "continuityNotes", MAX_CONTINUITY_CHARS) }.orEmpty()
        val research = researchNotes(metadata?.opt("researchNotes"))
        val scenarioState = metadata?.optJSONObject("scenarioState")?.let { runCatching { DataCodec.gson.fromJson(it.toString(), ScenarioState::class.java) }.getOrNull() } ?: ScenarioState()
        val scenarioRoster = metadata?.optJSONArray("scenarioRoster")?.let { array ->
            (0 until array.length()).mapNotNull { index -> runCatching { DataCodec.gson.fromJson(array.getJSONObject(index).toString(), ScenarioPerson::class.java) }.getOrNull() }.take(200)
        }.orEmpty()
        val messages = rows.filter { it.has("mes") || it.has("content") }.map { item ->
            val role = if (item.has("role")) item.getString("role") else if (item.optBoolean("is_user")) "user" else "assistant"
            require(role in listOf("user","assistant","system","tool")) { "不支持的消息角色" }
            withWritingState(Message(
                role = role,
                content = item.optString("mes", item.optString("content")),
                reasoning = item.optString("reasoning_content"),
                toolCallsJson = item.optJSONArray("tool_calls")?.toString() ?: "[]",
                toolCallId = item.optString("tool_call_id"),
                status = item.optString("status", "complete"),
                errorMessage = item.optString("errorMessage"),
                finishReason = item.optString("finishReason"),
                requestId = item.optString("requestId")
            ), item)
        }.toList()
        // A JSONL export always has a metadata row, even for a newly created
        // empty chat. Preserve that chat instead of rejecting it as malformed;
        // a truly blank file is still rejected by the rows.isNotEmpty check.
        require(rows.isNotEmpty() && messages.size <= 50000) { "会话为空或过大" }
        requireDistinctMessageIds(messages)
        return Chat(title=title,providerId=providerId,modelId=modelId,messages=messages,continuityNotes=continuity,knowledgeNotes=research,scenarioState=scenarioState,scenarioRoster=scenarioRoster)
    }

    private fun chatMarkdown(markdown: String, providerId: String, modelId: String): Chat {
        val matches = markdownMessageHeading.findAll(markdown).toList()
        require(matches.isNotEmpty()) { "Markdown 中没有可识别的会话消息" }
        val title = Regex("(?m)^#\\s+(.+?)\\s*$").find(markdown)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "导入会话" }
        val continuityLines = markdown.substring(0, matches.first().range.first).lineSequence()
            .map(String::trim).filter { it.startsWith(CONTINUITY_MARKER) }.toList()
        require(continuityLines.size <= 1) { "Markdown 连续性笔记元数据重复" }
        val continuity = continuityLines.firstOrNull()?.let { line ->
            val value = JSONArray("[${commentPayload(line, CONTINUITY_MARKER, MAX_CONTINUITY_CHARS * 6 + 16)}]")
            require(value.length() == 1 && value.get(0) is String) { "Markdown 连续性笔记格式无效" }
            (value.get(0) as String).also { require(it.length <= MAX_CONTINUITY_CHARS) { "连续性笔记过长" } }
        }.orEmpty()
        val researchLines = markdown.substring(0, matches.first().range.first).lineSequence()
            .map(String::trim).filter { it.startsWith(RESEARCH_MARKER) }.toList()
        require(researchLines.size <= 1) { "Markdown 研究资料元数据重复" }
        val research = researchLines.firstOrNull()?.let { line ->
            researchNotes(JSONArray(commentPayload(line, RESEARCH_MARKER, 12_500_000)))
        }.orEmpty()
        val messages = matches.mapIndexedNotNull { index, match ->
            val role = match.groupValues[1]
            // Hidden system prompts and tool records are not dialogue turns. Tool calls
            // cannot be reconstructed safely from the human-readable Markdown format.
            if (role !in listOf("user", "assistant")) return@mapIndexedNotNull null
            val bodyStart = markdown.indexOf('\n', match.range.last + 1).let { if (it < 0) markdown.length else it + 1 }
            val bodyEnd = matches.getOrNull(index + 1)?.range?.first ?: markdown.length
            var body = markdown.substring(bodyStart, bodyEnd)
            val leading = body.trimStart()
            val state = if (leading.startsWith(MESSAGE_MARKER)) {
                val lineEnd = leading.indexOf('\n').let { if (it < 0) leading.length else it }
                val parsed = JSONObject(commentPayload(leading.substring(0, lineEnd).trimEnd(), MESSAGE_MARKER, MAX_MESSAGE_METADATA_CHARS))
                body = leading.substring(lineEnd)
                require(!body.trimStart().startsWith(MESSAGE_MARKER)) { "Markdown 消息元数据重复" }
                parsed
            } else null
            val content = cleanMarkdownMessageContent(body, role)
            if (content.isBlank()) null else withWritingState(Message(role = role, content = content), state)
        }
        require(messages.isNotEmpty() && messages.size <= 50000) { "会话为空或过大" }
        requireDistinctMessageIds(messages)
        return Chat(title = title, providerId = providerId, modelId = modelId, messages = messages, continuityNotes = continuity, knowledgeNotes = research)
    }

    /** Removes only known export annotations; ordinary user and assistant prose is retained verbatim. */
    internal fun cleanMarkdownMessageContent(content: String, role: String): String {
        val withoutSafety = Regex("(?s)<ds_safety>.*?</ds_safety>").replace(content, "")
        val lines = withoutSafety.lines()
        val firstTextLine = lines.indexOfFirst { it.isNotBlank() }
        val metadataLine = lines.indices.firstOrNull { index ->
            val line = lines[index]
            val trimmed = line.trim()
            trimmed.matches(Regex("###\\s*(推理记录|思考记录|推理过程)")) ||
                trimmed == "工具调用：" ||
                (role == "assistant" && trimmed.startsWith("错误：") && (index == firstTextLine || lines.getOrNull(index - 1).isNullOrBlank()))
        } ?: -1
        val visible = lines.take(if (metadataLine < 0) lines.size else metadataLine).joinToString("\n").trim()
        val attachmentPattern = Regex("(?m)^附件：(.+?)（([^（）]+)）\\s*$")
        val attachments = attachmentPattern.findAll(visible).map { match ->
            "（历史附件未包含在 Markdown 文件中：${match.groupValues[1]}，${match.groupValues[2]}）"
        }.toList()
        val prose = attachmentPattern.replace(visible, "").trim()
        return listOf(prose, attachments.joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n\n").trim()
    }

    fun chatJsonl(chat: Chat): String = (listOf(JSONObject().put("user_name","用户").put("character_name",chat.title)
        .put("chat_metadata",JSONObject().put("pocket_tavern",JSONObject().put("continuityNotes",chat.continuityNotes)
            .put("researchNotes",researchNotesJson(chat))
            .put("scenarioState", JSONObject(DataCodec.gson.toJson(chat.scenarioState)))
            .put("scenarioRoster", JSONArray(DataCodec.gson.toJson(chat.scenarioRoster))))).toString()) + chat.messages.map {
        writingState(it).put("name",if(it.role=="user") "用户" else chat.title).put("is_user",it.role=="user").put("mes",it.content).put("role",it.role).put("reasoning_content",it.reasoning).put("tool_calls",JSONArray(it.toolCallsJson)).put("tool_call_id",it.toolCallId).put("status",it.status).put("errorMessage",it.errorMessage).put("finishReason",it.finishReason).put("requestId",it.requestId).toString()
    }).joinToString("\n")

    /** These comments are archive metadata, never model instructions or executable content. */
    internal fun markdownMessageMetadata(message: Message): String =
        "$MESSAGE_MARKER ${writingState(message).toString().replace(">", "\\u003e")} -->"

    internal fun markdownContinuityMetadata(text: String): String =
        "$CONTINUITY_MARKER ${JSONObject.quote(text).replace(">", "\\u003e")} -->"

    internal fun markdownResearchMetadata(chat: Chat): String =
        "$RESEARCH_MARKER ${researchNotesJson(chat).toString().replace(">", "\\u003e")} -->"

    private fun researchNotesJson(chat: Chat): JSONArray = JSONArray().apply {
        chat.knowledgeNotes.filter { it.isResearch }.forEach { note ->
            put(JSONObject().put("id", note.id).put("text", note.text).put("createdAt", note.createdAt)
                .put("modelId", note.modelId).put("sourceKind", "research"))
        }
    }

    private fun researchNotes(value: Any?): List<KnowledgeNote> {
        if (value == null) return emptyList()
        require(value is JSONArray && value.length() <= app.pockettavern.engine.KnowledgeVault.MAX_RESEARCH_NOTES) { "研究资料格式无效或数量过多" }
        val notes = (0 until value.length()).map { index ->
            val item = value.getJSONObject(index)
            val id = checkedString(item, "id", 512)
            val text = checkedString(item, "text", app.pockettavern.engine.KnowledgeVault.MAX_RESEARCH_NOTE_CHARS)
            require(id.isNotBlank() && text.isNotBlank() && checkedString(item, "sourceKind", 32) == "research") { "研究资料来源类型或内容无效" }
            val created = item.opt("createdAt")
            require((created is Long || created is Int) && (created as Number).toLong() >= 0) { "研究资料时间无效" }
            KnowledgeNote(id=id, text=text, createdAt=(created as Number).toLong(), modelId=checkedString(item, "modelId", 512), sourceKind="research")
        }
        require(notes.map { it.id }.distinct().size == notes.size && notes.sumOf { it.text.length } <= app.pockettavern.engine.KnowledgeVault.MAX_RESEARCH_TOTAL_CHARS) { "研究资料 ID 重复或总量过大" }
        return notes
    }

    private fun writingState(message: Message): JSONObject = JSONObject()
        .put("id", message.id).put("createdAt", message.createdAt)
        .put("contextExcluded", message.contextExcluded).put("intent", message.intent)
        .put("generationId", message.generationId).put("revisionOf", message.revisionOf)
        .put("qualityNotice", message.qualityNotice).put("skillSnapshotJson", message.skillSnapshotJson)

    private fun withWritingState(message: Message, state: JSONObject?): Message {
        if (state == null) return message
        val id = checkedString(state, "id", 512, message.id)
        require(id.isNotBlank()) { "消息 ID 不能为空" }
        val intent = checkedString(state, "intent", 32, "dialogue")
        require(intent in setOf("dialogue", "correction", "rewrite")) { "消息类型无效" }
        val excluded = state.opt("contextExcluded")
        require(excluded == null || excluded is Boolean) { "废稿标记必须是布尔值" }
        val created = state.opt("createdAt")
        require(created == null || (created is Long || created is Int) && (created as Number).toLong() >= 0) { "消息时间格式无效" }
        val snapshot = checkedString(state, "skillSnapshotJson", MAX_SNAPSHOT_CHARS)
        if (snapshot.isNotBlank()) require(runCatching { JSONObject(snapshot) }.isSuccess) { "Skills 快照格式无效" }
        return message.copy(id = id, createdAt = (created as? Number)?.toLong() ?: message.createdAt,
            contextExcluded = excluded as? Boolean ?: false, intent = intent,
            generationId = checkedString(state, "generationId", 512), revisionOf = checkedString(state, "revisionOf", 512),
            qualityNotice = checkedString(state, "qualityNotice", 16_384), skillSnapshotJson = snapshot)
    }

    private fun checkedString(data: JSONObject, key: String, limit: Int, default: String = ""): String {
        if (!data.has(key)) return default
        val value = data.get(key)
        require(value is String && value.length <= limit) { "$key 格式无效或过长" }
        return value
    }

    private fun commentPayload(line: String, marker: String, limit: Int): String {
        require(line.length <= limit && line.startsWith(marker) && line.endsWith("-->")) { "Markdown 写作元数据格式无效或过长" }
        return line.removePrefix(marker).removeSuffix("-->").trim()
    }

    private fun requireDistinctMessageIds(messages: List<Message>) {
        require(messages.map { it.id }.distinct().size == messages.size) { "消息 ID 重复，无法保留版本关系" }
    }

    private const val MESSAGE_MARKER = "<!-- pocket-tavern-message:"
    private const val CONTINUITY_MARKER = "<!-- pocket-tavern-continuity:"
    private const val RESEARCH_MARKER = "<!-- pocket-tavern-research:"
    private const val MAX_CONTINUITY_CHARS = 65_536
    private const val MAX_SNAPSHOT_CHARS = 524_288
    private const val MAX_MESSAGE_METADATA_CHARS = 3_300_000

    private const val DEFAULT_SUMMARY_PROMPT = "用中文准确总结已有对话中的人物、事实、约定、未解决事项和当前进度。保留关键细节，不添加推测或新事实。"
}
