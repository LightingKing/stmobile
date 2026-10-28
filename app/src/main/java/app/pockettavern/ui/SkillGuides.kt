package app.pockettavern.ui

import app.pockettavern.domain.GitHubStars

/** App-authored plain-language summary for a bundled Skill; upstream SKILL.md stays untouched. */
data class SkillGuide(val effect: String, val note: String = "", val example: Pair<String, String>? = null)

private const val ADULT_NOTE = "成人向：需先确认年满 18 岁，只用于虚构成年角色。"

object SkillGuides {
    private val guides: Map<String, SkillGuide> = mapOf(
        "wf-fantasy" to SkillGuide("回复会更注重魔法和能力体系的规则与代价，世界观更自洽，偏西式奇幻。"),
        "wf-mystery" to SkillGuide("回复会更会埋线索、设误导，真相揭开时前文有据可查。"),
        "wf-dialogue" to SkillGuide(
            "对话更像真人：有潜台词、有节奏，台词配动作。",
            "效果和“网文对话与台词”接近，更通用，不带爽文腔。"
        ),
        "wf-scene" to SkillGuide("每段剧情会写清目标、阻碍、后果、角色反应和下一步决定，推进感更强。"),
        "wf-romance" to SkillGuide(
            "感情线更有层次：吸引、拉扯、冲突、升温，不会一下跳到确定关系。",
            "不含露骨内容。"
        ),
        "sm-story-structure" to SkillGuide(
            "给出故事大纲、节拍表、人物弧线等整体规划。",
            "只做规划，不写正文；适合策划，不适合直接游玩。英文原文。"
        ),
        "sm-fiction-scene" to SkillGuide(
            "场景更有画面和感官细节，台词带潜台词。",
            "原文要求写完后附一段“本场景完成了什么、用了什么手法”，回复末尾可能多出这段说明。英文原文。"
        ),
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
        "qm-apocalypse" to SkillGuide(
            "按无限流、末世题材出总纲和章纲：资源约束、死亡条件、隐藏奖励。",
            "只出大纲，会先要求填“题材卡”，不写正文。"
        ),
        "qm-outline-builder" to SkillGuide(
            "一步步引导你从点子做出完整大纲：卖点、主角、配角、世界观。",
            "写书用，不写正文，不用于游玩。"
        ),
        "qm-world-rules" to SkillGuide(
            "回复会更严格地守住规则的来源、边界和代价，不临时冒出万能规则。",
            "偏设计期使用，原文面向大纲。"
        ),
        "qm-supporting-cast" to SkillGuide(
            "按功能给配角定位（资源、压力、反派等），写明各自目标和退场方式。",
            "偏设计期使用，原文面向大纲。"
        ),
        "ss-hard-sci-fi" to SkillGuide(
            "回复更讲究能源、物资、废热、通信等硬约束，适合规则严密的末世。",
            "原文是长篇写书流程，带 6 份参考文件，内容较长。"
        ),
        "fv-card-author" to SkillGuide(
            "帮你写新的角色卡：设定、开场白、世界书。",
            "做卡用，游玩时开着没用；正文约 2 万字，很占上下文。"
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
        "cn-chinese-novelist" to SkillGuide(
            "把长篇写作拆成需求、规划、章节、校验和断点续写，提醒模型持续核对人物、情节和已完成进度。",
            "GitHub ★ 4k+、MIT；原文依赖项目文件、脚本和子 Agent，手机端只使用其中的文本流程与参考资料。"
        ),
        "sh-shuorenhua" to SkillGuide(
            "保留事实、条件、否定、责任主体和作者语气，只清理空包装语与翻译腔，减少改写时擅自补内容。",
            "GitHub ★ 2k+、MIT；适合修正文稿和处理“番茄炒蛋（不加青椒）”这类明确事实边界，默认做最小必要修改。"
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
        "an-awesome-novel" to SkillGuide(
            "长篇按设定→卷纲→章纲→提示词→正文→去 AI 味→归档推进，每个关键节点停下等你确认；也有短篇分支。",
            "原版依赖 init.py 和多个子代理，手机上不能运行脚本，只在对话里按流程走；带 160 多份参考文件。AGPL-3.0。"
        ),
        "an-roleplay-sandbox" to SkillGuide(
            "卡剧情时设好舞台、角色和触发条件，模型逐个推演每个角色的行动、台词和神态，你每轮可改，推到剧情目标为止。",
            "原文要求把推演记录写进 sandbox/ 文件，没授权文件写入时只在对话里输出。AGPL-3.0。"
        ),
        "os-story" to SkillGuide(
            "按你的说法判断该走开书、续写、拆文、扫榜还是审稿，并转到对应流程。",
            "原版是桌面路由，会提到 /story-* 命令、子代理和本地部署；手机上要同时开对应的 oh-story 技能才有完整说明。"
        ),
        "os-story-long-write" to SkillGuide(
            "长篇从选题、设定、卷纲、细纲到正文按教练式流程推进；只讨论结构时不写正文，你明确要求后才写章节。",
            "带 70 多份参考文件，模型会频繁读取；原版的检测脚本和子代理手机上用不了。"
        ),
        "os-story-short-write" to SkillGuide(
            "短篇以情绪为目标，从构思、排纲到成稿写完一整篇，注重情绪拉扯和反转节奏。",
            "带 30 多份参考文件；原版的字数和 AI 腔检测脚本手机上跑不了。"
        ),
        "os-story-deslop" to SkillGuide(
            "从表达、叙事用笔、故事因果三层找出 AI 腔并改写，保留你的文风和已定剧情。",
            "和“去 AI 味”作用接近，二选一即可；去味时只改已有文字，不擅自新增微动作、冲突、证据或设定；会附诊断说明，篇幅偏长。"
        ),
        "os-story-review" to SkillGuide(
            "你说“审一下”时，从结构、角色、文字、设定几个角度挑问题，给出有原文依据的修改建议。",
            "原版并行派多个审稿子代理；手机上由一个模型依次审（solo 模式），报告较长。"
        ),
        "os-story-long-analyze" to SkillGuide(
            "把一本长篇按黄金三章、逐章摘要、情绪、节奏、角色、设定和文风拆开分析。",
            "需要你把原文贴进对话，长书要分批；原版会写多份文件并跑脚本，手机上只能在对话里做。"
        ),
        "os-story-short-analyze" to SkillGuide(
            "拆解爆款短篇的故事核、结构、情感线、反转和写作手法，给你写下一篇做参考。",
            "需要你提供原文；报告较长。"
        ),
        "os-story-long-scan" to SkillGuide(
            "按起点、番茄、晋江、七猫等榜单样本找题材规律，结合你的条件给出可行性和风险。",
            "原版要用浏览器抓榜单；手机上需要你贴榜单数据，否则只能凭模型已有知识，结论仅供参考。"
        ),
        "os-story-short-scan" to SkillGuide(
            "按知乎盐言、七猫、黑岩、点众等平台的热门短篇样本，给出能写的情绪方向和题材候选。",
            "原版要用浏览器抓榜单；手机上需要你贴榜单数据，否则只能凭模型已有知识，结论仅供参考。"
        ),
        "os-story-import" to SkillGuide(
            "把你写好的小说（半成品或完本）反向整理成设定、大纲、正文和伏笔追踪，方便接着写。",
            "需要你把原文贴进对话，长书要分批；原版的文件管道手机上用不了。"
        ),
        "cw-webnovel" to SkillGuide(
            "给一段简介，按选材、构思、分卷、章纲、开头、节奏、章末钩子和去 AI 味的方法规划并起稿。",
            "带 70 多份写法模块；原版的语料库和检索脚本没有收录，“模仿检索”用不了。上游无许可证，仅限个人自用。"
        ),
        "vn-booming" to SkillGuide(
            "把后续剧情往更狠、冲突更高、不可逆的方向推，给出掀桌式方案，用不用由你决定。",
            "方案偏激进，会大改走向；适合卡文时临时开。"
        ),
        "vn-fuck-it" to SkillGuide(
            "不改本章结局，只放大过程中的阻力、烈度、压迫感和场面感。",
            "篇幅会变长；原文面向 vibe-noveling 的项目文件。"
        ),
        "vn-bookplan" to SkillGuide(
            "按 Save the Cat 的故事母型和 15 个节拍规划全书主线和分卷节奏。",
            "只做规划不写正文；原文要写进项目目录，没授权文件写入时只在对话里给出。"
        ),
        "vn-discuss" to SkillGuide(
            "讨论角色、世界观、修炼体系、势力、物品和事件走向时，按对应方法给出方案对比，帮你拍板。",
            "参考文件 event-canvas.md 含要求模型放弃安全判断的句子，整个文件没收录，事件讨论少一份参考。"
        ),
        "vn-name" to SkillGuide(
            "按角色、功法、门派、物品、灵兽、地点、丹药、法号八类，按稀有度起中文风格名字。",
            "原版用 Python 脚本随机抽名；手机上模型参考随附词库手动起名，偶尔会重名。"
        ),
        "vn-polish" to SkillGuide(
            "章节写完后按 AI 味修复、一致性校验、独立润色几轮把关。",
            "第一轮的检测脚本手机上跑不了，模型会跳过；多轮润色较慢。"
        ),
        "vn-revise" to SkillGuide(
            "正文里加粗处扩写、删除线处精简、斜体处润色，没标记的字一个不动。",
            "需要你在发给模型的正文里用 Markdown 标出要改的地方。"
        ),
        "vn-sync" to SkillGuide(
            "章节确认后把剧情摘要、伏笔状态和人物关系回写到项目记忆。",
            "原版写入项目文件和知识图谱；没授权文件写入时只在对话里汇总。"
        ),
        "vn-taste" to SkillGuide(
            "给你几组对比样例让你挑，摸清你真正喜欢的文风，作为后续写作基线。",
            "要来回几轮问答；结果原文写进项目文件。"
        ),
        "vn-training-ground" to SkillGuide(
            "输出一段不算正史的战斗草稿，检验角色和物品的战斗设定是否完整、合不合预期。",
            "试战结果不进正文；原文依赖项目里的角色和物品设定文件。"
        ),
        "vn-write" to SkillGuide(
            "根据事件卡把剧情链展开成正文，补环境、感官、动作和台词，保持角色声线、避免 AI 腔。",
            "需要先有事件卡（可配合“创作讨论与设定设计”）；原文面向项目目录。"
        ),
        "ns-novel-studio" to SkillGuide(
            "长篇按总编、架构师、设定师、写手、编辑、设计师六个角色的流水线推进，有目录规范、状态记录和质量关卡。",
            "要和“小说工作室·”各角色技能一起开才完整；原版按目录写文件，没授权文件写入时只在对话里推进。"
        ),
        "ns-chief-editor" to SkillGuide(
            "模型当总编：立项、编计划、分派任务、审核阶段成果、分批向你汇报，不自己写正文。",
            "需配合“小说工作室总控”和其他角色技能。"
        ),
        "ns-architect" to SkillGuide(
            "按世界观→故事梗概→章节大纲的顺序产出全书施工图。",
            "需配合“小说工作室总控”；只做规划不写正文。"
        ),
        "ns-lore-master" to SkillGuide(
            "把大纲拆成人物、任务线、场景、关键道具和关键情节的设定卡。",
            "需要先有大纲；需配合“小说工作室总控”。"
        ),
        "ns-writer" to SkillGuide(
            "按大纲和设定卡分批写初稿，只决定怎么写，不改剧情走向和人物命运。",
            "需要先有大纲和设定卡；需配合“小说工作室总控”。"
        ),
        "ns-editor" to SkillGuide(
            "对初稿润色，并对照梗概、大纲和设定卡查穿帮，产出定稿和修订记录。",
            "需配合“小说工作室总控”；修订记录较长。"
        ),
        "ns-designer" to SkillGuide(
            "按设定为封面、角色立绘和关键情节插画写出可直接用于文生图的提示词和排版规格。",
            "只写提示词，不生成图片；需配合“小说工作室总控”。"
        ),
        "iw-ip-worldbuilding" to SkillGuide(
            "你给故事灵感或世界观雏形，模型先系统提问，再整理成“世界观底层规则”文档：运行法则、力量体系、代价与禁忌。",
            "提示词约 4 万字节，很占上下文；只出设定不写正文。上游无许可证，仅限个人自用。"
        ),
        "tc-tavern-design" to SkillGuide(
            "做角色卡或世界书前先讨论大方向、世界观、角色、剧情和风格，或把小说、剧本改成故事大纲，整理成设计文档。",
            "问卷里有 SFW/NSFW 分级一项由你选择；产出给“酒馆角色卡与世界书”接着用。上游无许可证，仅限个人自用。"
        ),
        "tc-tavern-cards" to SkillGuide(
            "按设计文档编写 SillyTavern 角色卡和世界书：人设、世界观、NPC、开场白、条目配置，以及 MVU 变量和 EJS。",
            "原版用 Node 脚本打包卡文件，手机上跑不了，只产出文字内容；参考文件 50 多份。上游无许可证，仅限个人自用。"
        ),
        "tc-tavern-ui" to SkillGuide(
            "帮你写在酒馆消息楼层里渲染的状态栏和交互界面（HTML/CSS/JS/Vue 3）。",
            "需要桌面版 SillyTavern 和酒馆助手扩展才能显示，口袋酒馆不会渲染这些界面。上游无许可证，仅限个人自用。"
        ),
        "rs-renqing-shi-gu" to SkillGuide(
            "遇到求人、谈合作、饭局、亲戚局时，按陈汉生的处事方式分析权力、面子、筹码和风险，给出下一步具体做法。",
            "原型来自小说人物，偏创业、地推、BD 场景；可用来写角色，也可自己参考。"
        ),
        "rq-renqing-shigu" to SkillGuide(
            "把直接、生硬、冒犯的话改成得体、留余地又守住边界的说法，适合回领导、长辈、熟人，婉拒和提意见。",
            "回复通常给多个备选说法，篇幅偏长。"
        ),
        "gq-workplace-eq" to SkillGuide(
            "面对领导、同事、下属、体制内和危机场景，给出得体、稳妥的回话和表达，覆盖 23 种以上常见场景。",
            "原版的 Python 话术生成脚本没有收录；回复常带多个备选话术。"
        ),
        "tz-tizhi" to SkillGuide(
            "问到机关、事业单位、国企里的领导相处、汇报、写材料、留痕、考核提拔时，按体制内的规矩给出应对办法。",
            "侧重处境应对，不代写整篇公文。"
        ),
        "sp-socialization" to SkillGuide(
            "可以问社交难题，也可以进入训练模式做情景题，依据《关键对话》《乌合之众》《人情世故大全》讲解。",
            "训练模式会出题并等你作答，要来回几轮。"
        ),
        "dp-chapter-direct" to SkillGuide(
            "写动作、情感、对话等场景时，从句子节奏、微张力到整章张力弧线和风险升级控制叙事节奏。",
            "正文约 3.8 万字节，很占上下文；原文面向 dreampowers 的章节文件夹流程。"
        ),
        "dp-chapter-draft" to SkillGuide(
            "大纲定好后按写作蓝图起草章节：先预审、再分段写、最后自检，衔接前情和伏笔。",
            "原文依赖章节文件夹和子代理；0.1.19 已另收录原版成人场景技能，可按需勾选。"
        ),
        "dp-chapter-summary" to SkillGuide(
            "章节写完后生成不超过 150 字的纯文本摘要和角色章末状态快照，方便跨章保持连贯。",
            "原文要求写入摘要文件；没授权文件写入时只在对话里输出。"
        ),
        "dp-character-style" to SkillGuide(
            "为角色建立说话习惯和语言档案，写对白时检查声线是否一致，并用角色回避不说的话写潜台词。",
            "和本应用编写的“说话风格符合角色设定”作用接近，后者更短；原文约 2 万字节。"
        ),
        "dp-review-consistency" to SkillGuide(
            "对章节做多维度一致性检查（设定、人物、时间线、伏笔等），同时修文字质量、查 AI 腔。",
            "正文约 4 万字节，很占上下文；先核对原文和已确认事实，能删不改、能改不加。例如用户说“番茄炒蛋（不加青椒）”，不得擅自改成加青椒；只补用户明确授权或上下文已有依据的细节。报告较长。"
        ),
        "dp-review-final-report" to SkillGuide(
            "全书写完时对照世界观、人物、大纲和时间线，出一份全书一致性报告。",
            "需要全书内容和设定；原版读项目文件，手机上要分批贴给模型。"
        ),
        "dp-review-reader" to SkillGuide(
            "以没看过大纲和设定的普通读者身份试读，评估追读欲、理解负担、共情强度和节奏感。",
            "只评估不改稿。"
        ),
        "dp-set-concept" to SkillGuide(
            "帮你搭世界观、写角色卡、导入外部设定，并把设定拆成独立文件，按章节控制哪些设定可见。",
            "拆分文件需要授权文件写入；原文约 2 万字节。"
        ),
        "dp-set-outline" to SkillGuide(
            "世界观和角色定好后，规划故事大纲和章节结构，并管理伏笔的埋设与回收。",
            "正文约 4.6 万字节，很占上下文；原文会建章节文件夹和符号链接，手机上做不到。"
        ),
        "dp-set-style" to SkillGuide(
            "动笔前用七维问卷定下整部作品的叙事文风基线，后面写作都按这个基线走。",
            "问卷要来回几轮；结果原文写进 style.md。"
        ),
        "dp-tool-research" to SkillGuide(
            "开新书时帮你发展故事前提、查证现实细节，读完已写章节后按你的意见调整后续方向。",
            "查证现实细节原版要联网搜索；没授权 network 时只能凭模型已有知识。"
        ),
        "dp-using-dreampowers" to SkillGuide(
            "开始写中文小说时先建立写作流程，并按阶段提示该用 dreampowers 的哪个技能。",
            "原文要求创作前先调用对应技能，回复里会频繁提到技能名；成人场景技能已另收录，dp-tool-version 未收录。"
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
