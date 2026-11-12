package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Memory
import app.pockettavern.domain.SkillRecord
import app.pockettavern.data.BuiltinSkills
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ToolResult(val content: String, val memories: List<Memory>? = null)

/** Small, bounded, permission-gated runtime for the first-party mobile tools. */
class ToolRuntime(
    private val workspace: File,
    private val resourceReader: (SkillRecord, String) -> String? = { skill, path -> skill.files[path] },
    private val credentialResolver: (String) -> String = { "" }
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val root: File = workspace.canonicalFile

    fun definitions(permissions: List<String>): String = definitionsForPermissions(permissions.toSet())

    /** Cancels any in-flight network tool call so the UI Stop action is responsive. */
    fun cancel() {
        client.dispatcher.cancelAll()
    }

    /** Marks a request with the chat it serves, so stopping one chat leaves the others' calls alone. */
    private class ChatTag(val chatId: String)

    /** Cancels only [chatId]'s in-flight network tool call. */
    fun cancel(chatId: String) {
        client.dispatcher.runningCalls().filter { it.request().tag(ChatTag::class.java)?.chatId == chatId }.forEach { it.cancel() }
    }

    suspend fun execute(
        name: String,
        argumentsJson: String,
        skills: List<SkillRecord>,
        data: AppData,
        chat: Chat
    ): ToolResult = withContext(Dispatchers.IO) {
        if (argumentsJson.toByteArray(Charsets.UTF_8).size > MAX_ARGUMENT_BYTES) {
            return@withContext ToolResult("工具参数超过大小限制")
        }
        // An empty selection means that this chat has no Skills enabled. Never treat
        // it as a wildcard: callers may pass the installed catalog for convenience,
        // but only an explicit per-chat selection can grant a tool.
        val enabled = activeSkills(skills, data.settings, chat)
        if (name == "skill_read" && enabled.isEmpty()) return@withContext ToolResult("工具权限被拒绝：没有启用的 Skill")
        val grants = enabled.flatMap { it.permissions }.toSet()
        val required = permissionFor(name)
        val skillReadGranted = name == "skill_read" && enabled.any(::hasReferences)
        if (required != null && required !in grants && !skillReadGranted) return@withContext ToolResult("工具权限被拒绝：$name 需要 $required")
        try {
            executeChecked(name, JSONObject(argumentsJson), enabled, data, chat)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            ToolResult("工具执行失败：${failure.message ?: "参数无效"}")
        }
    }

    private suspend fun executeChecked(name: String, args: JSONObject, skills: List<SkillRecord>, data: AppData, chat: Chat): ToolResult {
        return when (name) {
            "history_search" -> ToolResult(KnowledgeVault.searchJson(chat, args.optString("query")))
            "history_read" -> ToolResult(KnowledgeVault.read(chat, args.optString("id"), args.optInt("offset", 0)))
            "workspace_list" -> listWorkspace(args)
            "workspace_read" -> readWorkspace(args)
            "workspace_write" -> writeWorkspace(args)
            "web_fetch" -> networkRequest(args, "GET", data, chat, false)
            "http_request" -> networkRequest(args, args.optString("method", "GET").uppercase(), data, chat, true)
            "public_research" -> publicResearch(args, chat)
            "memory_search" -> memorySearch(args, data, chat)
            "memory_save" -> memorySave(args, data, chat)
            "skill_read" -> readSkill(args, skills)
            "skill_list" -> ToolResult(SkillRuntimeSnapshot.manifest(skills, toolsSupported = true))
            else -> ToolResult("未知工具：$name")
        }
    }

    private fun listWorkspace(args: JSONObject): ToolResult {
        val dir = safePath(args.optString("path", ""), false)
        if (!dir.isDirectory) return ToolResult("工作区目录不存在")
        val result = JSONArray()
        dir.listFiles()?.filterNot(::isSecretPath)?.sortedBy { it.name }?.take(MAX_LIST_ITEMS)?.forEach { file ->
            result.put(JSONObject().put("name", file.name).put("directory", file.isDirectory).put("size", file.length()))
        }
        return ToolResult(result.toString())
    }

    private fun readWorkspace(args: JSONObject): ToolResult {
        val file = safePath(args.optString("path"), true)
        if (!file.isFile) return ToolResult("工作区文件不存在")
        if (file.length() > MAX_RESULT_BYTES) return ToolResult("文件超过读取大小限制")
        return ToolResult(file.readText(Charsets.UTF_8).take(MAX_RESULT_BYTES))
    }

    private fun writeWorkspace(args: JSONObject): ToolResult {
        val file = safePath(args.optString("path"), true)
        val content = args.optString("content", "")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_WRITE_BYTES) return ToolResult("写入内容超过大小限制")
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return ToolResult("已写入 ${root.toPath().relativize(file.toPath())}")
    }

    private fun safePath(raw: String, fileExpected: Boolean): File {
        if (raw.isBlank() && fileExpected) throw IllegalArgumentException("缺少 path")
        val candidate = if (raw.isBlank()) root else File(root, raw)
        val canonical = candidate.canonicalFile
        val rootPath = root.toPath()
        if (!canonical.toPath().startsWith(rootPath)) throw SecurityException("路径超出工作区")
        if (isSecretPath(canonical)) throw SecurityException("禁止访问敏感文件")
        if (fileExpected && canonical.exists() && canonical.isDirectory) throw IllegalArgumentException("path 必须是文件")
        return canonical
    }

    private fun networkRequest(args: JSONObject, method: String, data: AppData, chat: Chat, allowProvider: Boolean): ToolResult {
        val providerId = args.optString("providerId")
        if (providerId.isNotBlank() && !allowProvider) throw SecurityException("web_fetch 不支持 Provider 认证")
        val url = if (providerId.isNotBlank()) providerUrl(args, providerId, data, chat) else args.optString("url")
        val uri = URI(url)
        if (uri.scheme?.lowercase() != "https") throw SecurityException("网络工具只允许 HTTPS")
        if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) throw IllegalArgumentException("不支持的 HTTP 方法")
        val builder = Request.Builder().url(url).tag(ChatTag::class.java, ChatTag(chat.id))
        val headers = args.optJSONObject("headers")
        if (headers != null) {
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.lowercase() !in SAFE_HEADERS) throw SecurityException("禁止模型设置敏感或未批准的请求头")
                builder.header(key, headers.optString(key))
            }
        }
        val bodyText = args.optString("body", "")
        if (bodyText.toByteArray(Charsets.UTF_8).size > MAX_WRITE_BYTES) throw IllegalArgumentException("请求体超过大小限制")
        if (method == "GET" && bodyText.isNotBlank()) throw IllegalArgumentException("GET 请求不支持 body")
        val credential = if (providerId.isNotBlank()) {
            val key = try {
                credentialResolver(providerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                throw IllegalStateException("Provider credential unavailable")
            }
            if (key.isBlank()) throw IllegalStateException("Provider 未配置 API key")
            val provider = data.providers.first { it.id == providerId }
            if (provider.kind.equals("anthropic", true) || provider.baseUrl.contains("anthropic", true)) {
                builder.header("x-api-key", key)
                builder.header("anthropic-version", "2023-06-01")
            } else {
                builder.header("Authorization", "Bearer $key")
            }
            key
        } else null
        val body = if (method == "GET") null
        else bodyText.toRequestBody((headers?.optString("Content-Type") ?: "application/json").toMediaTypeOrNull())
        builder.method(method, body)
        client.newCall(builder.build()).execute().use { response ->
            val responseText = response.body?.byteStream()?.use(::readBounded).orEmpty()
            val text = credential?.takeIf { it.isNotBlank() }?.let { responseText.replace(it, "[REDACTED]") } ?: responseText
            return ToolResult(JSONObject().put("status", response.code).put("body", text).toString())
        }
    }

    /** Read-only public web search. Search snippets are returned as sources, never as asserted facts. */
    private suspend fun publicResearch(args: JSONObject, chat: Chat): ToolResult {
        val categories = args.optJSONArray("categories")?.let { values ->
            (0 until values.length()).mapNotNull { ResearchCategory.fromWire(values.optString(it)) }
        }?.ifEmpty { ResearchCategory.values().toList() } ?: ResearchCategory.values().toList()
        val request = PublicResearchRequest(
            subject = args.optString("subject").trim(),
            categories = categories.distinct(),
            query = args.optString("query").trim(),
            allowFiction = args.optBoolean("allowFiction", true),
            asOf = args.optString("asOf").trim().takeIf(String::isNotBlank)
        )
        request.validate().takeIf { it.isNotEmpty() }?.let { return ToolResult(JSONObject().put("status", "invalid").put("errors", JSONArray(it)).toString()) }
        val result = try {
            var lastFailure: Throwable? = null
            var found: JSONObject? = null
            for (searchUrl in PublicResearch.searchUrls(request)) {
                currentCoroutineContext().ensureActive()
                try {
                    val http = Request.Builder().url(searchUrl).tag(ChatTag::class.java, ChatTag(chat.id)).header("Accept", "text/html").header("User-Agent", "PocketTavern/0.1 public-research").get().build()
                    client.newCall(http).execute().use { response ->
                        val html = response.body?.byteStream()?.use(::readBounded).orEmpty()
                        if (!response.isSuccessful) throw IllegalStateException("公开搜索 HTTP ${response.code}")
                        val hits = PublicResearch.parseSearchHtml(html).filter(PublicResearch::isSafeSourceHit)
                            .filter { PublicResearch.isRelevantSourceHit(it, request) }
                        if (hits.isEmpty()) throw IllegalStateException("公开搜索没有可解析结果")
                        val sources = JSONArray()
                        hits.forEach { hit -> sources.put(JSONObject().put("title", hit.title).put("url", hit.url).put("snippet", hit.snippet)) }
                        found = JSONObject().put("status", "ok").put("subject", request.subject).put("requestedAt", Instant.now().toString()).put("sources", sources)
                            .put("note", "这些是公开网页检索来源，不是已核实事实；请逐条标记 fact/inference/fictional 后再写入剧情。")
                    }
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    currentCoroutineContext().ensureActive()
                    lastFailure = failure
                }
                if (found != null) break
            }
            found ?: throw (lastFailure ?: IllegalStateException("公开搜索失败"))
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            currentCoroutineContext().ensureActive()
            val fallback = PublicResearch.fallback(request, failure.message ?: "公开搜索失败")
            fallback.toJson().put("status", "failed")
        }
        // Search results are evidence in the tool turn, not user-confirmed memory.
        return ToolResult(result.toString())
    }

    private fun providerUrl(args: JSONObject, providerId: String, data: AppData, chat: Chat): String {
        if (chat.providerId.isNotBlank() && providerId != chat.providerId) throw SecurityException("Provider 必须是当前会话已选择的 Provider")
        val provider = data.providers.firstOrNull { it.id == providerId } ?: throw IllegalArgumentException("Provider 不存在")
        val path = args.optString("path")
        val pathUri = runCatching { URI(path) }.getOrNull()
        val rawPath = pathUri?.rawPath.orEmpty()
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") || pathUri == null || pathUri.isAbsolute || pathUri.host != null || pathUri.fragment != null || rawPath.split('/').any { it == ".." || it.isBlank() } || containsEncodedTraversal(rawPath)) {
            throw SecurityException("Provider API path 无效")
        }
        val base = URI(provider.baseUrl)
        if (base.scheme?.lowercase() != "https" || base.host.isNullOrBlank()) throw SecurityException("Provider base URL 必须是 HTTPS")
        val basePath = base.path.trimEnd('/')
        val baseDirectory = URI(base.scheme, base.authority, "$basePath/", null, null)
        val resolved = baseDirectory.resolve(pathUri)
        val sameOrigin = resolved.scheme.equals(base.scheme, true) && resolved.host.equals(base.host, true) && resolved.port == base.port && resolved.userInfo == base.userInfo
        val resolvedPath = resolved.path
        val requiredPrefix = if (basePath.isBlank()) "/" else "$basePath/"
        if (!sameOrigin || !(resolvedPath == basePath || resolvedPath.startsWith(requiredPrefix)) || containsEncodedTraversal(resolved.rawPath.orEmpty())) {
            throw SecurityException("Provider API path 超出 base URL")
        }
        return resolved.toString()
    }

    private fun containsEncodedTraversal(rawPath: String): Boolean {
        var decoded = rawPath
        repeat(3) {
            decoded = runCatching { URLDecoder.decode(decoded, StandardCharsets.UTF_8.name()) }.getOrNull() ?: return true
            if (decoded.split('/').any { it == ".." }) return true
        }
        return false
    }

    private fun readBounded(input: InputStream): String {
        val output = ByteArrayOutputStream(MAX_RESULT_BYTES)
        val buffer = ByteArray(8192)
        var total = 0
        while (total < MAX_RESULT_BYTES) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_RESULT_BYTES - total))
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private fun memorySearch(args: JSONObject, data: AppData, chat: Chat): ToolResult {
        val query = args.optString("query").trim()
        if (query.isBlank()) return ToolResult("query 不能为空")
        val terms = query.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        val excludedSources = chat.messages.filter { it.contextExcluded }.map { it.id }.toSet()
        val matches = data.memories.filter { memory ->
            memory.enabled && memory.sourceMessageId !in excludedSources && (memory.scope.equals("global", true) ||
                (memory.scope.equals("chat", true) && memory.targetId.isNotBlank() && memory.targetId == chat.id) ||
                (memory.scope.equals("character", true) && memory.targetId.isNotBlank() && memory.targetId == chat.characterId)) &&
                terms.any { memory.text.lowercase().contains(it) }
        }.take(MAX_MEMORY_RESULTS)
        val result = JSONArray()
        matches.forEach { result.put(JSONObject().put("id", it.id).put("text", it.text).put("scope", it.scope).put("targetId", it.targetId)) }
        return ToolResult(result.toString())
    }

    private fun memorySave(args: JSONObject, data: AppData, chat: Chat): ToolResult {
        val text = args.optString("text").trim()
        if (text.isBlank()) return ToolResult("text 不能为空")
        if (text.codePointCount(0, text.length) > MAX_MEMORY_TEXT_CHARS) return ToolResult("记忆文本超过大小限制")
        if (data.memories.size >= MAX_MEMORY_ENTRIES) return ToolResult("记忆条目数量已达到限制")
        val totalTextBytes = data.memories.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() }
        if (totalTextBytes + text.toByteArray(Charsets.UTF_8).size > MAX_MEMORY_TEXT_BYTES) return ToolResult("记忆总文本超过大小限制")
        val requestedGlobal = args.optString("scope").equals("global", true)
        val scope = "chat"
        val target = chat.id
        val memory = Memory(text = text, scope = scope, targetId = target, sourceMessageId = chat.messages.lastOrNull()?.id.orEmpty())
        val note = if (requestedGlobal) "（模型不能直接提升为全局记忆，请在记忆管理中由用户手动提升）" else ""
        return ToolResult("已保存会话记忆：${memory.text}$note", listOf(memory))
    }

    private fun readSkill(args: JSONObject, skills: List<SkillRecord>): ToolResult {
        // Models frequently pass the display name in skillId, so accept either form.
        val key = args.optString("skillId").ifBlank { args.optString("skill") }.trim()
        val skill = skills.firstOrNull { it.id == key } ?: skills.firstOrNull { it.name == key }
            ?: return ToolResult("未找到已启用的 Skill：$key。当前会话可用：" + skills.joinToString("；") { "skillId=${it.id}（${it.name}）" })
        var requested = args.optString("path").replace('\\', '/').trim().trimEnd('/')
        while (requested.startsWith("./")) requested = requested.removePrefix("./")
        if (requested.isBlank()) requested = "SKILL.md"
        if (requested.startsWith('/') || requested.split('/').any { it == "." || it == ".." || it.isBlank() }) return ToolResult("Skill 路径无效")
        // Large reference trees are summarised per folder in the prompt; a folder path lists it.
        val inFolder = referencePaths(skill).filter { it.startsWith("$requested/") }
        if (inFolder.isNotEmpty() && skill.files[requested] == null) {
            return ToolResult("目录 $requested/ 下共 ${inFolder.size} 个文件：\n" + inFolder.take(MAX_LIST_ITEMS).joinToString("\n") { "- $it" })
        }
        val content = readSkillResource(skill, requested) ?: if (requested == "SKILL.md") skill.content else null
            ?: return ToolResult("Skill 引用文件不存在：$requested。可读取：" + (listOf("SKILL.md") + referencePaths(skill)).joinToString("、"))
        if (content.startsWith("base64:")) return ToolResult("该 Skill 文件是二进制内容，无法作为文本读取：$requested")
        return ToolResult(content.take(MAX_RESULT_BYTES))
    }

    private fun readSkillResource(skill: SkillRecord, path: String): String? {
        val direct = runCatching { resourceReader(skill, path) }
        if (direct.getOrNull() != null) return direct.getOrNull()
        val assetBacked = skill.files[path]?.startsWith(BuiltinSkills.ASSET_PREFIX) == true
        val originalId = if (assetBacked) DUPLICATE_SKILL_ID.matchEntire(skill.id)?.groupValues?.get(1) else null
        if (originalId != null) return resourceReader(skill.copy(id = originalId), path)
        return direct.getOrThrow()
    }

    companion object {
        private const val MAX_RESULT_BYTES = 100_000
        private const val MAX_WRITE_BYTES = 500_000
        private const val MAX_ARGUMENT_BYTES = 64 * 1024
        private const val MAX_MEMORY_TEXT_CHARS = 8_000
        private const val MAX_MEMORY_ENTRIES = 2_000
        private const val MAX_MEMORY_TEXT_BYTES = 4L * 1024 * 1024
        private const val MAX_LIST_ITEMS = 500
        private const val MAX_MEMORY_RESULTS = 20
        private val DUPLICATE_SKILL_ID = Regex("^(.+)-duplicate-\\d+$")
        private val SAFE_HEADERS = setOf("accept", "content-type", "user-agent", "cache-control")

        /** Skills that this chat explicitly selected and that the user is allowed to use. */
        fun activeSkills(skills: List<SkillRecord>, settings: AppSettings, chat: Chat): List<SkillRecord> {
            return skillsInOrder(skills, chat.skillIds)
                .filter { it.enabled && (!it.adult || settings.adultSkillsConfirmed) }
        }

        /** Resolves installed Skills in the exact order stored by the chat. */
        fun skillsInOrder(skills: List<SkillRecord>, skillIds: List<String>): List<SkillRecord> {
            val installed = skills.associateBy { it.id }
            return skillIds.distinct().mapNotNull(installed::get)
        }

        /** Files the model may request through skill_read besides the SKILL.md body. */
        fun referencePaths(skill: SkillRecord): List<String> =
            skill.files.keys.filter { it != "SKILL.md" && it != "LICENSE" }.sorted()

        fun hasReferences(skill: SkillRecord): Boolean = referencePaths(skill).isNotEmpty()

        /** Listing is always read-only; skill_read is implied by bundled references. */
        fun definitionsForSkills(skills: List<SkillRecord>): String =
            definitionsForPermissions(skills.flatMap { it.permissions }.toSet(), skills.any(::hasReferences))

        internal fun definitionsForPermissions(permissions: Set<String>, skillRead: Boolean = false): String {
            val tools = JSONArray()
            fun add(name: String, description: String, properties: JSONObject, required: List<String> = emptyList()) {
                val allowed = name == "skill_list" || permissionFor(name)?.let(permissions::contains) == true || (name == "skill_read" && skillRead)
                if (!allowed) return
                tools.put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", name).put("description", description)
                    .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)))))
            }
            add("workspace_list", "列出应用工作区中的文件", JSONObject().put("path", JSONObject().put("type", "string")))
            add("workspace_read", "读取应用工作区中的 UTF-8 文件", JSONObject().put("path", JSONObject().put("type", "string")), listOf("path"))
            add("workspace_write", "写入应用工作区中的 UTF-8 文件", JSONObject().put("path", JSONObject().put("type", "string")).put("content", JSONObject().put("type", "string")), listOf("path", "content"))
            add("web_fetch", "获取 HTTPS 网页", JSONObject().put("url", JSONObject().put("type", "string")), listOf("url"))
            add("http_request", "向公共 HTTPS URL 或已配置 Provider API 发起受限请求", JSONObject()
                .put("url", JSONObject().put("type", "string")).put("providerId", JSONObject().put("type", "string"))
                .put("path", JSONObject().put("type", "string")).put("method", JSONObject().put("type", "string"))
                .put("body", JSONObject().put("type", "string")))
            if (permissions.contains("network")) tools.put(PublicResearch.toolDefinition())
            add("memory_search", "检索当前会话可见的记忆", JSONObject().put("query", JSONObject().put("type", "string")), listOf("query"))
            add("memory_save", "保存一条属于当前会话的记忆；是否提升为全局记忆由用户在界面中决定", JSONObject().put("text", JSONObject().put("type", "string")), listOf("text"))
            add("skill_read", "读取当前会话已启用 Skill 的引用文件（系统提示中列出的 skillId 与文件路径）；path 填目录名时列出该目录下的文件", JSONObject()
                .put("skillId", JSONObject().put("type", "string").put("description", "系统提示中【Skill 引用文件】给出的 skillId"))
                .put("path", JSONObject().put("type", "string").put("description", "引用文件的相对路径，例如 references/guide.md；也可填目录，例如 content")),
                listOf("skillId", "path"))
            add("skill_list", "只读查询本轮真实启用的 Skills 数量、ID、名称与顺序；区分正文注入和引用读取。本工具不修改开关，也不证明写作规则已执行。", JSONObject())
            return tools.toString()
        }

        private fun permissionFor(name: String): String? = when (name) {
            "workspace_list", "workspace_read", "skill_read" -> "files.read"
            "workspace_write" -> "files.write"
            "web_fetch", "http_request", "public_research" -> "network"
            "memory_search", "memory_save" -> "memory"
            else -> null
        }

        private fun isSecretPath(file: File): Boolean {
            for (component in file.toPath()) {
                val name = component.toString().lowercase()
                if (name == ".env" || name.startsWith(".env.") || name.endsWith(".pem") || name.endsWith(".key") ||
                    name.contains("secret") || name.contains("password") || name.contains("token") || name.contains("credential")) return true
            }
            return false
        }
    }
}
