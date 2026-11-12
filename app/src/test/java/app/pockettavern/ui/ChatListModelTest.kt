package app.pockettavern.ui

import app.pockettavern.domain.Attachment
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.engine.VideoBrowse
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each row of the home list says: the last line, the time, and what search finds. */
class ChatListModelTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    private fun chat(vararg messages: Message, title: String = "黑猫") = Chat(id = "c", title = title, messages = messages.toList())

    @Test fun previewIsTheLastThingSaidOnOneLine() {
        assertEquals("还没有消息", ChatListModel.preview(chat()))
        assertEquals("你：今天看了什么番？", ChatListModel.preview(chat(Message(role = "user", content = "今天看了什么番？"))))
        assertEquals("哼，才不是 特意等你。", ChatListModel.preview(chat(
            Message(role = "user", content = "在吗"),
            Message(role = "assistant", content = "哼，才不是\n\n特意等你。"))))
        val long = "字".repeat(200)
        assertEquals(ChatListModel.PREVIEW_CHARS, ChatListModel.preview(chat(Message(role = "assistant", content = long))).length)
    }

    @Test fun previewSkipsToolTrafficAndTheBrowseInstruction() {
        val call = """[{"id":"t1","type":"function","function":{"name":"web_reach","arguments":"{}"}}]"""
        val messages = arrayOf(
            Message(role = "assistant", content = "刚刷到一个猫猫开会的视频。"),
            Message(role = "user", content = "（系统指令：去刷视频）", intent = VideoBrowse.INTENT),
            Message(role = "assistant", content = "", toolCallsJson = call),
            Message(role = "tool", content = "{\"ok\":true}", toolCallId = "t1"))
        assertEquals("the row never shows a tool result or the hidden instruction", "刚刷到一个猫猫开会的视频。", ChatListModel.preview(chat(*messages)))
    }

    @Test fun previewNamesWhatHappenedWhenThereIsNoText() {
        assertEquals("回复出错：上游返回 502", ChatListModel.preview(chat(Message(role = "assistant", content = "", status = "error", errorMessage = "上游返回 502"))))
        assertEquals("已停止", ChatListModel.preview(chat(Message(role = "assistant", content = "", status = "stopped"))))
        assertEquals("…", ChatListModel.preview(chat(Message(role = "assistant", content = "", status = "streaming"))))
        assertEquals("[附件]", ChatListModel.preview(chat(Message(role = "user", content = "", attachments = listOf(Attachment(name = "a.png", mime = "image/png", dataUrl = "x"))))))
    }

    @Test fun stickerMarkupIsNotShownAsText() {
        val withText = ChatListModel.preview(chat(Message(role = "assistant", content = "笑死我了 [sticker:laugh]")))
        assertFalse(withText, withText.contains("sticker"))
        assertTrue(withText.startsWith("笑死我了"))
        assertEquals("[表情]", ChatListModel.preview(chat(Message(role = "assistant", content = "[sticker:laugh]"))))
    }

    @Test fun timeReadsLikeAChatApp() {
        val now = at(2026, 11, 12, 21, 14)
        assertEquals("刚刚", ChatListModel.time(now - 20_000, now, zone))
        assertEquals("the list's clock ticks every 30 s, so a chat can be newer than it", "刚刚", ChatListModel.time(now + 25_000, now, zone))
        assertEquals("20:40", ChatListModel.time(at(2026, 11, 12, 20, 40), now, zone))
        assertEquals("昨天", ChatListModel.time(at(2026, 11, 11, 23, 59), now, zone))
        assertEquals("周一", ChatListModel.time(at(2026, 11, 9, 9, 0), now, zone))
        assertEquals("11/05", ChatListModel.time(at(2026, 11, 5, 9, 0), now, zone))
        assertEquals("2025/12/31", ChatListModel.time(at(2025, 12, 31, 9, 0), now, zone))
        assertEquals("", ChatListModel.time(0, now, zone))
    }

    @Test fun searchCoversTitleCharacterAndWhatWasSaid() {
        val talk = chat(Message(role = "user", content = "聊聊《白色相簿》"), Message(role = "tool", content = "隐藏的工具结果"), title = "深夜闲聊")
        val heimao = Character(id = "h", name = "黑猫")
        assertTrue(ChatListModel.matches(talk, heimao, ""))
        assertTrue(ChatListModel.matches(talk, heimao, " 深夜 "))
        assertTrue(ChatListModel.matches(talk, heimao, "黑猫"))
        assertTrue(ChatListModel.matches(talk, heimao, "白色相簿"))
        assertFalse("tool results are not part of the conversation the user searches", ChatListModel.matches(talk, heimao, "工具结果"))
        assertFalse(ChatListModel.matches(talk, null, "黑猫"))
    }

    @Test fun letterAvatarUsesTheCharacterNameAndNeverHalfAnEmoji() {
        assertEquals("黑", ChatListModel.initial(Chat(title = "深夜闲聊"), Character(name = "黑猫")))
        assertEquals("深", ChatListModel.initial(Chat(title = "深夜闲聊"), null))
        assertEquals("🐟", ChatListModel.initial(Chat(title = "🐟大肥鱼"), null))
        assertEquals("聊", ChatListModel.initial(Chat(title = "  "), null))
        assertTrue(ChatListModel.tintIndex("any-chat-id", 5) in 0..4)
        assertEquals(ChatListModel.tintIndex("same", 5), ChatListModel.tintIndex("same", 5))
    }
}
