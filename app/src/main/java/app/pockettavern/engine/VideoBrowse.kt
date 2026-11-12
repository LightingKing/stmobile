package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.ProviderConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 自己刷视频: a few times a day the character browses Bilibili on its own through web_reach and
 * sends the user one message about a video it liked. The turn that starts it is a user message
 * with [INTENT]; the chat shows it as a small marker, and the model sees the full instruction only
 * while it is the current turn.
 */
object VideoBrowse {
    const val INTENT = "browse"
    const val MAX_PER_DAY = 3
    const val START_HOUR = 9
    const val END_HOUR = 23
    private const val MIN_GAP_MS = 2 * 60 * 60 * 1000L
    private const val QUIET_AFTER_CHAT_MS = 30 * 60 * 1000L
    private const val SLOT_OFFSET_MS = 30 * 60 * 1000L

    /** A chat on the PC writing server whose model can call tools. */
    fun eligible(chat: Chat, providers: List<ProviderConfig>): Boolean {
        val provider = providers.firstOrNull { it.id == chat.providerId } ?: return false
        return chat.task.isBlank() && chat.group == null && provider.kind.equals("writing-server", ignoreCase = true) && provider.models.firstOrNull { it.id == chat.modelId }?.supportsTools == true
    }

    /**
     * The day's active hours are split into [Chat.browsePerDay] slots; each slot allows one browse,
     * at least two hours apart, never at night and never while the user has been chatting in the
     * last half hour.
     */
    fun due(chat: Chat, now: Long, zone: ZoneId): Boolean {
        val perDay = chat.browsePerDay.coerceIn(0, MAX_PER_DAY)
        if (perDay == 0) return false
        val local = Instant.ofEpochMilli(now).atZone(zone)
        if (local.hour < START_HOUR || local.hour >= END_HOUR) return false
        val dayStart = local.toLocalDate().atTime(START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        val runsToday = chat.browseLog.count { it >= dayStart && it <= now }
        if (runsToday >= perDay) return false
        val slot = (END_HOUR - START_HOUR) * 60 * 60 * 1000L / perDay
        if (now < dayStart + runsToday * slot + SLOT_OFFSET_MS) return false
        if (chat.browseLog.any { now - it in 0 until MIN_GAP_MS }) return false
        val lastChat = chat.messages.lastOrNull { it.intent != INTENT && it.role in setOf("user", "assistant") }?.createdAt ?: 0
        return now - lastChat >= QUIET_AFTER_CHAT_MS
    }

    fun instruction(now: Long, zone: ZoneId): String {
        val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(now).atZone(zone))
        return "【自己刷视频】现在是 $time，用户没有发消息。这是你自己的空闲时间：按你的人设和兴趣，用 web_reach 去 B 站刷一会儿视频，" +
            "可以看 hot 热门，也可以 search 你感兴趣的话题。挑一个你最想分享的，用 subtitle 读它的字幕了解内容（读不到就看标题和简介）。" +
            "然后像主动来找用户聊天一样，用你的口吻发一条消息：说说刷到了什么、你的看法或感受，末尾附上视频链接。" +
            "只发这一条，简短自然；不要提到系统、指令或工具，不要列清单。工具出错或没刷到合适的，就简单说一句刚刷了会儿没什么意思。" +
            "如果你在扮演角色卡里的角色，就以这个角色的身份和说话方式来分享；角色的世界里没有手机和网络（古代、奇幻等）时，不要在剧情里刷视频，" +
            "改用括号写一小段戏外的话，像角色本人在幕后跟用户分享。如果你在主持多人剧本，就挑一个最合适的角色来分享，不推进剧情。"
    }

    /** What the model sees for a past browse turn: the long instruction is no longer needed. */
    const val PAST_TURN = "【此前你自己刷了会儿视频，主动给用户发了一条分享；这不是用户的发言】"
}
