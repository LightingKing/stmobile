package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Character
import app.pockettavern.domain.Preset
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * "AI 帮我写" in the character form: one or two sentences about a character become a full card the
 * user reviews before saving. One request with any text model, no tools, so it works on every
 * platform. Editing an existing card sends it along, so the model completes or revises it.
 */
object CharacterDrafts {
    private data class Field(val key: String, val label: String, val guide: String, val max: Int)

    private val FIELDS = listOf(
        Field("name", "名称", "角色名", 60),
        Field("description", "简介", "外貌、身份、背景，150 到 400 字", 8000),
        Field("personality", "性格", "性格、说话方式、口头禅、喜欢和讨厌的东西，100 到 300 字", 4000),
        Field("scenario", "场景", "她和 {{user}} 是什么关系、现在在什么场景，50 到 200 字", 4000),
        Field("first_message", "开场白", "她对 {{user}} 说的第一段话，用她自己的语气，带一点动作或情景，50 到 200 字", 4000),
        Field("examples", "示例对话", "两到三段示例对话，每段以 <START> 开头，用“{{user}}:”和“{{char}}:”标明谁在说，体现她的说话方式", 8000),
    )

    const val SYSTEM = "你是角色卡作者，为一款中文聊天和写作 App 写角色卡。根据用户的设想写一张完整的角色卡，让模型扮演这个角色时有足够依据：" +
        "外貌和身份具体，性格有层次和矛盾，说话方式有辨识度，不要写成千篇一律的网文腔。用户没说到的部分合理补全，但不要违背用户写明的设定。" +
        "只做虚构或原创角色：不给现实中可识别的真人（主播、明星、网红或身边的人）做角色卡，不沿用真人的名字、长相或可识别特征；用户要的是真人时，改成另起名字的原创角色，并在简介开头用一句话说明。" +
        "角色涉及恋爱或成人内容时必须明确是成年人。动画、游戏、小说里的虚构角色可以照原作整理。"

    fun systemPrompt(): String = SYSTEM + "\n只返回一个 JSON 对象，不要任何其他文字，字段如下：\n" +
        FIELDS.joinToString("\n") { "- ${it.key}：${it.guide}" }

    private fun values(character: Character): Map<String, String> = mapOf(
        "name" to character.name, "description" to character.description, "personality" to character.personality,
        "scenario" to character.scenario, "first_message" to character.firstMessage, "examples" to character.examples)

    fun hasContent(character: Character): Boolean = values(character).values.any { it.isNotBlank() }

    /** What the user asks for: the idea, plus the card so far when there is one. */
    fun userText(idea: String, base: Character): String {
        val existing = values(base).filterValues { it.isNotBlank() }
        return buildString {
            if (existing.isNotEmpty()) {
                append("已有的角色卡如下。保留已经写好的设定，按我的要求修改，并补全空着的部分：\n")
                append(JSONObject(existing as Map<*, *>).toString())
                append("\n\n")
            }
            append(if (idea.isBlank()) "请补全这张角色卡。" else "我的要求：${idea.trim()}")
        }
    }

    /** The request: the user's idea, plus the card so far when there is one. */
    fun messages(idea: String, base: Character): String = JSONArray()
        .put(JSONObject().put("role", "system").put("content", systemPrompt()))
        .put(JSONObject().put("role", "user").put("content", userText(idea, base))).toString()

    /** Room for a full card; a little warmth for variety. Thinking, if the model does it, is not limited. */
    fun preset(): Preset = Preset(temperature = 0.9, maxOutput = 4000)

    /** The card in the model's reply. Fences and stray prose around the JSON are tolerated. */
    fun parse(content: String): Result<Character> = runCatching {
        val start = content.indexOf('{')
        require(start >= 0) { "模型没有返回角色卡，请重试或换一个模型" }
        val root = JSONTokener(content.substring(start)).nextValue() as? JSONObject ?: error("角色卡格式不对，请重试或换一个模型")
        val read = FIELDS.associate { field -> field.key to root.optString(field.key).trim().take(field.max) }
        require(read.getValue("name").isNotBlank()) { "模型没有给出角色名，请重试" }
        require(read.getValue("description").isNotBlank()) { "模型没有写简介，请重试" }
        Character(name = read.getValue("name"), description = read.getValue("description"), personality = read.getValue("personality"),
            scenario = read.getValue("scenario"), firstMessage = read.getValue("first_message"), examples = read.getValue("examples"))
    }

    /** Puts a draft into the form: written fields replace the form's, everything else (id, avatar, packs) stays. */
    fun merge(form: Character, draft: Character): Character = form.copy(
        name = draft.name.ifBlank { form.name },
        description = draft.description.ifBlank { form.description },
        personality = draft.personality.ifBlank { form.personality },
        scenario = draft.scenario.ifBlank { form.scenario },
        firstMessage = draft.firstMessage.ifBlank { form.firstMessage },
        examples = draft.examples.ifBlank { form.examples })

    /**
     * The model a draft uses unless the user picks another: the open chat's, when it is a usable
     * text model with a key, otherwise the first one that is.
     */
    fun defaultModel(data: AppData, selectedChatId: String, hasKeys: Set<String>): Pair<String, String>? {
        fun usable(providerId: String, modelId: String) = providerId in hasKeys &&
            data.providers.firstOrNull { it.id == providerId }?.models?.any { it.id == modelId && it.type == "text" && !it.offline } == true
        data.chats.firstOrNull { it.id == selectedChatId }?.let { chat -> if (usable(chat.providerId, chat.modelId)) return chat.providerId to chat.modelId }
        return data.providers.asSequence().filter { it.id in hasKeys }
            .mapNotNull { provider -> provider.models.firstOrNull { it.type == "text" && !it.offline }?.let { provider.id to it.id } }.firstOrNull()
    }
}
