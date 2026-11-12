package app.pockettavern.domain

/**
 * A group chat: several characters in one conversation. It is an ordinary [Chat] with this block,
 * so history, memory, backups and multi-chat replies work as for any chat.
 *
 * [members] are character ids in speaking order. [relations] say how one member (or the user,
 * [GroupRelation.USER]) sees another. [mode] decides who speaks: [AUTO] lets the model pick up to
 * [maxSpeakers], [NAMED] answers only whoever the user @-mentions, [ROUND] has every member say
 * one line in order. [narrator] adds a narrator for actions and surroundings.
 */
data class GroupSetup(
    val members: List<String> = emptyList(),
    val scene: String = "",
    val relations: List<GroupRelation> = emptyList(),
    val mode: String = AUTO,
    val maxSpeakers: Int = 2,
    val narrator: Boolean = false
) {
    companion object {
        const val AUTO = "auto"
        const val NAMED = "named"
        const val ROUND = "round"
        val MODES = listOf(AUTO to "自动", NAMED to "点名才说", ROUND to "轮流")
        const val MIN_MEMBERS = 2
        const val MAX_MEMBERS = 6
        const val MAX_SCENE = 2000
        const val MAX_RELATION = 200
        const val MAX_RELATIONS = 30
        const val NARRATOR = "旁白"
    }
}

data class GroupRelation(val from: String = "", val to: String = "", val text: String = "") {
    companion object {
        /** Stands for the user in [from] or [to]. */
        const val USER = "user"
    }
}

/** One speaker's part of a group reply. [speaker] is null for text the model wrote without a name. */
data class GroupLine(val speaker: String?, val text: String)

object GroupChats {
    /** A setup read from storage or a form, cleaned: unknown members, empty and duplicate parts go. */
    fun normalize(value: GroupSetup?, characterIds: Set<String>? = null): GroupSetup? {
        if (value == null) return null
        val members = runCatching { value.members }.getOrNull().orEmpty().filterNotNull().map(String::trim)
            .filter { it.isNotEmpty() && (characterIds == null || it in characterIds) }.distinct().take(GroupSetup.MAX_MEMBERS)
        val allowed = members.toSet() + GroupRelation.USER
        val relations = runCatching { value.relations }.getOrNull().orEmpty().filterNotNull().mapNotNull { relation ->
            val from = relation.from.orEmpty().trim()
            val to = relation.to.orEmpty().trim()
            val text = relation.text.orEmpty().trim().take(GroupSetup.MAX_RELATION)
            GroupRelation(from, to, text).takeIf { from in allowed && to in allowed && from != to && text.isNotEmpty() }
        }.distinctBy { it.from to it.to }.take(GroupSetup.MAX_RELATIONS)
        val mode = value.mode.orEmpty().takeIf { mode -> GroupSetup.MODES.any { it.first == mode } } ?: GroupSetup.AUTO
        return GroupSetup(members, value.scene.orEmpty().trim().take(GroupSetup.MAX_SCENE), relations, mode,
            value.maxSpeakers.coerceIn(1, members.size.coerceAtLeast(1)), value.narrator)
    }

    /** Why a setup cannot be saved, or null when it can. */
    fun problem(setup: GroupSetup, title: String): String? = when {
        title.isBlank() -> "请填写群名"
        setup.members.size < GroupSetup.MIN_MEMBERS -> "至少选 ${GroupSetup.MIN_MEMBERS} 个角色"
        setup.members.size > GroupSetup.MAX_MEMBERS -> "最多 ${GroupSetup.MAX_MEMBERS} 个角色"
        else -> null
    }

    /** The members that still exist, in speaking order. */
    fun members(setup: GroupSetup, characters: List<Character>): List<Character> {
        val byId = characters.associateBy { it.id }
        return setup.members.mapNotNull(byId::get)
    }

    fun displayName(character: Character): String = character.name.trim().ifEmpty { "未命名角色" }

