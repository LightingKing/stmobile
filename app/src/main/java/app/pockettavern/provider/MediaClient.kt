package app.pockettavern.provider

import app.pockettavern.domain.MediaJob
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private fun newMediaHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(120, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/** Venice typed media transport. Paid asynchronous jobs are deliberately never retried. */
class MediaClient(
    client: OkHttpClient = newMediaHttpClient(),
    private val allowInsecureHttpForTests: Boolean = false
) {
    private val client: OkHttpClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val activeCall = AtomicReference<Call?>(null)

    suspend fun execute(
        provider: ProviderConfig,
        key: String,
        model: ModelInfo,
        requestJson: String,
        outputDir: File
    ): MediaJob = withContext(Dispatchers.IO) {
        val base = checkedBaseUrl(provider)
        val type = model.type.lowercase(Locale.ROOT)
        if (type !in SUPPORTED_TYPES) throw ProviderException(ProviderException.Kind.UNSUPPORTED, "不支持的媒体类型: ${model.type}")
        if (model.id.isBlank()) throw ProviderException(ProviderException.Kind.UNSUPPORTED, "未选择媒体模型")
        val input = parseObject(requestJson)
        val action = input.optString("_action", "").lowercase(Locale.ROOT)
        input.remove("_action")
        // Venice image/upscale selects the upscaler server-side; its JSON schema
        // accepts image, scale, and creativity only and rejects a model field.
        if (type == "upscale") input.remove("model") else input.put("model", model.id)
        validateRequired(type, input)
        val jobBase = MediaJob(providerId = provider.id, modelId = model.id, type = type, requestJson = requestJson)
        when (type) {
            "video", "music" -> {
                when (action) {
                    "quote" -> submitAsync(base, key, type, "quote", input, jobBase)
                    "queue" -> submitAsync(base, key, type, "queue", input, jobBase)
                    else -> jobBase.copy(status = "needs_quote", resultJson = "{\"error\":\"请先报价，再确认提交\"}")
                }
            }
            else -> invokeImmediate(base, key, type, input, jobBase, outputDir)
        }
    }

    suspend fun retrieve(
        provider: ProviderConfig,
        key: String,
        job: MediaJob,
        outputDir: File
    ): MediaJob = withContext(Dispatchers.IO) {
        val base = checkedBaseUrl(provider)
        val type = job.type.lowercase(Locale.ROOT)
        if (type != "video" && type != "music") throw ProviderException(ProviderException.Kind.UNSUPPORTED, "只有视频和音乐任务支持恢复查询")
        if (job.queueId.isBlank()) return@withContext job.copy(status = "unknown", resultJson = "{\"error\":\"缺少 queue_id，无法查询\"}")
        val payload = JSONObject().put("queue_id", job.queueId).put("model", job.modelId)
        val response = post(base, key, typeEndpoint(type, "retrieve"), payload)
        try {
            if (!response.isSuccessful) throw error(response, key)
            val contentType = response.header("Content-Type")
            val isJsonResponse = isJsonResponse(response)
            val bytes = if (isJsonResponse) readLimited(response.body, MAX_JSON_BYTES) else ByteArray(0)
            val body = if (isJsonResponse) bytes.toString(Charsets.UTF_8) else ""
            val current = if (body.isNotBlank()) runCatching { JSONObject(body) }.getOrNull() else null
            val result = mergeResult(job.resultJson, current)
            var file = if (isJsonResponse) result?.let { saveResponse(it, contentType, outputDir, job.id) } else saveResponseBody(response.body, outputDir, job.id, extension(contentType, type))
            // A binary retrieve response, or a JSON response containing a saved binary,
            // is itself completion evidence even when the previous poll said PROCESSING.
            val state = when {
                file != null -> "complete"
                !isJsonResponse -> "unknown"
                else -> result?.let(::statusOf) ?: "complete"
            }
            // The POST body has been consumed. Release its slot before the nested download call.
            activeCall.set(null)
            if (file == null && result != null && state == "complete") {
                val downloadUrl = result.optString("download_url", result.optString("downloadUrl", ""))
                if (downloadUrl.isNotBlank()) file = download(downloadUrl, key, outputDir, job.id, type)
            }
            val summary = when {
                state == "complete" && result == null -> JSONObject().put("status", "COMPLETED")
                state == "complete" && result != null && statusOf(result) != "complete" -> JSONObject(result.toString()).put("status", "COMPLETED")
                else -> result
            }
            job.copy(status = state, resultJson = summary?.let { mediaResultJson(it) } ?: body.take(MAX_RESULT_BYTES).ifBlank { job.resultJson }, filePath = file?.absolutePath ?: job.filePath)
        } finally {
            activeCall.set(null)
            response.close()
        }
    }

    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    private fun invokeImmediate(base: HttpUrl, key: String, type: String, payload: JSONObject, job: MediaJob, outputDir: File): MediaJob {
        val response = if (type == "asr") postAsr(base, key, payload) else post(base, key, typeEndpoint(type, null), payload)
        try {
            if (!response.isSuccessful) throw error(response, key)
            val contentType = response.header("Content-Type")
            val isJsonResponse = isJsonResponse(response)
            val bytes = if (isJsonResponse) readLimited(response.body, MAX_JSON_BYTES) else ByteArray(0)
            val bodyText = if (isJsonResponse) bytes.toString(Charsets.UTF_8) else ""
            val json = if (bodyText.isNotBlank()) runCatching { JSONObject(bodyText) }.getOrNull() else null
            var file = if (json != null) saveResponse(json, contentType, outputDir, job.id) else if (isJsonResponse) null else saveResponseBody(response.body, outputDir, job.id, extension(contentType, type))
            if (json != null && file == null && type in STRUCTURED_JSON_TYPES && bodyText.length > MAX_RESULT_BYTES) {
                file = saveJsonResponse(bodyText, outputDir, job.id)
            }
            val typedStatus = json?.let(::statusOf)?.takeUnless { it == "pending" } ?: "complete"
            val resultJson = when {
                json == null -> bodyText.take(MAX_RESULT_BYTES)
                type in MEDIA_TYPES -> mediaResultJson(json)
                file != null && type in STRUCTURED_JSON_TYPES -> structuredOverview(json, file)
                else -> json.toString()
            }
            return job.copy(status = typedStatus, resultJson = resultJson, filePath = file?.absolutePath.orEmpty())
        } finally {
            activeCall.set(null)
            response.close()
        }
    }

    private fun submitAsync(base: HttpUrl, key: String, type: String, action: String, payload: JSONObject, job: MediaJob): MediaJob {
        val requestPayload = if (action == "quote") quotePayload(type, payload) else payload
        val response = post(base, key, typeEndpoint(type, action), requestPayload)
        try {
            if (!response.isSuccessful) throw error(response, key)
            val body = response.body?.string().orEmpty()
            val result = parseObject(body)
            val queueId = result.optString("queue_id", result.optString("queueId", payload.optString("queue_id")))
            val state = if (action == "quote") "quoted" else statusOf(result).let { if (it == "pending") "queued" else it }
            return job.copy(queueId = queueId, status = state, resultJson = boundedJson(result))
        } finally {
            activeCall.set(null)
            response.close()
        }
    }

    /** Quote endpoints reject generation-only fields; queue keeps the full request. */
    private fun quotePayload(type: String, payload: JSONObject): JSONObject {
        val allowed = if (type == "music") {
            setOf("model", "duration_seconds", "character_count")
        } else {
            setOf("model", "duration", "resolution", "aspect_ratio", "audio", "upscale_factor", "enhancement_model", "target_fps", "slowdown_factor", "video_url", "reference_video_total_duration")
        }
        val result = JSONObject()
        for (name in allowed) if (payload.has(name)) result.put(name, payload.opt(name))
        if (type == "music" && !result.has("character_count")) {
            val prompt = payload.optString("prompt", "")
            if (prompt.isNotEmpty()) result.put("character_count", prompt.length)
        }
        return result
    }

    private fun post(base: HttpUrl, key: String, endpoint: String, payload: JSONObject): Response {
        val cleanKey = key.trim()
        if (cleanKey.isBlank()) throw ProviderException(ProviderException.Kind.AUTH, "请先配置 API key")
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments(endpoint).build())
            .header("Authorization", "Bearer $cleanKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        val call = client.newCall(request)
        if (!activeCall.compareAndSet(null, call)) {
            call.cancel()
            throw ProviderException(ProviderException.Kind.CANCELLED, "已有媒体请求正在执行")
        }
        return try {
            call.execute()
        } catch (e: IOException) {
            activeCall.compareAndSet(call, null)
            if (call.isCanceled()) throw ProviderException(ProviderException.Kind.CANCELLED, "请求已取消", e)
            throw ProviderException(ProviderException.Kind.NETWORK, "网络连接失败", e)
        }
    }

    /** Venice documents STT as multipart/form-data; accept JSON data URLs/base64 in our editor and encode them here. */
    private fun postAsr(base: HttpUrl, key: String, payload: JSONObject): Response {
        val cleanKey = key.trim()
        if (cleanKey.isBlank()) throw ProviderException(ProviderException.Kind.AUTH, "请先配置 API key")
        val encoded = payload.optString("file", payload.optString("audio", payload.optString("base64", "")))
        if (encoded.isBlank()) throw ProviderException(ProviderException.Kind.UNSUPPORTED, "语音转文字需要 file/audio/base64 参数")
        val comma = encoded.indexOf(',')
        val raw = if (encoded.startsWith("data:") && comma > 0) encoded.substring(comma + 1) else encoded
        val bytes = try { java.util.Base64.getMimeDecoder().decode(raw) } catch (e: Exception) { throw ProviderException(ProviderException.Kind.UNSUPPORTED, "音频 base64 无效", e) }
        val mime = payload.optString("mime_type", "application/octet-stream").toMediaType()
        val filePart = bytes.toRequestBody(mime)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", payload.optString("filename", "audio.bin"), filePart)
        val names = payload.names()
        if (names != null) for (i in 0 until names.length()) {
            val name = names.optString(i)
            if (name !in setOf("file", "audio", "base64", "mime_type", "filename", "model")) multipart.addFormDataPart(name, payload.optString(name))
        }
        multipart.addFormDataPart("model", payload.optString("model"))
        val request = Request.Builder().url(base.newBuilder().addPathSegment("audio").addPathSegment("transcriptions").build())
            .header("Authorization", "Bearer $cleanKey")
            .post(multipart.build())
            .build()
        val call = client.newCall(request)
        if (!activeCall.compareAndSet(null, call)) { call.cancel(); throw ProviderException(ProviderException.Kind.CANCELLED, "已有媒体请求正在执行") }
        return try { call.execute() } catch (e: IOException) {
            activeCall.compareAndSet(call, null)
            if (call.isCanceled()) throw ProviderException(ProviderException.Kind.CANCELLED, "请求已取消", e)
            throw ProviderException(ProviderException.Kind.NETWORK, "网络连接失败", e)
        }
    }

    private fun download(rawUrl: String, key: String, outputDir: File, id: String, type: String): File? {
        val url = rawUrl.toHttpUrlOrNull() ?: throw ProviderException(ProviderException.Kind.HTTP, "媒体下载地址无效")
        if (url.scheme != "https" && !(allowInsecureHttpForTests && url.scheme == "http" && isLocal(url.host))) throw ProviderException(ProviderException.Kind.HTTP, "媒体下载地址必须使用 HTTPS")
        // A download URL may be a presigned URL on another host. Never forward the provider key.
        val request = Request.Builder().url(url).get().build()
        val call = client.newCall(request)
        if (!activeCall.compareAndSet(null, call)) { call.cancel(); throw ProviderException(ProviderException.Kind.CANCELLED, "已有媒体请求正在执行") }
        val response = try { call.execute() } catch (e: IOException) {
            activeCall.compareAndSet(call, null)
            if (call.isCanceled()) throw ProviderException(ProviderException.Kind.CANCELLED, "请求已取消", e)
            throw ProviderException(ProviderException.Kind.NETWORK, "媒体下载失败", e)
        }
        try {
            if (!response.isSuccessful) throw ProviderException(ProviderException.Kind.HTTP, "媒体下载 HTTP ${response.code}")
            return saveResponseBody(response.body, outputDir, id, extension(response.header("Content-Type"), type))
        } finally {
            activeCall.set(null)
            response.close()
        }
    }

    private fun checkedBaseUrl(provider: ProviderConfig): HttpUrl {
        if (provider.kind.lowercase(Locale.ROOT) != "venice") throw ProviderException(ProviderException.Kind.UNSUPPORTED, "媒体接口目前仅支持 Venice")
        val url = provider.baseUrl.trim().toHttpUrlOrNull() ?: throw ProviderException(ProviderException.Kind.INVALID_URL, "API 地址无效")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw ProviderException(ProviderException.Kind.INVALID_URL, "API 地址不能包含账号信息")
        if (url.scheme != "https" && !(allowInsecureHttpForTests && url.scheme == "http" && isLocal(url.host))) throw ProviderException(ProviderException.Kind.INVALID_URL, "API 地址必须使用 HTTPS")
        return url
    }

    private fun typeEndpoint(type: String, action: String?): String = when (type) {
        "image" -> "image/generate"
        "inpaint" -> "image/edit"
        "upscale" -> "image/upscale"
        "tts" -> "audio/speech"
        "asr" -> "audio/transcriptions"
        "embedding" -> "embeddings"
        "decision" -> "decisions"
        "video" -> "video/${action ?: "quote"}"
        "music" -> "audio/${action ?: "quote"}"
        else -> throw ProviderException(ProviderException.Kind.UNSUPPORTED, "未知媒体类型: $type")
    }

    private fun validateRequired(type: String, payload: JSONObject) {
        fun requireField(vararg names: String) {
            if (names.none { payload.optString(it, "").isNotBlank() }) throw ProviderException(ProviderException.Kind.UNSUPPORTED, "${type} 缺少必填参数: ${names.joinToString("/")}")
        }
        when (type) {
            "image" -> requireField("prompt")
            "inpaint" -> { requireField("prompt"); requireField("image", "image_url") }
            "upscale" -> requireField("image", "image_url")
            "tts" -> requireField("input", "text")
            "asr" -> requireField("file", "audio", "base64")
            "embedding" -> requireField("input")
            "decision" -> {
                if (!payload.has("state") || payload.opt("state") == JSONObject.NULL || payload.optString("state", "").isBlank()) {
                    throw ProviderException(ProviderException.Kind.UNSUPPORTED, "decision 缺少必填参数: state")
                }
                val questions = payload.optJSONObject("questions")
                if (questions == null || questions.length() == 0) {
                    throw ProviderException(ProviderException.Kind.UNSUPPORTED, "decision 缺少必填参数: questions")
                }
            }
        }
    }

    private fun parseObject(text: String): JSONObject = try { JSONObject(text) } catch (e: Exception) { throw ProviderException(ProviderException.Kind.UNSUPPORTED, "媒体参数必须是 JSON 对象", e) }

    private fun error(response: Response, key: String): ProviderException {
        val body = response.body?.string().orEmpty().replace(key, "[REDACTED]").take(MAX_RESULT_BYTES)
        val kind = when (response.code) {
            401, 403 -> ProviderException.Kind.AUTH
            402 -> ProviderException.Kind.BILLING
            429 -> ProviderException.Kind.RATE_LIMIT
            else -> ProviderException.Kind.HTTP
        }
        return ProviderException(kind, "HTTP ${response.code}${if (body.isBlank()) "" else ": $body"}")
    }

    private fun statusOf(json: JSONObject): String {
        if (json.has("error")) return "failed"
        val value = json.optString("status", json.optString("state", json.optString("message", ""))).lowercase(Locale.ROOT)
        return when {
            value.contains("complete") || value in setOf("succeeded", "success", "ok") -> "complete"
            value.contains("fail") || value.contains("error") -> "failed"
            value.contains("cancel") -> "cancelled"
            value.contains("process") || value.contains("pending") -> "pending"
            value.contains("queue") -> "queued"
            value.isBlank() -> "pending"
            else -> value
        }
    }

    private fun mergeResult(previousJson: String, current: JSONObject?): JSONObject? {
        if (current == null && previousJson.isBlank()) return null
        val merged = runCatching { JSONObject(previousJson) }.getOrElse { JSONObject() }
        if (current != null) {
            val names = current.names()
            if (names != null) for (i in 0 until names.length()) {
                val name = names.optString(i)
                merged.put(name, current.opt(name))
            }
        }
        return merged
    }

    /** Remove already handled base64 media while retaining status and download metadata. */
    private fun mediaResultJson(json: JSONObject): String = boundedJson(sanitizeMediaJson(json) as JSONObject)

    private fun sanitizeMediaJson(value: Any?, field: String? = null, inheritedMime: String? = null): Any? {
        return when (value) {
            is JSONObject -> {
                val localMime = value.optString("mime_type", value.optString("mimeType", inheritedMime.orEmpty()))
                    .takeIf { it.isNotBlank() } ?: inheritedMime
                val output = JSONObject()
                val names = value.names()
                if (names != null) for (i in 0 until names.length()) {
                    val name = names.optString(i)
                    val child = value.opt(name)
                    if (name in BINARY_FIELDS && findBinary(child, name, localMime) != null) continue
                    output.put(name, sanitizeMediaJson(child, name, localMime))
                }
                output
            }
            is JSONArray -> {
                val output = JSONArray()
                for (i in 0 until value.length()) output.put(sanitizeMediaJson(value.opt(i), field, inheritedMime))
                output
            }
            else -> value
        }
    }

    private fun boundedJson(json: JSONObject): String {
        val text = json.toString()
        if (text.length <= MAX_RESULT_BYTES) return text
        val overview = JSONObject().put("truncated", true).put("original_bytes", text.length).put("keys", jsonKeys(json))
        for (name in arrayOf("status", "state", "queue_id", "queueId", "download_url", "downloadUrl", "request_id", "requestId")) {
            if (json.has(name)) overview.put(name, json.opt(name))
        }
        return overview.toString()
    }

    private fun structuredOverview(json: JSONObject, file: File): String = JSONObject()
        .put("saved_file", file.absolutePath)
        .put("truncated", true)
        .put("keys", jsonKeys(json))
        .toString()

    private fun jsonKeys(json: JSONObject): JSONArray {
        val result = JSONArray()
        val names = json.names()
        if (names != null) for (i in 0 until names.length()) result.put(names.optString(i))
        return result
    }

    private fun saveJsonResponse(text: String, outputDir: File, id: String): File {
        val file = safeOutputFile(outputDir, id, "json")
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    private fun saveResponse(json: JSONObject, contentType: String?, outputDir: File, id: String): File? {
        val candidate = findBinary(json) ?: return null
        val bytes = try { java.util.Base64.getMimeDecoder().decode(candidate.encoded) } catch (_: Exception) { return null }
        val detected = detectBinaryExtension(bytes)
        return saveBytes(bytes, outputDir, id, detected ?: extension(candidate.mimeType ?: contentType, "bin"))
    }

    private data class BinaryCandidate(val encoded: String, val mimeType: String?)

    /**
     * Locate only fields used by Venice media responses.  Walking every string and
     * guessing whether it is base64 turns embedding vectors, request ids, and plain
     * text into bogus files, so arbitrary object fields are never decoded here.
     */
    private fun findBinary(value: Any?, field: String? = null, inheritedMime: String? = null): BinaryCandidate? {
        when (value) {
            is JSONObject -> {
                val localMime = value.optString("mime_type", value.optString("mimeType", inheritedMime.orEmpty()))
                    .takeIf { it.isNotBlank() } ?: inheritedMime
                val names = value.names() ?: return null
                for (i in 0 until names.length()) {
                    val name = names.optString(i)
                    val child = value.opt(name)
                    if (name in BINARY_FIELDS || child is JSONObject || child is JSONArray) {
                        findBinary(child, name, localMime)?.let { return it }
                    }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) findBinary(value.opt(i), field, inheritedMime)?.let { return it }
            is String -> {
                if (field == null || field !in BINARY_FIELDS) return null
                val text = value.trim()
                if (text.startsWith("data:", ignoreCase = true)) {
                    val comma = text.indexOf(',')
                    if (comma > 5) return BinaryCandidate(text.substring(comma + 1), text.substring(5, comma).substringBefore(';'))
                } else if (looksBase64Value(text)) {
                    return BinaryCandidate(text, inheritedMime)
                }
            }
        }
        return null
    }

    private fun looksBase64Value(text: String): Boolean = text.length >= 4 && text.length % 4 == 0 && text.matches(Regex("[A-Za-z0-9+/=\\r\\n]+"))

    private fun detectBinaryExtension(bytes: ByteArray): String? {
        fun ascii(offset: Int, value: String): Boolean = offset >= 0 && offset + value.length <= bytes.size &&
            value.indices.all { (bytes[offset + it].toInt() and 0xff) == value[it].code }
        fun starts(vararg values: Int): Boolean = bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 0xff) == values[it] }
        return when {
            starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "png"
            starts(0xff, 0xd8, 0xff) -> "jpg"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "webp"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "wav"
            ascii(0, "fLaC") -> "flac"
            ascii(0, "OggS") -> "ogg"
            ascii(0, "ID3") -> "mp3"
            ascii(0, "MThd") -> "mid"
            ascii(0, "%PDF") -> "pdf"
            bytes.size >= 8 && ascii(4, "ftyp") -> "mp4"
            else -> null
        }
    }

    private fun saveBytes(bytes: ByteArray, outputDir: File, id: String, ext: String): File? {
        if (bytes.isEmpty()) return null
        val file = safeOutputFile(outputDir, id, ext)
        file.writeBytes(bytes)
        return file
    }

    private fun saveResponseBody(body: okhttp3.ResponseBody?, outputDir: File, id: String, ext: String): File? {
        if (body == null) return null
        val file = safeOutputFile(outputDir, id, ext)
        var total = 0L
        try {
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_MEDIA_BYTES) throw ProviderException(ProviderException.Kind.HTTP, "媒体响应超过大小限制")
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file.takeIf { total > 0L }
    }

    private fun readLimited(body: okhttp3.ResponseBody?, maxBytes: Long): ByteArray {
        if (body == null) return ByteArray(0)
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw ProviderException(ProviderException.Kind.HTTP, "媒体 JSON 响应超过大小限制")
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun safeOutputFile(outputDir: File, id: String, ext: String): File {
        if (!outputDir.exists() && !outputDir.mkdirs()) throw ProviderException(ProviderException.Kind.NETWORK, "无法创建媒体目录")
        val safeId = id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "media" }
        val file = File(outputDir, "$safeId.${ext.trimStart('.')}")
        val canonicalDir = outputDir.canonicalFile
        if (file.canonicalFile.parentFile != canonicalDir) throw ProviderException(ProviderException.Kind.NETWORK, "媒体输出路径无效")
        return file
    }

    private fun isJsonResponse(response: Response): Boolean {
        if (response.header("Content-Type")?.contains("json", true) == true) return true
        val body = response.body ?: return false
        val peek = body.source().peek()
        return peek.request(1) && peek.readByte().toInt().toChar() == '{'
    }

    private fun extension(contentType: String?, type: String): String = when {
        contentType?.contains("png", true) == true -> "png"
        contentType?.contains("webp", true) == true -> "webp"
        contentType?.contains("jpeg", true) == true || contentType?.contains("jpg", true) == true -> "jpg"
        contentType?.contains("wav", true) == true -> "wav"
        contentType?.contains("opus", true) == true -> "opus"
        contentType?.contains("aac", true) == true -> "aac"
        contentType?.contains("flac", true) == true -> "flac"
        contentType?.contains("ogg", true) == true -> "ogg"
        contentType?.contains("mpeg", true) == true || contentType?.contains("mp3", true) == true -> "mp3"
        contentType?.contains("mp4", true) == true -> "mp4"
        type == "tts" || type == "music" -> "mp3"
        type == "video" -> "mp4"
        type == "asr" -> "json"
        else -> "bin"
    }

    private fun isLocal(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1"

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val SUPPORTED_TYPES = setOf("image", "inpaint", "upscale", "tts", "asr", "video", "music", "embedding", "decision")
        val MEDIA_TYPES = setOf("image", "inpaint", "upscale", "tts", "asr", "video", "music")
        val STRUCTURED_JSON_TYPES = setOf("embedding", "decision")
        val BINARY_FIELDS = setOf("b64_json", "base64", "data_url", "audio", "image", "images", "video", "file", "image_base64", "audio_base64")
        const val MAX_RESULT_BYTES = 16_384
        const val MAX_JSON_BYTES = 16L * 1024L * 1024L
        const val MAX_MEDIA_BYTES = 512L * 1024L * 1024L
    }
}
