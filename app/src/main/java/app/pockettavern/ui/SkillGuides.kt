package app.pockettavern.ui

import app.pockettavern.domain.GitHubStars

/** App-authored plain-language summary for a bundled Skill; upstream SKILL.md stays untouched. */
data class SkillGuide(val effect: String, val note: String = "", val example: Pair<String, String>? = null)

private const val ADULT_NOTE = "成人向：需先确认年满 18 岁，只用于虚构成年角色。"

object SkillGuides {
    private val guides: Map<String, SkillGuide> = mapOf(
        "hn-scene-writing" to SkillGuide("亲密场景会按 L1–L6 分级安排节奏和感官描写。", ADULT_NOTE),
        "hn-worldbuilding" to SkillGuide("设定里会加入双修功法、成人场所之类的规则。", ADULT_NOTE),
        "hn-continuity" to SkillGuide("追踪好感度、关系进展和身体与情感状态，保持前后一致。", ADULT_NOTE),
        "va-velvet-ascent" to SkillGuide(
            "每轮回复套固定格式：【当前状态】【关系/状态变化】【行动选项】等栏目，关系分阶段升温。",
            "${ADULT_NOTE}每轮都会带状态栏和选项。"
        ),
        "dn-deep-novel" to SkillGuide(
            "长篇写作会先回顾前文和要求，再分段起草、按禁词表和风格自查，并持续记住角色状态和关系。",
            "${ADULT_NOTE}原文包含负面约束和角色知识边界：先核对原文与已确认事实，能删不改、能改不加，不凭空补设定；原文要求建项目目录和记忆文件，需授权文件写入。"
        ),
        "dp-chapter-adult-original" to SkillGuide(
            "按成人章节写作流程安排场景节奏、感官细节和人物情绪，适合小说正文。",
            "${ADULT_NOTE}上游 SKILL.md 原样收录；桌面项目和脚本流程不等于手机端已实现。"
        ),
        "at-adult-tension-original" to SkillGuide(
            "以自主决策的 NPC 推进成人向互动叙事，结合关系变化、世界素材与存档。",
            "${ADULT_NOTE}附 221 份文本资源；DSH 的 Python 运行时未移植，需支持工具的模型读取引用。"
        ),
        "ds-roleplay-preset-original" to SkillGuide(
            "为 DeepSeek 角色扮演提供沉浸式演出格式、角色行为约定和文件记忆安排。",
            "原版入口是 agent.cordis.yml，字节原样保留；手机按文本预设使用，未实现 DSH 专用配置和工具语义。"
        ),
        "fv-chat-doctor" to SkillGuide(
            "聊崩时开一次：诊断是 AI 味、OOC、遗忘、太长还是太短，并给修正办法。",
            "诊断用，用完可关掉。"
        ),
        "tt-sp-roleplay" to SkillGuide(
            "模型会为每个角色记住位置、手里的东西和说话方式，减少串戏。",
            "原文要求建草稿文件，手机上只能靠上下文维护；模型开头可能先声明正在使用这个技能。英文原文。"
        ),
        "jw-game-facilitator" to SkillGuide(
            "回复更像跑团主持：接住你的点子，给出选择和后果，失败也有戏。",
            "和剧本自带的主持预设有重叠；英文原文较长。"
        ),
        "tm-deslop" to SkillGuide(
            "回复会少掉“像 AI 写的”毛病：堆比喻、三连排比、“非常/极其”、“然而”、空洞感慨，语气更口语、句子更短。",
            "适合常开。风格会往朴实网文拉，【】只留给系统弹窗；原文流程会附“原句→改后”对照，不想看可以要求不出。",
            "寒风如刀锋般呼啸。管家非常紧张，感到无比不安。然而，生活总是充满挑战。" to
                "风刮了一夜，窗框一直在响。管家第三次去看温度计，零下四十一度，没说话。"
        ),
        "anti-ai-slop-writing" to SkillGuide(
            "按结构、句式、标点、准确性和角色声线检查 AI 写作痕迹，适合在成稿后做一次针对性去味。",
            "GitHub 英文原版，无许可证，仅限个人自用；中文正文以 tm-deslop 和 os-story-deslop 为主，这个 Skill 作为额外检查清单。"
        ),
        "ss-stop-slop" to SkillGuide(
            "从填充、套话、空泛断言和机械节奏等结构层面找 AI 腔，改写时尽量保留原意和作者声音。",
            "GitHub ★ 18k+、MIT；原文规则偏英文，中文正文要按语义判断，适合审稿或改写时临时开启，不建议和多个去味 Skill 一起常驻。"
        ),
        "tm-dialogue" to SkillGuide(
            "不同 NPC 说话更有区分度（口头禅、句长、语气各不相同），台词更口语、话里有话，少用“愤怒地说”。",
            "NPC 多、开始一个腔调时开。偶尔会把台词写得偏爽文金句；原文流程会附“改前/改后”对照，不想看可以要求不出。",
            "安保队长：“少爷，我对局势感到十分担忧，需要加强防御。”" to
                "安保队长把对讲机拍在桌上：“东门外有车印，新的。今晚我守。”"
        ),
        "tm-continuity" to SkillGuide(
            "更注意前后对得上：时间线、物资和点数增减、谁知道你的秘密、伤势、战力。",
            "聊到几十上百轮、设定开始乱时开。发“体检最近 20 轮”会出矛盾清单和改法；原文可能主动附体检报告，不想看可以要求不出。",
            "上一轮柴油只剩 3 吨，这一轮管家却说“够烧一冬”。" to
                "管家：“按现在的烧法，撑不过十天。”"
        ),
        "dv-roleplay-modes" to SkillGuide(
            "模型读到 DeepSeek V4 官方的思考模式说明，可按你的要求在思考里写括号内心独白（角色沉浸），或只做剧情分析（纯分析）。",
            "整篇使用说明原样发给模型（含 API 示例和 Star 图），比较占上下文；只对有思考过程的 DeepSeek 起作用。上游无许可证，仅限个人自用。"
        ),
        "an-roleplay-sandbox" to SkillGuide(
            "卡剧情时设好舞台、角色和触发条件，模型逐个推演每个角色的行动、台词和神态，你每轮可改，推到剧情目标为止。",
            "原文要求把推演记录写进 sandbox/ 文件，没授权文件写入时只在对话里输出。AGPL-3.0。"
        ),
        "os-story-deslop" to SkillGuide(
            "从表达、叙事用笔、故事因果三层找出 AI 腔并改写，保留你的文风和已定剧情。",
            "和“去 AI 味”作用接近，二选一即可；去味时只改已有文字，不擅自新增微动作、冲突、证据或设定；会附诊断说明，篇幅偏长。"
        ),
        "dp-chapter-direct" to SkillGuide(
            "写动作、情感、对话等场景时，从句子节奏、微张力到整章张力弧线和风险升级控制叙事节奏。",
            "正文约 3.8 万字节，很占上下文；原文面向 dreampowers 的章节文件夹流程。"
        ),
        "dp-character-style" to SkillGuide(
            "为角色建立说话习惯和语言档案，写对白时检查声线是否一致，并用角色回避不说的话写潜台词。",
            "和本应用编写的“说话风格符合角色设定”作用接近，后者更短；原文约 2 万字节。"
        ),
        "pt-character-voice" to SkillGuide(
            "每个角色按自己的角色卡说话：用词、句子长短、正式程度、口头禅跟着人设走，不同 NPC 不再一个腔调。",
            "主要为 DeepSeek V4.1 Flash 写，其他模型也能用。依据角色卡的示例对话、人设和描述，写得越细越准。只管台词和内心独白，旁白文风不变。",
            "老管家和十七岁学徒都说：“少爷，我认为我们需要尽快采取行动。”" to
                "管家：“少爷，库里的柴，紧着用也就半个月。”学徒：“完了完了，冬天咋过啊？”"
        ),
        "pt-china-common-sense" to SkillGuide(
            "现代背景下不再套美国或日本的设定：钱用人民币、付款扫微信支付宝、报警打 110、叫人用“师傅”“阿姨”“X 总”、上学考中考高考，城市生活按当代中国大陆写。",
            "主要为 DeepSeek V4.1 Flash 写，其他模型也能用。角色卡或世界书写明在国外、古代或架空世界时，以设定为准，只补没写到的日常细节。",
            "警察对他宣读米兰达权利，他刷信用卡付了二十美元小费。" to
                "派出所民警让他先坐下做笔录，他扫码付了二十块打车钱。"
        ),
        "pt-web-reach" to SkillGuide(
            "模型能联网：搜网页、B 站、YouTube、GitHub、V2EX，读你贴的链接，回答时注明来源。",
            "只在用电脑写作服务、支持工具调用的模型上生效；由电脑上的 Agent Reach 执行，只读、不登录任何账号。"
        ),
        "se-sepia" to SkillGuide(
            "写或改小说时先查叙事结构里的 AI 痕迹：旁白替读者点题、因果太整齐、情绪只靠身体反应、结局靠成长收束，再修措辞和句长节奏。",
            "GitHub ★ 3.5k、MIT；附中文校准文件。规则与参考文件较长，比较占上下文，适合写整章或审稿时开；不建议和其他去 AI 味 Skill 同时常驻。"
        ),
        "fp-fiction-prose-style" to SkillGuide(
            "旁白贴着视角人物的口吻，台词能“演出来”，每场戏有在场的人和具体物件，细节点到为止，读起来不像 AI。",
            "英文原文，基于一位英文读者的偏好，中文正文需按语义取用；开启后模型会先读约 1.2 万 tokens 的偏好文件，比较占上下文。"
        ),
        "ec-erotica-craft" to SkillGuide(
            "纠正写淡、用代称、关键处留白切走的倾向：给出身体反应、心理在场和用词密度的基准，器官直呼、声音直接写，并避开 AI 腔用词。",
            "${ADULT_NOTE}上游没有许可证，仅限个人自用；样本节选自已出版小说。只管尺度和用词，不管情节与结构，适合和成人场景写作搭配。"
        ),
        "fe-fictional-erotica" to SkillGuide(
            "亲密场景里台词属于具体人物，身体、姿势、衣物和关系状态前后连贯，每一段都推动身体或情绪变化；露骨程度按你的措辞判断。",
            "${ADULT_NOTE}中英双语原文，人设连续性、台词与角色分工等 8 份参考按需读取，需支持工具的模型。"
        )
    ).mapKeys { "builtin-writing-${it.key}" }

    val ids: Set<String> get() = guides.keys

    fun forSkill(id: String): SkillGuide? = guides[id]
}

fun skillGuide(id: String): SkillGuide? = SkillGuides.forSkill(id)

fun starsCardLine(stars: GitHubStars): String = stars.stars?.let { "GitHub 仓库 ★ $it" } ?: "GitHub 仓库星数未知"

fun starsDetailLine(stars: GitHubStars): String {
    val count = stars.stars?.toString() ?: "未知"
    val date = stars.checkedAt.takeIf { it.isNotBlank() }?.let { "，$it 记录" } ?: "，记录日期未知"
    return "GitHub 仓库星数：$count（${stars.repo}$date，非实时）"
}
