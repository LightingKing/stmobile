package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.ChatTask
import app.pockettavern.domain.ChatTasks
import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.Summary
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.7: replies sometimes arrive with one character replaced by two or three U+FFFD marks. The
 * model is asked which character belongs there; saved replies can be repaired without their
 * summaries going out of date.
 */
class DamagedTextTest {
    private val x = "\uFFFD"
    private val broken = "家里并不宽裕，父母都$x$x$x，她放学后要去打工。\n过了好$x${x}会儿才斜眼瞥过来。"
    private val whole = "家里并不宽裕，父母都忙，她放学后要去打工。\n过了好一会儿才斜眼瞥过来。"

    @Test fun damagedPlacesAreFoundAndShownWithTheirSurroundings() {
        val spots = DamagedText.spots(broken)
        assertEquals(listOf(3, 2), spots.map { it.length })
        assertTrue(DamagedText.spots("没有损坏").isEmpty())
        assertFalse(DamagedText.damaged("没有损坏"))
        assertEquals("家里并不宽裕，父母都⟦?⟧，她放学后要去打工。 过了好□会儿才斜眼瞥过来。", DamagedText.line(broken, spots[0]))
        val request = JSONArray(DamagedText.messages(spots.map { DamagedText.line(broken, it) }))
        assertEquals(DamagedText.SYSTEM, request.getJSONObject(0).getString("content"))
        val user = request.getJSONObject(1).getString("content")
        assertTrue(user.startsWith("1. 家里并不宽裕") && user.contains("\n2. ") && !user.contains(x))
        val long = "前".repeat(200) + x + x + "后".repeat(200)
        assertEquals(40 + "⟦?⟧".length + 40, DamagedText.line(long, DamagedText.spots(long).single()).length)
        assertEquals("none", DamagedText.preset().reasoningEffort)
        assertEquals(0.0, DamagedText.preset().temperature, 0.0)
    }

    @Test fun anAnswerIsUsedOnlyWhenItIsOneLostCharacter() {
        val spots = DamagedText.spots(broken)
        assertEquals(listOf("忙", "一"), DamagedText.parse("好的：\n```json\n[\"忙\",\"一\"]\n```", 2))
        assertNull("one answer short", DamagedText.parse("[\"忙\"]", 2))
        assertNull(DamagedText.parse("我不知道", 2))
        assertEquals(listOf(null, "一"), DamagedText.parse("[7,\"一\"]", 2))
        assertEquals(DamagedText.Fixed(whole, 2), DamagedText.apply(broken, spots, listOf("忙", "一")))
        for (bad in listOf("忙碌", "a", "", " ", x, null, "\n")) {
            val fixed = DamagedText.apply(broken, spots, listOf(bad, "一"))
            assertEquals(bad.toString(), 1, fixed.repaired)
            assertTrue(fixed.text.contains("父母都$x$x$x，") && fixed.text.contains("好一会儿"))
        }
        assertEquals(broken, DamagedText.apply(broken, spots, null).text)
        assertEquals(0, DamagedText.apply(broken, spots, listOf("忙")).repaired)
        val emoji = "开心$x$x$x$x！"
        assertEquals("a four-byte character leaves up to four marks", "开心🫠！", DamagedText.apply(emoji, DamagedText.spots(emoji), listOf("🫠")).text)
        val wide = "坏了" + x.repeat(5) + "一片"
        assertEquals("a longer run is not one cut character", 0, DamagedText.apply(wide, DamagedText.spots(wide), listOf("字")).repaired)
    }

    @Test fun earlierRepliesGoBackToTheModelWithoutTheMarksAndStaySavedAsTheyWere() {
        val provider = ProviderConfig(id = "p", models = listOf(ModelInfo(id = "m", contextTokens = 200_000)))
        val messages = listOf(Message(content = "我发的$x"), Message(role = "assistant", content = broken), Message(id = "now", content = "继续写"))
        val chat = Chat(id = "c", providerId = "p", modelId = "m", messages = messages)
        val sent = JSONArray(ContextEngine.build(AppData(providers = listOf(provider), chats = listOf(chat)), chat).messagesJson)
        val byRole = (0 until sent.length()).map(sent::getJSONObject)
        assertEquals(DamagedText.strip(broken), byRole.first { it.getString("role") == "assistant" }.getString("content"))
        assertFalse(byRole.first { it.getString("role") == "assistant" }.getString("content").contains(x))
        assertEquals("what the user typed is sent as typed", "我发的$x", byRole.first { it.getString("role") == "user" }.getString("content"))
        assertEquals(broken, chat.messages[1].content)
        assertEquals(1 to 2, DamagedText.count(chat))
        assertEquals(0 to 0, DamagedText.count(chat.copy(messages = messages.take(1))))
    }

    @Test fun fillingInACharacterKeepsValidSummariesAndNotesValid() {
        val m1 = Message(id = "m1", content = "开头")
        val m2 = Message(id = "m2", role = "assistant", content = "完好的回复")
        val m3 = Message(id = "m3", content = "继续")
        val m4 = Message(id = "m4", role = "assistant", content = broken)
        val before = Chat(id = "c", messages = listOf(m1, m2, m3, m4),
            summaries = listOf(
                Summary(id = "early", text = "前两条", throughMessageId = "m2", sourceHash = ContextEngine.sourceHash(listOf(m1, m2))),
                Summary(id = "all", text = "全部", throughMessageId = "m4", sourceHash = ContextEngine.sourceHash(listOf(m1, m2, m3, m4))),
                Summary(id = "stale", text = "早就过期", throughMessageId = "m4", sourceHash = "not-the-hash")),
            knowledgeNotes = listOf(
                KnowledgeNote(id = "about-m4", text = "她要打工", sourceMessageIds = listOf("m3", "m4"), sourceHash = ContextEngine.sourceHash(listOf(m3, m4))),
                KnowledgeNote(id = "about-m2", text = "别的", sourceMessageIds = listOf("m2"), sourceHash = ContextEngine.sourceHash(listOf(m2))),
                KnowledgeNote(id = "stale-note", text = "过期", sourceMessageIds = listOf("m4"), sourceHash = "old")))
        val fixedMessages = listOf(m1, m2, m3, m4.copy(content = whole))
        val after = DamagedText.reseal(before, before.copy(messages = fixedMessages))
        assertEquals(whole, after.messages[3].content)
        assertEquals("a summary that ends before the change is untouched", before.summaries[0], after.summaries[0])
        assertEquals(ContextEngine.sourceHash(fixedMessages), after.summaries[1].sourceHash)
        assertEquals("a summary that was out of date stays out of date", "not-the-hash", after.summaries[2].sourceHash)
        assertEquals(ContextEngine.sourceHash(listOf(m3, fixedMessages[3])), after.knowledgeNotes[0].sourceHash)
        assertEquals(before.knowledgeNotes[1], after.knowledgeNotes[1])
        assertEquals("old", after.knowledgeNotes[2].sourceHash)
        assertEquals("the texts themselves are kept", before.summaries.map { it.text }, after.summaries.map { it.text })
        // Nothing changed, nothing rehashed.
        assertEquals(before, DamagedText.reseal(before, before))
    }

    @Test fun repairingSavedRepliesIsUpkeepWithItsOwnLabel() {
        assertEquals("正在修复乱码…", ChatTasks.label(ChatTask(ChatTask.REPAIR)))
        assertFalse(ChatTask(ChatTask.REPAIR).isReply)
        assertTrue(ChatTasks.refusal(mapOf("a" to ChatTask(ChatTask.REPAIR)), "a", ChatTask.REPLY)!!.contains("正在修复乱码"))
    }
}
