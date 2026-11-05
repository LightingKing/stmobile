package app.pockettavern.provider

import app.pockettavern.domain.PcBackup
import app.pockettavern.domain.PcStatus
import app.pockettavern.domain.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class PcLinkException(message: String, val httpCode: Int = 0, cause: Throwable? = null) : IOException(message, cause)

/**
 * The PC writing server's own endpoints: status and phone backups. Uses the
 * provider's base URL and gateway token, under the same URL policy as chat.
 */
class PcLinkClient(client: OkHttpClient = OkHttpClient(), private val allowInsecureHttpForTests: Boolean = false) {
    // Backups can be large and cross a slow tunnel; only ten minutes without a single byte counts as failed.
    private val client = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(TRANSFER_IDLE_MINUTES, TimeUnit.MINUTES)
        .writeTimeout(TRANSFER_IDLE_MINUTES, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    // Status and the backup list are small: an offline PC should be reported quickly.
    private val quick = this.client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun status(provider: ProviderConfig, key: String): PcStatus = withContext(Dispatchers.IO) {
        val json = parse(call(quick, request(provider, key, "pocket/status").get().build()) { it.body?.string().orEmpty() })
        val upstream = json.optJSONObject("upstream") ?: JSONObject()
        val backups = json.optJSONObject("backups") ?: JSONObject()
        PcStatus(
            version = json.optString("version"), ready = json.optBoolean("ready"),
            upstreamHost = upstream.optString("host"), upstreamModel = upstream.optString("model"),
            reviewSkill = json.optString("review_skill"), activeRequests = json.optInt("active_requests"),
            clientAddress = json.optString("client_address"),
            listenHosts = json.optJSONObject("listen")?.optJSONArray("hosts").strings(),
            uptimeSeconds = json.optLong("uptime_seconds"),
            backupCount = backups.optInt("count"), backupBytes = backups.optLong("total_bytes"), backupKeep = backups.optInt("keep"),
            features = json.optJSONArray("features").strings()
        )
    }

    suspend fun backups(provider: ProviderConfig, key: String): List<PcBackup> = withContext(Dispatchers.IO) {
        val data = parse(call(quick, request(provider, key, "backups").get().build()) { it.body?.string().orEmpty() }).optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(::backup) }
    }

    /** Returns the stored backup and whether the PC already had identical bytes as its newest copy. */
    suspend fun upload(provider: ProviderConfig, key: String, file: File, reason: String, device: String, appVersion: String): Pair<PcBackup, Boolean> = withContext(Dispatchers.IO) {
        val request = request(provider, key, "backups")
            .header("X-Pocket-Device", headerSafe(device))
            .header("X-Pocket-App-Version", headerSafe(appVersion))
            .header("X-Pocket-Backup-Reason", reason)
            .post(file.asRequestBody(ZIP))
            .build()
        val json = parse(call(client, request) { it.body?.string().orEmpty() })
        backup(json) to json.optBoolean("duplicate")
    }

    /** Streams the backup into [target], refusing more than [maxBytes] or bytes that do not match its checksum. */
    suspend fun download(provider: ProviderConfig, key: String, backup: PcBackup, target: File, maxBytes: Long = MAX_BACKUP_BYTES): File = withContext(Dispatchers.IO) {
        call(client, request(provider, key, "backups/${backup.id}").get().build()) { response ->
            val body = response.body ?: throw PcLinkException("电脑没有返回备份内容")
            if (body.contentLength() > maxBytes) throw PcLinkException("备份超过 128 MB，无法在手机上恢复")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw PcLinkException("备份超过 128 MB，无法在手机上恢复")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = response.header("X-Pocket-Sha256").orEmpty().ifBlank { backup.sha256 }
            if (expected.isNotBlank() && !actual.equals(expected, ignoreCase = true)) {
                target.delete()
                throw PcLinkException("下载的备份校验不一致，可能传输中断，请重试")
            }
        }
        target
    }

    private fun base(provider: ProviderConfig): HttpUrl = try {
        ProviderUrlPolicy.parse(provider, allowInsecureHttpForTests)
    } catch (error: IllegalArgumentException) {
        throw PcLinkException(error.message ?: "电脑地址无效")
    }

    private fun request(provider: ProviderConfig, key: String, path: String): Request.Builder {
        if (key.isBlank()) throw PcLinkException("请先在平台页填写电脑写作服务的 Token")
        return Request.Builder().url(base(provider).newBuilder().addPathSegments(path).build()).header("Authorization", "Bearer ${key.trim()}")
    }

    private fun <T> call(transport: OkHttpClient, request: Request, read: (Response) -> T): T {
        val response = try { transport.newCall(request).execute() } catch (error: IOException) {
            throw PcLinkException("连不上电脑上的写作服务。请确认电脑已开机、服务在运行，手机与电脑在同一网络或远程地址可用。", cause = error)
        }
        return response.use {
            if (!it.isSuccessful) throw failure(it)
            try { read(it) } catch (error: PcLinkException) { throw error } catch (error: IOException) {
                throw PcLinkException("与电脑的连接中断，请重试", cause = error)
            }
        }
    }

    private fun failure(response: Response): PcLinkException {
        val message = runCatching { JSONObject(response.body?.string().orEmpty()).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
        val text = when (response.code) {
            401 -> "网关 Token 不正确。请对照电脑上的“手机连接.txt”重新填写这个平台的 API Key。"
            429 -> {
                val minutes = ((response.header("Retry-After")?.toLongOrNull() ?: 900L) + 59) / 60
                "Token 错误次数过多，电脑暂时拒绝了这台设备，请约 $minutes 分钟后再试。"
            }
            404 -> "电脑上的写作服务版本过旧，没有这个功能。请在电脑上更新并重启写作服务。"
            413 -> "备份超过电脑允许的 128 MB。"
            507 -> "电脑磁盘空间不足，备份没有保存。"
            else -> "电脑返回 HTTP ${response.code}" + if (message.isNotBlank()) "：$message" else ""
        }
        return PcLinkException(text, response.code)
    }

    private fun parse(text: String): JSONObject = try { JSONObject(text) } catch (error: JSONException) {
        throw PcLinkException("电脑返回的内容无法识别，这个地址可能不是口袋酒馆写作服务", cause = error)
    }

    private fun backup(json: JSONObject) = PcBackup(
        id = json.optString("id"), createdAt = json.optString("created_at"), size = json.optLong("size"),
        sha256 = json.optString("sha256"), device = json.optString("device"), appVersion = json.optString("app_version"),
        reason = json.optString("reason", "manual")
    )

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

    /** OkHttp only sends printable ASCII header values; device names may be localized. */
    private fun headerSafe(value: String): String = value.filter { it in ' '..'~' }.trim().take(60)

    companion object {
        const val MAX_BACKUP_BYTES = 128L * 1024 * 1024
        const val TRANSFER_IDLE_MINUTES = 10L
        private val ZIP = "application/zip".toMediaType()
    }
}
