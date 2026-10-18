package app.pockettavern.data

import app.pockettavern.domain.*
import com.google.gson.*

object DataCodec {
    const val MAX_JSON_UTF8_BYTES = 64L * 1024L * 1024L
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun encode(data: AppData): String {
        val json = gson.toJson(data)
        requireJsonSize(json)
        return json
    }

    fun decode(json: String): AppData {
        requireJsonSize(json)
        val root = JsonParser.parseString(json)
        require(root.isJsonObject && root.asJsonObject.has("schemaVersion")) { "不是口袋酒馆备份" }
        validateTree(root, 0)
        require(root.asJsonObject.get("schemaVersion").asInt == 1) { "不支持该备份版本，请升级应用" }
        val data = gson.fromJson(root, AppData::class.java)
        require(data.providers.size <= 100 && data.chats.size <= 10000 && data.skills.size <= 500) { "备份条目过多" }
        require(data.presets.isNotEmpty()) { "备份必须至少包含一个预设" }
        require(data.providers.map { it.id }.distinct().size == data.providers.size) { "平台 ID 重复" }
        require(data.chats.map { it.id }.distinct().size == data.chats.size) { "会话 ID 重复" }
        data.providers.forEach {
            require(it.kind in listOf("venice", "openai", "anthropic")) { "未知平台协议" }
            val uri=java.net.URI(it.baseUrl)
            require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null) { "备份平台地址无效" }
        }
        data.chats.forEach { chat -> chat.messages.flatMap { it.attachments }.forEach { require(it.path.isBlank()) { "备份附件必须嵌入数据，不能引用本地路径" } } }
        data.presets.forEach { require(it.maxOutput in 1..131072 && it.temperature in 0.0..2.0) { "预设参数无效" } }
        require(data.settings.keepRecentTurns in 1..100 && data.settings.compressionThreshold in 0.2..0.95 && data.settings.maxToolSteps in 1..16) { "压缩或工具设置无效" }
        return data
    }

    private fun requireJsonSize(json: String) {
        require(json.toByteArray(Charsets.UTF_8).size.toLong() <= MAX_JSON_UTF8_BYTES) {
            "状态 JSON 超过 64 MiB"
        }
    }

    private fun validateTree(element: JsonElement, depth: Int) {
        require(depth <= 40) { "JSON 嵌套过深" }
        require(!element.isJsonNull) { "备份包含空字段" }
        if (element.isJsonObject) element.asJsonObject.entrySet().forEach { validateTree(it.value, depth + 1) }
        if (element.isJsonArray) element.asJsonArray.forEach { validateTree(it, depth + 1) }
    }
}
