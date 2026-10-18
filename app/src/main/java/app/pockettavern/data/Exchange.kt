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
        return Character(name=data.optString("name"),description=data.optString("description"),personality=data.optString("personality"),scenario=data.optString("scenario"),firstMessage=data.optString("first_mes"),examples=data.optString("mes_example"),systemPrompt=data.optString("system_prompt"),originalJson=root.toString())
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
            WorldEntry(name=item.optString("comment",item.optString("name","世界书条目")),content=item.optString("content"),keywords=(0 until keys.length()).map { keys.getString(it) },always=item.optBoolean("constant"),enabled=!item.optBoolean("disable"),characterId=characterId,priority=item.optInt("order",item.optInt("insertion_order",0)))
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
        return Preset(
            id = id,
            name = name,
            systemPrompt = data.optString("systemPrompt", data.optString("content", data.optString("system_prompt"))),
            temperature = data.optDouble("temperature", 0.8),
            maxOutput = data.optInt("maxOutput", data.optInt("openai_max_tokens", data.optInt("max_tokens", 2048))),
            summaryPrompt = data.optString("summaryPrompt", data.optString("summary_prompt", DEFAULT_SUMMARY_PROMPT)),
            summaryProviderId = data.optString("summaryProviderId", data.optString("summary_provider_id")),
            summaryModelId = data.optString("summaryModelId", data.optString("summary_model_id"))
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
        .toString(2)

    fun chat(jsonl: String, providerId: String, modelId: String): Chat {
        val messages = jsonl.lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.filter { it.has("mes") || it.has("content") }.map { item ->
            val role = if (item.has("role")) item.getString("role") else if (item.optBoolean("is_user")) "user" else "assistant"
            require(role in listOf("user","assistant","system","tool")) { "不支持的消息角色" }
            Message(role=role,content=item.optString("mes",item.optString("content")),reasoning=item.optString("reasoning_content"),toolCallsJson=item.optJSONArray("tool_calls")?.toString() ?: "[]",toolCallId=item.optString("tool_call_id"))
        }.toList()
        require(messages.isNotEmpty() && messages.size <= 50000) { "会话为空或过大" }
        return Chat(title="导入会话",providerId=providerId,modelId=modelId,messages=messages)
    }
    fun chatJsonl(chat: Chat): String = (listOf(JSONObject().put("user_name","用户").put("character_name",chat.title).put("chat_metadata",JSONObject()).toString()) + chat.messages.map {
        JSONObject().put("name",if(it.role=="user") "用户" else chat.title).put("is_user",it.role=="user").put("mes",it.content).put("role",it.role).put("reasoning_content",it.reasoning).put("tool_calls",JSONArray(it.toolCallsJson)).put("tool_call_id",it.toolCallId).toString()
    }).joinToString("\n")

    private const val DEFAULT_SUMMARY_PROMPT = "用中文准确总结已有对话中的人物、事实、约定、未解决事项和当前进度。保留关键细节，不添加推测或新事实。"
}
