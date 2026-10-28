package app.pockettavern.provider

import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.VeniceBalanceScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class VeniceBalanceClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After fun tearDown() { server.shutdown() }

    private fun provider(kind: String = "venice", base: String = server.url("/api/v1").newBuilder().host("127.0.0.1").build().toString()) = ProviderConfig(
        id = "venice-test", name = "Venice 测试", kind = kind, baseUrl = base
    )

    private fun client() = VeniceBalanceClient(allowInsecureHttpForTests = true)

    @Test fun accountBalanceMapsSubscriptionAndPurchasedCredits() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"canConsume":true,"balances":{"diem":90.5,"usd":25,"bundledCredits":10,"earnedCredits":5}}
        """.trimIndent()))
        val balance = client().fetch(provider(), "test-key")
        assertEquals(VeniceBalanceScope.ACCOUNT, balance.scope)
        assertEquals(BigDecimal("10"), balance.subscriptionCredits)
        assertEquals(BigDecimal("25"), balance.purchasedCredits)
        assertEquals(BigDecimal("90.5"), balance.diem)
        assertEquals(BigDecimal("5"), balance.earnedCredits)
        assertEquals(true, balance.canConsume)
        val request = server.takeRequest()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals("/api/v1/billing/balance", request.path)
    }

    @Test fun forbiddenAccountBalanceFallsBackToKeyAllowance() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("private"))
        server.enqueue(MockResponse().setBody("""
            {"data":{"accessPermitted":true,"balances":{"USD":50.23,"DIEM":100.023,"BUNDLED_CREDITS":25}}}
        """.trimIndent()))
        val balance = client().fetch(provider(), "test-key")
        assertEquals(VeniceBalanceScope.API_KEY, balance.scope)
        assertEquals(BigDecimal("25"), balance.subscriptionCredits)
        assertEquals(BigDecimal("50.23"), balance.purchasedCredits)
        assertEquals(BigDecimal("100.023"), balance.diem)
        assertEquals(true, balance.canConsume)
        assertEquals("/api/v1/billing/balance", server.takeRequest().path)
        assertEquals("/api/v1/api_keys/rate_limits", server.takeRequest().path)
    }

    @Test fun unauthorizedHeadersDoNotWaitForUnusedErrorBodyBeforeFallback() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unused error body")
            .setBodyDelay(2, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setBody("""{"data":{"balances":{"USD":12,"BUNDLED_CREDITS":7}}}"""))
        val balance = VeniceBalanceClient(allowInsecureHttpForTests = true, callTimeoutMillis = 500)
            .fetch(provider(), "test-secret-key")
        assertEquals(VeniceBalanceScope.API_KEY, balance.scope)
        assertEquals(BigDecimal("7"), balance.subscriptionCredits)
        assertEquals(2, server.requestCount)
    }

    @Test fun serverErrorAndRateLimitDoNotFallback() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal"))
        assertThrowsVenice { client().fetch(provider(), "key") }
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(429).setBody("busy"))
        assertThrowsVenice { client().fetch(provider(), "key") }
        assertEquals(2, server.requestCount)
    }

    @Test fun missingAndNullFieldsRemainUnknownExceptDocumentedUsdNull() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"balances":{"usd":null,"bundledCredits":null}}"""))
        val balance = client().fetch(provider(), "key")
        assertNull(balance.subscriptionCredits)
        assertEquals(BigDecimal.ZERO, balance.purchasedCredits)
        assertNull(balance.diem)
        assertNull(balance.earnedCredits)
    }

    @Test fun negativeValuesAndStringValuesAreHandledWithoutLeakingResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"balances":{"usd":-1}}"""))
        val negative = client().fetch(provider(), "secret-key")
        assertEquals(BigDecimal("-1"), negative.purchasedCredits)

        server.enqueue(MockResponse().setBody("""{"balances":{"usd":"25"}}"""))
        val invalid = runCatching { client().fetch(provider(), "secret-key") }.exceptionOrNull()
        assertTrue(invalid is VeniceBalanceException)
        assertFalse(invalid?.message.orEmpty().contains("secret-key"))
    }

    @Test fun invalidProviderIsRejectedBeforeNetwork() = runBlocking {
        assertThrowsVenice { client().fetch(provider("openai"), "key") }
        assertEquals(0, server.requestCount)
        val external = provider(base = "https://example.com/api/v1")
        assertThrowsVenice { VeniceBalanceClient().fetch(external, "key") }
    }

    @Test fun cancellationCancelsCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val error = runCatching {
            withTimeout(100) { client().fetch(provider(), "key") }
        }.exceptionOrNull()
        assertTrue(error is CancellationException || error is kotlinx.coroutines.TimeoutCancellationException)
    }

    @Test fun timeoutBeforeHeadersReportsObservedStageWithoutTryingSecondEndpoint() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val events = mutableListOf<String>()
        val error = runCatching {
            VeniceBalanceClient(allowInsecureHttpForTests = true, callTimeoutMillis = 500,
                onDiagnostic = { events += it }).fetch(provider(), "test-secret-key")
        }.exceptionOrNull()
        assertTrue(error is VeniceBalanceException)
        assertTrue(error?.message.orEmpty().contains("等待响应头"))
        assertTrue(error?.message.orEmpty().contains("单次上限 0.5s"))
        assertEquals(1, server.requestCount)
        val terminal = JSONObject(events.last())
        assertEquals("balance_finished", terminal.getString("event"))
        assertEquals("timeout", terminal.getString("outcome"))
        assertEquals("account_response_headers", terminal.getString("phase"))
        assertEquals("/billing/balance", terminal.getString("endpoint_path"))
        assertEquals(500, terminal.getInt("call_timeout_ms"))
        assertFalse(events.joinToString().contains("test-secret-key"))
    }

    @Test fun timeoutReadingSuccessfulBodyRetainsHttpStatusAndEndpointStage() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"balances":{"usd":12345.6789}}""")
            .setBodyDelay(2, TimeUnit.SECONDS))
        val events = mutableListOf<String>()
        val error = runCatching {
            VeniceBalanceClient(allowInsecureHttpForTests = true, callTimeoutMillis = 500,
                onDiagnostic = { events += it }).fetch(provider(), "test-secret-key")
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("读取余额响应"))
        val terminal = JSONObject(events.last())
        assertEquals("timeout", terminal.getString("outcome"))
        assertEquals("account_response_body", terminal.getString("phase"))
        assertEquals(200, terminal.getInt("http_status"))
        assertTrue(terminal.getLong("first_byte_ms") >= 0L)
        assertFalse(events.joinToString().contains("12345.6789"))
    }

    @Test fun fallbackDiagnosticsRetainSeparateRequestScopesWithoutBalancesOrKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("test-secret-key"))
        server.enqueue(MockResponse().setBody("""{"data":{"balances":{"USD":12345.6789}}}"""))
        val events = mutableListOf<String>()
        VeniceBalanceClient(allowInsecureHttpForTests = true, onDiagnostic = { events += it })
            .fetch(provider(), "test-secret-key")
        val finished = events.map(::JSONObject).filter { it.getString("event") == "balance_finished" }
        assertEquals(2, finished.size)
        assertEquals("http_error", finished[0].getString("outcome"))
        assertEquals(403, finished[0].getInt("http_status"))
        assertEquals("complete", finished[1].getString("outcome"))
        assertEquals("key_allowance_parse", finished[1].getString("phase"))
        assertNotEquals(finished[0].getString("trace_id"), finished[1].getString("trace_id"))
        assertFalse(events.joinToString().contains("test-secret-key"))
        assertFalse(events.joinToString().contains("12345.6789"))
    }

    @Test fun malformedPayloadProducesSanitizedDiagnosticAndThrowingObserverDoesNotBreakSuccess() = runBlocking {
        server.enqueue(MockResponse().setBody("private-response-value"))
        val events = mutableListOf<String>()
        assertThrowsVenice {
            VeniceBalanceClient(allowInsecureHttpForTests = true, onDiagnostic = { events += it })
                .fetch(provider(), "test-secret-key")
        }
        assertEquals("invalid_response", JSONObject(events.last()).getString("outcome"))
        assertFalse(events.joinToString().contains("private-response-value"))

        server.enqueue(MockResponse().setBody("""{"balances":{"usd":4}}"""))
        val balance = VeniceBalanceClient(allowInsecureHttpForTests = true,
            onDiagnostic = { throw IllegalStateException("broken observer") }).fetch(provider(), "test-secret-key")
        assertEquals(BigDecimal("4"), balance.purchasedCredits)
    }

    @Test fun explicitCancelIsNotReportedAsNetworkTimeout() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val events = mutableListOf<String>()
        val client = VeniceBalanceClient(allowInsecureHttpForTests = true, onDiagnostic = { events += it })
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { client.fetch(provider(), "test-secret-key") }.exceptionOrNull()
        }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        client.cancel()
        assertTrue(pending.await() is CancellationException)
        assertEquals("cancelled", JSONObject(events.last()).getString("outcome"))
    }

    private suspend fun assertThrowsVenice(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected VeniceBalanceException, got $error", error is VeniceBalanceException)
    }
}



