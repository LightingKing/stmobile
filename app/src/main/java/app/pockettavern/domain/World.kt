package app.pockettavern.domain

/**
 * 小世界 as the phone sees them. The worlds themselves live on the PC writing server (since server
 * 0.13.0); the phone keeps nothing about them in its own data and only shows what it last read.
 */
data class WorldUsage(val ticks: Int = 0, val calls: Int = 0, val promptTokens: Long = 0, val completionTokens: Long = 0) {
    val tokens: Long get() = promptTokens + completionTokens
}

/** Each world's pace and limits, as the PC checks them (ranges in [WorldLimits]). */
data class WorldSettings(
    val tickMinutes: Int = 30, val quietStart: String = "01:00", val quietEnd: String = "08:00",
    val dailyTicks: Int = 40, val dailyCalls: Int = 80, val slowWhenAway: Boolean = true
)

object WorldLimits {
    const val RESIDENTS = 8
    const val USER_TEXT = 2000
    val TICK_MINUTES = listOf(15, 20, 30, 45, 60, 90, 120)
    val DAILY_TICKS = 0..200
    val DAILY_CALLS = 1..400
    private val HHMM = Regex("([01]\\d|2[0-3]):[0-5]\\d")
    fun validTime(value: String) = HHMM.matches(value)
}

data class WorldResident(
    val id: String, val name: String, val location: String = "", val activity: String = "", val mood: String = "",
    val plan: String = "", val notes: String = "", val card: String = "", val aliases: List<String> = emptyList()
)

/** One line of a world's timeline: a message in the group or a private chat, or an event. */
data class WorldItem(
    val seq: Long, val kind: String, val channel: String = "", val from: String = "", val name: String = "",
    val text: String = "", val time: String = "", val worldAt: Long = 0, val who: List<String> = emptyList()
) {
    val isEvent: Boolean get() = kind == "event"
    val fromUser: Boolean get() = from == "user"
}

data class WorldSummary(
    val id: String, val name: String, val summary: String = "", val worldTimeLabel: String = "",
    val paused: Boolean = false,
    /** Why it is not moving on right now: paused, calls, ticks or quiet; blank while it runs. */
    val hold: String = "",
    val busy: Boolean = false, val unread: Int = 0, val lastMessage: WorldItem? = null,
    val usage: WorldUsage = WorldUsage(), val totals: WorldUsage = WorldUsage(), val settings: WorldSettings = WorldSettings(),
    val model: String = "pocket-direct", val residents: List<WorldResident> = emptyList(),
    val nextTickAt: Long? = null, val intervalMinutes: Int = 30, val lastError: String = "", val seq: Long = 0
)

data class WorldDetail(val summary: WorldSummary, val groupName: String = "", val userName: String = "", val userRole: String = "", val setting: String = "",
    /** 要记住的事: lines the user wrote that every request of the world carries. */
    val facts: List<String> = emptyList())

data class WorldDiaryEntry(val day: String, val text: String)

/** The world open on screen: what has been read of it, and a message on its way. */
data class OpenWorld(
    val id: String, val detail: WorldDetail? = null, val items: List<WorldItem> = emptyList(), val next: Long = 0,
    val pending: WorldItem? = null, val error: String = "", val busy: String = "",
    val diary: List<WorldDiaryEntry>? = null, val memories: Map<String, String> = emptyMap(),
    /** Where to land when it opens, e.g. "dm:r1" from a notification; [focusToken] changes with every such open. */
    val focus: String = "", val focusToken: Long = 0
)

data class WorldsState(val list: List<WorldSummary>? = null, val error: String = "", val open: OpenWorld? = null)

object Worlds {
    fun holdLabel(hold: String): String = when (hold) {
        "paused" -> "已暂停"
        "calls" -> "今天的请求次数用完了"
        "ticks" -> "今天的推进次数用完了"
        "quiet" -> "安静时段，大家在睡觉"
        else -> "运行中"
    }

    /** New lines after what is already shown, in timeline order, each once. */
    fun merge(old: List<WorldItem>, fresh: List<WorldItem>): List<WorldItem> {
        if (fresh.isEmpty()) return old
        val known = old.mapTo(HashSet()) { it.seq }
        return (old + fresh.filter { it.seq !in known }).sortedBy { it.seq }
    }

    fun groupLines(items: List<WorldItem>): List<WorldItem> = items.filter { it.isEvent || it.channel == "group" }
    fun dmLines(items: List<WorldItem>, residentId: String): List<WorldItem> = items.filter { it.channel == "dm:$residentId" }

    /** The private chats someone has written in, newest first, with their last line. */
    fun dmThreads(items: List<WorldItem>, residents: List<WorldResident>): List<Pair<WorldResident, WorldItem?>> =
        residents.map { resident -> resident to items.lastOrNull { it.channel == "dm:${resident.id}" } }
            .sortedByDescending { it.second?.seq ?: -1 }

    /** A fresh summary from the PC, keeping the residents' cards and aliases the detail already had. */
    fun refreshed(detail: WorldDetail, summary: WorldSummary?): WorldDetail {
        if (summary == null || summary.id != detail.summary.id) return detail
        val known = detail.summary.residents.associateBy { it.id }
        return detail.copy(summary = summary.copy(residents = summary.residents.map { it.copy(card = known[it.id]?.card.orEmpty(), aliases = known[it.id]?.aliases.orEmpty()) }))
    }

    /** Private messages residents sent after [afterSeq]: what a new-message notification is about. */
    fun newDms(items: List<WorldItem>, afterSeq: Long): List<WorldItem> = items.filter { it.seq > afterSeq && it.channel.startsWith("dm:") && !it.fromUser && !it.isEvent }

    /** "苏糖发来 2 条私信" or, from several people, "苏糖、林晚发来 3 条私信"; the last line follows. */
    fun noticeTitle(world: String, dms: List<WorldItem>): String {
        val names = dms.map { it.name }.filter { it.isNotBlank() }.distinct()
        return "$world · ${names.take(3).joinToString("、")}${if (names.size > 3) " 等" else ""}" + if (dms.size > 1) "发来 ${dms.size} 条私信" else ""
    }

    /** Why these settings would be refused by the PC, or null. */
    fun settingsProblem(settings: WorldSettings): String? = when {
        settings.tickMinutes !in WorldLimits.TICK_MINUTES -> "推进间隔无效"
        !WorldLimits.validTime(settings.quietStart) || !WorldLimits.validTime(settings.quietEnd) -> "安静时段要写成 HH:MM，例如 01:00"
        settings.dailyTicks !in WorldLimits.DAILY_TICKS -> "每天推进次数要在 0 到 200 之间"
        settings.dailyCalls !in WorldLimits.DAILY_CALLS -> "每天请求次数要在 1 到 400 之间"
        else -> null
    }

    fun preview(item: WorldItem?): String = when {
        item == null -> "还没有消息"
        item.isEvent -> item.text
        item.channel.startsWith("dm:") -> "${item.name}（私信）：${item.text}"
        else -> "${item.name}：${item.text}"
    }
}
