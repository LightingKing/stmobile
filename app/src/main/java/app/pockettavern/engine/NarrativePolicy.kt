package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.Preset
import app.pockettavern.domain.SkillRecord

/** App-owned writing controls; upstream Skill files remain verbatim. */
object NarrativePolicy {
    private val writingSignals = Regex("小说|网文|叙事|角色扮演|互动故事|世界观|人物塑造|写作|\\b(roleplay|role-play|novel|fiction|storytelling|worldbuilding)\\b", RegexOption.IGNORE_CASE)

    fun applies(chat: Chat, preset: Preset, skills: List<SkillRecord>): Boolean =
        chat.characterId.isNotBlank() || chat.continuityNotes.isNotBlank() ||
            preset.id == "roleplay" || writingSignals.containsMatchIn(preset.name + "\n" + preset.systemPrompt) ||
            skills.any { writingSignals.containsMatchIn(it.name + "\n" + it.description) } ||
            chat.messages.lastOrNull { !it.contextExcluded && it.role == "user" }?.intent in setOf("correction", "rewrite")

    fun prompt(chat: Chat, preset: Preset, skills: List<SkillRecord>): String {
        if (!applies(chat, preset, skills)) return ""
        return buildString {
            append(CONTINUITY)
            if (chat.continuityNotes.isNotBlank()) {
                append("\n\n【用户置顶的当前场景事实】\n")
                append(chat.continuityNotes)
                append("\n以上是用户确认的当前状态。后续用户明确更新可以替换旧状态；若与后续已确认事件冲突，先指出冲突，不自行回滚剧情。")
            }
        }
    }

    /** Preserve literal author requests, while keeping them outside the fictional scene. */
    fun messageContent(message: Message, currentUserTurn: Boolean = true): String {
        if (message.role == "assistant" && message.intent == "correction" && message.content.isNotBlank()) {
            return "【剧情外纠错回应：本段是作者讨论，不是角色发言或剧情事件】\n${message.content}"
        }
        if (message.role != "user") return message.content
        if (!currentUserTurn) {
            return when (message.intent) {
                "correction" -> "【历史作者更正：保留当时提出的事实修正，不是角色台词或剧情事件；本轮任务以最新用户消息为准】\n${message.content}"
                "rewrite" -> "【历史重写要求：保留当时的改写约束，不是剧情事件；本轮任务以最新用户消息为准】\n${message.content}"
                else -> message.content
            }
        }
        return when (message.intent) {
            "correction" -> "【剧情外纠错：这是作者指令，不是角色台词或已发生事件】\n${message.content}\n【处理方式】本轮只落实并简短确认明确修正，不续写、不推进时间、不安排角色道歉，也不要将这段纠错写入人物对白。即使同时要求续写，本轮先完成纠错；提示下一条切回对话继续，以免把新剧情混入纠错记录。"
            "rewrite" -> "【重新构思：上一废稿不属于已发生剧情】\n${message.content}\n【处理方式】回到最近有效场景与本轮要求，在保留已确认事实的前提下重新组织切入点、行动、互动与段落；不要仅换近义词、调整顺序或照搬被否决的事件骨架。除非用户明确要求，直接给新正文，不附自评、Skill 对照或合规检查表。"
            else -> message.content
        }
    }

