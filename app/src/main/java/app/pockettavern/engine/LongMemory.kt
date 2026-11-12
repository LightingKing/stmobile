package app.pockettavern.engine

import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Chat
import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.domain.Message

/**
 * What a long chat's memory sends with every request besides the working summary: one digest per
 * chapter, and the current file of each person. Both are ordinary knowledge notes, so they are
 * source-checked, searchable and exported like the rest.
 */
data class MemoryLayers(val chapters: List<KnowledgeNote> = emptyList(), val profiles: List<KnowledgeNote> = emptyList())

/**
 * Long-novel memory. The original text is never dropped; this decides when older turns are folded
 * into a chapter and how much of each layer goes to the model. History is folded by its own
 * length: the number of selected Skills and the input budget play no part.
 */
object LongMemory {
    const val CHAPTER = "chapter"
    const val PROFILE = "profile"

    /** Turns that make one chapter. */
    const val CHAPTER_TURNS = 8
    /** Recent turns kept as original text on a fresh install; the setting can change it. */
    const val DEFAULT_RECENT_TURNS = 16
    /** Estimated tokens of original text the recent window may hold, whatever the turn count. */
    const val RECENT_TOKEN_CAP = 32_000
    /** Unfolded text beyond the recent window that is enough for a chapter in fewer turns. */
    const val CHAPTER_TOKEN_CAP = 12_000
    /** Folded before a reply without asking; a longer stretch waits for the user's go. */
    const val AUTO_FOLD_TURNS = 2 * CHAPTER_TURNS
    const val PROFILE_PROMPT_CAP = 6_000
    const val CHAPTER_PROMPT_CAP = 8_000
    const val SETTINGS_REVISION = 1

    /** True when the text after the summary has grown by a chapter beyond the recent window. */
    fun due(liveTurns: Int, liveTokens: Int, keepTurns: Int): Boolean =
        liveTurns >= keepTurns.coerceAtLeast(1) + CHAPTER_TURNS || liveTokens > RECENT_TOKEN_CAP + CHAPTER_TOKEN_CAP

    /**
     * Once: the old default of 8 recent turns becomes 16. Eight was chosen when history was only
     * folded under budget pressure; with regular chapters a longer run of original text reads better.
     */
    fun migrate(settings: AppSettings): AppSettings = if (settings.memoryRevision >= SETTINGS_REVISION) settings else settings.copy(
        keepRecentTurns = if (settings.keepRecentTurns == 8) DEFAULT_RECENT_TURNS else settings.keepRecentTurns,
        memoryRevision = SETTINGS_REVISION
    )

    /** Paid requests a fold of [turns] needs: one per chapter. */
    fun foldRequests(turns: Int): Int = (turns + CHAPTER_TURNS - 1) / CHAPTER_TURNS

    fun name(note: KnowledgeNote): String = note.title.trim().ifBlank { note.entity.substringAfter(':').trim() }.ifBlank { "未命名" }

    private class Cached(val messages: List<Message>, val notes: List<KnowledgeNote>, val cutoff: Int, val layers: MemoryLayers)
    @Volatile private var cached: Cached? = null

    /**
     * Chapters in story order and the newest file of each person, taken only from notes whose
     * sources are unchanged and lie inside the summarized part (up to [cutoff]). With no valid
     * summary nothing is returned: the original text itself is being sent then.
     */
    fun layers(chat: Chat, cutoff: Int): MemoryLayers {
        if (cutoff < 0) return MemoryLayers()
        cached?.takeIf { it.messages === chat.messages && it.notes === chat.knowledgeNotes && it.cutoff == cutoff }?.let { return it.layers }
        val positions = chat.messages.mapIndexed { index, message -> message.id to index }.toMap()
        val hashes = HashMap<List<String>, String>()
        fun lastSource(note: KnowledgeNote): Int? {
            if (note.sourceKind != "conversation" || note.text.isBlank() || note.sourceMessageIds.isEmpty()) return null
            val indices = note.sourceMessageIds.map { positions[it] ?: return null }
            if (indices.any { it > cutoff }) return null
            val hash = hashes.getOrPut(note.sourceMessageIds) { ContextEngine.sourceHash(indices.map { chat.messages[it] }) }
            return if (hash == note.sourceHash) indices.max() else null
        }
        val placed = chat.knowledgeNotes.filter { it.kind == CHAPTER || it.kind == PROFILE }.mapNotNull { note -> lastSource(note)?.let { note to it } }
        val order = compareBy<Pair<KnowledgeNote, Int>>({ it.second }, { it.first.createdAt })
        val chapters = placed.filter { it.first.kind == CHAPTER }.sortedWith(order).map { it.first }
        val profiles = placed.filter { it.first.kind == PROFILE }
            .groupBy { KnowledgeOrganization.canonicalEntity(it.first.entity.ifBlank { it.first.title }) }
            .map { (_, versions) -> versions.maxWithOrNull(order)!! }
            .sortedWith(order.reversed()).map { it.first }
        return MemoryLayers(chapters, profiles).also { cached = Cached(chat.messages, chat.knowledgeNotes, cutoff, it) }
    }

