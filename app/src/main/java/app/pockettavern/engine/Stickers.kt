package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.Sticker
import app.pockettavern.domain.StickerPack

/**
 * Per-chat sticker frequency. [recentLimit] stickers are allowed in the last
 * [StickerGate.WINDOW] finished replies; the app, not the model, enforces it.
 */
enum class StickerFrequency(val id: String, val label: String, val recentLimit: Int) {
    OFF("off", "关闭", 0),
    LOW("low", "少", 1),
    STANDARD("standard", "标准", 2),
    HIGH("high", "多", 4),
    EVERY("every", "每条", StickerGate.WINDOW);

    companion object {
        fun of(id: String?): StickerFrequency = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** The model writes one "[表情:名称]" line; the app shows the image and never sends the marker back. */
object StickerMarkup {
    private val marker = Regex("""[\[【](?:表情|sticker)[:：]\s*([^\]】\n]{1,40}?)\s*[\]】]""", RegexOption.IGNORE_CASE)
    /** A marker still being streamed at the very end of the text. */
    private val partial = Regex("""[\[【](?:表|表情|sticker|s[a-z]{0,6})(?:[:：][^\]】\n]{0,40})?$""", RegexOption.IGNORE_CASE)

    data class Parsed(val text: String, val names: List<String>)

    fun parse(content: String): Parsed {
        val names = marker.findAll(content).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        if (names.isEmpty() && !marker.containsMatchIn(content)) return Parsed(content, emptyList())
        val text = marker.replace(content, "").lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        return Parsed(text, names)
    }

    /** Text the model sees for an earlier reply: the marker is display-only. */
    fun strip(content: String): String = if (marker.containsMatchIn(content)) parse(content).text else content

    /** While streaming, hide a half-written marker instead of flashing raw brackets. */
    fun hidePartial(content: String): String = partial.replace(content, "").trimEnd()

    /**
     * Keeps at most one marker, only when this turn allowed one and it names a
     * known sticker, and puts it on its own last line in canonical form.
     */
    fun normalize(content: String, allowed: Boolean, stickers: List<Sticker>): String {
        val parsed = parse(content)
        if (parsed.names.isEmpty()) return content
        val chosen = if (allowed) parsed.names.firstNotNullOfOrNull { resolve(it, stickers) } else null
        return if (chosen == null) parsed.text else "${parsed.text}\n\n[表情:${chosen.key}]".trimStart()
    }

    /** Exact key first, then a case-insensitive key or display-name match. */
    fun resolve(name: String, stickers: List<Sticker>): Sticker? {
        val wanted = name.trim()
        return stickers.firstOrNull { it.key == wanted }
            ?: stickers.firstOrNull { it.key.equals(wanted, ignoreCase = true) }
            ?: stickers.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    }
}

/** Decides from finished replies whether this turn may, must or must not carry a sticker. */
object StickerGate {
    const val WINDOW = 5

    enum class Turn { NONE, MAY, MUST }

    fun stickers(data: AppData): List<Sticker> = data.stickerPacks.filter { it.enabled }.flatMap { it.stickers }

    /**
     * Sticker packs live on the PC writing server, so a chat gets them only while it talks to
     * one. [pcPacks] is what that PC holds, or null before it has been read. A character that
     * names its own packs gets only those, so 黑猫 never sends 大肥鱼's faces.
     */
    fun forChat(chat: Chat, providers: List<ProviderConfig>, pcPacks: List<StickerPack>?, characters: List<Character> = emptyList()): List<Sticker> {
        val kind = providers.firstOrNull { it.id == chat.providerId }?.kind.orEmpty()
        if (!kind.equals("writing-server", ignoreCase = true)) return emptyList()
        return packsFor(characters.firstOrNull { it.id == chat.characterId }, pcPacks.orEmpty()).flatMap { it.stickers }
    }

    /** The enabled packs a character uses: the ones it names when any of them exist, otherwise all. */
    fun packsFor(character: Character?, packs: List<StickerPack>): List<StickerPack> {
        val enabled = packs.filter { it.enabled }
        val own = character?.stickerPacks.orEmpty()
        return enabled.filter { it.name in own }.ifEmpty { enabled }
    }

    fun decide(chat: Chat, available: Boolean): Turn {
        val frequency = StickerFrequency.of(chat.stickerFrequency)
        if (frequency == StickerFrequency.OFF || !available) return Turn.NONE
        // Author corrections and rewrites sit outside the scene; no stickers there.
        val latestUser = chat.messages.lastOrNull { it.role == "user" && !it.contextExcluded }
        if (latestUser?.intent in setOf("correction", "rewrite")) return Turn.NONE
        if (frequency == StickerFrequency.EVERY) return Turn.MUST
        val recent = finishedReplies(chat).takeLast(WINDOW)
        val sent = recent.count { StickerMarkup.parse(it).names.isNotEmpty() }
        return if (sent < frequency.recentLimit) Turn.MAY else Turn.NONE
    }

    /** Keys of the stickers used in the last few replies, newest last, so the prompt can ask for variety. */
    fun recentKeys(chat: Chat): List<String> =
        finishedReplies(chat).takeLast(WINDOW).flatMap { StickerMarkup.parse(it).names }.distinct()

    private fun finishedReplies(chat: Chat): List<String> = chat.messages
        .filter { it.role == "assistant" && it.status == "complete" && !it.contextExcluded && it.content.isNotBlank() }
        .map { it.content }
}

/** Prompt text: a stable list (cacheable) and a short per-turn instruction. */
object StickerPrompt {
    private const val DETAILED = 80
    private const val MAX_LISTED = 240

    fun list(stickers: List<Sticker>): String {
        val shown = stickers.take(MAX_LISTED)
        // Past DETAILED the hints would cost too much prompt, so only the names are listed; they read like captions.
        val lines = if (shown.size > DETAILED) shown.chunked(20).joinToString("\n") { row -> row.joinToString("、") { it.key } }
        else shown.joinToString("\n") { sticker ->
            val hint = listOf(sticker.name.takeIf { it != sticker.key }.orEmpty(), sticker.description, sticker.tags.joinToString(" "))
                .filter { it.isNotBlank() }.joinToString("；").take(48)
            if (hint.isBlank()) "- ${sticker.key}" else "- ${sticker.key}：$hint"
        }
        val more = if (stickers.size > shown.size) "\n（另有 ${stickers.size - shown.size} 张未列出，只能用上面的名称）" else ""
        return "【表情包】本会话可以在回复正文之后另起一行写「[表情:名称]」发一张表情图，由 App 显示图片。" +
            "名称只能从下面列表里选；不要描述、解释或复述表情，同一条回复最多一张；严肃、悲伤或剧情关键时刻不要发。是否发由下方【本轮表情】决定。\n" +
            lines + more
    }

    fun turn(turn: StickerGate.Turn, recent: List<String>): String {
        val variety = if (recent.isEmpty()) "" else "最近用过：${recent.joinToString("、")}，尽量换一张。"
        return when (turn) {
            StickerGate.Turn.MUST -> "【本轮表情】这条回复末尾另起一行发一张最贴合语气的表情。$variety"
            StickerGate.Turn.MAY -> "【本轮表情】如果这条回复语气轻松、适合配图，可以在末尾另起一行发一张表情；不合适就不发。$variety"
            StickerGate.Turn.NONE -> "【本轮表情】这条回复不要发表情。"
        }
    }
}
