package app.pockettavern.provider

import app.pockettavern.domain.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private fun newVeniceBillingHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/**
 * Read-only Venice ledger lookup used to verify a completed inference charge.
 * It intentionally lives beside the provider transport and does not infer a
 * charge from timestamps: only inferenceDetails.requestId is accepted.
 */
class VeniceBillingClient(
    client: OkHttpClient = newVeniceBillingHttpClient(),
    private val allowInsecureHttpForTests: Boolean = false
) {
    private val client = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val activeCall = AtomicReference<Call?>(null)
    private val cancellationRequested = AtomicBoolean(false)

    suspend fun lookup(
        provider: ProviderConfig,
        key: String,
        requestIds: Set<String>,
        startedAtMs: Long,
        endedAtMs: Long
    ): String = withContext(Dispatchers.IO) {
        val ids = requestIds.map(String::trim).filter(String::isNotBlank).toSet()
        if (ids.isEmpty()) return@withContext "无法核对扣款：没有可用 requestId，不按时间猜测。"
        if (startedAtMs < 0L || endedAtMs < startedAtMs) {
            return@withContext "无法核对扣款：查询时间窗口无效，未发起账单请求。"
        }
        if (provider.kind.lowercase() != "venice") {
            return@withContext "无法核对扣款：账单核对仅支持 Venice 平台。"
        }
        val base = try {
            checkedBaseUrl(provider)
        } catch (error: BillingFailure) {
            return@withContext "无法核对扣款：${error.publicMessage}。"
        }
        if (key.trim().isBlank()) return@withContext "无法核对扣款：未配置 Venice API key。"
        cancellationRequested.set(false)

        val totals = LinkedHashMap<String, LedgerTotal>()
        var pageCount = 0
        var cursor: String? = null
        var capped = false
        var malformedMatchedEntry = false
        val seenCursors = HashSet<String>()
        try {
            while (true) {
                val url = base.newBuilder().addPathSegments("billing/usage-history").apply {
                    if (cursor.isNullOrBlank()) {
                        addQueryParameter("pageSize", PAGE_SIZE.toString())
                        addQueryParameter("startTimestamp", isoUtc(startedAtMs))
                        addQueryParameter("endTimestamp", isoUtc(endedAtMs))
                    } else {
                        // The API requires subsequent pages to carry only cursor.
                        addQueryParameter("cursor", cursor)
                    }
                }.build()
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${key.trim()}")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                val response = execute(request)
                try {
                    val body = readBody(response)
                    if (!response.isSuccessful) throw BillingHttpFailure(response.code)
                    val root = try {
                        JSONObject(body)
                    } catch (_: Exception) {
                        throw BillingFailure("账单响应格式无效")
                    }
                    val entries = root.optJSONArray("data")
                        ?: throw BillingFailure("账单响应缺少 data")
                    collectMatches(entries, ids, totals) { malformedMatchedEntry = true }
                    pageCount++
                    val next = nextCursor(root)
                    if (next.isNullOrBlank()) break
                    if (pageCount >= MAX_PAGES || !seenCursors.add(next)) {
                        capped = true
                        break
                    }
                    cursor = next
                } finally {
                    response.close()
                    activeCall.set(null)
                }
            }
            formatResult(startedAtMs, endedAtMs, pageCount, capped, totals, malformedMatchedEntry)
        } catch (_: CancellationException) {
            throw CancellationException("账单核对已取消")
        } catch (error: BillingHttpFailure) {
            formatHttpFailure(error.code)
        } catch (error: BillingFailure) {
            "账单核对未知：${error.publicMessage}。未展示原始响应，无法确认是否扣款。"
        } catch (_: SocketTimeoutException) {
            if (cancellationRequested.get()) throw CancellationException("账单核对已取消")
            "账单核对未知：账单查询超时；账单可能存在同步延迟，无法确认是否扣款。"
        } catch (error: IOException) {
            if (cancellationRequested.get() || activeCall.get()?.isCanceled() == true) {
                throw CancellationException("账单核对已取消")
            }
            "账单核对未知：账单查询网络失败；账单可能存在同步延迟，无法确认是否扣款。"
        } finally {
            activeCall.set(null)
        }
    }

    fun cancel() {
        cancellationRequested.set(true)
        activeCall.getAndSet(null)?.cancel()
    }

    private fun execute(request: Request): okhttp3.Response {
        val call = client.newCall(request)
        if (!activeCall.compareAndSet(null, call)) {
            call.cancel()
            throw BillingFailure("账单查询已在进行中")
        }
        return try {
            call.execute()
        } catch (error: SocketTimeoutException) {
            activeCall.compareAndSet(call, null)
            throw error
        } catch (error: IOException) {
            activeCall.compareAndSet(call, null)
            if (call.isCanceled()) throw CancellationException("账单核对已取消")
            throw error
        }
    }

    private fun checkedBaseUrl(provider: ProviderConfig): HttpUrl {
        val url = provider.baseUrl.trim().toHttpUrlOrNull()
            ?: throw BillingFailure("Venice API 地址无效")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            throw BillingFailure("Venice API 地址不能包含账号信息或查询参数")
        }
        val localTest = allowInsecureHttpForTests && url.scheme == "http" && isLocal(url.host)
        if (!localTest && (url.scheme != "https" || url.host != "api.venice.ai")) {
            throw BillingFailure("账单核对仅允许官方 api.venice.ai HTTPS 地址")
        }
        return url
    }

    private fun readBody(response: okhttp3.Response): String {
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
        if (total > MAX_BODY_BYTES) throw BillingFailure("账单响应超过 1 MiB 限制")
        return buffer.readString(Charsets.UTF_8)
    }

    private fun collectMatches(
        entries: JSONArray,
        requestIds: Set<String>,
        totals: LinkedHashMap<String, LedgerTotal>,
        onMalformed: () -> Unit
    ) {
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val details = entry.optJSONObject("inferenceDetails") ?: continue
            val requestId = details.opt("requestId")
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                ?.takeIf(String::isNotBlank)
                ?: continue
            if (requestId !in requestIds) continue
            val currency = entry.optString("currency").trim().ifBlank { "未知币种" }
            val rawAmount = entry.opt("amount")?.takeUnless { it == JSONObject.NULL }?.toString()
            val amount = rawAmount?.let { runCatching { BigDecimal(it) }.getOrNull() }
            if (amount == null) {
                onMalformed()
                continue
            }
            totals.getOrPut(currency) { LedgerTotal() }.add(amount)
        }
    }

    private fun formatResult(
        startedAtMs: Long,
        endedAtMs: Long,
        pages: Int,
        capped: Boolean,
        totals: LinkedHashMap<String, LedgerTotal>,
        malformedMatchedEntry: Boolean
    ): String {
        val window = "查询窗口 ${isoUtc(startedAtMs)} 至 ${isoUtc(endedAtMs)}"
        if (totals.isEmpty()) {
            return buildString {
                append("未找到与 inferenceDetails.requestId 精确匹配的账单条目；这不等于没有扣款。")
                append(window).append("；已读取 ").append(pages).append(" 页，最多查询").append(MAX_PAGES).append("页")
                append("；账单可能存在同步延迟，当前结果未知。")
                if (malformedMatchedEntry) append("已找到精确匹配条目但金额格式无效，金额未知。")
                if (capped) append("已达到分页上限，后续页面未读取。")
            }
        }
        return buildString {
            append("账单核对完成：按 inferenceDetails.requestId 精确匹配 ")
                .append(totals.values.sumOf { it.count }).append(" 条；").append(window)
                .append("；已读取 ").append(pages).append(" 页。")
            totals.forEach { (currency, total) ->
                append('\n').append(currency).append("：")
                if (total.debitAbs.signum() > 0) append("负金额扣款合计 ").append(decimal(total.debitAbs)).append("；")
                if (total.credit.signum() > 0) append("正金额退款/返还合计 ").append(decimal(total.credit)).append("；")
                if (total.zeroCount > 0) append('0').append(" 金额 ").append(total.zeroCount).append(" 条（无扣款）；")
                append("账本合计 ").append(signedDecimal(total.signed))
            }
            if (malformedMatchedEntry) append("\n有精确匹配条目的金额格式无效，金额合计可能未知。")
            if (capped) append("\n已达到分页上限").append(MAX_PAGES).append("页，后续页面未读取，完整结果仍可能未知。")
        }
    }

    private fun formatHttpFailure(code: Int): String = when (code) {
        401, 403 -> "账单核对未知：账单 API 返回 HTTP $code，平台拒绝账单查询，无法确认是否扣款。可在 Venice 网页账单导出对应时段的 CSV，按响应 ID 核对。"
        429 -> "账单核对未知：账单 API 返回 HTTP 429，查询被限流，无法确认是否扣款。"
        else -> "账单核对未知：账单 API 返回 HTTP $code，未展示原始响应，无法确认是否扣款。"
    }

    private fun nextCursor(root: JSONObject): String? = root.opt("nextCursor")
        ?.takeUnless { it == JSONObject.NULL }
        ?.toString()
        ?.takeIf(String::isNotBlank)

    private fun isoUtc(epochMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))
    private fun decimal(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
    private fun signedDecimal(value: BigDecimal): String = if (value.signum() > 0) "+${decimal(value)}" else decimal(value)
    private fun isLocal(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1"

    private class LedgerTotal {
        var count: Int = 0
        var signed: BigDecimal = BigDecimal.ZERO
        var debitAbs: BigDecimal = BigDecimal.ZERO
        var credit: BigDecimal = BigDecimal.ZERO
        var zeroCount: Int = 0

        fun add(amount: BigDecimal) {
            count++
            signed = signed.add(amount)
            when {
                amount.signum() < 0 -> debitAbs = debitAbs.add(amount.abs())
                amount.signum() > 0 -> credit = credit.add(amount)
                else -> zeroCount++
            }
        }
    }

    private class BillingFailure(val publicMessage: String) : IOException(publicMessage)
    private class BillingHttpFailure(val code: Int) : IOException("HTTP $code")

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 10
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}
