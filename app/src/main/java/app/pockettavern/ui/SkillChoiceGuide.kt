package app.pockettavern.ui

import app.pockettavern.domain.SkillRecord

/** App-authored guidance based on public catalog descriptions, not an effectiveness ranking. */
enum class SkillScenario(val label: String, val description: String) {
    ROLEPLAY("陪角色聊天", "想接着和角色互动、推进剧情，先看这一组。"),
    NOVEL("接着写小说", "已经有设定或大纲，想把这一章写成正文。"),
    OUTLINE("先搭大纲", "只有点子，先定主线、人物和世界规则。"),
    POLISH("改文和对白", "已经有原文，想去 AI 腔、修台词或润色。"),
    SOCIAL("写人情世故", "写饭局、职场、求人和利益关系，让人物处事更贴近情境。"),
    ADULT_STORY("成人向写作", "虚构成年角色的小说正文；与选项式互动分开选择。"),
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
        pick("dp-chapter-draft", SkillSuggestionRole.PRIMARY, "有大纲后写本章初稿，目标明确。"),
        pick("vn-write", SkillSuggestionRole.ALTERNATIVE, "更适合已经准备好事件卡和剧情链的章节。"),
        pick("tm-continuity", SkillSuggestionRole.SUPPORT, "长篇设定、时间线和物资开始混乱时再加。")
    ),
    SkillScenario.OUTLINE to listOf(
        pick("qm-outline-builder", SkillSuggestionRole.PRIMARY, "从点子逐步搭完整大纲，适合还没开写时用。"),
        pick("vn-bookplan", SkillSuggestionRole.ALTERNATIVE, "已有故事方向，想细排全书主线和分卷节拍。"),
        pick("qm-world-rules", SkillSuggestionRole.SUPPORT, "能力、资源和规则边界需要单独理清时再加。")
    ),
    SkillScenario.POLISH to listOf(
        pick("tm-deslop", SkillSuggestionRole.PRIMARY, "对照已有原文修掉堆砌和空话，偏朴实网文。"),
        pick("os-story-deslop", SkillSuggestionRole.ALTERNATIVE, "想连叙事和因果一起检查时试这套，报告会更长。"),
        pick("tm-dialogue", SkillSuggestionRole.SUPPORT, "主要问题在人物台词时，单独添加。")
    ),
    SkillScenario.SOCIAL to listOf(
        pick("rs-renqing-shi-gu", SkillSuggestionRole.PRIMARY, "先分析面子、利益和风险，适合写复杂处事场景。"),
        pick("rq-renqing-shigu", SkillSuggestionRole.ALTERNATIVE, "只想把一句话说得得体、有边界时用这一项。"),
        pick("pt-china-common-sense", SkillSuggestionRole.SUPPORT, "中国现代背景需要补常识时再加；这是本应用自编项。")
    ),
    SkillScenario.ADULT_STORY to listOf(
        pick("hn-scene-writing", SkillSuggestionRole.PRIMARY, "面向单个成年角色亲密场景的小说写作。"),
        pick("dp-chapter-adult-original", SkillSuggestionRole.ALTERNATIVE, "已经在用 Dreampowers 写章节时，选同套成人章节模块。"),
        pick("hn-continuity", SkillSuggestionRole.SUPPORT, "跨章关系、情绪和角色状态需要衔接时再加。")
    ),
    SkillScenario.ADULT_ROLEPLAY to listOf(
        pick("va-velvet-ascent", SkillSuggestionRole.PRIMARY, "想看状态栏、关系阶段和行动选项，可以先试这一套。"),
        pick("at-adult-tension-original", SkillSuggestionRole.ALTERNATIVE, "偏自主 NPC 和关系叙事；手机不能运行其 DSH Python 脚本。"),
        pick("hn-continuity", SkillSuggestionRole.SUPPORT, "关系和状态开始前后不一致时，再加连续性辅助。")
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
    add("dp-chapter-draft", "ns-writer", whenToUse = "大纲和人物已定，需要一章初稿。", pairing = "正文主技能先选一个；同套工作流需先提供它要求的大纲与设定。")
    add("vn-write", whenToUse = "有事件卡、角色目标和本章剧情链，准备展开正文。", pairing = "先与其他章节写手选一个；项目文件约定需模型和文件权限支持。")
    add("qm-outline-builder", "sm-story-structure", whenToUse = "只有点子，先把全书结构和人物弧线搭起来。", pairing = "规划阶段使用；开始写正文后可换成章节写作技能。")
    add("vn-bookplan", "ns-architect", "dp-set-outline", whenToUse = "故事方向已定，要安排全书主线、分卷和章节节奏。", pairing = "大纲主技能先选一个，需要时另加世界规则或配角设计。")
    add("qm-apocalypse", whenToUse = "写末世或无限流，想先定资源约束与生存规则。", pairing = "属于题材大纲工具；填好题材信息再用，不负责直接游玩。")
    add("tm-deslop", "os-story-deslop", whenToUse = "已有原文，觉得套话、比喻和排比太多。", pairing = "两套去 AI 味先选一个；想修台词可再加对白辅助。")
    add("tm-dialogue", "wf-dialogue", "dp-character-style", whenToUse = "NPC 台词都像一个人，或说话不符合身份。", pairing = "同类对白技能先试一个；可配正文或主扮演技能。")
    add("tm-continuity", "dp-review-consistency", whenToUse = "长篇的时间线、资源、人物认知或状态对不上。", pairing = "有较长前文或状态资料时再加；它不能找回没有发送给模型的内容。")
    add("vn-revise", whenToUse = "只想改指定句段，不动其他原文。", pairing = "先按说明给原文加 Markdown 标记，避免同时开整章重写技能。")
    add("vn-polish", "ns-editor", whenToUse = "章节初稿已经写完，准备审读与润色。", pairing = "修订阶段选一个即可；手机不执行上游桌面检测脚本。")
    add("os-story", "dp-using-dreampowers", "ns-novel-studio", "ns-chief-editor", whenToUse = "想管理一整套写作流程，而不是只写一段正文。", pairing = "总控先选一个；按当前阶段另选同套具体模块，不用整套全开。")
    add("an-awesome-novel", "os-story-long-write", "os-story-short-write", "cw-webnovel", "ss-hard-sci-fi", whenToUse = "愿意从构思到成稿分步骤完成整部作品。", pairing = "完整工作流先选一套；手机不运行它依赖的桌面脚本或子代理。")
    add("os-story-long-analyze", "os-story-short-analyze", "os-story-import", whenToUse = "有现成小说原文，想拆解或整理后接着写。", pairing = "先提供原文，长篇分批发；和直接写章节的技能分阶段使用。")
    add("os-story-long-scan", "os-story-short-scan", whenToUse = "想分析榜单与题材方向，手里有榜单样本。", pairing = "需要提供样本或可用网络工具；不是实时榜单数据源。")
    add("vn-sync", "dp-chapter-summary", whenToUse = "一章已确认，准备整理摘要和人物状态。", pairing = "章节结束时临时使用；写入文件需要相应授权。")
    add("vn-taste", "dp-set-style", whenToUse = "还没确定文风，想先挑样例或定义风格。", pairing = "开写前先用；风格定好后再换正文技能。")
    add("vn-booming", "vn-fuck-it", whenToUse = "剧情冲突不够或卡文，想试更强的推进方案。", pairing = "临时试一个，先确认是否接受剧情改动，再恢复原写作流程。")
    add("fv-card-author", "tc-tavern-design", "tc-tavern-cards", "tc-tavern-ui", whenToUse = "正在制作角色卡、世界书或酒馆界面。", pairing = "属于制作工具，直接游玩通常不用开；手机不运行桌面前端扩展。")
    add("rs-renqing-shi-gu", whenToUse = "要写求人、饭局、谈合作，先判断利益和分寸。", pairing = "偏处事分析；只改一句话时可改选高情商表达技能。")
    add("rq-renqing-shigu", "gq-workplace-eq", whenToUse = "已有想表达的意思，想说得得体又有边界。", pairing = "同类话术先选一个；写正文时告诉模型把建议变成角色台词。")
    add("tz-tizhi", whenToUse = "场景明确在机关、事业单位或国企。", pairing = "题材不相关就不用开；仍以你已定的人物与时代设定为准。")
    add("sp-socialization", whenToUse = "希望用问答或情景演练练习社交。", pairing = "适合单独训练；不想被出题时先用其他社会场景技能。")
    add("hn-scene-writing", "dp-chapter-adult-original", whenToUse = "写虚构成年角色的亲密场景正文。", pairing = "场景写作先选一个；跨章时可加连续性，不需要同时开互动引擎。")
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
