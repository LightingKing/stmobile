package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.Preset
import org.json.JSONArray
import org.json.JSONObject

/**
 * A reply sometimes arrives with one character replaced by two to four U+FFFD marks: its bytes
 * were cut apart and decoded separately before the text reached the phone. The character is gone,
 * but its place is known and it is always a single one, so the model is asked, in one small
 * request, which character belongs at each place. The PC writing server does the same for chats
 * that go through it (server 0.10.0); this covers chats that go straight to a platform, and
 * messages saved before either existed.
 */
object DamagedText {
    private const val LOST = '\uFFFD'
    private const val MARK = "⟦?⟧"
    private val RUN = Regex("\uFFFD+")

    /** More damaged places than this in one request is not asked for at once. */
    const val MAX_SPOTS = 24
    private const val CONTEXT = 40
    /** One cut character leaves at most four marks. */
    private const val MAX_RUN = 4

    const val SYSTEM = "下面每一行是一段文字的片段，其中有一处在传输中损坏、丢了一个字符（一个汉字或一个全角标点），位置用 $MARK 标出。" +
        "根据前后文判断每一处丢的是哪一个字符：选让这句话最通顺、最常见的那个字，标点成对出现时补上缺的那一半。" +
        "只返回一个 JSON 数组，按行的顺序，每项就是那一个字符本身，例如 [\"忙\",\"一\",\"（\"]。不要解释，不要输出别的内容。"

    data class Spot(val index: Int, val length: Int)
    data class Fixed(val text: String, val repaired: Int)

    fun damaged(text: String): Boolean = text.indexOf(LOST) >= 0

    /** Every run of marks in [text], in order. */
    fun spots(text: String): List<Spot> = if (!damaged(text)) emptyList() else RUN.findAll(text).map { Spot(it.range.first, it.value.length) }.toList()

    private fun flat(text: String): String = text.replace(RUN, "□").replace(Regex("\\s+"), " ")

    /** The text around one damaged place, with the place marked. */
    fun line(text: String, spot: Spot): String =
        flat(text.substring((spot.index - CONTEXT).coerceAtLeast(0), spot.index)) + MARK +
            flat(text.substring(spot.index + spot.length, (spot.index + spot.length + CONTEXT).coerceAtMost(text.length)))

    /** The request asking for the missing characters, one numbered line per damaged place. */
    fun messages(lines: List<String>): String = JSONArray()
        .put(JSONObject().put("role", "system").put("content", SYSTEM))
        .put(JSONObject().put("role", "user").put("content", lines.mapIndexed { index, line -> "${index + 1}. $line" }.joinToString("\n"))).toString()

    /** A one-character answer needs no thinking and no variety. */
    fun preset(): Preset = Preset(temperature = 0.0, maxOutput = 1200, reasoningEffort = "none", includeVeniceSystemPrompt = false)

    /** The model's answer as one entry per damaged place, or null when it is anything else. */
    fun parse(content: String, count: Int): List<String?>? {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val list = runCatching { JSONArray(content.substring(start, end + 1)) }.getOrNull() ?: return null
        if (list.length() != count) return null
        return (0 until count).map { index -> list.opt(index) as? String }
    }

    /** One character that can have been cut: not ASCII, not a blank, not a mark. */
    private fun oneLostCharacter(value: String?): Boolean {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length) != 1) return false
        val code = value.codePointAt(0)
        return code > 0x7F && code != LOST.code && !Character.isWhitespace(code) && !MARK.contains(value)
    }

    /** Puts accepted answers in place; a place whose answer is not one character stays as it came. */
    fun apply(text: String, spots: List<Spot>, answers: List<String?>?): Fixed {
        if (answers == null || answers.size != spots.size) return Fixed(text, 0)
        val result = StringBuilder(text)
        var repaired = 0
        for (index in spots.indices.reversed()) {
            val spot = spots[index]
            val answer = answers[index]
            if (spot.length > MAX_RUN || !oneLostCharacter(answer)) continue
            result.replace(spot.index, spot.index + spot.length, answer)
            repaired++
        }
        return Fixed(result.toString(), repaired)
    }

    /** An earlier reply without its marks, for what is sent to the model; the stored message keeps them. */
    fun strip(text: String): String = if (damaged(text)) text.replace(RUN, "") else text

    /** How many saved replies of [chat] are damaged, and in how many places. */
    fun count(chat: Chat): Pair<Int, Int> {
        var messages = 0
        var places = 0
        chat.messages.forEach { message ->
            if (message.role == "assistant" && damaged(message.content)) { messages++; places += spots(message.content).size }
        }
        return messages to places
    }

    /**
     * Summaries and notes are tied to the exact text of the messages they were made from. Filling
     * in a lost character changes that text but not what was said, so a summary or note that was
     * valid for [before] is tied to the same messages of [after]; one that was already out of date
     * stays so.
     */
    fun reseal(before: Chat, after: Chat): Chat {
        val changed = before.messages.zip(after.messages).filter { (old, new) -> old.id == new.id && old.content != new.content }.map { it.first.id }.toSet()
        if (changed.isEmpty() || before.messages.size != after.messages.size) return after
        val firstChanged = before.messages.indexOfFirst { it.id in changed }
        val newById = after.messages.associateBy { it.id }
        val oldById = before.messages.associateBy { it.id }
        val summaries = after.summaries.map { summary ->
            val end = before.messages.indexOfFirst { it.id == summary.throughMessageId }
            if (end < firstChanged || summary.sourceHash != ContextEngine.sourceHash(before.messages.take(end + 1))) summary
            else summary.copy(sourceHash = ContextEngine.sourceHash(after.messages.take(end + 1)))
        }
        val notes = after.knowledgeNotes.map { note ->
            if (note.sourceMessageIds.none { it in changed }) return@map note
            val old = note.sourceMessageIds.mapNotNull(oldById::get)
            if (old.size != note.sourceMessageIds.size || note.sourceHash != ContextEngine.sourceHash(old)) note
            else note.copy(sourceHash = ContextEngine.sourceHash(note.sourceMessageIds.map(newById::getValue)))
        }
        return after.copy(summaries = summaries, knowledgeNotes = notes)
    }
}
