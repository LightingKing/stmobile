package app.pockettavern.engine

import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import org.json.JSONArray
import org.json.JSONObject

/**
 * 角色卡助手: a chat whose task is making one character card. It can watch videos and read pages
 * through web_reach, drafts the card in the conversation, and saves it with one tool once the user
 * agrees. Only fictional or original characters: real, identifiable people are refused.
 */
object CharacterCardTool {
    const val TASK = "character_card"
    const val NAME = "character_card_save"

    const val GREETING = "我是角色卡助手。告诉我你想做的角色：可以发 B 站或 YouTube 视频链接、网页，或者直接描述。" +
        "我会去看视频和资料，先把角色卡草稿发给你确认，你说可以了我再保存到角色列表。"

    const val PROMPT = "【角色卡助手】本会话的任务是帮用户做一张角色卡。用户给视频链接或要求参考视频时，用 web_reach 读视频信息和字幕（subtitle），网页用 read；资料不够就问用户。" +
        "先在对话里给出草稿：名称、简介（外貌、身份、背景）、性格、场景、开场白、示例对话，让用户确认或修改；用户明确同意保存后，再调用 character_card_save 保存，保存后告诉用户去“角色”页查看和修改。" +
        "只做虚构或原创角色：不给现实中可识别的真人（主播、明星、网红或身边的人）做角色卡，不沿用真人的名字、长相或可识别特征；可以借鉴风格，但要另起名字和设定，并向用户说明。" +
        "角色涉及恋爱或成人内容时必须是成年人。动画、游戏、小说里的虚构角色可以照原作整理。"

    private val FIELDS = listOf(
        Triple("name", "角色名称", 60),
        Triple("description", "简介：外貌、身份、背景", 8000),
        Triple("personality", "性格与说话方式", 4000),
        Triple("scenario", "所处场景或与用户的关系", 4000),
        Triple("first_message", "开场白：角色的第一句话", 4000),
        Triple("examples", "示例对话（可用 <START> 分段）", 8000),
        Triple("system_prompt", "可选：额外的扮演要求", 4000),
    )

    fun available(chat: Chat, toolsSupported: Boolean): Boolean = chat.task == TASK && toolsSupported

    fun definition(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", NAME)
        .put("description", "把用户确认过的角色卡保存到角色列表（新建一个角色）。只在用户明确同意保存后调用。")
        .put("parameters", JSONObject().put("type", "object")
            .put("properties", JSONObject().apply {
                FIELDS.forEach { (key, label, max) -> put(key, JSONObject().put("type", "string").put("description", "$label，最多 $max 字")) }
            })
            .put("required", JSONArray(listOf("name", "description")))))

    /** The card the model asked to save, or why it cannot be saved. */
    fun character(arguments: JSONObject): Result<Character> = runCatching {
        val values = FIELDS.associate { (key, label, max) ->
            val value = arguments.optString(key).trim()
            require(value.length <= max) { "$label 超过 $max 字" }
            key to value
        }
        require(values.getValue("name").isNotBlank()) { "缺少角色名称" }
        require(values.getValue("description").isNotBlank()) { "缺少角色简介" }
        Character(name = values.getValue("name"), description = values.getValue("description"), personality = values.getValue("personality"),
            scenario = values.getValue("scenario"), firstMessage = values.getValue("first_message"), examples = values.getValue("examples"),
            systemPrompt = values.getValue("system_prompt"))
    }
}
