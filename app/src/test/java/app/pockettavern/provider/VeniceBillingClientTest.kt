package app.pockettavern.provider

import app.pockettavern.domain.ProviderConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VeniceBillingClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun provider(kind: String = "venice") = ProviderConfig(
        id = "venice-test",
        name = "Venice 测试",
        kind = kind,
        baseUrl = server.url("/api/v1").newBuilder().host("127.0.0.1").build().toString()
    )

    @Test fun matchesInferenceRequestIdsExactlyAndSeparatesCurrencies() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {
              "data": [
                {"amount":-0.06356,"currency":"DIEM","timestamp":"2026-10-20T00:00:01Z","sku":"chat","inferenceDetails":{"requestId":"req-1","promptTokens":10,"completionTokens":20}},
                {"amount":-0.5,"currency":"DIEM","timestamp":"2026-10-20T00:00:02Z","sku":"chat","inferenceDetails":{"requestId":"req-10"}},
                {"amount":0.2,"currency":"USD","timestamp":"2026-10-20T00:00:03Z","sku":"credit","inferenceDetails":{"requestId":"req-2"}},
                {"amount":0,"currency":"DIEM","timestamp":"2026-10-20T00:00:04Z","sku":"free","inferenceDetails":{"requestId":"req-3"}}
              ],
              "nextCursor": null
            }
        """.trimIndent()))

        val result = VeniceBillingClient(allowInsecureHttpForTests = true).lookup(
            provider(), "secret-key", setOf("req-1", "req-2", "req-3"),
            1_792_444_800_000L, 1_792_444_900_000L
        )

        assertTrue("result=$result", result.contains("DIEM"))
        assertTrue(result.contains("0.06356"))
        assertTrue(result.contains("USD"))
        assertTrue(result.contains("退款"))
        assertTrue(result.contains("0 金额"))
        assertFalse(result.contains("0.5"))
        val request = server.takeRequest()
        assertTrue(request.getHeader("Authorization") == "Bearer secret-key")
        assertTrue(request.requestUrl!!.queryParameter("pageSize") == "100")
        assertTrue(request.requestUrl!!.queryParameter("startTimestamp")!!.endsWith("Z"))
        assertTrue(request.requestUrl!!.queryParameter("endTimestamp")!!.endsWith("Z"))
    }

    @Test fun subsequentPagesUseOnlyCursor() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"amount":-1,"currency":"DIEM","inferenceDetails":{"requestId":"first"}}],"nextCursor":"cursor-1"}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"amount":-2,"currency":"DIEM","inferenceDetails":{"requestId":"second"}}],"nextCursor":null}"""))

        val result = VeniceBillingClient(allowInsecureHttpForTests = true).lookup(
            provider(), "key", setOf("first", "second"), 1_792_444_800_000L, 1_792_444_900_000L
        )

        assertTrue("result=$result", result.contains("匹配 2 条"))
        server.takeRequest()
        val second = server.takeRequest().requestUrl!!
        assertTrue(second.queryParameter("cursor") == "cursor-1")
        assertTrue(second.queryParameter("pageSize") == null)
        assertTrue(second.queryParameter("startTimestamp") == null)
        assertTrue(second.queryParameter("endTimestamp") == null)
    }

    @Test fun missingRequestIdsRefuseTimeBasedGuessingWithoutNetwork() = runBlocking {
        val result = VeniceBillingClient(allowInsecureHttpForTests = true).lookup(
            provider(), "key", emptySet(), 1_792_444_800_000L, 1_792_444_900_000L
        )
        assertTrue("result=$result", result.contains("requestId"))
        assertTrue(result.contains("不按时间猜测"))
        assertTrue(server.requestCount == 0)
    }

    @Test fun notFoundStatesWindowPaginationDelayAndUnknown() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[],"nextCursor":null}"""))
        val result = VeniceBillingClient(allowInsecureHttpForTests = true).lookup(
            provider(), "key", setOf("missing"), 1_792_444_800_000L, 1_792_444_900_000L
        )
        assertTrue("result=$result", result.contains("不等于没有扣款"))
        assertTrue(result.contains("查询窗口"))
        assertTrue(result.contains("10页"))
        assertTrue(result.contains("同步延迟"))
        assertTrue(result.contains("未知"))
    }

    @Test fun authFailureIsUnknownAndDoesNotLeakKeyOrRawBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("api_key=secret-key; internal ledger details"))
        val result = VeniceBillingClient(allowInsecureHttpForTests = true).lookup(
            provider(), "secret-key", setOf("req"), 1_792_444_800_000L, 1_792_444_900_000L
        )
        assertTrue("result=$result", result.contains("401"))
        assertTrue(result.contains("未知"))
        assertFalse(result.contains("secret-key"))
        assertFalse(result.contains("internal ledger details"))
    }

    @Test fun oversizedResponseIsRejectedWithoutReadingRawBody() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1)))
        val result = VeniceBillingClient(allowInsecureHttpForTests = true).lookup(
            provider(), "key", setOf("req"), 1_792_444_800_000L, 1_792_444_900_000L
        )
        assertTrue("result=$result", result.contains("超过 1 MiB"))
        assertFalse(result.contains("xxx"))
    }

    @Test fun nonVeniceProviderIsRejectedBeforeRequest() = runBlocking {
        val result = VeniceBillingClient(allowInsecureHttpForTests = true).lookup(
            provider("openai"), "key", setOf("req"), 1_792_444_800_000L, 1_792_444_900_000L
        )
        assertTrue(result.contains("Venice"))
        assertTrue(server.requestCount == 0)
    }
}
