package app.pockettavern.provider

import app.pockettavern.data.DataCodec
import app.pockettavern.domain.AppData
import app.pockettavern.domain.Character
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.WorldItem
import app.pockettavern.domain.Worlds
import app.pockettavern.engine.WorldPack
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 0.4.1 (W3): residents, diary and 要记住的事 edited from the phone; new-message notifications. */
class PcLinkWorldEditsTest {
    private lateinit var server: MockWebServer
    private lateinit var pc: ProviderConfig
    private val client = PcLinkClient(allowInsecureHttpForTests = true)
    private val id = "wcfad81922177"

    @Before fun start() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        pc = ProviderConfig(id = "pc", name = "电脑", kind = "writing-server", baseUrl = server.url("/v1").newBuilder().host("127.0.0.1").build().toString())
    }

    @After fun stop() { server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val detail = """{"id":"$id","name":"梧桐里 302","residents":[{"id":"r1","name":"林晚","notes":"新室友喜欢团子"},{"id":"r5","name":"王阿姨"}],
        "paused":true,"hold":"paused","usage":{},"totals":{},"settings":{},"seq":40,
        "definition":{"groupName":"302 合租群","facts":["新室友叫阿遥","",  "团子对芒果过敏"],
        "residents":[{"id":"r1","name":"林晚","aliases":["晚晚"],"card":"插画师"},{"id":"r5","name":"王阿姨","aliases":["房东"],"card":"一楼房东"}]}}"""

    @Test fun addingAResidentPostsItAndTheDetailCarriesAliasesAndFacts() {
        server.enqueue(json(detail, 201))
        val world = runBlocking { client.addWorldResident(pc, "token", id, JSONObject().put("name", "王阿姨").put("card", "一楼房东")) }
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/pocket/worlds/$id/residents", request.path)
        assertEquals("王阿姨", JSONObject(request.body.readUtf8()).getString("name"))
        assertEquals(listOf("晚晚"), world.summary.residents[0].aliases)
        assertEquals("一楼房东", world.summary.residents[1].card)
        assertEquals(listOf("新室友叫阿遥", "团子对芒果过敏"), world.facts)
        // A later summary keeps both cards and aliases.
        val refreshed = Worlds.refreshed(world, WorldJson.summary(JSONObject(detail).put("seq", 41)))
        assertEquals(listOf("房东"), refreshed.summary.residents[1].aliases)
    }

    @Test fun editsGoToTheirOwnPaths() {
        server.enqueue(json(detail))
        runBlocking { client.updateWorldResident(pc, "token", id, "r1", JSONObject().put("notes", "答应周末一起做饭")) }
        server.takeRequest().let { assertEquals("PATCH", it.method); assertEquals("/v1/pocket/worlds/$id/residents/r1", it.path); assertEquals("答应周末一起做饭", JSONObject(it.body.readUtf8()).getString("notes")) }
        server.enqueue(json(detail))
        runBlocking { client.removeWorldResident(pc, "token", id, "r5") }
        server.takeRequest().let { assertEquals("DELETE", it.method); assertEquals("/v1/pocket/worlds/$id/residents/r5", it.path) }
        server.enqueue(json("""{"entries":[{"day":"2026-11-17","text":"改过的日记"}],"facts":[],"residents":[{"id":"r1","name":"林晚","notes":"记得"}]}"""))
        val diary = runBlocking { client.editWorldDiary(pc, "token", id, "2026-11-17", "改过的日记") }
        server.takeRequest().let { assertEquals("PATCH", it.method); assertEquals("/v1/pocket/worlds/$id/diary/2026-11-17", it.path); assertEquals("改过的日记", JSONObject(it.body.readUtf8()).getString("text")) }
        assertEquals("改过的日记", diary.entries.single().text)
        assertEquals(mapOf("林晚" to "记得"), diary.memories)
        server.enqueue(json(detail))
        runBlocking { client.saveWorldFacts(pc, "token", id, listOf("团子对芒果过敏")) }
        server.takeRequest().let { assertEquals("PATCH", it.method); assertEquals("/v1/pocket/worlds/$id", it.path); assertEquals("团子对芒果过敏", JSONObject(it.body.readUtf8()).getJSONArray("facts").getString(0)) }
    }

    @Test fun malformedResidentIdsAndDaysNeverLeaveThePhone() {
        for (call in listOf<suspend () -> Unit>(
            { client.updateWorldResident(pc, "token", id, "../r1", JSONObject()) },
            { client.removeWorldResident(pc, "token", id, "lin") },
            { client.editWorldDiary(pc, "token", id, "today", "x") },
        )) {
            try { runBlocking { call() }; fail("expected PcLinkException") } catch (error: PcLinkException) { assertTrue(error.message!!.contains("无效")) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun onlyResidentsPrivateMessagesAfterTheLastLookAreNotified() {
        val items = listOf(
            WorldItem(seq = 10, kind = "message", channel = "dm:r1", from = "r1", name = "林晚", text = "旧的"),
            WorldItem(seq = 11, kind = "message", channel = "group", from = "r3", name = "苏糖", text = "群里的"),
            WorldItem(seq = 12, kind = "message", channel = "dm:r1", from = "user", name = "阿遥", text = "我说的"),
            WorldItem(seq = 13, kind = "event", text = "团子打翻水碗"),
            WorldItem(seq = 14, kind = "message", channel = "dm:r3", from = "r3", name = "苏糖", text = "晚上吃面吗"),
            WorldItem(seq = 15, kind = "message", channel = "dm:r1", from = "r1", name = "林晚", text = "谢谢你"),
        )
        val dms = Worlds.newDms(items, afterSeq = 10)
        assertEquals(listOf(14L, 15L), dms.map { it.seq })
        assertEquals("梧桐里 302 · 苏糖、林晚发来 2 条私信", Worlds.noticeTitle("梧桐里 302", dms))
        assertEquals("梧桐里 302 · 林晚", Worlds.noticeTitle("梧桐里 302", dms.takeLast(1)))
    }

    @Test fun aCharacterCardIsCopiedInAsTheResidentsOwnText() {
        val card = Character(name = "黑猫", description = "二十岁的大学生", personality = "毒舌、中二", examples = "<START>\n{{char}}: 哼。", systemPrompt = "")
        val resident = WorldPack.residentFromCharacter(card)
        assertEquals("黑猫", resident.getString("name"))
        val text = resident.getString("card")
        assertTrue(text.startsWith("【简介】\n二十岁的大学生"))
        assertTrue(text.contains("【性格】\n毒舌、中二"))
        assertTrue(text.contains("【说话示例】"))
        assertFalse(text.contains("【补充规则】"))
        assertEquals("无名", WorldPack.residentFromCharacter(Character(name = "无名")).getString("card"))
    }

    @Test fun olderDataKeepsWorldNotificationsOn() {
        val saved = JSONObject(DataCodec.encode(AppData()))
        saved.getJSONObject("settings").remove("worldNotificationsOff")
        assertFalse(DataCodec.decode(saved.toString()).settings.worldNotificationsOff)
    }
}
