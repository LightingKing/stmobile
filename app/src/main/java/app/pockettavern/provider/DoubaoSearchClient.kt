package app.pockettavern.provider

import app.pockettavern.engine.PublicResearch
import app.pockettavern.engine.PublicResearchRequest
import app.pockettavern.engine.PublicResearchSourceHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Standalone Doubao Search Custom API, authenticated independently of Ark model access. */
class DoubaoSearchClient(
    client: OkHttpClient = OkHttpClient(),
    testEndpoint: String? = null,
    callTimeoutMillis: Long = 30_000L
) {
    init { require(callTimeoutMillis > 0) { "callTimeoutMillis must be positive" } }
    private val endpoint = if (testEndpoint == null) ENDPOINT else {
        val parsed = testEndpoint.toHttpUrlOrNull()
        require(parsed != null && parsed.scheme == "http" && parsed.host == "127.0.0.1" &&
            parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null) {
            "Test endpoint must be loopback HTTP"
        }
        parsed.toString()
    }
    private val client = client.newBuilder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private data class Active(val call: Call, val cancelled: AtomicBoolean = AtomicBoolean(false))
    private val active = AtomicReference<Active?>(null)

    fun cancel() { active.get()?.let { it.cancelled.set(true); it.call.cancel() } }

    suspend fun search(key: String, request: PublicResearchRequest): List<PublicResearchSourceHit> {
        require(key.isNotBlank()) { "请填写豆包搜索独立 API Key" }
        require(request.validate().isEmpty()) { request.validate().joinToString("；") }
        val http = Request.Builder().url(endpoint).header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .post(requestBody(request).toString().toRequestBody("application/json".toMediaType())).build()
        val operation = Active(client.newCall(http))
        check(active.compareAndSet(null, operation)) { "已有豆包搜索正在进行" }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { operation.cancelled.set(true); operation.call.cancel() }
            operation.call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    active.compareAndSet(operation, null)
                    continuation.resumeWith(Result.failure(classifyFailure(operation, e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            checkActive(operation)
                            if (!response.isSuccessful) throw httpError(response.code)
                            val body = response.body ?: throw ProviderException(ProviderException.Kind.IN_BAND, "豆包搜索缺少响应正文，未保存资料")
                            val bytes = ByteArrayOutputStream()
                            body.byteStream().use { stream ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    checkActive(operation)
                                    val n = stream.read(buffer)
                                    if (n < 0) break
                                    if (bytes.size() + n > MAX_RESPONSE_BYTES) throw ProviderException(ProviderException.Kind.IN_BAND, "豆包搜索结果超过 2 MB，未保存资料")
                                    bytes.write(buffer, 0, n)
                                }
                            }
                            checkActive(operation)
                            parseResponse(bytes.toString(Charsets.UTF_8.name())).also { checkActive(operation) }
                        }
                    }.recoverCatching { throw classifyFailure(operation, it) }
                    active.compareAndSet(operation, null)
                    continuation.resumeWith(result)
                }
            })
        }
    }

    private fun checkActive(operation: Active) {
        if (operation.cancelled.get()) throw CancellationException("豆包搜索已停止")
        if (operation.call.isCanceled()) throw ProviderException(ProviderException.Kind.TIMEOUT, "豆包搜索超时，未保存资料")
    }

    private fun classifyFailure(operation: Active, failure: Throwable): Throwable = when {
        operation.cancelled.get() -> CancellationException("豆包搜索已停止")
        failure is ProviderException || failure is CancellationException -> failure
        failure is InterruptedIOException || operation.call.isCanceled() -> ProviderException(ProviderException.Kind.TIMEOUT, "豆包搜索超时，未保存资料")
        failure is IOException -> ProviderException(ProviderException.Kind.NETWORK, "豆包搜索网络连接失败，未保存资料")
        else -> ProviderException(ProviderException.Kind.IN_BAND, "豆包搜索返回内容无法解析，未保存资料")
    }

    companion object {
        const val ENDPOINT = "https://open.feedcoopapi.com/search_api/web_search"
        const val CREDENTIAL_ID = "doubao-standalone-search"
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

        internal fun requestBody(request: PublicResearchRequest): JSONObject {
            val subject = PublicResearch.profileUrlSubject(request.subject)?.let { profile ->
                val accountId = URI(profile).path.trimEnd('/').substringAfterLast('/')
                // A truncated opaque identifier searches for a different account. Drop URL boilerplate first.
                require(accountId.length <= 100) { "抖音账号标识超过搜索接口的 100 字符限制，请补充公开昵称或姓名后查询" }
                if (accountId.length <= 97) "抖音 $accountId" else accountId
            } ?: request.subject.trim()
            val query = buildString {
                append(subject)
                if (request.query.isNotBlank()) append(' ').append(request.query.trim())
                else if (request.categories.size == 1) append(' ').append(request.categories.single().displayName)
            }.replace(Regex("\\s+"), " ")
            return JSONObject().put("Query", bounded(query, 100)).put("SearchType", "web").put("Count", 8)
                .put("Filter", JSONObject().put("NeedUrl", true))
                .put("QueryControl", JSONObject().put("QueryRewrite", false))
        }

        internal fun parseResponse(raw: String): List<PublicResearchSourceHit> {
            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw ProviderException(ProviderException.Kind.IN_BAND, "豆包搜索返回的响应不是合法 JSON")
            }
            val meta = json.optJSONObject("ResponseMetadata")
                ?: throw ProviderException(ProviderException.Kind.IN_BAND, "豆包搜索缺少响应状态，未保存资料")
            if (meta.has("Error") && !meta.isNull("Error")) throw ProviderException(
                ProviderException.Kind.IN_BAND, "豆包搜索接口返回错误，请检查独立搜索 Key 与 web 搜索服务是否已开通"
            )
            val rows = json.optJSONObject("Result")?.optJSONArray("WebResults")
                ?: throw ProviderException(ProviderException.Kind.IN_BAND, "豆包搜索返回的网页结果结构无效，未保存资料")
            val hits = linkedMapOf<String, PublicResearchSourceHit>()
            for (i in 0 until minOf(rows.length(), 50)) {
                val row = rows.optJSONObject(i) ?: continue
                // The API recommends Summary/Content for model input; Snippet is only a short list preview.
                val evidence = row.optString("Summary").takeUnless { it.isBlank() || it == "null" }
                    ?: row.optString("Content").takeUnless { it.isBlank() || it == "null" }
                    ?: continue
                val leadingText = evidence.trim().take(240)
                if (leadingText.contains("CCTV.com - ERROR", ignoreCase = true) ||
                    Regex("^(?:404(?:\\s|:|-)|page not found|access denied|该页面不存在|页面不存在|对不起，可能是网络原因或无此页面)", RegexOption.IGNORE_CASE).containsMatchIn(leadingText)) continue
                val title = row.optString("Title").trim()
                val url = row.optString("Url").trim()
                if (title.isBlank() || title == "null" || !PublicResearch.isPublicHttps(url)) continue
                val published = row.optString("PublishTime").takeIf { it.length <= 64 }
                    ?.takeIf { runCatching { OffsetDateTime.parse(it) }.isSuccess }
                val hit = PublicResearchSourceHit(bounded(title, 300), url, bounded(evidence.trim(), 5_000), published)
                if (!PublicResearch.isSafeSourceHit(hit)) continue
                val normalized = PublicResearch.canonicalSourceUrl(url) ?: continue
                hits.putIfAbsent(normalized, hit)
                if (hits.size == 8) break
            }
            if (hits.isEmpty()) throw ProviderException(
                ProviderException.Kind.EMPTY_RESPONSE, "豆包搜索未返回带可用摘要或正文的公开 HTTPS 来源；请补充人物身份或作品名后重试"
            )
            return hits.values.toList()
        }

        private fun bounded(text: String, points: Int): String = if (text.codePointCount(0, text.length) <= points) text
            else text.substring(0, text.offsetByCodePoints(0, points))

        private fun httpError(code: Int): ProviderException = when (code) {
            401 -> ProviderException(ProviderException.Kind.AUTH, "豆包搜索 HTTP 401：请检查独立搜索 API Key，火山方舟模型 Key 不能替代它")
            403 -> ProviderException(ProviderException.Kind.AUTH, "豆包搜索 HTTP 403：请检查独立搜索服务的 web 搜索开通状态与 Key 权限")
            429 -> ProviderException(ProviderException.Kind.RATE_LIMIT, "豆包搜索 HTTP 429：请求受限，请稍后重试")
            400 -> ProviderException(ProviderException.Kind.UNSUPPORTED, "豆包搜索 HTTP 400：搜索参数无效，请缩短查询后重试")
            else -> ProviderException(ProviderException.Kind.HTTP, "豆包搜索 HTTP $code，未保存资料")
        }
    }
}