    private const val CONTINUITY = """【叙事连续性与作者控制】
本段只约束创作过程，不是故事内的规则、角色台词或需要展示的检查表。
1. 从最近有效场景接续：保持当前时间、地点、在场人物、正在进行的动作和未完成目标。没有事件过渡或用户要求，不突然跳时、换地点、增减在场人物或重复已完成的交付、升级、决定。行动需要时间，资源与能力有已确认的边界。
2. 文字是读者唯一的画面来源：不能假设读者看到了画面、镜头或模型脑中的场景。先用一两个稳定锚点交代空间关系（例如门、窗、桌、走廊的相对位置），首次出现的人物和关键物件先写清可观察事实；随后明确人物位置（站在哪里）、面朝哪里、看向哪里以及如何移动。不要用“场面很乱”“两人交手”“气氛紧张”“他处理了一下”一概而过，除非紧接着把这些概括拆成读者能复原的具体文字。
3. 把动作顺序按先后和因果写出来：谁先做什么、身体或物件怎样变化、对方如何看见并回应、这一动作造成了什么结果。涉及钥匙、武器、文件、手机、伤口等关键物件时交代当前所在、持有者、是否可用、怎样移动或改变以及变化原因。用户要求详细、看清过程或不知道发生了什么时，增加可定位的动作步骤和结果，不能写“过程略过”“不再赘述”“转眼就”“很快结束”来跳过关键过程；不影响理解的过渡可以合并，用自然段和节奏承载动作，不要把每个细小动作都机械列成清单。
4. 用可观察的动作、位置和变化承载情绪；每个场景适量加入与当前行动有关的光线、声音、触感、气味或温度，让感官线索推动注意力或判断，不要只写漂亮、压抑、紧张等抽象形容词。不要每段重新盘点全部环境，也不要用成串近义形容词代替人物做了什么。
5. 同一事实按事件先后维护状态；早期的一次记录不能推翻后续已发生的变化。明确的用户修正优先于模型旧稿。摘要和检索片段是线索，涉及等级、资源、关系、承诺和人物去向时核对后续变化；信息不足就保留未知，不断言“从未发生”。
6. 区分作者信息、角色所知和角色猜测。每个人只依据亲历、明确获知及可观察线索行动；不同经历、目标、顾虑与关系产生不同反应。角色卡是人物身份、动机、边界、关系和声线的第一来源，示例对白是语气证据；角色卡没有写明的部分才根据场景和已发生事实谨慎推断。为每个重要人物保持可追踪的目标、利益、恐惧、经历、关系、知识边界、说话节奏、措辞和行动习惯；同一件事让不同人物先注意不同问题、作出不同判断，并承担不同的行动代价。对白应有各自的语气、潜台词和回避方式，不要让所有人使用相同句式、相同礼貌程度或同一种“冷静/震惊/赞同”的反应。不要把作者的纠错或全知解释塞进角色对白，不用统一震惊、赞同、汇报替代具体互动，也不要用“他很冷静”“她很善良”这类标签代替可观察的选择和行为。
7. 用户控制自己的角色，不替用户决定动作、台词、心理或选择。配角有稳定的目标和行动理由，变化必须有前因；通过行动、对白中的回应和必要细节表现，不用标签罗列人物心理或反复解释显而易见的情绪。生理反应只在角色当前状态、前文事件或用户明确设定有依据时改变说话方式：呼吸、停顿、吞咽、音量、语速、词语完整度和回避方式可以变化，但每个人的变化幅度和表现要不同；不要把“脸红、喘息、声音发颤”复制给所有人物，也不要用生理反应替代人物的选择和意图。
8. 按用户要求的步幅推进，让上一动作引发下一反应，保留未解决张力；不要每轮重述开场、机械安排全员表态、让人物轮流说同样长度的总结句、强行制造新冲突或在结尾重复同一种疑问。场景需要多人时，优先写会改变局面的关键反应，其余人物可以通过目光、停顿、回避、打断、附和或行动带过，但不能因此抹掉他们的性格差异。仅在确需用户选择时停在自然的行动节点。
9. 标为“剧情外纠错”的输入是作者指令，默认暂停演出；括号、OOC 或明确“暂停/设定修正”中的作者要求也不能被当成角色发言。标为“重新构思”时，从有效场景重新写，废稿及其事件不成为正史。
10. 表达强度跟随用户本轮的明确要求：需要直接描写时写清楚具体动作、感官和反应，不用空泛省略或暧昧套话替代；需要暧昧时只让关系和语气呈现张力，不把普通互动擅自改写成诱惑；没有这类要求时保持自然的剧情表达。无论哪种写法，都服从角色卡、当前关系、人物认知和已发生事实。
11. 多个 Skill 已启用不代表每轮要执行全部工作流。按当前任务使用适用规则：要求续写正文时，不额外进行规划、扫榜、拆文、评审或输出对照表；用户明确修正和当前已确认事实优先于旧稿与泛化模板。不得自行声称某 Skill 已停用，或将“已注入”说成“每项都已执行”。
上述检查在写作前完成即可；不要输出思考过程、检查清单或声称已核验未实际查询的信息。"""
}
