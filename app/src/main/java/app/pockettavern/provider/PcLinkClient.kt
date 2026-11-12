package app.pockettavern.provider

import app.pockettavern.data.StickerImporter
import app.pockettavern.domain.PcBackup
import app.pockettavern.domain.PcSettings
import app.pockettavern.domain.PcStatus
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.Sticker
import app.pockettavern.domain.StickerPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
        // A fresh connection each time: the PC's Node server closes idle ones after 5 s, before OkHttp
        // would notice, and without retries a reused dead one fails the call (see ProviderClient).
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
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
            features = json.optJSONArray("features").strings(),
            defaultModelId = json.optJSONObject("default_model")?.optString("id").orEmpty().ifBlank { upstream.optString("model") },
            defaultModelName = json.optJSONObject("default_model")?.optString("name").orEmpty(),
            modelsAvailable = if (json.has("models_available") && !json.isNull("models_available")) json.optInt("models_available") else null,
            autoRevise = json.optBoolean("auto_revise"),
            bilibiliLogin = json.optJSONObject("reach")?.takeIf { it.has("bilibili_login") }?.optBoolean("bilibili_login")
        )
    }

    suspend fun settings(provider: ProviderConfig, key: String): PcSettings = withContext(Dispatchers.IO) {
        settingsFrom(parse(call(quick, request(provider, key, "pocket/config").get().build()) { it.body?.string().orEmpty() }))
    }

    /** Sends only [changes]; the PC validates them, writes config.json and applies them at once. */
    suspend fun saveSettings(provider: ProviderConfig, key: String, changes: Map<String, Any>): PcSettings = withContext(Dispatchers.IO) {
        val body = JSONObject().put("settings", JSONObject(changes)).toString().toRequestBody(JSON)
        settingsFrom(parse(call(quick, request(provider, key, "pocket/config").patch(body).build()) { it.body?.string().orEmpty() }))
    }

    private fun settingsFrom(json: JSONObject): PcSettings {
        val settings = json.optJSONObject("settings") ?: throw PcLinkException("电脑没有返回设置内容")
        val defaults = PcSettings()
        return PcSettings(
            upstreamModel = settings.optString("upstreamModel"),
            reasoningEffort = settings.optString("reasoningEffort", defaults.reasoningEffort),
            reviewReasoningEffort = settings.optString("reviewReasoningEffort", defaults.reviewReasoningEffort),
            reviewSkill = settings.optString("reviewSkill", defaults.reviewSkill),
            autoRevise = settings.optBoolean("autoRevise"),
            includeVeniceSystemPrompt = settings.optBoolean("includeVeniceSystemPrompt"),
            upstreamIdleTimeoutSec = settings.optInt("upstreamIdleTimeoutSec", defaults.upstreamIdleTimeoutSec),
            backupKeep = settings.optInt("backupKeep", defaults.backupKeep),
            upstreamHost = json.optJSONObject("fixed")?.optString("upstream_host").orEmpty()
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

    /** One DSH engine command, run on the PC; returns the engine's JSON ({ok, data, error}) as text. */
    suspend fun runDsh(provider: ProviderConfig, key: String, command: String, input: JSONObject): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("command", command).put("input", input).toString().toRequestBody(JSON)
        val text = call(client, request(provider, key, "pocket/dsh").post(body).build()) { it.body?.string().orEmpty() }
        parse(text).toString()
    }

    /** One web search or page read, run by Agent Reach on the PC; the trimmed result JSON comes back as is. */
    suspend fun reach(provider: ProviderConfig, key: String, call: JSONObject): String = withContext(Dispatchers.IO) {
        val text = call(client, request(provider, key, "pocket/reach").post(call.toString().toRequestBody(JSON)).build()) { it.body?.string().orEmpty() }
        parse(text).toString()
    }

    /** Sticker packs kept on the PC. Their files are "<packId>/<NNN.ext>", fetched with [stickerImage]. */
    suspend fun stickerPacks(provider: ProviderConfig, key: String): List<StickerPack> = withContext(Dispatchers.IO) {
        val packs = parse(call(quick, request(provider, key, "stickers").get().build()) { it.body?.string().orEmpty() }).optJSONArray("packs") ?: JSONArray()
        (0 until packs.length()).mapNotNull { packs.optJSONObject(it)?.let(::stickerPack) }
    }

    /**
     * Uploads a pack the phone has read from the user's ZIP: a 4-byte header length, the JSON
     * header, then each image's bytes in order. The PC checks every image and name again.
     */
    suspend fun uploadStickerPack(provider: ProviderConfig, key: String, pack: StickerImporter.Pack, source: String): StickerPack = withContext(Dispatchers.IO) {
        val header = JSONObject().put("name", pack.name).put("license", pack.license).put("source", source.take(120)).put("stickers", JSONArray().apply {
            pack.items.forEach { item ->
                put(JSONObject().put("key", item.key).put("name", item.name).put("description", item.description)
                    .put("tags", JSONArray(item.tags)).put("extension", item.extension).put("size", item.bytes.size))
            }
        }).toString().toByteArray(Charsets.UTF_8)
        val body = java.io.ByteArrayOutputStream(4 + header.size + pack.items.sumOf { it.bytes.size }).apply {
            write(java.nio.ByteBuffer.allocate(4).putInt(header.size).array())
            write(header)
            pack.items.forEach { write(it.bytes) }
        }.toByteArray().toRequestBody(STICKERS)
        stickerPack(parse(call(client, request(provider, key, "stickers/packs").post(body).build()) { it.body?.string().orEmpty() }))
    }

    suspend fun setStickerPackEnabled(provider: ProviderConfig, key: String, id: String, enabled: Boolean): StickerPack = withContext(Dispatchers.IO) {
        val body = JSONObject().put("enabled", enabled).toString().toRequestBody(JSON)
        stickerPack(parse(call(quick, request(provider, key, "stickers/packs/$id").patch(body).build()) { it.body?.string().orEmpty() }))
    }

    suspend fun deleteStickerPack(provider: ProviderConfig, key: String, id: String) = withContext(Dispatchers.IO) {
        call(quick, request(provider, key, "stickers/packs/$id").delete().build()) { }
    }

    /** Blocking: called from the image loader's IO thread. Returns the image bytes, at most 2 MB. */
    fun stickerImage(provider: ProviderConfig, key: String, file: String): ByteArray {
        require(STICKER_FILE.matches(file)) { "表情图片路径无效" }
        return call(quick, request(provider, key, "stickers/files/$file").get().build()) { response ->
            val body = response.body ?: throw PcLinkException("电脑没有返回表情图片")
            val output = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > MAX_STICKER_BYTES) throw PcLinkException("表情图片超过 2 MB")
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray()
        }
    }

    private fun stickerPack(json: JSONObject): StickerPack {
        val stickers = json.optJSONArray("stickers") ?: JSONArray()
        return StickerPack(
            id = json.optString("id"), name = json.optString("name"), license = json.optString("license"), source = json.optString("source"),
            enabled = json.optBoolean("enabled", true),
            importedAt = runCatching { java.time.Instant.parse(json.optString("imported_at")).toEpochMilli() }.getOrDefault(0L),
            stickers = (0 until stickers.length()).mapNotNull { index ->
                stickers.optJSONObject(index)?.let { item ->
                    Sticker(key = item.optString("key"), name = item.optString("name"), description = item.optString("description"),
                        tags = item.optJSONArray("tags").strings(), file = item.optString("file"))
                }?.takeIf { it.key.isNotBlank() && STICKER_FILE.matches(it.file) }
            }
        )
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
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val STICKERS = "application/vnd.pocket-tavern.stickers".toMediaType()
        private const val MAX_STICKER_BYTES = 2 * 1024 * 1024
        private val STICKER_FILE = Regex("[0-9a-f]{12}/\\d{3}\\.(png|jpg|gif|webp)")
    }
}
