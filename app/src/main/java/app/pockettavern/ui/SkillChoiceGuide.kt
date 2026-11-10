package app.pockettavern.ui

import app.pockettavern.domain.SkillRecord

/** App-authored guidance based on public catalog descriptions, not an effectiveness ranking. */
enum class SkillScenario(val label: String, val description: String) {
    ROLEPLAY("陪角色聊天", "想接着和角色互动、推进剧情，先看这一组。"),
    NOVEL("接着写小说", "要写这一章正文，想让描写更具体、读起来不像 AI。"),
    VOICE("人物口吻与描写", "几个人物说话都像同一个人，或描写太笼统、看不出场面。"),
    POLISH("去 AI 味", "已经写出来的内容套话多、点题多、因果太整齐。"),
    COMMON_SENSE("补现实常识", "现代中国背景下，人物的生活常识或处事细节出错。"),
    ADULT_STORY("成人向写作", "虚构成年角色的亲密场景正文；与选项式互动分开选择。"),
    ADULT_ROLEPLAY("成人向互动", "虚构成年角色的关系推进、状态变化和选项式游玩。")
}

enum class SkillSuggestionRole(val label: String) {
    PRIMARY("首选 · 先试这一个"),
    ALTERNATIVE("备选 · 与首选选一个"),
    SUPPORT("辅助 · 按需添加")
}

data class SkillSuggestion(val skill: SkillRecord, val role: SkillSuggestionRole, val reason: String)
data class SkillChoiceHint(val whenToUse: String, val pairing: String)

private data class SuggestionSpec(val id: String, val role: SkillSuggestionRole, val reason: String)
private fun pick(id: String, role: SkillSuggestionRole, reason: String) = SuggestionSpec("builtin-writing-$id", role, reason)

private val scenarioSuggestions = mapOf(
    SkillScenario.ROLEPLAY to listOf(
        pick("tt-sp-roleplay", SkillSuggestionRole.PRIMARY, "侧重角色状态和扮演连贯；想直接接着聊，可以先试。"),
        pick("ds-roleplay-preset-original", SkillSuggestionRole.ALTERNATIVE, "偏 DeepSeek 沉浸演出格式；手机仅把原版 YAML 当文本参考。"),
        pick("pt-character-voice", SkillSuggestionRole.SUPPORT, "把角色卡里的示例对白、身份和口头习惯落实到每个 NPC。")
    ),
    SkillScenario.NOVEL to listOf(
        pick("fp-fiction-prose-style", SkillSuggestionRole.PRIMARY, "旁白贴着人物口吻、场面里有人有物，细节具体但不堆砌；英文规则，中文按意思用。"),
        pick("dp-chapter-direct", SkillSuggestionRole.ALTERNATIVE, "更关心动作、情感和对话场景的节奏与张力时选它。"),
        pick("tm-continuity", SkillSuggestionRole.SUPPORT, "长篇设定、时间线和物资开始混乱时再加。")
    ),
    SkillScenario.VOICE to listOf(
        pick("tm-dialogue", SkillSuggestionRole.PRIMARY, "给主要角色定语言指纹，台词口语化、有潜台词，一人一声。"),
        pick("dp-character-style", SkillSuggestionRole.ALTERNATIVE, "想先给每个角色建说话档案，再按档案检查声线时选它。"),
        pick("pt-character-voice", SkillSuggestionRole.SUPPORT, "把角色卡里的示例对白和口头习惯落实到每个 NPC。")
    ),
    SkillScenario.POLISH to listOf(
        pick("se-sepia", SkillSuggestionRole.PRIMARY, "以小说为主：先修点题、因果太整齐等结构问题，再改措辞；附中文校准。"),
        pick("tm-deslop", SkillSuggestionRole.ALTERNATIVE, "只想修掉堆砌、排比和空话，偏朴实网文、篇幅更短。"),
        pick("tm-dialogue", SkillSuggestionRole.SUPPORT, "主要问题在人物台词时，单独添加。")
    ),
    SkillScenario.COMMON_SENSE to listOf(
        pick("pt-china-common-sense", SkillSuggestionRole.PRIMARY, "中国现代背景需要补常识时添加；这是本应用自编项。")
    ),
    SkillScenario.ADULT_STORY to listOf(
        pick("fe-fictional-erotica", SkillSuggestionRole.PRIMARY, "台词属于具体人物，身体和关系状态前后连贯，露骨程度按你的措辞判断。"),
        pick("hn-scene-writing", SkillSuggestionRole.ALTERNATIVE, "面向单个亲密场景的中文写作规范。"),
        pick("ec-erotica-craft", SkillSuggestionRole.SUPPORT, "写得太淡、太含蓄或总用代称时再加，只管尺度和用词。")
    ),
    SkillScenario.ADULT_ROLEPLAY to listOf(
        pick("va-velvet-ascent", SkillSuggestionRole.PRIMARY, "想看状态栏、关系阶段和行动选项，可以先试这一套。"),
        pick("at-adult-tension-original", SkillSuggestionRole.ALTERNATIVE, "偏自主 NPC 和关系叙事；手机不能运行其 DSH Python 脚本。"),
        pick("fe-fictional-erotica", SkillSuggestionRole.SUPPORT, "亲密场景的台词、身体连贯和露骨程度需要更稳时再加。")
    )
)

