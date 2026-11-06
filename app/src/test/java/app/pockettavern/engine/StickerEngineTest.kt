package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.Sticker
import app.pockettavern.domain.StickerPack
import app.pockettavern.domain.WorldEntry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerEngineTest {
    private val stickers = listOf(
        Sticker(key = "smile", name = "smile", description = "微笑", file = "p/001.png"),
        Sticker(key = "干饭", name = "干饭", tags = listOf("饭点"), file = "p/002.png")
    )

    private fun reply(text: String) = Message(role = "assistant", content = text, status = "complete")

    @Test fun markersAreParsedInBothBracketStylesAndStrippedFromText() {
        val parsed = StickerMarkup.parse("好呀，开饭！\n\n[表情:干饭]")
        assertEquals("好呀，开饭！", parsed.text)
        assertEquals(listOf("干饭"), parsed.names)
        assertEquals(listOf("smile"), StickerMarkup.parse("嗯【表情：smile】").names)
        assertEquals("没有表情", StickerMarkup.strip("没有表情"))
        assertEquals("好的", StickerMarkup.hidePartial("好的\n[表情:sm"))
        assertEquals("好的", StickerMarkup.hidePartial("好的\n[表"))
    }

    @Test fun normalizeKeepsOneKnownStickerOnlyWhenTheTurnAllowsIt() {
        assertEquals("哈哈\n\n[表情:smile]", StickerMarkup.normalize("哈哈 [表情:不存在] [表情:SMILE] [表情:干饭]", allowed = true, stickers = stickers))
        assertEquals("哈哈", StickerMarkup.normalize("哈哈\n[表情:smile]", allowed = false, stickers = stickers))
        assertEquals("哈哈", StickerMarkup.normalize("哈哈\n[表情:不存在]", allowed = true, stickers = stickers))
        assertEquals("[表情:干饭]", StickerMarkup.normalize("[表情:干饭]", allowed = true, stickers = stickers))
        assertEquals("纯文字", StickerMarkup.normalize("纯文字", allowed = true, stickers = stickers))
    }

    @Test fun gateFollowsFrequencyRecentRepliesAndAuthorTurns() {
        fun chat(frequency: String, replies: List<String>, intent: String = "dialogue") = Chat(id = "c", stickerFrequency = frequency,
            messages = replies.flatMap { listOf(Message(role = "user", content = "说"), reply(it)) } + Message(role = "user", content = "继续", intent = intent))
        assertEquals(StickerGate.Turn.NONE, StickerGate.decide(chat("off", emptyList()), available = true))
        assertEquals(StickerGate.Turn.NONE, StickerGate.decide(chat("standard", emptyList()), available = false))
        assertEquals(StickerGate.Turn.MUST, StickerGate.decide(chat("every", emptyList()), available = true))
        assertEquals(StickerGate.Turn.MAY, StickerGate.decide(chat("standard", listOf("a\n[表情:smile]", "b")), available = true))
        assertEquals(StickerGate.Turn.NONE, StickerGate.decide(chat("standard", listOf("a\n[表情:smile]", "b\n[表情:干饭]")), available = true))
        assertEquals(StickerGate.Turn.NONE, StickerGate.decide(chat("low", listOf("a\n[表情:smile]", "b", "c", "d")), available = true))
        // Only the last five replies count.
        assertEquals(StickerGate.Turn.MAY, StickerGate.decide(chat("low", listOf("a\n[表情:smile]", "b", "c", "d", "e", "f")), available = true))
        assertEquals(StickerGate.Turn.NONE, StickerGate.decide(chat("every", emptyList(), intent = "correction"), available = true))
        assertEquals(listOf("smile", "干饭"), StickerGate.recentKeys(chat("high", listOf("a\n[表情:smile]", "b\n[表情:干饭]"))))
    }

    @Test fun onlyChatsOnThePcWritingServerGetThePcStickers() {
        val pc = ProviderConfig(id = "pc", kind = "writing-server", baseUrl = "http://192.168.1.22:8791/v1")
        val venice = ProviderConfig(id = "venice")
        val packs = listOf(StickerPack(id = "a", stickers = stickers), StickerPack(id = "b", enabled = false, stickers = listOf(Sticker(key = "off", file = "b/001.png"))))
        assertEquals(listOf("smile", "干饭"), StickerGate.forChat(Chat(id = "c", providerId = "pc"), listOf(pc, venice), packs).map { it.key })
        assertTrue(StickerGate.forChat(Chat(id = "c", providerId = "venice"), listOf(pc, venice), packs).isEmpty())
        assertTrue("not read from the PC yet", StickerGate.forChat(Chat(id = "c", providerId = "pc"), listOf(pc), null).isEmpty())

        // The app passes the chat's stickers; phone-side packs alone no longer reach the prompt.
        val chat = Chat(id = "c", providerId = "venice", stickerFrequency = "every", messages = listOf(Message(role = "user", content = "早")))
        val data = AppData(chats = listOf(chat), stickerPacks = listOf(StickerPack(id = "p", stickers = stickers)))
        val messages = JSONArray(ContextEngine.build(data, chat, emptyList()).messagesJson).toString()
        assertFalse(messages.contains("【表情包】"))
        assertTrue(JSONArray(ContextEngine.build(data, chat, stickers).messagesJson).toString().contains("【表情包】"))
    }

    @Test fun largeCollectionsAreListedByNameSoEveryStickerIsVisible() {
        fun many(count: Int) = (1..count).map { Sticker(key = "表情$it", description = "说明$it", file = "p/$it.png") }
        val listedKeys = { text: String -> text.lines().drop(1).flatMap { it.split("、") }.map { it.removePrefix("- ").substringBefore("：") } }

        val detailed = StickerPrompt.list(many(80))
        assertTrue(detailed.contains("- 表情80：说明80"))

        val compact = StickerPrompt.list(many(229))
        assertEquals(many(229).map { it.key }, listedKeys(compact))
        assertFalse("hints are dropped once the list is long", compact.contains("说明1"))
        assertFalse(compact.contains("未列出"))

        val capped = StickerPrompt.list(many(250))
        assertEquals(240, listedKeys(capped.substringBefore("\n（另有")).size)
        assertTrue(capped.endsWith("（另有 10 张未列出，只能用上面的名称）"))
    }

    @Test fun contextSendsTheListOnlyWhenOnAndNeverEchoesPastMarkers() {
        val history = listOf(Message(role = "user", content = "早"), reply("早呀\n\n[表情:smile]"), Message(role = "user", content = "吃了吗"))
        val pack = StickerPack(id = "p", name = "包", stickers = stickers)
        fun build(frequency: String): List<String> {
            val chat = Chat(id = "c", stickerFrequency = frequency, messages = history)
            val data = AppData(chats = listOf(chat), stickerPacks = listOf(pack), worldEntries = listOf(WorldEntry(name = "地点", content = "食堂", always = true)))
            val messages = JSONArray(ContextEngine.build(data, chat).messagesJson)
            return (0 until messages.length()).map { messages.getJSONObject(it).optString("content") }
        }
        val off = build("off")
        assertTrue(off.none { it.startsWith("【表情包】") || it.startsWith("【本轮表情】") })
        assertTrue("the past marker is display-only", off.contains("早呀"))

        val on = build("standard")
        val list = on.single { it.startsWith("【表情包】") }
        assertTrue(list.contains("- smile：微笑") && list.contains("- 干饭：饭点"))
        val turn = on.single { it.startsWith("【本轮表情】") }
        assertTrue(turn.contains("可以在末尾") && turn.contains("最近用过：smile"))
        assertTrue(on.indexOf(list) < on.indexOfFirst { it.startsWith("【世界书资料】") })
        assertTrue(on.indexOf(turn) > on.indexOfFirst { it.startsWith("【世界书资料】") })
        assertFalse(on.any { it.contains("[表情:smile]") })

        val every = build("every")
        assertTrue(every.single { it.startsWith("【本轮表情】") }.contains("发一张最贴合"))
        val disabled = AppData(chats = listOf(Chat(id = "c", stickerFrequency = "every", messages = history)), stickerPacks = listOf(pack.copy(enabled = false)))
        assertTrue(JSONArray(ContextEngine.build(disabled, disabled.chats.single()).messagesJson).toString().contains("【表情包】").not())
    }
}
