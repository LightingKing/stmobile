package app.pockettavern.provider

import app.pockettavern.engine.PublicResearchRequest
import app.pockettavern.engine.ResearchCategory
import app.pockettavern.engine.ResearchEvidence
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

class DoubaoResearchClientTest {
    private lateinit var server: MockWebServer
    private val research = PublicResearchRequest("林岚", listOf(ResearchCategory.APPEARANCE, ResearchCategory.PERSONALITY), allowFiction = true)
    private val profileId = "MS4wLjABAAAAooLSS6wjG5PApQvRX2XKnVol9NcQbFBgDWj_xZBEj0_xd-cVwlAIo8-8aT01cczH"
    private val profileUrl = "https://www.douyin.com/user/$profileId"
    private val profileResearch = research.copy(subject = "$profileUrl?from_tab_name=main")
    private val now = "2026-10-24T03:04:05Z"

    @Before fun setup() { server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) } }
    @After fun teardown() { server.shutdown() }
    private fun base() = server.url("/api/v3").newBuilder().host("127.0.0.1").build().toString()

    private fun row(text: String = "访谈文字介绍她留短发", evidence: String = "fact", url: String = "https://example.com/profile") = JSONObject()
        .put("category", "appearance").put("text", text).put("evidence", evidence)
        .put("observedAt", "invented-date").put("confidence", 0.8)
        .put("source", JSONObject().put("url", url).put("title", "模型猜的标题"))

    private fun response(
        rows: JSONArray = JSONArray().put(row()), searched: Boolean = true, citations: Boolean = true,
        subject: String = research.subject, citationUrl: String = "https://example.com/profile",
        citationSummary: String = "采访公开文字"
    ): JSONObject {
        val dossier = JSONObject().put("subject", subject).put("requestedAt", now).put("items", rows)
        val output = JSONArray()
        if (searched) output.put(JSONObject().put("type", "web_search_call").put("status", "completed"))
        val content = JSONObject().put("type", "output_text").put("text", dossier.toString())
        if (citations) content.put("annotations", JSONArray().put(JSONObject().put("type", "url_citation")
            .put("url", citationUrl).put("title", "访谈来源")
            .put("publish_time", "2026-10-01T00:00:00Z").put("summary", citationSummary)))
        output.put(JSONObject().put("type", "message").put("role", "assistant").put("status", "completed").put("content", JSONArray().put(content)))
        return JSONObject().put("status", "completed").put("output", output)
    }

    @Test fun postsOfficialSearchSourceAndBoundedRequestWithoutChatState() = runBlocking {
        server.enqueue(MockResponse().setBody(response().toString()))
        val result = DoubaoResearchClient(allowInsecureHttpForTests = true).research(base(), "private-test-key", "ep-user-model", research)
        val received = server.takeRequest()
        assertEquals("/api/v3/responses", received.path)
        assertEquals("Bearer private-test-key", received.getHeader("Authorization"))
        val body = JSONObject(received.body.readUtf8())
        assertEquals("ep-user-model", body.getString("model"))
        assertFalse(body.getJSONArray("tools").getJSONObject(0).has("sources"))
        assertEquals(2, body.getJSONArray("tools").getJSONObject(0).getInt("max_keyword"))
        assertEquals("web_search", body.getJSONObject("tool_choice").getString("type"))
        assertEquals(10_000, body.getInt("max_output_tokens"))
        assertEquals(3, body.getInt("max_tool_calls"))
        assertFalse(body.getBoolean("store"))
        assertFalse(body.has("previous_response_id"))
        assertEquals(2, body.getJSONArray("input").length())
        assertEquals(ResearchEvidence.FACT, result.items.single().evidence)
        assertEquals("访谈来源", result.items.single().source?.title)
        assertNotEquals("invented-date", result.items.single().observedAt)
        assertEquals("2026-10-01T00:00:00Z", result.items.single().publishedAt)
        assertTrue(result.warnings.any { it.contains("性格") })
    }

    @Test fun requiresActualCompletedSearchEvenWhenModelClaimsToHaveSearched() {
        val raw = response(searched = false)
        val failure = runCatching { DoubaoResearchClient.parseResponse(raw.toString(), research, now) }.exceptionOrNull()
        assertTrue(failure is ProviderException)
        assertEquals(ProviderException.Kind.UNSUPPORTED, (failure as ProviderException).kind)
    }

    @Test fun cannotPromoteFabricatedUrlsToFacts() {
        val raw = response(JSONArray().put(row(url = "https://invented.example.com/profile")).put(row("虚构设定：戴圆框眼镜", "fictional")))
        val result = DoubaoResearchClient.parseResponse(raw.toString(), research, now)
        assertEquals(1, result.items.size)
        assertEquals(ResearchEvidence.FICTIONAL, result.items.single().evidence)
        assertNull(result.items.single().source)
        assertTrue(result.warnings.any { it.contains("校验") })
    }

    @Test fun refusesUncitedFactsEmptyResultsAndIncompleteResponses() {
        val bad = listOf(response(citations = false), response(JSONArray()), response().put("status", "incomplete"))
        bad.forEach {
            assertTrue(runCatching { DoubaoResearchClient.parseResponse(it.toString(), research, now) }.exceptionOrNull() is ProviderException)
        }
    }

    @Test fun profileSearchWithoutAssociatedCitationsReturnsUnverifiedEvenWhenModelInventsDetails() {
        listOf(
            response(subject = profileResearch.subject),
            response(subject = profileResearch.subject, citations = false),
            response(JSONArray(), subject = profileResearch.subject, citations = false),
            response(JSONArray().put(row("虚构设定：戴圆框眼镜", "fictional")), subject = profileResearch.subject)
        ).forEach { raw ->
            val result = DoubaoResearchClient.parseResponse(raw.toString(), profileResearch, now)
            assertEquals("unverified", result.networkStatus)
            assertEquals(profileResearch.subject, result.subject)
            assertEquals(now, result.requestedAt)
            assertTrue(result.items.isEmpty())
            assertTrue(result.warnings.any { it.contains("没有找到与该主页匹配") })
        }
    }

    @Test fun profileCitationMatchesIgnoringTrackingParameters() {
        val raw = response(JSONArray().put(row(url = profileUrl)), subject = profileResearch.subject, citationUrl = profileUrl)
        val result = DoubaoResearchClient.parseResponse(raw.toString(), profileResearch, now)
        assertEquals("ok", result.networkStatus)
        assertEquals(profileUrl, result.items.single().source?.url)
        assertEquals("访谈来源", result.items.single().source?.title)
    }

    @Test fun encyclopediaCitationContainingCompleteProfileIdCanSupportProfileResearch() {
        val encyclopedia = "https://baike.example.com/item/artist"
        val raw = response(
            JSONArray().put(row(url = encyclopedia)), subject = profileResearch.subject,
            citationUrl = encyclopedia, citationSummary = "公开页面列出的抖音主页为 $profileUrl 。"
        )
        val result = DoubaoResearchClient.parseResponse(raw.toString(), profileResearch, now)
        assertEquals("ok", result.networkStatus)
        assertEquals(encyclopedia, result.items.single().source?.url)
    }

    @Test fun truncatedAndDifferentCaseProfileIdsDoNotAssociateAnExternalCitation() {
        listOf(profileId.dropLast(4), profileId.lowercase(), profileId + "extra").forEach { wrongId ->
            val raw = response(subject = profileResearch.subject, citationSummary = "抖音账号：$wrongId")
            val result = DoubaoResearchClient.parseResponse(raw.toString(), profileResearch, now)
            assertEquals("unverified", result.networkStatus)
            assertTrue(result.items.isEmpty())
        }
    }

    @Test fun associatedCitationDoesNotAuthorizeUnrelatedRows() {
        val raw = response(
            JSONArray().put(row(url = profileUrl)).put(row("另一账号的公开访谈", url = "https://example.com/profile")),
            subject = profileResearch.subject, citationUrl = profileUrl
        )
        raw.getJSONArray("output").getJSONObject(1).getJSONArray("content").getJSONObject(0)
            .getJSONArray("annotations").put(JSONObject().put("type", "url_citation")
                .put("url", "https://example.com/profile").put("title", "另一账号访谈").put("summary", "另一位创作者"))
        val result = DoubaoResearchClient.parseResponse(raw.toString(), profileResearch, now)
        assertEquals("ok", result.networkStatus)
        assertEquals(profileUrl, result.items.single().source?.url)
    }

    @Test fun associatedCitationWithNoValidSourceRowsReturnsUnverified() {
        listOf(JSONArray(), JSONArray().put(row(url = "https://invented.example.com/profile"))).forEach { rows ->
            val raw = response(rows, subject = profileResearch.subject, citationUrl = profileUrl)
            val result = DoubaoResearchClient.parseResponse(raw.toString(), profileResearch, now)
            assertEquals("unverified", result.networkStatus)
            assertTrue(result.items.isEmpty())
        }
    }

    @Test fun profileSearchStillRequiresCompletedSearchAndWellFormedCompleteResponse() {
        val malformedDossier = response(subject = profileResearch.subject, citations = false)
        malformedDossier.getJSONArray("output").getJSONObject(1).getJSONArray("content").getJSONObject(0)
            .put("text", "not json")
        val invalidSchema = response(subject = profileResearch.subject, citations = false)
        invalidSchema.getJSONArray("output").getJSONObject(1).getJSONArray("content").getJSONObject(0)
            .put("text", JSONObject().put("subject", profileResearch.subject).put("items", JSONArray()).toString())
        val missingText = response(subject = profileResearch.subject, citations = false)
        missingText.getJSONArray("output").getJSONObject(1).put("content", JSONArray())
        listOf(
            "not json" to ProviderException.Kind.IN_BAND,
            response(subject = profileResearch.subject, searched = false, citations = false).toString() to ProviderException.Kind.UNSUPPORTED,
            response(subject = profileResearch.subject, citations = false).put("status", "incomplete").toString() to ProviderException.Kind.IN_BAND,
            malformedDossier.toString() to ProviderException.Kind.IN_BAND,
            invalidSchema.toString() to ProviderException.Kind.IN_BAND,
            missingText.toString() to ProviderException.Kind.EMPTY_RESPONSE
        ).forEach { (raw, expected) ->
            val error = runCatching { DoubaoResearchClient.parseResponse(raw, profileResearch, now) }.exceptionOrNull()
            assertTrue("Expected $expected, got $error", error is ProviderException)
            assertEquals(expected, (error as ProviderException).kind)
        }
    }

    @Test fun reportsAuthAndDoesNotRetryOrExposeResponseBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("private-test-key unexpected debug secrets"))
        val error = runCatching { DoubaoResearchClient(allowInsecureHttpForTests = true).research(base(), "private-test-key", "model", research) }.exceptionOrNull()
        assertEquals(ProviderException.Kind.AUTH, (error as ProviderException).kind)
        assertFalse(error.message.orEmpty().contains("private-test-key"))
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectsAreNeverFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/stolen-key")))
        val error = runCatching { DoubaoResearchClient(allowInsecureHttpForTests = true).research(base(), "key", "model", research) }.exceptionOrNull()
        assertTrue(error is ProviderException)
        assertEquals(1, server.requestCount)
    }

    @Test fun modelNotOpenReportsActivationRequirementWithoutProviderDetails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody(JSONObject().put("error", JSONObject()
            .put("code", "ModelNotOpen")
            .put("message", "Account secret-account has not activated model; private-test-key"))
            .put("request_id", "private-request-id").toString()))
        val error = runCatching { DoubaoResearchClient(allowInsecureHttpForTests = true).research(base(), "private-test-key", "model", research) }.exceptionOrNull()
        assertEquals(ProviderException.Kind.UNSUPPORTED, (error as ProviderException).kind)
        assertTrue(error.message.orEmpty().contains("尚未开通所选模型"))
        assertTrue(error.message.orEmpty().contains("火山方舟控制台"))
        listOf("secret-account", "private-test-key", "private-request-id").forEach { assertFalse(error.message.orEmpty().contains(it)) }
        assertEquals(1, server.requestCount)
    }

    @Test fun unknownProviderErrorCodeIsNotEchoed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"private-test-key","message":"secret-account"}}"""))
        val error = runCatching { DoubaoResearchClient(allowInsecureHttpForTests = true).research(base(), "private-test-key", "model", research) }.exceptionOrNull()
        assertTrue(error is ProviderException)
        assertFalse(error?.message.orEmpty().contains("private-test-key"))
        assertFalse(error?.message.orEmpty().contains("secret-account"))
    }

    @Test fun cancellationStopsCallWithoutRetryOrFallback() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = DoubaoResearchClient(allowInsecureHttpForTests = true)
        val pending = async { client.research(base(), "key", "model", research) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        client.cancel()
        val error = runCatching { withTimeout(3_000) { pending.await() } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, server.requestCount)
    }

    @Test fun coroutineCancellationClosesHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = DoubaoResearchClient(allowInsecureHttpForTests = true)
        val pending = async { client.research(base(), "key", "model", research) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        pending.cancel()
        withTimeout(3_000) { pending.join() }
        assertTrue(pending.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun totalCallTimeoutIsReportedAsTimeoutRatherThanUserCancellation() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = DoubaoResearchClient(allowInsecureHttpForTests = true, callTimeoutMillis = 500)
        val error = runCatching { withTimeout(5_000) { client.research(base(), "key", "model", research) } }.exceptionOrNull()
        assertTrue("Expected provider timeout, got $error", error is ProviderException)
        assertEquals(ProviderException.Kind.TIMEOUT, (error as ProviderException).kind)
        assertTrue(error.message.orEmpty().contains("超时"))
        assertEquals(1, server.requestCount)
    }

    @Test fun officialEndpointBindingRejectsOtherHostsPathsCredentialsAndQueries() {
        assertTrue(DoubaoResearchClient.isSupportedBaseUrl(DoubaoResearchClient.DEFAULT_BASE_URL))
        assertTrue(DoubaoResearchClient.isSupportedBaseUrl(DoubaoResearchClient.DEFAULT_BASE_URL + "/"))
        listOf("https://ark.cn-beijing.volces.com.evil.example/api/v3", "https://user@ark.cn-beijing.volces.com/api/v3", "http://ark.cn-beijing.volces.com/api/v3", "https://ark.cn-beijing.volces.com/api/v4", "https://ark.cn-beijing.volces.com/api/v3?q=x", "https://ark.cn-beijing.volces.com:8443/api/v3").forEach {
            assertFalse(it, DoubaoResearchClient.isSupportedBaseUrl(it))
        }
    }
}
