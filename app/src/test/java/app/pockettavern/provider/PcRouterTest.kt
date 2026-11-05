package app.pockettavern.provider

import app.pockettavern.domain.ProviderConfig
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** At home the PC is used over the LAN, but only after it proves it knows the token. */
class PcRouterTest {
    private lateinit var lan: MockWebServer
    private lateinit var pc: ProviderConfig
    private var answer: (String) -> MockResponse = { nonce -> MockResponse().setBody("""{"proof":"${PcRouter.expectedProof("test-token", nonce)}"}""") }

    @Before fun start() {
        lan = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = answer(request.requestUrl?.queryParameter("nonce").orEmpty())
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        pc = ProviderConfig(id = "pc", kind = "writing-server", baseUrl = "https://pt.example.com/v1",
            lanBaseUrl = lan.url("/v1").newBuilder().host("127.0.0.1").build().toString())
    }

    @After fun stop() { lan.shutdown() }

    @Test fun theProofMatchesWhatThePcComputes() {
        // Same value as Node's createHmac('sha256', 'test-token').update('pocket-tavern-lan:' + 'a'.repeat(32)).
        assertEquals("d7996a5f4512c266ebe0bdcb590294ab0cc0e1de390bf24b1412c7944abecda9", PcRouter.expectedProof("test-token", "a".repeat(32)))
    }

    @Test fun atHomeTheLanIsUsedAndRememberedUntilTheNetworkChanges() {
        val router = PcRouter(allowInsecureHttpForTests = true)
        assertNull(router.onLan(pc))
        assertEquals(pc.lanBaseUrl, runBlocking { router.resolve(pc, "test-token") }.baseUrl)
        val request = lan.takeRequest()
        assertTrue(request.path!!.startsWith("/v1/pocket/hello?nonce="))
        assertNull("the token itself is never sent to the LAN check", request.getHeader("Authorization"))
        assertEquals(pc.lanBaseUrl, runBlocking { router.resolve(pc, "test-token") }.baseUrl)
        assertEquals(1, lan.requestCount)
        assertEquals(pc.lanBaseUrl, router.current(pc).baseUrl)
        router.forget()
        runBlocking { router.resolve(pc, "test-token") }
        assertEquals(2, lan.requestCount)
    }

    @Test fun aDeviceThatCannotProveTheTokenIsNeverUsed() {
        val router = PcRouter(allowInsecureHttpForTests = true)
        answer = { _ -> MockResponse().setBody("""{"proof":"${"0".repeat(64)}"}""") }
        assertEquals(pc.baseUrl, runBlocking { router.resolve(pc, "test-token") }.baseUrl)
        assertEquals(false, router.onLan(pc))
        router.forget()
        answer = { _ -> MockResponse().setResponseCode(404) }
        assertEquals(pc.baseUrl, runBlocking { router.resolve(pc, "test-token") }.baseUrl)
        router.forget()
        answer = { nonce -> MockResponse().setBody("""{"proof":"${PcRouter.expectedProof("someone-elses-token", nonce)}"}""") }
        assertEquals(pc.baseUrl, runBlocking { router.resolve(pc, "test-token") }.baseUrl)
        assertEquals(pc.baseUrl, router.current(pc).baseUrl)
    }

    @Test fun awayFromHomeTheRemoteAddressIsUsedQuickly() {
        val router = PcRouter(allowInsecureHttpForTests = true)
        val unreachable = pc.copy(lanBaseUrl = "http://127.0.0.1:9/v1")
        val started = System.currentTimeMillis()
        assertEquals(unreachable.baseUrl, runBlocking { router.resolve(unreachable, "test-token") }.baseUrl)
        assertTrue("an absent LAN costs at most the short probe", System.currentTimeMillis() - started < 3_000)
    }

    @Test fun onlyPcPlatformsWithALanAddressAreTouched() {
        val router = PcRouter(allowInsecureHttpForTests = true)
        val plain = pc.copy(lanBaseUrl = "")
        assertEquals(plain, runBlocking { router.resolve(plain, "test-token") })
        val venice = ProviderConfig(id = "v", lanBaseUrl = pc.lanBaseUrl)
        assertEquals(venice, runBlocking { router.resolve(venice, "test-token") })
        assertEquals(0, lan.requestCount)
    }

    @Test fun savedDataWithoutALanAddressStillLoadsAndBadOnesAreRefused() {
        val data = app.pockettavern.domain.AppData(providers = listOf(pc.copy(lanBaseUrl = "http://192.168.1.22:8791/v1")))
        assertEquals("http://192.168.1.22:8791/v1", app.pockettavern.data.DataCodec.decode(app.pockettavern.data.DataCodec.encode(data)).providers.single().lanBaseUrl)
        val old = app.pockettavern.data.DataCodec.encode(data).replace(",\"lanBaseUrl\":\"http://192.168.1.22:8791/v1\"", "")
        assertFalse(old.contains("lanBaseUrl"))
        assertEquals("", app.pockettavern.data.DataCodec.decode(old).providers.single().lanBaseUrl)
        val bad = app.pockettavern.data.DataCodec.encode(data.copy(providers = listOf(pc.copy(lanBaseUrl = "http://evil.example/v1"))))
        try { app.pockettavern.data.DataCodec.decode(bad); org.junit.Assert.fail("a public plain-HTTP LAN address must be refused") } catch (_: IllegalArgumentException) { }
    }

    @Test fun lanAddressesFollowTheSameUrlRules() {
        assertNull(ProviderUrlPolicy.lanViolation(pc.copy(lanBaseUrl = "http://192.168.1.22:8791/v1")))
        assertNull(ProviderUrlPolicy.lanViolation(pc.copy(lanBaseUrl = "")))
        assertTrue(ProviderUrlPolicy.lanViolation(pc.copy(lanBaseUrl = "http://example.com/v1"))!!.startsWith("局域网地址"))
        assertFalse(ProviderUrlPolicy.lanViolation(ProviderConfig(lanBaseUrl = "http://192.168.1.22:8791/v1")).isNullOrBlank())
    }
}