/** Exact IDs only; returns installed records unchanged and never reads content or files. */
fun suggestSkills(skills: List<SkillRecord>, scenario: SkillScenario): List<SkillSuggestion> {
    val adult = scenario == SkillScenario.ADULT_STORY || scenario == SkillScenario.ADULT_ROLEPLAY
    val installed = skills.associateBy { it.id }
    return scenarioSuggestions.getValue(scenario).mapNotNull { spec ->
        installed[spec.id]?.takeIf { it.adult == adult }?.let { SkillSuggestion(it, spec.role, spec.reason) }
    }
}

private val specificHints = buildMap<String, SkillChoiceHint> {
    fun add(vararg ids: String, whenToUse: String, pairing: String) {
        ids.forEach { put("builtin-writing-$it", SkillChoiceHint(whenToUse, pairing)) }
    }
    add("tt-sp-roleplay", whenToUse = "正在跑角色戏，人物位置、物品或说话方式容易串。", pairing = "可按需加一个对白辅助；文件草稿约定不能当作手机已实现的专用功能。")
    add("ds-roleplay-preset-original", whenToUse = "使用 DeepSeek，想试酒馆演出格式和角色沉浸。", pairing = "先和其他主扮演预设选一个；DSH 配置不由手机解释，效果取决于模型。")
    add("an-roleplay-sandbox", whenToUse = "卡在一场戏，想推演几个人物会怎么行动。", pairing = "适合临时推演；结束后关掉，再回到原来的主扮演技能。")
    add("jw-game-facilitator", whenToUse = "希望模型像跑团主持，给选择与后果。", pairing = "先与其他主持技能选一个；剧本已有主持预设时先检查是否需要。")
    add("fv-chat-doctor", whenToUse = "已经聊崩、串戏或遗忘，想找出问题。", pairing = "诊断时临时开启，修好后关闭，不必整段游玩都开。")
    add("dv-roleplay-modes", whenToUse = "明确在用有思考过程的 DeepSeek，想了解角色沉浸模式。", pairing = "这是模式说明，不是通用写作增强；其他模型不优先选。")
    add("fp-fiction-prose-style", whenToUse = "正文读着像 AI、描写笼统，想让旁白和台词更像真人写的。", pairing = "先单独试；它会先读较长的偏好文件，不要和多个去 AI 味 Skill 同时开。")
    add("dp-chapter-direct", whenToUse = "动作、情感或对话场景节奏平、没有张力。", pairing = "可配一个对白或人物口吻辅助；不负责规划剧情。")
    add("se-sepia", "tm-deslop", "os-story-deslop", "ss-stop-slop", "anti-ai-slop-writing", whenToUse = "已有原文，觉得套话、点题、比喻和排比太多。", pairing = "去 AI 味先选一个，不要几个同时常驻；想修台词可再加对白辅助。")
    add("tm-dialogue", "dp-character-style", whenToUse = "NPC 台词都像一个人，或说话不符合身份。", pairing = "同类对白技能先试一个；可配正文或主扮演技能。")
    add("tm-continuity", whenToUse = "长篇的时间线、资源、人物认知或状态对不上。", pairing = "有较长前文或状态资料时再加；它不能找回没有发送给模型的内容。")
    add("pt-china-common-sense", whenToUse = "现代中国背景下，人物的生活常识或处事细节出错。", pairing = "只在对应背景下添加；人物设定和具体情境优先。")
    add("fe-fictional-erotica", "hn-scene-writing", "dp-chapter-adult-original", whenToUse = "写虚构成年角色的亲密场景正文。", pairing = "场景写作先选一个；跨章时可加连续性，不需要同时开互动引擎。")
    add("ec-erotica-craft", whenToUse = "成人场景写得太淡、用代称或在关键处留白切走。", pairing = "辅助项，可配一个成人场景写作主技能；只管尺度和用词，不改情节。")
    add("va-velvet-ascent", whenToUse = "想玩带状态栏、关系阶段和选项的成人向互动。", pairing = "先和其他主互动技能选一个；不想每轮带栏目时改选正文写作。")
    add("at-adult-tension-original", whenToUse = "想试更重 NPC 自主行动与关系状态的成人向互动。", pairing = "先与其他互动引擎选一个；引用需工具模型，手机不运行 DSH Python。")
    add("hn-continuity", whenToUse = "成人向故事的跨章关系、情绪或状态开始混乱。", pairing = "它是辅助，可配一个正文或互动主技能，不必开局就加。")
    add("hn-worldbuilding", whenToUse = "需要先建立成人向题材的世界规则。", pairing = "设定阶段用，进入正文后按需关闭，不是互动引擎。")
    add("dn-deep-novel", whenToUse = "想用项目记忆管理成人向长篇创作。", pairing = "完整工作流先选一套；文件记忆需要工具模型和文件写入权限。")
}

