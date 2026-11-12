package app.pockettavern.ui

import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.engine.StickerMarkup
import app.pockettavern.engine.VideoBrowse
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What a row of the chat list says. Pure, so the wording is tested without a screen. */
internal object ChatListModel {
    const val PREVIEW_CHARS = 60
    private val whitespace = Regex("\\s+")

    /** Lines a person wrote or reads: no tool traffic, no browse instruction, no bare tool call. */
    private fun spoken(message: Message): Boolean = when (message.role) {
        "user" -> message.intent != VideoBrowse.INTENT
        "assistant" -> message.content.isNotBlank() || message.toolCallsJson == "[]"
        else -> false
    }

    /** The last thing said in [chat], on one line, without sticker markup. In a group it names the speaker. */
    fun preview(chat: Chat, speakerNames: List<String> = emptyList()): String {
        val last = chat.messages.lastOrNull(::spoken) ?: return "还没有消息"
        val plain = StickerMarkup.strip(last.content)
        val said = if (last.role == "assistant" && speakerNames.isNotEmpty()) app.pockettavern.domain.GroupChats.lastLine(plain, speakerNames) else plain
        val text = said.replace(whitespace, " ").trim()
        return when {
            last.status == "error" -> "回复出错：" + last.errorMessage.replace(whitespace, " ").trim().take(40).ifEmpty { "点开查看" }
            text.isNotEmpty() -> (if (last.role == "user") "你：" else "") + text.take(PREVIEW_CHARS)
            last.status == "stopped" -> "已停止"
            last.status == "streaming" -> "…"
            last.attachments.isNotEmpty() -> "[附件]"
            last.content.isNotBlank() -> "[表情]"
            else -> "还没有消息"
        }
    }

    /** Search covers the title, the character's name and what was said. */
    fun matches(chat: Chat, character: Character?, query: String): Boolean {
        val wanted = query.trim()
        if (wanted.isEmpty()) return true
        return chat.title.contains(wanted, ignoreCase = true) ||
            character?.name?.contains(wanted, ignoreCase = true) == true ||
            chat.messages.any { spoken(it) && it.content.contains(wanted, ignoreCase = true) }
    }

    /** 刚刚 (also for a chat newer than the list's clock), a time today, 昨天, a weekday within a week, then the date. */
    fun time(timestamp: Long, now: Long, zone: ZoneId): String {
        if (timestamp <= 0L) return ""
        val then = Instant.ofEpochMilli(timestamp).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone)
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), today.toLocalDate())
        return when {
            now - timestamp < 60_000 -> "刚刚"
            days <= 0L -> "%02d:%02d".format(then.hour, then.minute)
            days == 1L -> "昨天"
            days < 7L -> "周" + "一二三四五六日"[then.dayOfWeek.value - 1]
            then.year == today.year -> "%02d/%02d".format(then.monthValue, then.dayOfMonth)
            else -> "%d/%02d/%02d".format(then.year, then.monthValue, then.dayOfMonth)
        }
    }

    /** The character shown when a chat has no picture; never half of an emoji. */
    fun initial(chat: Chat, character: Character?): String {
        val name = (character?.name?.takeIf { it.isNotBlank() } ?: chat.title).trim()
        return if (name.isEmpty()) "聊" else String(java.lang.Character.toChars(name.codePointAt(0)))
    }

    /** A steady colour per chat for the letter avatar. */
    fun tintIndex(seed: String, count: Int): Int = Math.floorMod(seed.hashCode(), count)
}
