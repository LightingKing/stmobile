package app.pockettavern.engine

import app.pockettavern.data.DataCodec
import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自己刷视频: when a chat may browse on its own, and what the model sees of it. */
class VideoBrowseTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(hour: Int, minute: Int = 0, day: Int = 11) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val pc = ProviderConfig(id = "pc", kind = "writing-server", baseUrl = "https://pt.example.com/v1",
        models = listOf(ModelInfo(id = "tools", type = "text", supportsTools = true), ModelInfo(id = "plain", type = "text")))
    private fun chat(perDay: Int, log: List<Long> = emptyList(), lastMessageAt: Long = at(1, day = 10)) = Chat(id = "c", providerId = "pc", modelId = "tools",
        browsePerDay = perDay, browseLog = log, messages = listOf(Message(role = "user", content = "早", createdAt = lastMessageAt)))

    @Test fun browsingFollowsTheDailySlotsAndStaysQuiet() {
        assertFalse("off", VideoBrowse.due(chat(0), at(12), zone))
        assertFalse("not at night", VideoBrowse.due(chat(3), at(8, 59), zone))
        assertFalse("not at night", VideoBrowse.due(chat(3), at(23, 5), zone))
        // Twice a day: 9:00–16:00 and 16:00–23:00, each opening half an hour in.
        assertFalse(VideoBrowse.due(chat(2), at(9, 10), zone))
        assertTrue(VideoBrowse.due(chat(2), at(9, 40), zone))
        val morning = at(9, 40)
        assertFalse("one per slot", VideoBrowse.due(chat(2, listOf(morning)), at(15, 0), zone))
        assertTrue(VideoBrowse.due(chat(2, listOf(morning)), at(16, 40), zone))
        assertFalse("daily cap", VideoBrowse.due(chat(2, listOf(morning, at(16, 40))), at(22, 0), zone))
        assertTrue("yesterday's runs do not count", VideoBrowse.due(chat(2, listOf(at(16, 40, day = 10), at(20, 0, day = 10))), at(9, 40), zone))
        assertFalse("two hours apart", VideoBrowse.due(chat(3, listOf(at(13, 50))), at(14, 30), zone))
        assertFalse("not while the user is chatting", VideoBrowse.due(chat(2, lastMessageAt = at(9, 30)), at(9, 40), zone))
        val browseTurnOnly = chat(2).let { it.copy(messages = it.messages + Message(role = "user", content = "x", intent = VideoBrowse.INTENT, createdAt = at(9, 35))) }
        assertTrue("its own browse turn is not the user chatting", VideoBrowse.due(browseTurnOnly, at(9, 40), zone))
    }

    @Test fun onlyPcChatsWithToolModelsCanBrowse() {
        assertTrue(VideoBrowse.eligible(chat(1), listOf(pc)))
        assertFalse(VideoBrowse.eligible(chat(1).copy(modelId = "plain"), listOf(pc)))
        assertFalse(VideoBrowse.eligible(chat(1), listOf(pc.copy(kind = "venice"))))
        assertFalse("the card assistant does not browse", VideoBrowse.eligible(chat(1).copy(task = CharacterCardTool.TASK), listOf(pc)))
    }

    @Test fun theInstructionStaysInCharacterAndPastTurnsShrink() {
        val text = VideoBrowse.instruction(at(10, 5), zone)
        assertTrue(text.contains("10:05") && text.contains("subtitle") && text.contains("附上视频链接"))
        assertTrue("roleplay keeps the character", text.contains("以这个角色的身份") && text.contains("没有手机和网络") && text.contains("多人剧本"))
        val first = Message(role = "user", content = VideoBrowse.instruction(at(10), zone), intent = VideoBrowse.INTENT)
        val share = Message(role = "assistant", content = "刚刷到一个猫猫视频")
        val again = Message(role = "user", content = VideoBrowse.instruction(at(17), zone), intent = VideoBrowse.INTENT)
        val c = chat(2).copy(messages = listOf(first, share, again))
        val sent = JSONArray(ContextEngine.build(AppData(providers = listOf(pc), chats = listOf(c)), c, emptyList(), onPc = true).messagesJson)
        val contents = (0 until sent.length()).map { sent.getJSONObject(it).optString("content") }
        assertTrue("the earlier browse turn is a short note", contents.contains(VideoBrowse.PAST_TURN))
        assertEquals("only the current turn carries the full instruction", 1, contents.count { it.startsWith("【自己刷视频】") })
        assertTrue("the tool is there to browse with", ContextEngine.build(AppData(providers = listOf(pc), chats = listOf(c)), c, emptyList(), onPc = true).toolDefinitions.contains(ReachTool.NAME))
    }

    @Test fun oldSavedChatsLoadWithBrowsingOff() {
        val data = AppData(chats = listOf(chat(2, listOf(at(10))).copy(task = CharacterCardTool.TASK)))
        val decoded = DataCodec.decode(DataCodec.encode(data)).chats.single()
        assertEquals(2, decoded.browsePerDay)
        assertEquals(listOf(at(10)), decoded.browseLog)
        assertEquals(CharacterCardTool.TASK, decoded.task)
        val old = DataCodec.encode(data).replace(Regex(",\"browsePerDay\":2,\"browseLog\":\\[[0-9]+],\"task\":\"character_card\""), "")
        assertFalse(old.contains("browsePerDay"))
        val legacy = DataCodec.decode(old).chats.single()
        assertEquals(0, legacy.browsePerDay)
        assertEquals(emptyList<Long>(), legacy.browseLog)
        assertEquals("", legacy.task)
        val tampered = DataCodec.encode(data.copy(chats = listOf(chat(9).copy(task = "something-else"))))
        DataCodec.decode(tampered).chats.single().let { assertEquals(VideoBrowse.MAX_PER_DAY, it.browsePerDay); assertEquals("", it.task) }
    }
}
