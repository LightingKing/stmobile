package app.pockettavern.provider

import app.pockettavern.engine.PublicResearch
import app.pockettavern.engine.PublicResearchRequest
import app.pockettavern.engine.PublicResearchResult
import app.pockettavern.engine.PublicResearchSourceHit
import app.pockettavern.engine.ResearchEvidence
import app.pockettavern.engine.ResearchSource
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Ark Responses API with the standard web-search component. This isolated request never includes chat history. */
class DoubaoResearchClient(
    client: OkHttpClient = OkHttpClient(),
    private val allowInsecureHttpForTests: Boolean = false,
    callTimeoutMillis: Long = 210_000L
) {
    init { require(callTimeoutMillis > 0) { "callTimeoutMillis must be positive" } }
    private val client = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private data class ActiveRequest(val call: Call, val userCancelled: AtomicBoolean = AtomicBoolean(false))
    private val activeRequest = AtomicReference<ActiveRequest?>(null)

    fun cancel() { activeRequest.get()?.let { it.userCancelled.set(true); it.call.cancel() } }

    suspend fun research(baseUrl: String, key: String, modelId: String, request: PublicResearchRequest): PublicResearchResult {
        require(request.validate().isEmpty()) { request.validate().joinToString("；") }
        require(key.isNotBlank()) { "请先填写火山方舟 API Key" }
        require(modelId.isNotBlank() && modelId.length <= 200) { "请填写已开通联网搜索的豆包模型或接入点 ID" }
        val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw ProviderException(ProviderException.Kind.INVALID_URL, "豆包搜索地址无效")
        val testLocal = allowInsecureHttpForTests && base.scheme == "http" && base.host == "127.0.0.1"
        if (!testLocal && !isSupportedBaseUrl(baseUrl)) throw ProviderException(
            ProviderException.Kind.INVALID_URL, "豆包搜索需使用火山方舟官方地址 $DEFAULT_BASE_URL"
        )
        val observedAt = Instant.now().toString()
        val http = Request.Builder().url(base.newBuilder().addPathSegment("responses").build())
            .header("Authorization", "Bearer $key").header("Accept", "application/json")
            .post(requestBody(modelId.trim(), request, observedAt).toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(http)
        val active = ActiveRequest(call)
        check(activeRequest.compareAndSet(null, active)) { "已有豆包研究正在进行" }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { active.userCancelled.set(true); call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeRequest.compareAndSet(active, null)
                    val failure = when {
                        active.userCancelled.get() -> CancellationException("豆包研究已停止")
                        e is InterruptedIOException || call.isCanceled() -> ProviderException(ProviderException.Kind.TIMEOUT, "豆包研究超时，未保存任何不完整资料", e)
                        else -> ProviderException(ProviderException.Kind.NETWORK, "豆包研究网络连接失败，未保存资料", e)
                    }
                    continuation.resumeWith(Result.failure(failure))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            checkCancellation(active)
                            if (!response.isSuccessful) {
                                // Only read a small prefix and expose a recognized error code, never provider messages/account details.
                                val errorCode = response.body?.byteStream()?.use { stream ->
                                    val bytes = ByteArrayOutputStream()
                                    val buffer = ByteArray(1024)
                                    while (bytes.size() < 16 * 1024) {
                                        checkCancellation(active)
                                        val count = stream.read(buffer, 0, minOf(buffer.size, 16 * 1024 - bytes.size()))
                                        if (count < 0) break
                                        bytes.write(buffer, 0, count)
                                    }
                                    runCatching { JSONObject(bytes.toString(Charsets.UTF_8.name())).optJSONObject("error")?.optString("code").orEmpty() }.getOrDefault("")
                                }.orEmpty()
                                throw httpError(response.code, errorCode)
                            }
                            val body = response.body ?: throw ProviderException(ProviderException.Kind.EMPTY_RESPONSE, "豆包返回空响应")
                            val bytes = ByteArrayOutputStream()
                            body.byteStream().use { stream ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    checkCancellation(active)
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    if (bytes.size() + count > 2 * 1024 * 1024) throw ProviderException(ProviderException.Kind.IN_BAND, "豆包研究结果超过 2 MB")
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            checkCancellation(active)
                            parseResponse(bytes.toString(Charsets.UTF_8.name()), request, observedAt).also { checkCancellation(active) }
                        }
                    }.recoverCatching { error ->
                        if (active.userCancelled.get()) throw CancellationException("豆包研究已停止")
                        if (error is ProviderException || error is CancellationException) throw error
                        if (error is InterruptedIOException || call.isCanceled()) throw ProviderException(ProviderException.Kind.TIMEOUT, "豆包研究超时，未保存资料")
                        throw ProviderException(ProviderException.Kind.IN_BAND, "豆包研究返回内容无法解析，未保存资料")
                    }
                    activeRequest.compareAndSet(active, null)
                    continuation.resumeWith(result)
                }
            })
        }
    }

    private fun checkCancellation(active: ActiveRequest) {
        if (active.userCancelled.get()) throw CancellationException("豆包研究已停止")
        if (active.call.isCanceled()) throw ProviderException(ProviderException.Kind.TIMEOUT, "豆包研究超时，未保存资料")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
        const val DEFAULT_MODEL = "doubao-seed-2-1-turbo-260628"

        fun isSupportedBaseUrl(raw: String): Boolean = raw.trim().trimEnd('/').toHttpUrlOrNull()?.let {
            it.scheme == "https" && it.host == "ark.cn-beijing.volces.com" && it.port == 443 &&
                it.encodedPath == "/api/v3" && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
        } ?: false

        internal fun requestBody(modelId: String, request: PublicResearchRequest, observedAt: String): JSONObject {
            val schemaExample = """{"subject":"查询对象原文","requestedAt":"$observedAt","items":[{"category":"appearance","text":"资料要点","evidence":"fact","observedAt":"$observedAt","confidence":0.8,"source":{"url":"本次搜索实际返回的 HTTPS URL","title":"标题"},"basis":"推测必须说明依据"}],"warnings":[]}"""
            val instructions = PublicResearch.instructions(request) + "\n" +
                "必须先调用 web_search 搜索，再仅返回一个合法 JSON 对象（不要 Markdown 代码块）。格式示例：$schemaExample\n" +
                "subject 必须原样使用查询参数；只写用户勾选类别，每类最多 4 条，每条不超过 400 字；没查到的信息在 warnings 说明，不写占位废话。" +
                "fact/inference 必须引用本次搜索来源的完整 URL，并保留平台的网页引用标注；禁止使用记忆中猜测的网址。" +
                "fictional 必须写成‘虚构设定：…’，不附伪造来源，且不得混成真人现实事实。\n" +
                "当前检索时间：$observedAt。不要把检索时间当作网页发布日期。"
            return JSONObject().put("model", modelId).put("stream", false).put("store", false)
                .put("max_output_tokens", 10_000).put("max_tool_calls", 3)
                .put("tool_choice", JSONObject().put("type", "web_search"))
                .put("tools", JSONArray().put(JSONObject().put("type", "web_search").put("max_keyword", 2).put("limit", 8)))
                .put("input", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", instructions))
                    .put(JSONObject().put("role", "user").put("content", "请研究以下查询数据：\n${request.toJson()}")))
        }

        internal fun parseResponse(raw: String, request: PublicResearchRequest, observedAt: String): PublicResearchResult {
            val response = runCatching { JSONObject(raw) }.getOrElse {
                throw ProviderException(ProviderException.Kind.IN_BAND, "豆包返回的响应不是合法 JSON")
            }
            if (response.has("error") && !response.isNull("error")) throw ProviderException(ProviderException.Kind.IN_BAND, "豆包研究返回错误，未保存资料")
            if (response.optString("status") != "completed") throw ProviderException(
                ProviderException.Kind.IN_BAND, "豆包研究未完整完成（可能达到输出限制），未保存不完整资料"
            )
            val output = response.optJSONArray("output") ?: throw ProviderException(ProviderException.Kind.EMPTY_RESPONSE, "豆包没有返回研究内容")
            var searched = false
            val texts = mutableListOf<String>()
            val citations = linkedMapOf<String, JSONObject>()
            val profileQuery = PublicResearch.profileUrlSubject(request.subject) != null
            val associatedCitations = linkedSetOf<String>()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                if (item.optString("type") == "web_search_call" && item.optString("status") == "completed") searched = true
                if (item.optString("type") != "message" || item.optString("role") != "assistant") continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") != "output_text") continue
                    if (item.optString("phase") != "commentary") texts += part.optString("text")
                    val annotations = part.optJSONArray("annotations") ?: continue
                    for (k in 0 until annotations.length()) {
                        val annotation = annotations.optJSONObject(k) ?: continue
                        if (annotation.optString("type") != "url_citation") continue
                        val hit = PublicResearchSourceHit(annotation.optString("title"), annotation.optString("url"), annotation.optString("summary"))
                        if (PublicResearch.isSafeSourceHit(hit)) PublicResearch.canonicalSourceUrl(hit.url)?.let { url ->
                            citations[url] = annotation
                            if (profileQuery && PublicResearch.isRelevantSourceHit(hit, request)) associatedCitations += url
                        }
                    }
                }
            }
            if (!searched) throw ProviderException(ProviderException.Kind.UNSUPPORTED, "本次豆包未实际完成联网搜索；请确认已开通联网内容插件及所选模型的工具能力")
            if (!profileQuery && citations.isEmpty()) throw ProviderException(ProviderException.Kind.EMPTY_RESPONSE, "豆包完成搜索但没有返回可用的网页来源，未保存资料；请补充人物身份或作品名后重试")
            if (texts.isEmpty()) throw ProviderException(ProviderException.Kind.EMPTY_RESPONSE, "豆包完成搜索但未返回资料摘要")
            val text = texts.joinToString("\n").trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            if (profileQuery) {
                // Validate the dossier before treating a completed search as a genuine no-match result.
                // A malformed response must remain an error, even when no citation is associated.
                if (PublicResearch.parse(text, request).result == null) throw ProviderException(
                    ProviderException.Kind.IN_BAND, "豆包资料格式或来源校验未通过，未保存；请重试"
                )
                if (associatedCitations.isEmpty()) return unverifiedProfile(request, observedAt)
            }
            val report = PublicResearch.parse(text, request, if (profileQuery) associatedCitations else citations.keys)
            val parsed = report.result ?: if (profileQuery) return unverifiedProfile(request, observedAt) else throw ProviderException(
                ProviderException.Kind.IN_BAND, "豆包资料格式或来源校验未通过，未保存；请重试"
            )
            if (parsed.items.isEmpty()) {
                if (profileQuery) return unverifiedProfile(request, observedAt)
                throw ProviderException(ProviderException.Kind.EMPTY_RESPONSE, "搜索已完成，但没有可保存的相关资料；请补充人物身份或作品名后重试")
            }
            val items = parsed.items.map { item ->
                val citation = item.source?.let { citations[PublicResearch.canonicalSourceUrl(it.url)] }
                item.copy(observedAt = observedAt,
                    source = citation?.let { ResearchSource(it.optString("url"), it.optString("title")) },
                    publishedAt = citation?.optString("publish_time")?.takeIf(String::isNotBlank))
            }
            val missing = request.categories.filter { category -> items.none { it.category == category } }
            val warnings = parsed.warnings + listOf("来源为豆包搜索返回的网页引用；App 未逐页核验，也未进行图片观察。") +
                (if (missing.isEmpty()) emptyList() else listOf("未获得可保存的类别：${missing.joinToString("、") { it.displayName }}")) +
                (if (items.all { it.evidence == ResearchEvidence.FICTIONAL }) listOf("本次未获得可核验资料，保存内容均为虚构设定。") else emptyList())
            return parsed.copy(requestedAt = observedAt, items = items, warnings = warnings.distinct(), networkStatus = "ok")
        }

        private fun unverifiedProfile(request: PublicResearchRequest, observedAt: String) = PublicResearchResult(
            request.subject, observedAt, emptyList(),
            warnings = listOf("本次没有找到与该主页匹配且可保存的来源资料，原链接待核验。"),
            networkStatus = "unverified"
        )

        private fun httpError(code: Int, providerCode: String = ""): ProviderException {
            if (providerCode == "ModelNotOpen") return ProviderException(
                ProviderException.Kind.UNSUPPORTED,
                "豆包搜索 HTTP $code：当前账号尚未开通所选模型，请到火山方舟控制台开通该模型，或填写已开通的模型/接入点 ID"
            )
            return when (code) {
            401 -> ProviderException(ProviderException.Kind.AUTH, "豆包搜索 HTTP 401：火山方舟 API Key 无效或已过期")
            403 -> ProviderException(ProviderException.Kind.AUTH, "豆包搜索 HTTP 403：请在火山方舟开通所选模型和联网内容插件")
            429 -> ProviderException(ProviderException.Kind.RATE_LIMIT, "豆包搜索 HTTP 429：调用受限，请稍后重试")
            400, 404 -> ProviderException(ProviderException.Kind.UNSUPPORTED, "豆包搜索 HTTP $code：请检查模型 ID、接入点及联网搜索能力是否已开通")
            else -> ProviderException(ProviderException.Kind.HTTP, "豆包搜索 HTTP $code，未保存资料")
            }
        }
    }
}
