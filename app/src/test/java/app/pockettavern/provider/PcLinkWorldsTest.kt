package app.pockettavern.provider

import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.WorldItem
import app.pockettavern.domain.WorldSettings
import app.pockettavern.domain.Worlds
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 0.4.0: the phone side of 小世界 (PC writing server 0.13.0). JSON shaped like the real server's. */
class PcLinkWorldsTest {
    private lateinit var server: MockWebServer
    private lateinit var pc: ProviderConfig
    private val client = PcLinkClient(allowInsecureHttpForTests = true)

    @Before fun start() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        pc = ProviderConfig(id = "pc", name = "电脑", kind = "writing-server", baseUrl = server.url("/v1").newBuilder().host("127.0.0.1").build().toString())
    }

    @After fun stop() { server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val summary = """{"id":"wcfad81922177","name":"梧桐里 302","summary":"四个合租的年轻人","residents":[{"id":"r1","name":"林晚","location":"卧室","activity":"睡觉","mood":"困","plan":"中午起来画稿"},{"id":"r3","name":"苏糖","location":"咖啡店"}],
        "paused":false,"hold":null,"busy":false,"world_time":1794886486447,"world_time_label":"2026-11-17 周二 12:00","next_tick_at":1794889379262,"interval_minutes":30,
        "usage":{"day":"2026-11-17","ticks":2,"calls":4,"prompt_tokens":9906,"completion_tokens":4344},"totals":{"ticks":2,"calls":4,"prompt_tokens":9906,"completion_tokens":4344},
        "settings":{"tickMinutes":30,"quietStart":"01:00","quietEnd":"08:00","dailyTicks":4,"dailyCalls":8,"slowWhenAway":true},"model":"pocket-direct","seq":34,"unread":9,
        "last_message":{"seq":34,"kind":"message","at":1794887602055,"worldAt":1794886486447,"channel":"dm:r3","from":"r3","name":"苏糖","text":"就吃碗面","tick":"t2","time":"11-17 11:34"},
        "last_error":null,"created_at":1794885000000}"""

    @Test fun theWorldListIsReadWithTheToken() {
        server.enqueue(json("""{"object":"list","data":[$summary]}"""))
        val worlds = runBlocking { client.worlds(pc, "token") }
        val request = server.takeRequest()
        assertEquals("/v1/pocket/worlds", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        val world = worlds.single()
        assertEquals("梧桐里 302", world.name)
        assertEquals("", world.hold)
        assertFalse(world.paused)
        assertEquals(9, world.unread)
        assertEquals(4, world.usage.calls)
        assertEquals(14_250L, world.usage.tokens)
        assertEquals(8, world.settings.dailyCalls)
        assertEquals(1794889379262L, world.nextTickAt)
        assertEquals("苏糖（私信）：就吃碗面", Worlds.preview(world.lastMessage))
        assertEquals(listOf("林晚", "苏糖"), world.residents.map { it.name })
    }

    @Test fun oneWorldCarriesItsResidentsCardsAndGroupName() {
        val detail = JSONObject(summary).put("definition", JSONObject("""{"groupName":"302 合租群","userName":"新室友","userRole":"你是新室友","setting":"上海老公房",
            "residents":[{"id":"r1","name":"林晚","card":"26 岁，自由插画师"},{"id":"r3","name":"苏糖","card":"23 岁，咖啡店店员"}]}"""))
        server.enqueue(json(detail.toString()))
        val world = runBlocking { client.world(pc, "token", "wcfad81922177") }
        assertEquals("/v1/pocket/worlds/wcfad81922177", server.takeRequest().path)
        assertEquals("302 合租群", world.groupName)
        assertEquals("新室友", world.userName)
        assertEquals("26 岁，自由插画师", world.summary.residents.first().card)
        // A later summary from the timeline keeps the cards.
        val later = WorldJson.summary(JSONObject(summary).put("world_time_label", "2026-11-17 周二 12:30"))
        val refreshed = Worlds.refreshed(world, later)
        assertEquals("2026-11-17 周二 12:30", refreshed.summary.worldTimeLabel)
        assertEquals("23 岁，咖啡店店员", refreshed.summary.residents[1].card)
    }

    @Test fun theTimelineIsReadAfterASequenceNumberAndMarkedSeen() {
        server.enqueue(json("""{"items":[{"seq":35,"kind":"event","text":"团子打翻了水碗。","who":["r1"],"time":"11-17 12:01","worldAt":1},
            {"seq":36,"kind":"message","channel":"group","from":"r3","name":"苏糖","text":"今晚吃火锅吗","time":"11-17 12:02","worldAt":2}],"next":36,"more":false,"world":$summary}"""))
        val page = runBlocking { client.worldMessages(pc, "token", "wcfad81922177", since = 34) }
        assertEquals("/v1/pocket/worlds/wcfad81922177/messages?since=34&seen=1", server.takeRequest().path)
        assertEquals(36L, page.next)
        assertTrue(page.items.first().isEvent)
        assertEquals(listOf("r1"), page.items.first().who)
        assertEquals("苏糖", page.world?.lastMessage?.name)
        server.enqueue(json("""{"items":[],"next":0,"more":false}"""))
        runBlocking { client.worldMessages(pc, "token", "wcfad81922177", tail = 300) }
        assertEquals("/v1/pocket/worlds/wcfad81922177/messages?tail=300&seen=1", server.takeRequest().path)
    }

    @Test fun speakingSendsTheChannelAndText() {
        server.enqueue(json("""{"items":[{"seq":37,"kind":"message","channel":"dm:r1","from":"user","name":"新室友","text":"你好呀","time":"11-17 12:05"},
            {"seq":38,"kind":"message","channel":"dm:r1","from":"r1","name":"林晚","text":"唔……","time":"11-17 12:05"}],"world":$summary}"""))
        val page = runBlocking { client.sayInWorld(pc, "token", "wcfad81922177", "dm:r1", "你好呀") }
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/pocket/worlds/wcfad81922177/messages", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("dm:r1", body.getString("channel"))
        assertEquals("你好呀", body.getString("text"))
        assertEquals(listOf("新室友", "林晚"), page.items.map { it.name })
        assertTrue(page.items.first().fromUser)
    }

    @Test fun theWorldsOwnRefusalsAreShownAsTheyAre() {
        server.enqueue(json("""{"error":{"message":"世界已暂停，恢复后再说话","code":"world_paused"}}""", 409))
        assertEquals("世界已暂停，恢复后再说话", failure { client.sayInWorld(pc, "token", "wcfad81922177", "group", "在吗") }.message)
        server.enqueue(json("""{"error":{"message":"今天的请求次数已经用完，明天再来","code":"world_budget"}}""", 429))
        assertEquals("今天的请求次数已经用完，明天再来", failure { client.tickWorld(pc, "token", "wcfad81922177") }.message)
        // A throttled token still gets the token explanation, not the world's.
        server.enqueue(json("""{"error":{"message":"令牌错误次数过多"}}""", 429).setHeader("Retry-After", "600"))
        assertTrue(failure { client.worlds(pc, "token") }.message!!.contains("Token 错误次数过多"))
        // An older PC has no worlds at all.
        server.enqueue(json("""{"error":{"message":"路径不存在"}}""", 404))
        val old = failure { client.worlds(pc, "token") }
        assertEquals(404, old.httpCode)
        assertTrue(old.message!!.contains("版本过旧"))
        // A malformed id never leaves the phone.
        assertTrue(failure { client.world(pc, "token", "../config") }.message!!.contains("世界编号无效"))
    }

    @Test fun settingsArePatchedWholeAndPausingUsesItsOwnPath() {
        val detail = JSONObject(summary).put("definition", JSONObject("""{"groupName":"302 合租群","residents":[]}"""))
        server.enqueue(json(detail.toString()))
        runBlocking { client.saveWorld(pc, "token", "wcfad81922177", WorldSettings(tickMinutes = 60, dailyCalls = 50)) }
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        val settings = JSONObject(patch.body.readUtf8()).getJSONObject("settings")
        assertEquals(60, settings.getInt("tickMinutes"))
        assertEquals(50, settings.getInt("dailyCalls"))
        assertEquals("01:00", settings.getString("quietStart"))
        server.enqueue(json(JSONObject(summary).put("paused", true).put("hold", "paused").toString()))
        val paused = runBlocking { client.setWorldPaused(pc, "token", "wcfad81922177", true) }
        assertEquals("/v1/pocket/worlds/wcfad81922177/pause", server.takeRequest().path)
        assertTrue(paused.paused)
        assertEquals("已暂停", Worlds.holdLabel(paused.hold))
    }

    @Test fun mergingKeepsOrderAndShowsEachLineOnce() {
        val a = WorldItem(seq = 1, kind = "message", channel = "group", text = "一")
        val b = WorldItem(seq = 2, kind = "message", channel = "dm:r1", text = "二")
        val c = WorldItem(seq = 3, kind = "event", text = "三")
        val merged = Worlds.merge(listOf(a, c), listOf(b, c))
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.seq })
        assertEquals(listOf(a, c), Worlds.groupLines(merged))
        assertEquals(listOf(b), Worlds.dmLines(merged, "r1"))
        assertNull(Worlds.settingsProblem(WorldSettings()))
        assertEquals("推进间隔无效", Worlds.settingsProblem(WorldSettings(tickMinutes = 25)))
        assertTrue(Worlds.settingsProblem(WorldSettings(quietStart = "1点"))!!.contains("HH:MM"))
        assertTrue(Worlds.settingsProblem(WorldSettings(dailyCalls = 0))!!.contains("1 到 400"))
    }

    private fun failure(block: suspend () -> Unit): PcLinkException = try {
        runBlocking { block() }
        fail("expected PcLinkException"); throw AssertionError()
    } catch (error: PcLinkException) { error }
}