fun skillChoiceHint(skill: SkillRecord): SkillChoiceHint = specificHints[skill.id] ?: when (skillUse(skill)) {
    SkillUse.WORKFLOW -> SkillChoiceHint("想按步骤管理写作或研究任务。", "完整工作流先选一套，具体模块按当前阶段添加。")
    SkillUse.OUTLINE -> SkillChoiceHint("正文还没开写，需要规划故事结构。", "规划阶段先选一个；开写后可换正文技能。")
    SkillUse.PROSE -> SkillChoiceHint("要写正文、场景或对白，需求与简介对应时。", "一个主写作技能起步，遇到具体问题再加辅助。")
    SkillUse.REVISION -> SkillChoiceHint("已有原文，需要检查或修订。", "先提供原文和要改的问题；同类修订技能先选一个。")
    SkillUse.CHARACTER -> SkillChoiceHint("要设计人物，或让不同角色更有区分度。", "可配一个写作或扮演主技能，先提供人物设定。")
    SkillUse.WORLDBUILDING -> SkillChoiceHint("世界规则、势力或命名还没定清楚。", "先提供现有设定；与正文技能分阶段使用更容易控制。")
    SkillUse.ROLEPLAY -> SkillChoiceHint("想由模型扮演人物或主持互动。", "先和其他主扮演技能选一个，再按需加对白或连续性辅助。")
    SkillUse.TAVERN -> SkillChoiceHint("准备制作角色卡或酒馆资源。", "制作阶段使用，游玩时通常不需要。")
    SkillUse.SOCIAL -> SkillChoiceHint("故事涉及现实社会常识和人际关系。", "只在对应背景下添加，人物设定和具体情境优先。")
    SkillUse.ALL, SkillUse.CUSTOM -> SkillChoiceHint("这是自定义或未分类技能，先看简介和详情是否符合本次任务。", "先单独试用，再决定是否与现有技能搭配。")
}