    /** The files handed to the summary model, newest first, so it updates them instead of starting over. */
    fun profileInput(profiles: List<KnowledgeNote>, cap: Int = PROFILE_PROMPT_CAP): List<KnowledgeNote> {
        var used = 0
        return profiles.takeWhile { note ->
            used += ContextEngine.estimateTokens(name(note)) + ContextEngine.estimateTokens(note.text) + 8
            used <= cap
        }
    }

    /** Every chapter in order. Past the cap the oldest ones shrink to their titles, then drop out. */
    fun chapterPrompt(chapters: List<KnowledgeNote>, cap: Int = CHAPTER_PROMPT_CAP): String {
        if (chapters.isEmpty()) return ""
        val head = "【章节摘要】按故事先后，由历史原文整理；下面的最近原文更新、更细，冲突时以原文为准。需要原话或细节时检索原文。"
        fun title(index: Int) = "第 ${index + 1} 章《${chapters[index].title.trim().ifBlank { "未命名" }}》"
        fun full(index: Int) = "${title(index)}：${chapters[index].text.trim()}"
        var used = ContextEngine.estimateTokens(head)
        val lines = arrayOfNulls<String>(chapters.size)
        var index = chapters.lastIndex
        // Newest first: what just happened matters most and is kept whole.
        while (index >= 0) {
            val line = full(index)
            val cost = ContextEngine.estimateTokens(line) + 1
            if (used + cost > cap) break
            lines[index] = line; used += cost; index--
        }
        while (index >= 0) {
            val line = title(index)
            val cost = ContextEngine.estimateTokens(line) + 1
            if (used + cost > cap) break
            lines[index] = line; used += cost; index--
        }
        val dropped = index + 1
        val note = if (dropped > 0) "\n（更早的 $dropped 章没有列出，内容仍在知识库里，可检索。）" else ""
        return head + note + "\n" + lines.filterNotNull().joinToString("\n")
    }

    /** The newest file of each person, most recently updated first; the rest are named only. */
    fun profilePrompt(profiles: List<KnowledgeNote>, cap: Int = PROFILE_PROMPT_CAP): String {
        if (profiles.isEmpty()) return ""
        val head = "【人物档案】由历史原文整理，记录到最近一次整理为止；下面的最近原文更新，冲突时以原文为准。"
        var used = ContextEngine.estimateTokens(head)
        val shown = mutableListOf<String>()
        val omitted = mutableListOf<String>()
        profiles.forEach { note ->
            val aliases = note.aliases.map(String::trim).filter { it.isNotEmpty() && it != name(note) }.distinct()
            val block = "■ ${name(note)}${if (aliases.isEmpty()) "" else "（又称：${aliases.joinToString("、")}）"}\n${note.text.trim()}"
            val cost = ContextEngine.estimateTokens(block) + 2
            if (omitted.isEmpty() && used + cost <= cap) { shown += block; used += cost } else omitted += name(note)
        }
        val rest = if (omitted.isEmpty()) "" else "\n另有人物未列出档案，可检索：${omitted.joinToString("、")}"
        return head + "\n" + shown.joinToString("\n") + rest
    }
}