    /** "@黑猫 …" at the start of a user message names who answers; null when nobody is named. */
    fun mentioned(text: String, members: List<Character>): Character? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("@") && !trimmed.startsWith("＠")) return null
        val rest = trimmed.substring(1)
        // The longest matching name wins, so "@黑猫猫" is not read as "@黑猫".
        return members.filter { displayName(it).isNotEmpty() && rest.startsWith(displayName(it)) }.maxByOrNull { displayName(it).length }
    }

    fun withMention(text: String, target: Character?): String =
        if (target == null || mentioned(text, listOf(target)) != null) text else "@${displayName(target)} $text"

    private val heading = Regex("^\\s*(?:【([^】\\n]{1,24})】|([^\\s：:【】]{1,24})\\s*[：:])\\s*")

    /**
     * Splits a reply into speakers. A line beginning with "【名字】" (or "名字：") starts that
     * speaker's part; only member names and 旁白 count, so a colon inside the story does not.
     * Text before the first name belongs to nobody in particular.
     */
    fun split(content: String, names: List<String>): List<GroupLine> {
        val known = names.filter { it.isNotBlank() }.toSet()
        val lines = mutableListOf<GroupLine>()
        var speaker: String? = null
        val buffer = StringBuilder()
        fun flush() {
            val text = buffer.toString().trim('\n', ' ')
            if (text.isNotEmpty()) lines += GroupLine(speaker, text)
            buffer.clear()
        }
        content.split('\n').forEach { line ->
            val match = heading.find(line)
            val name = match?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).trim() }
            if (match != null && name != null && name in known) {
                flush()
                speaker = name
                buffer.append(line.substring(match.range.last + 1))
            } else {
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(line)
            }
        }
        flush()
        return lines
    }

    /** Names the model may use in this group. */
    fun speakerNames(setup: GroupSetup, characters: List<Character>): List<String> =
        members(setup, characters).map(::displayName) + if (setup.narrator) listOf(GroupSetup.NARRATOR) else emptyList()

    private fun who(id: String, characters: Map<String, Character>): String =
        if (id == GroupRelation.USER) "{{user}}" else characters[id]?.let(::displayName) ?: "（已删除的角色）"

    /** Each member's card, bounded so six long cards do not swamp the request. */
    fun cards(members: List<Character>, globalPersona: String, perCard: Int = 6000): String {
        val parts = mutableListOf("【群聊成员的角色卡】下面每张卡只约束对应的那个角色。写某个角色时只用她自己的卡、示例对白和口癖，不同角色不能串成同一种语气。")
        members.forEach { member ->
            val card = buildList {
                add("■ ${displayName(member)}")
                if (member.description.isNotBlank()) add("描述：${member.description.trim()}")
                if (member.personality.isNotBlank()) add("性格：${member.personality.trim()}")
                if (member.scenario.isNotBlank()) add("她自己卡上的场景（以群聊场景为准）：${member.scenario.trim()}")
                if (member.examples.isNotBlank()) add("示例对话：${member.examples.trim()}")
                if (member.systemPrompt.isNotBlank()) add("她的补充设定：${member.systemPrompt.trim()}")
            }.joinToString("\n")
            parts += card.take(perCard)
        }
        if (globalPersona.isNotBlank()) parts += "全局用户人设：$globalPersona"
        return parts.joinToString("\n\n")
    }

    /**
     * The group's rules for this turn: scene, relations, the reply format, and who speaks. The app
     * splits the reply by the "【名字】" headings, so the format is part of the contract.
     */
    fun rules(setup: GroupSetup, characters: List<Character>, latestUserText: String): String {
        val byId = characters.associateBy { it.id }
        val members = members(setup, characters)
        val names = members.map(::displayName)
        val parts = mutableListOf<String>()
        parts += "【群聊】这是 {{user}} 和 ${names.joinToString("、")} 的群聊。{{user}} 是真人用户，不要替 {{user}} 说话或行动。"
        if (setup.scene.isNotBlank()) parts += "【当前场景】${setup.scene}"
        if (setup.relations.isNotEmpty()) parts += "【人物关系】\n" + setup.relations.joinToString("\n") { "- ${who(it.from, byId)} → ${who(it.to, byId)}：${it.text}" }
        val allowed = names + if (setup.narrator) listOf(GroupSetup.NARRATOR) else emptyList()
        parts += "【回复格式】每个人的发言单独起一行，以“【名字】”开头，后面紧跟她说的话和动作；名字只能是：${allowed.joinToString("、")}。" +
            "同一个人可以在一轮里出现多次，但不要把几个人写进同一段。成员之间可以互相接话、调侃、争论，按关系和性格来。" +
            if (setup.narrator) "【旁白】只写动作、神态和环境，不说台词，篇幅简短。" else "不写旁白，动作和神态写在说话人自己的段落里。"
        val named = mentioned(latestUserText, members)
        parts += when {
            named != null -> "【本轮谁说话】{{user}} 点名了${displayName(named)}：这一轮只由${displayName(named)}回答，其他人不说话。"
            setup.mode == GroupSetup.ROUND -> "【本轮谁说话】按顺序每人说一段：${names.joinToString(" → ")}。"
            setup.mode == GroupSetup.NAMED -> "【本轮谁说话】这个群只有被点名的人才回答；这一轮没有点名，由与话题最相关的一个人简短回应。"
            else -> "【本轮谁说话】根据话题、性格和关系，挑最自然会接话的人，最多 ${setup.maxSpeakers} 人；不必每个人都说。"
        }
        return parts.joinToString("\n")
    }

    /** The last thing said in a group, as the chat list shows it: "黑猫：……". */
    fun lastLine(content: String, names: List<String>): String {
        val last = split(content, names).lastOrNull() ?: return content
        return if (last.speaker == null) last.text else "${last.speaker}：${last.text}"
    }
}
