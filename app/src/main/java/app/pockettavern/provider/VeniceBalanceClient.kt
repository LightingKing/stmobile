package app.pockettavern.provider

import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.VeniceBalance
import app.pockettavern.domain.VeniceBalanceScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only Venice wallet/allowance lookup.
 *
 * The account endpoint is preferred. Some inference keys cannot access that
 * endpoint, so a 401/403 is retried against the key-scoped rate limit endpoint.
 * The result keeps the scope explicit so a key allowance is never presented as
 * the account's total balance.
 */
class VeniceBalanceClient(
    client: OkHttpClient = OkHttpClient(),
    private val allowInsecureHttpForTests: Boolean = false,
    callTimeoutMillis: Long = 45_000L,
    private val onDiagnostic: (String) -> Unit = {}
) {
    init {
        require(callTimeoutMillis > 0) { "callTimeoutMillis must be positive" }
    }

    private val client = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .eventListenerFactory { call ->
            val trace = call.request().tag(BalanceTrace::class.java)
            object : EventListener() {
                override fun dnsStart(call: Call, domainName: String) { trace?.stage = "dns" }
                override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) { trace?.stage = "connect" }
                override fun secureConnectStart(call: Call) { trace?.stage = "tls" }
                override fun requestHeadersStart(call: Call) { trace?.stage = "request_headers" }
                override fun requestHeadersEnd(call: Call, request: Request) { trace?.stage = "response_headers" }
            }
        }
        .build()
    /** Each provider may be queried independently; a balance lookup must not
     * serialize or block an unrelated provider's lookup. */
    private val activeCalls = Collections.newSetFromMap(ConcurrentHashMap<ActiveCall, Boolean>())

    suspend fun fetch(provider: ProviderConfig, key: String): VeniceBalance {
        if (!provider.kind.equals("venice", ignoreCase = true)) {
            throw VeniceBalanceException("余额查询仅支持 Venice 平台")
        }
        if (key.trim().isBlank()) throw VeniceBalanceException("未配置 Venice API key")
        val base = checkedBaseUrl(provider)
        val account = lookup(provider, base, key, Endpoint.ACCOUNT)
        return when {
            account.balance != null -> account.balance
            account.code == 401 || account.code == 403 -> {
                val scoped = lookup(provider, base, key, Endpoint.KEY_ALLOWANCE)
                if (scoped.code !in 200..299) throw httpFailure(scoped.code)
                requireNotNull(scoped.balance)
            }
            else -> throw httpFailure(account.code)
        }
    }

    private suspend fun lookup(provider: ProviderConfig, base: HttpUrl, key: String, endpoint: Endpoint): LookupResult {
        val url = base.newBuilder().addPathSegments(endpoint.path).build()
        val trace = BalanceTrace(provider, url, key, endpoint)
        trace.emit("balance_started")
        try {
            val response = execute(url, key, trace)
            val balance = if (response.code in 200..299) {
                trace.stage = "parse"
                if (endpoint == Endpoint.ACCOUNT) parseAccount(response.body) else parseKeyAllowance(response.body)
            } else null
            trace.emit("balance_finished", if (balance != null) "complete" else "http_error")
            return LookupResult(response.code, balance)
        } catch (error: Throwable) {
            trace.emit("balance_finished", when {
                error is CancellationException -> "cancelled"
                error is VeniceBalanceException -> error.kind
                else -> "error"
            }, error.javaClass.simpleName)
            throw error
        }
    }

    fun cancel() {
        activeCalls.toList().forEach {
            it.cancelled.set(true)
            it.call.cancel()
        }
    }

    private suspend fun execute(url: HttpUrl, key: String, trace: BalanceTrace): HttpResult = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${key.trim()}")
                .header("Accept", "application/json")
                .tag(BalanceTrace::class.java, trace)
                .get()
                .build()
        )
        val operation = ActiveCall(call)
        activeCalls.add(operation)
        continuation.invokeOnCancellation {
            operation.cancelled.set(true)
            call.cancel()
            activeCalls.remove(operation)
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                activeCalls.remove(operation)
                val failure = when {
                    operation.cancelled.get() -> CancellationException("余额查询已取消")
                    error is InterruptedIOException || call.isCanceled() -> trace.timeoutFailure()
                    else -> VeniceBalanceException("${trace.endpoint.label}网络连接失败（${trace.stageLabel()}）；请检查手机到 api.venice.ai 的连接后手动重试。", kind = "network_error")
                }
                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
            }

            override fun onResponse(call: Call, response: Response) {
                trace.httpStatus = response.code
                trace.firstByteMillis = trace.elapsedMillis()
                trace.stage = "response_headers"
                val result = runCatching {
                    response.use {
                        // Only a successful response has a body we use. Waiting for
                        // a stalled 401/403 error body used to prevent key fallback.
                        val body = if (response.isSuccessful) {
                            trace.stage = "response_body"
                            readBody(response)
                        } else ""
                        if (operation.cancelled.get()) throw CancellationException("余额查询已取消")
                        HttpResult(response.code, body)
                    }
                }.fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { Result.failure(classifyReadFailure(operation, trace, it)) }
                )
                activeCalls.remove(operation)
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }

    private fun classifyReadFailure(operation: ActiveCall, trace: BalanceTrace, error: Throwable): Throwable = when {
        operation.cancelled.get() -> CancellationException("余额查询已取消")
        error is VeniceBalanceException || error is CancellationException -> error
        error is InterruptedIOException || operation.call.isCanceled() -> trace.timeoutFailure()
        else -> VeniceBalanceException("${trace.endpoint.label}响应读取失败；HTTP ${trace.httpStatus} 不代表已获取到余额，请手动重试。", kind = "network_error")
    }

    private fun parseAccount(body: String): VeniceBalance {
        val root = parseJson(body)
        val balances = root.optJSONObject("balances")
            ?: throw VeniceBalanceException("余额响应缺少 balances")
        return VeniceBalance(
            subscriptionCredits = decimalField(balances, "bundledCredits"),
            purchasedCredits = decimalField(balances, "usd", nullMeansZero = true),
            diem = decimalField(balances, "diem"),
            earnedCredits = decimalField(balances, "earnedCredits"),
            scope = VeniceBalanceScope.ACCOUNT,
            canConsume = booleanField(root, "canConsume")
        )
    }

    private fun parseKeyAllowance(body: String): VeniceBalance {
        val root = parseJson(body)
        val data = root.optJSONObject("data")
            ?: throw VeniceBalanceException("Key 额度响应缺少 data")
        val balances = data.optJSONObject("balances")
            ?: throw VeniceBalanceException("Key 额度响应缺少 balances")
        return VeniceBalance(
            subscriptionCredits = decimalField(balances, "BUNDLED_CREDITS"),
            purchasedCredits = decimalField(balances, "USD"),
            diem = decimalField(balances, "DIEM"),
            scope = VeniceBalanceScope.API_KEY,
            canConsume = booleanField(data, "accessPermitted")
        )
    }

    private fun parseJson(body: String): JSONObject = try {
        JSONObject(body)
    } catch (_: Exception) {
        throw VeniceBalanceException("Venice 余额响应格式无效")
    }

    private fun decimalField(obj: JSONObject, name: String, nullMeansZero: Boolean = false): BigDecimal? {
        if (!obj.has(name)) return null
        if (obj.isNull(name)) return if (nullMeansZero) BigDecimal.ZERO else null
        val value = obj.opt(name)
        if (value !is Number) throw VeniceBalanceException("Venice 余额字段格式无效")
        val decimal = runCatching { BigDecimal(value.toString()) }.getOrNull()
            ?: throw VeniceBalanceException("Venice 余额字段格式无效")
        return decimal
    }

    private fun booleanField(obj: JSONObject, name: String): Boolean? = when {
        !obj.has(name) || obj.isNull(name) -> null
        obj.opt(name) is Boolean -> obj.optBoolean(name)
        else -> throw VeniceBalanceException("Venice 余额字段格式无效")
    }

    private fun checkedBaseUrl(provider: ProviderConfig): HttpUrl {
        val url = provider.baseUrl.trim().toHttpUrlOrNull()
            ?: throw VeniceBalanceException("Venice API 地址无效")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            throw VeniceBalanceException("Venice API 地址不能包含账号信息或查询参数")
        }
        val localTest = allowInsecureHttpForTests && url.scheme == "http" && isLocal(url.host)
        if (!localTest && (url.scheme != "https" || url.port != 443 || url.host.lowercase(Locale.ROOT) != "api.venice.ai")) {
            throw VeniceBalanceException("余额查询仅允许官方 api.venice.ai HTTPS 地址")
        }
        return url
    }

    private fun readBody(response: Response): String {
        val body = response.body ?: return ""
        val buffer = Buffer()
        var total = 0L
        body.source().use { source ->
            while (total <= MAX_BODY_BYTES) {
                val read = source.read(buffer, MAX_BODY_BYTES + 1L - total)
                if (read == -1L) break
                total += read
            }
        }
        if (total > MAX_BODY_BYTES) throw VeniceBalanceException("Venice 余额响应超过 256 KiB 限制")
        return buffer.readString(Charsets.UTF_8)
    }

    private fun httpFailure(code: Int): VeniceBalanceException = when (code) {
        401, 403 -> VeniceBalanceException("Venice 余额查询未获授权（HTTP $code）")
        429 -> VeniceBalanceException("Venice 余额查询被限流（HTTP 429）")
        in 500..599 -> VeniceBalanceException("Venice 余额服务暂时不可用（HTTP $code）")
        else -> VeniceBalanceException("Venice 余额查询失败（HTTP $code）")
    }

    private fun isLocal(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1"

    private data class ActiveCall(val call: Call, val cancelled: AtomicBoolean = AtomicBoolean(false))
    private data class HttpResult(val code: Int, val body: String)
    private data class LookupResult(val code: Int, val balance: VeniceBalance?)

    private enum class Endpoint(val path: String, val diagnosticName: String, val label: String) {
        ACCOUNT("billing/balance", "account", "Venice 账户余额查询"),
        KEY_ALLOWANCE("api_keys/rate_limits", "key_allowance", "Venice Key 额度查询")
    }

    private inner class BalanceTrace(
        private val provider: ProviderConfig,
        private val url: HttpUrl,
        private val key: String,
        val endpoint: Endpoint
    ) {
        private val traceId = UUID.randomUUID().toString()
        private val startedAt = System.currentTimeMillis()
        private val startedNanos = System.nanoTime()
        @Volatile var stage = "request"
        @Volatile var httpStatus = 0
        @Volatile var firstByteMillis = -1L

        fun elapsedMillis() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos).coerceAtLeast(0L)

        fun stageLabel() = when (stage) {
            "dns" -> "域名解析"
            "connect" -> "建立连接"
            "tls" -> "TLS 握手"
            "request_headers" -> "发送请求"
            "response_headers" -> "等待响应头"
            "response_body" -> "读取余额响应"
            else -> "等待请求完成"
        }

        fun timeoutFailure(): VeniceBalanceException {
            val seconds = String.format(Locale.ROOT, "%.1f", elapsedMillis() / 1000.0)
            val limit = String.format(Locale.ROOT, "%.1f", client.callTimeoutMillis / 1000.0)
            return VeniceBalanceException("${endpoint.label}超时（${stageLabel()}，已等待 ${seconds}s；单次上限 ${limit}s）。余额未更新；请检查连接后手动重试。", kind = "timeout")
        }

        fun emit(event: String, outcome: String = "", errorType: String = "") {
            val json = JSONObject()
                .put("event", event).put("trace_id", traceId)
                .put("purpose", "venice_balance")
                .put("provider_kind", "venice").put("provider_name", safe(provider.name)).put("provider_id", safe(provider.id))
                .put("api_host", url.host).put("endpoint_path", "/${endpoint.path}")
                .put("phase", "${endpoint.diagnosticName}_$stage")
                .put("started_at_ms", startedAt).put("elapsed_ms", elapsedMillis())
                .put("http_status", httpStatus).put("outcome", outcome).put("error_type", errorType)
                .put("timeout_ms", client.readTimeoutMillis).put("read_timeout_ms", client.readTimeoutMillis)
                .put("connect_timeout_ms", client.connectTimeoutMillis).put("write_timeout_ms", client.writeTimeoutMillis)
                .put("call_timeout_ms", client.callTimeoutMillis)
            if (firstByteMillis >= 0) json.put("first_byte_ms", firstByteMillis)
            runCatching { onDiagnostic(json.toString()) }
        }

        private fun safe(value: String) = value.replace(key.trim(), "[redacted]").take(160)
    }

    companion object {
        private const val MAX_BODY_BYTES = 256 * 1024L
    }
}

class VeniceBalanceException(message: String, cause: Throwable? = null, val kind: String = "invalid_response") : IOException(message, cause)


