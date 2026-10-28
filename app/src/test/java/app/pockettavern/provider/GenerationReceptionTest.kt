package app.pockettavern.provider

import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GenerationReceptionTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @After fun tearDown() { server.shutdown() }

    private fun provider() = ProviderConfig(kind = "openai",
        baseUrl = server.url("/v1").newBuilder().host("127.0.0.1").build().toString())

    private fun delta(field: String, text: String) =
        "data: " + JSONObject().put("choices", listOf(JSONObject().put("delta", JSONObject().put(field, text)))) + "\n\n"

    private val end = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    private fun inheritedShortDeadlines(observed: AtomicReference<Pair<Int, Long>>) = OkHttpClient.Builder()
        .readTimeout(100, TimeUnit.MILLISECONDS)
        .callTimeout(150, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            observed.set(chain.readTimeoutMillis() to TimeUnit.NANOSECONDS.toMillis(chain.call().timeout().timeoutNanos()))
            chain.proceed(chain.request())
        }
        .build()

    @Test(timeout = 10_000) fun defaultChatKeepsReceivingContinuousReasoningBeyondInheritedCallDeadline() = runBlocking {
        val firstFrame = delta("reasoning_content", "plan-0 ")
        val body = (0..5).joinToString("") { delta("reasoning_content", "plan-$it ") } + delta("content", "finished") + end
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
            .throttleBody(firstFrame.toByteArray().size.toLong(), 80, TimeUnit.MILLISECONDS))
        val observed = AtomicReference<Pair<Int, Long>>()
        val logs = mutableListOf<String>()
        val start = System.nanoTime()
        val result = ProviderClient(client = inheritedShortDeadlines(observed), allowInsecureHttpForTests = true,
            onDiagnostic = { logs += it }).chat(provider(), "test-key", "model", "[]", Preset())

        assertEquals((0..5).joinToString("") { "plan-$it " }, result.reasoning)
        assertEquals("finished", result.content)
        assertTrue("Fixture must outlast the inherited call deadline", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) > 150)
        // Check effective transport configuration without making this regression wait three real minutes.
        assertEquals("Default chat must have no read deadline", 0, observed.get().first)
        assertEquals("Default chat must have no total deadline", 0L, observed.get().second)
        val finished = logs.map(::JSONObject).single { it.optString("event") == "finished" }
        assertEquals("complete", finished.getString("outcome"))
        assertEquals(0L, finished.getLong("read_timeout_ms"))
        assertEquals(0L, finished.getLong("call_timeout_ms"))
    }

    @Test(timeout = 10_000) fun defaultChatWaitsThroughSilenceAfterReasoningInsteadOfUsingInheritedReadDeadline() = runBlocking {
        val firstFrame = delta("reasoning_content", "still thinking")
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(firstFrame + delta("content", "answer after silence") + end)
            .throttleBody(firstFrame.toByteArray().size.toLong(), 500, TimeUnit.MILLISECONDS))
        val observed = AtomicReference<Pair<Int, Long>>()
        var reasoningAt = 0L
        var answerAt = 0L
        val result = ProviderClient(client = inheritedShortDeadlines(observed), allowInsecureHttpForTests = true)
            .chat(provider(), "test-key", "model", "[]", Preset()) {
                if (it.reasoning.isNotEmpty() && reasoningAt == 0L) reasoningAt = System.nanoTime()
                if (it.content.isNotEmpty() && answerAt == 0L) answerAt = System.nanoTime()
            }

        assertEquals("still thinking", result.reasoning)
        assertEquals("answer after silence", result.content)
        assertTrue("Fixture must have a silent interval longer than the inherited read deadline",
            reasoningAt > 0 && answerAt > reasoningAt && TimeUnit.NANOSECONDS.toMillis(answerAt - reasoningAt) >= 400)
        assertEquals("Default chat must have no read deadline", 0, observed.get().first)
        assertEquals("Default chat must have no total deadline", 0L, observed.get().second)
    }

    @Test(timeout = 10_000) fun serverResponsesAreNotLocallyClippedToEnabledGenerationBudget() = runBlocking {
        val reasoning = "逐一核对场景中的位置和人物。".repeat(80)
        val answer = "窗边的灯仍亮着，她把信放回抽屉。".repeat(100)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(delta("reasoning_content", reasoning) + delta("content", answer) + end.replace("\"stop\"", "\"length\"")))
        val receivedReasoning = StringBuilder()
        val receivedAnswer = StringBuilder()
        val result = ProviderClient(allowInsecureHttpForTests = true).chat(provider(), "test-key", "model", "[]",
            Preset(maxOutput = 1, reasoningBudget = 1, generationBudgetEnabled = true)) {
                receivedReasoning.append(it.reasoning)
                receivedAnswer.append(it.content)
            }

        assertEquals(1, JSONObject(server.takeRequest().body.readUtf8()).getInt("max_tokens"))
        assertEquals(reasoning, result.reasoning)
        assertEquals(answer, result.content)
        assertEquals(reasoning, receivedReasoning.toString())
        assertEquals(answer, receivedAnswer.toString())
        assertEquals("length", result.finishReason)
        assertEquals("A length finish must not cause an automatic extra paid request", 1, server.requestCount)
    }

    @Test(timeout = 10_000) fun manualCancelPromptlyStopsUnlimitedReceptionAndPreservesReasoning() = runBlocking {
        val firstFrame = delta("reasoning_content", "already received reasoning")
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(firstFrame + delta("content", "not received yet") + end)
            .throttleBody(firstFrame.toByteArray().size.toLong(), 2, TimeUnit.SECONDS))
        val logs = mutableListOf<String>()
        val client = ProviderClient(allowInsecureHttpForTests = true, onDiagnostic = { logs += it })
        var cancelledAt = 0L
        val failure = runCatching {
            client.chat(provider(), "test-key", "model", "[]", Preset()) {
                if (it.reasoning.isNotEmpty() && cancelledAt == 0L) {
                    cancelledAt = System.nanoTime()
                    client.cancel()
                }
            }
        }.exceptionOrNull()

        assertTrue("Expected explicit cancellation, got $failure", failure is ProviderException)
        assertEquals(ProviderException.Kind.CANCELLED, (failure as ProviderException).kind)
        assertEquals("already received reasoning", failure.partial?.reasoning)
        assertEquals("", failure.partial?.content)
        assertTrue("Manual cancellation should not wait for the next server chunk",
            cancelledAt > 0 && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt) < 1_000)
        val finished = logs.map(::JSONObject).single { it.optString("event") == "finished" }
        assertEquals("cancelled", finished.getString("outcome"))
        assertTrue(finished.getInt("reasoning_chars") > 0)
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 10_000) fun explicitCallDeadlineStillAppliesWhileReasoningIsArriving() = runBlocking {
        val firstFrame = delta("reasoning_content", "plan-00 ")
        val body = (0..19).joinToString("") { delta("reasoning_content", "plan-${it.toString().padStart(2, '0')} ") } +
            delta("content", "too late for explicit deadline") + end
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
            .throttleBody(firstFrame.toByteArray().size.toLong(), 50, TimeUnit.MILLISECONDS))
        val logs = mutableListOf<String>()
        val failure = runCatching {
            ProviderClient(allowInsecureHttpForTests = true, timeoutMillis = 1_000, callTimeoutMillis = 350,
                onDiagnostic = { logs += it }).chat(provider(), "test-key", "model", "[]", Preset())
        }.exceptionOrNull()

        assertTrue("Expected the deliberately configured deadline, got $failure", failure is ProviderException)
        assertEquals(ProviderException.Kind.TIMEOUT, (failure as ProviderException).kind)
        assertTrue("Reasoning should arrive before the total deadline", failure.partial?.reasoning.orEmpty().isNotBlank())
        assertEquals("", failure.partial?.content)
        val finished = logs.map(::JSONObject).single { it.optString("event") == "finished" }
        assertEquals("timeout", finished.getString("outcome"))
        assertEquals(350L, finished.getLong("call_timeout_ms"))
    }

    @Test(timeout = 10_000) fun modelCatalogKeepsFiniteDeadlinesWhenChatDefaultsAreUnlimited() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"data\":[]}"))
        val observed = AtomicReference<Pair<Int, Long>>()
        val models = ProviderClient(client = inheritedShortDeadlines(observed), allowInsecureHttpForTests = true)
            .models(provider(), "test-key")
        assertTrue(models.isEmpty())
        assertEquals(120_000, observed.get().first)
        assertEquals(180_000L, observed.get().second)
    }

    @Test(timeout = 10_000) fun modelCatalogStillHonorsAnExplicitShortReadDeadline() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("{\"data\":[]}").setHeadersDelay(500, TimeUnit.MILLISECONDS))
        val failure = runCatching {
            ProviderClient(allowInsecureHttpForTests = true, timeoutMillis = 100, callTimeoutMillis = 5_000)
                .models(provider(), "test-key")
        }.exceptionOrNull()
        assertTrue("Expected bounded model catalog request, got $failure", failure is ProviderException)
        assertEquals(ProviderException.Kind.TIMEOUT, (failure as ProviderException).kind)
    }
}
