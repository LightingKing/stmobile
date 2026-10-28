package app.pockettavern.provider

import app.pockettavern.engine.PublicResearchRequest
import app.pockettavern.engine.ResearchCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class DoubaoSearchClientTest {
    private lateinit var server: MockWebServer
    private val research = PublicResearchRequest("林岚", listOf(ResearchCategory.STYLE))
    @Before fun setup() { server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) } }
    @After fun teardown() { server.shutdown() }
    private fun endpoint() = server.url("/search_api/web_search").newBuilder().host("127.0.0.1").build().toString()
    private fun client(timeout: Long = 30_000) = DoubaoSearchClient(testEndpoint = endpoint(), callTimeoutMillis = timeout)
    private fun row(url: String = "https://example.com/interview") = JSONObject().put("Title", "演员访谈")
        .put("Url", url).put("Snippet", "列表短片段")
        .put("Summary", "她在公开采访中介绍了简洁的舞台服装设计。")
        .put("Content", "完整正文中还有不相关财产等段落，不应用它替代摘要。")
        .put("PublishTime", "2026-10-20T12:30:00+08:00")
    private fun response(rows: JSONArray = JSONArray().put(row())) = JSONObject()
        .put("ResponseMetadata", JSONObject().put("RequestId", "test-request"))
        .put("Result", JSONObject().put("ResultCount", rows.length()).put("WebResults", rows))

    @Test fun standaloneAuthenticationAndDocumentedSearchPayloadAreIndependentOfArk() = runBlocking {
        server.enqueue(MockResponse().setBody(response().toString()))
        val hits = client().search("standalone-test-key", research)
        val received = server.takeRequest()
        assertEquals("/search_api/web_search", received.path)
        assertEquals("Bearer standalone-test-key", received.getHeader("Authorization"))
        val body = JSONObject(received.body.readUtf8())
        assertEquals("林岚 风格", body.getString("Query"))
        assertEquals("web", body.getString("SearchType"))
        assertEquals(8, body.getInt("Count"))
        assertTrue(body.getJSONObject("Filter").getBoolean("NeedUrl"))
        assertFalse(body.has("model"))
        assertFalse(body.has("input"))
        assertFalse(body.has("messages"))
        assertEquals("她在公开采访中介绍了简洁的舞台服装设计。", hits.single().snippet)
        assertEquals("2026-10-20T12:30:00+08:00", hits.single().publishedAt)
    }

    @Test fun summaryPreferredOverFullContentAndSnippetOnlyResultsNotUsedForModelEvidence() {
        val rows = JSONArray().put(row()).put(row("https://example.com/content").apply { remove("Summary"); put("Content", "公开正文介绍他的风格简洁。"); put("PublishTime", "malformed") })
            .put(row("https://example.com/snippet").apply { remove("Summary"); remove("Content") })
            .put(row("https://example.com/broken").put("Summary", "CCTV.com - ERROR\n对不起，可能是网络原因或无此页面，请稍后尝试。"))
        val hits = DoubaoSearchClient.parseResponse(response(rows).toString())
        assertEquals(2, hits.size)
        assertTrue(hits.none { "财产" in it.snippet })
        assertTrue(hits.none { it.snippet == "列表短片段" })
        assertNull(hits.last().publishedAt)
    }

    @Test fun boundsSourcesDeduplicatesUrlsAndFiltersUnsafeLinksAndSensitiveSummaries() {
        val rows = JSONArray()
            .put(row("https://127.0.0.1/private"))
            .put(row("http://example.com/http"))
            .put(row("https://example.com/contact").put("Summary", "联系电话和住址是私人资料"))
            .put(row().put("Content", "未采用的完整正文"))
            .put(row("https://example.com/interview#duplicate"))
        repeat(12) { rows.put(row("https://example.com/source$it").put("Summary", "公开资料".repeat(2000))) }
        val hits = DoubaoSearchClient.parseResponse(response(rows).toString())
        assertEquals(8, hits.size)
        assertEquals(8, hits.map { it.url }.toSet().size)
        assertEquals(1, hits.count { "interview" in it.url })
        assertTrue(hits.all { it.snippet.codePointCount(0, it.snippet.length) <= 5000 })
    }

    @Test fun inBandErrorCannotBecomeSuccessfulSourcesOrExposeErrorText() {
        val raw = response().put("ResponseMetadata", JSONObject().put("Error", JSONObject().put("Code", "NotAuthorized").put("Message", "private-search-key secret-account")))
        val error = runCatching { DoubaoSearchClient.parseResponse(raw.toString()) }.exceptionOrNull()
        assertTrue(error is ProviderException)
        assertFalse(error?.message.orEmpty().contains("private-search-key"))
        assertFalse(error?.message.orEmpty().contains("secret-account"))
    }

    @Test fun missingMetadataEmptyAndAllRejectedResultsFailExplicitly() {
        listOf(JSONObject().put("Result", response().getJSONObject("Result")), response(JSONArray()), response(JSONArray().put(row("http://example.com")))).forEach {
            assertTrue(runCatching { DoubaoSearchClient.parseResponse(it.toString()) }.exceptionOrNull() is ProviderException)
        }
    }

    @Test fun malformedResultCannotBeSavedAsACompletedSearchWithoutMatches() {
        listOf(
            response().apply { remove("Result") },
            response().put("Result", JSONObject()),
            response().put("Result", "invalid"),
            response().apply { getJSONObject("Result").put("WebResults", JSONObject()) }
        ).forEach { malformed ->
            val error = runCatching { DoubaoSearchClient.parseResponse(malformed.toString()) }.exceptionOrNull()
            assertEquals(ProviderException.Kind.IN_BAND, (error as ProviderException).kind)
        }
        val empty = runCatching { DoubaoSearchClient.parseResponse(response(JSONArray()).toString()) }.exceptionOrNull()
        assertEquals(ProviderException.Kind.EMPTY_RESPONSE, (empty as ProviderException).kind)
    }

    @Test fun queryRespectsOneHundredUnicodeCharacters() {
        val query = DoubaoSearchClient.requestBody(research.copy(subject = "😀".repeat(80), query = "更多资料".repeat(30))).getString("Query")
        assertEquals(100, query.codePointCount(0, query.length))
        assertFalse(query.last().isHighSurrogate())
        val broad = DoubaoSearchClient.requestBody(PublicResearchRequest("林岚")).getString("Query")
        assertEquals("林岚", broad)
    }

    @Test fun longDouyinProfileQueryKeepsTheEntireAccountIdAndOriginalSubject() {
        val accountId = "MS4wLjABAAAA" + "AbcdEfgh01234567".repeat(4)
        assertEquals(76, accountId.length)
        val profile = "https://www.douyin.com/user/$accountId?from_tab_name=main&modal_id=1234567890123456789"
        val request = PublicResearchRequest(profile, query = "公开采访与作品风格".repeat(10))
        val query = DoubaoSearchClient.requestBody(request).getString("Query")
        assertTrue(query.startsWith("抖音 $accountId "))
        assertEquals(100, query.codePointCount(0, query.length))
        assertFalse(query.contains("from_tab_name"))
        assertFalse(query.contains("modal_id"))
        assertEquals(profile, request.subject)
    }

    @Test fun bareLongDouyinProfileWithoutQueryKeepsTheEntireAccountId() {
        val accountId = "MS4wLjABAAAA" + "AbcdEfgh01234567".repeat(4)
        val profile = "https://www.douyin.com/user/$accountId"
        assertTrue(profile.length > 100)
        val request = PublicResearchRequest(profile)
        assertEquals("抖音 $accountId", DoubaoSearchClient.requestBody(request).getString("Query"))
        assertEquals(profile, request.subject)
    }

    @Test fun profileIdentityAtApiLimitIsKeptAndOversizedIdentityIsRejectedRatherThanTruncated() {
        val accountId = "A".repeat(100)
        val atLimit = PublicResearchRequest("https://www.douyin.com/user/$accountId", query = "更多线索")
        assertEquals(accountId, DoubaoSearchClient.requestBody(atLimit).getString("Query"))
        val oversized = atLimit.copy(subject = "https://www.douyin.com/user/${accountId}B")
        val error = runCatching { DoubaoSearchClient.requestBody(oversized) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("账号标识"))
    }

    @Test fun httpErrorsAndRedirectsNeverRetryOrExposeBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("private-key secret-account"))
        val auth = runCatching { client().search("private-key", research) }.exceptionOrNull() as ProviderException
        assertEquals(ProviderException.Kind.AUTH, auth.kind)
        assertTrue(auth.message.orEmpty().contains("独立搜索"))
        assertFalse(auth.message.orEmpty().contains("private-key"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirect")))
        assertTrue(runCatching { client().search("private-key", research) }.exceptionOrNull() is ProviderException)
        assertEquals(2, server.requestCount)
    }

    @Test fun deadlineTimeoutAndUserCancellationAreDistinct() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val timeout = runCatching { withTimeout(5_000) { client(500).search("key", research) } }.exceptionOrNull()
        assertEquals(ProviderException.Kind.TIMEOUT, (timeout as ProviderException).kind)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = client()
        val operation = async { client.search("key", research) }
        withContext(Dispatchers.IO) {
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        }
        client.cancel()
        assertTrue(runCatching { withTimeout(3_000) { operation.await() } }.exceptionOrNull() is CancellationException)
        assertEquals(2, server.requestCount)
    }

    @Test fun coroutineCancellationDoesNotProduceFallbackData() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val operation = async { client().search("key", research) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        operation.cancel()
        withTimeout(3_000) { operation.join() }
        assertTrue(operation.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun testEndpointCannotRedirectCredentialToRemoteHost() {
        assertTrue(runCatching { DoubaoSearchClient(testEndpoint = "https://attacker.example.com") }.isFailure)
        assertTrue(runCatching { DoubaoSearchClient(testEndpoint = "http://user@127.0.0.1/search") }.isFailure)
        assertEquals("https://open.feedcoopapi.com/search_api/web_search", DoubaoSearchClient.ENDPOINT)
    }
}
