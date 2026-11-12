package app.pockettavern

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import androidx.lifecycle.viewModelScope
import app.pockettavern.data.*
import app.pockettavern.domain.*
import app.pockettavern.engine.*
import app.pockettavern.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

interface NativeHost {
    fun requestGenerationNotifications() { }
    fun importFile(kind: String)
    fun exportText(name: String, mime: String, content: String)
    fun exportBinary(name: String, mime: String, file: File)
    fun pickAttachment()
    fun openFile(file: File, mime: String)
    fun openDoubaoWebResearch(requestId: String, chatId: String, subject: String, categories: List<String>, allowFiction: Boolean, prompt: String) { }
    fun pickAvatar(characterId: String) { }
}

internal fun applyChatSkillUpdate(
    data: AppData,
    chatId: String,
    skillIds: List<String>,
    summaries: List<Summary>,
    updatedAt: Long = System.currentTimeMillis()
): AppData {
    val chat = data.chats.firstOrNull { it.id == chatId } ?: error("会话不存在：$chatId")
    val requested = skillIds.distinct()
    val installedIds = ToolRuntime.skillsInOrder(data.skills, requested).map { it.id }
    val missing = requested.filterNot(installedIds.toSet()::contains)
    require(missing.isEmpty()) { "Skill 不存在：${missing.joinToString()}" }
    val blocked = if (data.settings.adultSkillsConfirmed) emptySet() else data.skills.filter { it.adult }.mapTo(mutableSetOf()) { it.id }
    val allowed = requested.filterNot(blocked::contains)
    val newlySelected = allowed.toSet() - chat.skillIds.toSet()
    return data.copy(
        chats = data.chats.map { current ->
            if (current.id == chatId) current.copy(skillIds = allowed, summaries = summaries, updatedAt = updatedAt) else current
        },
        skills = data.skills.map { skill ->
            if (skill.id in newlySelected && !skill.enabled) skill.copy(enabled = true) else skill
        }
    )
}

/**
 * Chooses the conversation to restore when the process starts.  A persisted
 * selection wins as long as that chat still exists; old installs which have
 * no selection fall back to the most recently changed conversation.
 */
internal fun preferredChatId(chats: List<Chat>, persistedId: String = ""): String =
    chats.firstOrNull { it.id == persistedId }?.id
        ?: chats.maxByOrNull { it.updatedAt }?.id.orEmpty()

/**
 * One long task of one chat: its coroutine, its own model connection and its stop reason. It rides
 * in the coroutine context, so code deep inside a generation reaches the right connection.
 */
private class ChatRun(
    val chatId: String,
    val kind: String,
    val background: Boolean,
    newClient: (ChatRun) -> ProviderClient
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ChatRun>
    /** What the request log calls this run's current request. */
    val purpose = java.util.concurrent.atomic.AtomicReference("chat")
    private val lazyClient = lazy { newClient(this) }
    val client: ProviderClient by lazyClient
    var job: Job? = null
    var stopReason: String? = null
    var queued = false
    fun cancelRequest() { if (lazyClient.isInitialized()) client.cancel() }
}

/**
 * One lease shared by every holder: opened by the first, closed after the last. The controller
 * uses it for the foreground service that keeps background replies alive.
 */
internal class SharedLease<T : Any>(private val open: () -> T, private val close: (T) -> Unit) {
    var current: T? = null
        private set
    private var holders = 0
    fun acquire(): T { holders++; return current ?: open().also { current = it } }
    fun release() {
        if (holders == 0) return
        if (--holders == 0) current?.let { current = null; close(it) }
    }
}

class AppController(application: Application) : AndroidViewModel(application), AppActions {
    private val app = application as PocketApplication
    private val sessionPreferences = app.getSharedPreferences(SESSION_PREFERENCES, Application.MODE_PRIVATE)
    private val installedVersion = app.packageManager.getPackageInfo(app.packageName, 0).let {
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
        "${it.versionName} ($code)"
    }
    private val mutable = MutableStateFlow(UiSession(appVersion=installedVersion))
    val state = mutable.asStateFlow()
    private val pcStore = SharedPreferencesPcLinkStore(app.getSharedPreferences("pc_link", Application.MODE_PRIVATE))
    private val pcClient = PcLinkClient()
    private val pcRouter = PcRouter()
    // Leaving or reaching home changes the network: the LAN check runs again on the next request.
    private val networkWatch = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = pcRouter.forget()
        override fun onLost(network: android.net.Network) = pcRouter.forget()
    }
    private val pcBackups = PcBackups(pcClient, pcStore, app.filesDir, app.cacheDir, "${Build.MANUFACTURER} ${Build.MODEL}".trim(), installedVersion)
    init { mutable.value = mutable.value.copy(pcLink = pcLinkFromStore(PcLinkState())) }
    init { runCatching { app.getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkWatch) } }
    var host: NativeHost? = null
    private val requestLogs by lazy { RequestLogStore(File(app.filesDir, "diagnostics"), installedVersion, Build.VERSION.SDK_INT) }
    private val compactCheckpoints by lazy { CompactCheckpointStore(File(app.filesDir, "compact-pending")) }
    @Volatile private var requestPurpose = "chat"
    @Volatile var uiVisible = false
        set(value) { field = value; if (value) markOpenChatRead() }
    private val providerClient = ProviderClient(onDiagnostic = { event ->
        runCatching { requestLogs.record(JSONObject(event).put("purpose", requestPurpose)
            .put("app_state", if (uiVisible) "foreground" else "background").toString()) }
    })
    // A large compact request can read a book and emit both working state and knowledge updates.
    // Keep its bounded transport independent from ordinary chat, and never retry it automatically.
    private val compactClient = ProviderClient(
        client = okhttp3.OkHttpClient.Builder().writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build(),
        timeoutMillis = 300_000L,
        callTimeoutMillis = COMPACT_REQUEST_DEADLINE_MS,
        onDiagnostic = { event ->
            runCatching { requestLogs.record(JSONObject(event).put("purpose", requestPurpose)
                .put("app_state", if (uiVisible) "foreground" else "background").toString()) }
        }
    )
    private val researchClient = DoubaoResearchClient()
    private val directSearchClient = DoubaoSearchClient()
    private val billingClient = VeniceBillingClient()
    private val veniceBalanceClient = VeniceBalanceClient(onDiagnostic = { event ->
        runCatching { requestLogs.record(JSONObject(event).put("purpose", "venice_balance")
            .put("app_state", if (uiVisible) "foreground" else "background").toString()) }
    })
    private val mediaClient = MediaClient()
    private val readAsset: (String) -> String = { path -> app.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }
    private val tools = ToolRuntime(File(app.filesDir,"workspace").apply { mkdirs() }, { skill, path -> BuiltinSkills.readResource(readAsset, skill, path) }) { id -> key(id) }
    private val builtinSkills by lazy { BuiltinSkills.loadAll(readAsset) }
    private val builtinScenarios by lazy {
        val read = { path: String -> app.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }
        BuiltinScenarios.load(read) + BuiltinScenarios.loadCharacters(read) { path -> app.assets.open(path).use { it.readBytes() } }
    }
    private val saves = StatePersistence<AppData>(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
        write = { app.database.state().save(StateDocument(json=DataCodec.encode(it))) },
        onError = { error -> viewModelScope.launch { status("保存失败：${error.message ?: "存储不可用"}") } }
    )
    private var loadFailed = false
    /** The one app-wide task (model refresh, Skill install, billing check, media). Chats do not wait for it. */
    private var operation: Job? = null
    /** Balance lookups are independent read-only jobs and must not occupy the chat busy flag. */
    private val veniceBalanceJobs = mutableMapOf<String, Job>()
    private data class GenerationLease(val token: String = newId(), val ready: CompletableDeferred<Unit> = CompletableDeferred())
    /** One foreground service covers every background chat task and stops with the last one. */
    private val leases = SharedLease(
        open = {
            GenerationLease().also { lease ->
                host?.requestGenerationNotifications()
                // A start refusal reaches every task waiting on this lease.
                runCatching { GenerationService.start(app, lease.token) }.onFailure { lease.ready.completeExceptionally(it) }
            }
        },
        close = { GenerationService.finish(app, it.token) }
    )
    private var stopReason: String? = null
    /** Long tasks by chat id. Only touched on the main thread. */
    private val chatRuns = LinkedHashMap<String, ChatRun>()
    private val replySlots = Semaphore(ChatTask.MAX_REPLIES)
    /** Connections of all chat tasks share one pool; each task still has its own client and cancel. */
    private val chatHttp = okhttp3.OkHttpClient()
    /** The summary transport handles one request at a time, whichever chat needs it. */
    private val compactGate = Mutex()
    private var compactOwner: ChatRun? = null
    private var installationCall: okhttp3.Call? = null
    private val data get() = mutable.value.data
    private val selected get() = data.chats.find { it.id == mutable.value.selectedChatId }

    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { app.database.state().load()?.let { DataCodec.decode(it.json) } ?: AppData() }
                val discardRepairBackups = withContext(Dispatchers.IO) { finishPendingRepairBackupDiscard(loaded) }
                val seeded = withContext(Dispatchers.IO) {
                    BuiltinScenarios.merge(BuiltinSkills.merge(loaded,builtinSkills),builtinScenarios)
                }
                val stars = withContext(Dispatchers.IO) { BuiltinSkills.loadStars(readAsset) }
                val restored = seeded.copy(chats=seeded.chats.map { chat -> chat.copy(messages=chat.messages.map { if(it.status=="streaming") it.copy(status="interrupted") else it }) })
                val restoredChatIds = restored.chats.mapTo(mutableSetOf()) { it.id }
                val restoredChats = restored.chats.associateBy { it.id }
                val repairBackups = if (discardRepairBackups) emptySet() else withContext(Dispatchers.IO) {
                    repairBackupDirectory().listFiles()?.filter { it.isFile && it.extension == "json" }?.mapNotNull { file ->
                        val id = file.nameWithoutExtension
                        val snapshot = id.takeIf { it in restoredChatIds }?.let { runCatching { readRepairBackup(it) }.getOrNull() }
                        val valid = snapshot != null && ConversationRepair.snapshotAppliesTo(snapshot, restoredChats.getValue(id))
                        if (valid) id else { file.delete(); null }
                    }?.toSet().orEmpty()
                }
                val restoredSelection = preferredChatId(restored.chats, sessionPreferences.getString(LAST_SELECTED_CHAT_ID, "").orEmpty())
                sessionPreferences.edit().putString(LAST_SELECTED_CHAT_ID, restoredSelection).apply()
                mutable.value = mutable.value.copy(data=restored,selectedChatId=restoredSelection,loading=false,hasResearchSearchKey=app.secrets.has(DoubaoSearchClient.CREDENTIAL_ID),hasKeys=restored.providers.filter { app.secrets.has(credentialId(it)) }.map { it.id }.toSet(),skillStars=stars,missingBuiltinSkills=BuiltinSkills.missing(restored,builtinSkills),repairBackupChatIds=repairBackups)
                if(restored != loaded) saves.submit(restored)
                VideoBrowseWorker.schedule(app, restored.chats.any { it.browsePerDay > 0 })
                pendingOpenChatId?.let { id -> pendingOpenChatId = null; if (restored.chats.any { it.id == id }) selectChat(id) }
                restored.chats.forEach { repairPendingTools(it.id) }
                refreshPcStickers()
            } catch (error: Exception) {
                loadFailed = true
                mutable.value = mutable.value.copy(loading=false,status="读取本地数据失败，原数据库已保留。已阻止写入；可到设置导出原始数据或恢复备份：${error.message}")
            }
        }
    }

    private fun change(value: AppData, persist: Boolean = true) {
        if(mutable.value.loading) { status("正在读取本地数据，请稍候"); return }
        if(loadFailed) { status("本地数据读取失败，已阻止覆盖。请在设置导出原始数据或恢复备份"); return }
        mutable.value = mutable.value.copy(data=value,missingBuiltinSkills=BuiltinSkills.missing(value,builtinSkills))
        if (persist) saves.submit(value)
    }
    private fun status(text: String) { mutable.value = mutable.value.copy(status=text) }
    /** [touch] false keeps the chat's time: streamed text must not reorder the chat list on every piece. */
    private fun changeChat(id: String, persist: Boolean = true, touch: Boolean = true, transform: (Chat)->Chat) {
        change(data.copy(chats=data.chats.map { if(it.id==id) transform(it).let { changed -> if (touch) changed.copy(updatedAt=System.currentTimeMillis()) else changed } else it }),persist)
    }
    private fun current(id: String): Chat = data.chats.find { it.id == id } ?: error("会话已删除")
    private fun provider(id: String): ProviderConfig = data.providers.find { it.id==id } ?: error("请先配置模型平台")
    private fun credentialId(provider: ProviderConfig): String = java.security.MessageDigest.getInstance("SHA-256").digest("${provider.id}|${provider.kind}|${provider.baseUrl.trim().trimEnd('/')}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun key(id: String): String = app.secrets.get(credentialId(provider(id))).also { require(it.isNotBlank()) { "请在模型页面填写 API key" } }
    private fun preset(chat: Chat): Preset = data.presets.find { it.id==chat.presetId } ?: data.presets.first()
    /** Saves the final state and runs the caller's check. Returns what to report and whether saving failed. */
    private suspend fun settle(failure: String?, verifySavedState: (suspend () -> Unit)?): Pair<String?, Boolean> {
        var result = failure
        var saveFailed = false
        runCatching { saves.flush(data) }.onFailure {
            val saveError = "保存失败：${it.message ?: "存储不可用"}"
            result = listOfNotNull(result, saveError).joinToString("\n")
            saveFailed = true
            status(saveError)
        }
        if (result == null && verifySavedState != null) {
            runCatching { verifySavedState() }.onFailure {
                val checkError = "保存后校验未通过：${it.message?.take(500) ?: "无法核对重建结果"}"
                result = checkError
                status(checkError)
            }
        }
        return result to saveFailed
    }

    /** An app-wide task (model refresh, Skill install, billing check, media): one at a time. Chats keep working. */
    private fun action(
        onStarted: () -> Unit = {},
        onFinished: (error: String?, cancelled: Boolean) -> Unit = { _, _ -> },
        verifySavedState: (suspend () -> Unit)? = null,
        block: suspend () -> Unit
    ) {
        if(mutable.value.loading) { status("正在读取本地数据，请稍候"); return }
        if(loadFailed) { status("请先恢复本地数据，已阻止新任务覆盖原数据库"); return }
        if(mutable.value.busy) { status("当前任务进行中，请先停止或等待完成"); return }
        stopReason = null
        mutable.value = mutable.value.copy(busy=true,status="")
        onStarted()
        operation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var failure: String? = null
            var cancelled = false
            try { block() }
            catch (_: TimeoutCancellationException) { failure = "任务等待超时，请查看诊断日志后重试" }
            catch (_: CancellationException) { cancelled = true; failure = stopReason ?: "任务已中断" }
            catch (error: Exception) { failure = error.message?.take(800) ?: "操作失败" }
            finally {
                failure?.let { status(it) }
                val settled = withContext(NonCancellable) { settle(failure, verifySavedState) }
                if (settled.second) cancelled = false
                mutable.value = mutable.value.copy(busy=false)
                operation = null
                onFinished(settled.first, cancelled)
            }
        }
        operation?.start()
    }

    private fun chatBusy(chatId: String?): Boolean = chatId != null && chatId in chatRuns

    /** True, with the reason in the status line, while this chat has a task running. */
    private fun chatLocked(chatId: String?): Boolean {
        val task = chatId?.let { mutable.value.chatTasks[it] } ?: return false
        status(ChatTasks.busyNotice(task))
        return true
    }

    private fun chatTaskRefusal(chatId: String, kind: String): String? = when {
        mutable.value.loading -> "正在读取本地数据，请稍候"
        loadFailed -> "请先恢复本地数据，已阻止新任务覆盖原数据库"
        else -> ChatTasks.refusal(mutable.value.chatTasks, chatId, kind)
    }

    private fun publishChatTasks() {
        mutable.value = mutable.value.copy(chatTasks = chatRuns.mapValues { ChatTask(it.value.kind, it.value.queued) })
        GenerationService.refresh()
    }

    /** A message about a chat the user is not looking at says which chat it is about. */
    private fun chatStatus(chatId: String, text: String) {
        val title = data.chats.firstOrNull { it.id == chatId }?.title?.takeIf { chatId != mutable.value.selectedChatId }
        status(if (title == null) text else "「${title.take(16)}」$text")
    }

    private fun logRequest(event: String, purpose: String) {
        runCatching { requestLogs.record(JSONObject(event).put("purpose", purpose)
            .put("app_state", if (uiVisible) "foreground" else "background").toString()) }
    }

    /**
     * A long task of one chat: a reply, or memory upkeep. Other chats and the settings stay usable;
     * only this chat is busy until it ends. Returns false, with the reason in the status line, when
     * it cannot start.
     */
    private fun chatTask(
        chatId: String,
        kind: String = ChatTask.REPLY,
        background: Boolean = true,
        onStarted: () -> Unit = {},
        onFinished: (error: String?, cancelled: Boolean) -> Unit = { _, _ -> },
        verifySavedState: (suspend () -> Unit)? = null,
        block: suspend () -> Unit
    ): Boolean {
        chatTaskRefusal(chatId, kind)?.let { status(it); return false }
        val run = ChatRun(chatId, kind, background) { owner ->
            ProviderClient(client = chatHttp, onDiagnostic = { event -> logRequest(event, owner.purpose.get()) })
        }
        val lease = if (background) leases.acquire() else null
        run.queued = kind == ChatTask.REPLY && ChatTasks.mustQueue(mutable.value.chatTasks)
        chatRuns[chatId] = run
        status("")
        publishChatTasks()
        onStarted()
        run.job = viewModelScope.launch(run, start = CoroutineStart.LAZY) {
            var failure: String? = null
            var cancelled = false
            var serviceReady = lease == null
            try {
                if (lease != null) {
                    withTimeout(10_000) { lease.ready.await() }
                    serviceReady = true
                }
                if (kind == ChatTask.REPLY) {
                    if (!replySlots.tryAcquire()) {
                        if (!run.queued) { run.queued = true; publishChatTasks() }
                        replySlots.acquire()
                    }
                    try {
                        if (run.queued) { run.queued = false; publishChatTasks() }
                        block()
                        markUnreadIfAway(chatId)
                    } finally { replySlots.release() }
                } else block()
            }
            catch (_: TimeoutCancellationException) {
                failure = if (serviceReady) "任务等待超时，请查看诊断日志后重试" else "后台生成服务未能及时启动，请返回应用后重试"
            }
            catch (error: Exception) {
                // A stopped request can surface as a provider error; the stop is still the reason.
                if (error is CancellationException || !currentCoroutineContext().isActive) {
                    cancelled = true
                    failure = run.stopReason ?: "生成已中断；已收到的内容已保留，可重试"
                } else failure = error.message?.take(800) ?: "操作失败"
            }
            finally {
                failure?.let { chatStatus(chatId, it) }
                // Keep the process foreground until the final text/status reached storage.
                val settled = withContext(NonCancellable) { settle(failure, verifySavedState) }
                if (settled.second) cancelled = false
                if (chatRuns[chatId] === run) chatRuns.remove(chatId)
                if (lease != null) leases.release()
                publishChatTasks()
                onFinished(settled.first, cancelled)
            }
        }
        run.job?.start()
        return true
    }

    /** The summary transport is shared: a chat waits here while another chat's memory is being written. */
    private suspend fun <T> compacting(block: suspend () -> T): T {
        val run = currentCoroutineContext()[ChatRun]
        if (run != null && compactGate.isLocked && !run.queued) { run.queued = true; publishChatTasks() }
        try {
            return compactGate.withLock {
                if (run?.queued == true) { run.queued = false; publishChatTasks() }
                compactOwner = run
                try { block() } finally { compactOwner = null }
            }
        } finally {
            if (run?.queued == true) { run.queued = false; publishChatTasks() }
        }
    }

    private fun stopRun(run: ChatRun, reason: String) {
        run.stopReason = reason
        run.job?.cancel()
        run.cancelRequest()
        if (compactOwner === run) compactClient.cancel()
        tools.cancel(run.chatId)
        if (run.kind == ChatTask.RESEARCH) { researchClient.cancel(); directSearchClient.cancel() }
    }

    internal fun isGenerationActive(token: String): Boolean = leases.current?.token == token
    internal fun onGenerationServiceReady(token: String) {
        leases.current?.takeIf { it.token == token }?.ready?.complete(Unit)
    }
    internal fun stopGenerationFromService(token: String, reason: String) {
        // The notification covers every background task, so its stop button stops them all.
        if (isGenerationActive(token)) chatRuns.values.filter { it.background }.forEach { stopRun(it, reason) }
    }
    /** How many chats the background notification stands for. */
    internal fun backgroundTaskCount(): Int = chatRuns.values.count { it.background }
    fun checkpoint() {
        if (!mutable.value.loading && !loadFailed) saves.submit(data)
    }

    override fun saveProvider(provider: ProviderConfig, key: String) {
        runCatching {
            require(!mutable.value.busy) { "请先停止当前任务再修改平台" }
            require(provider.name.isNotBlank() && provider.kind in listOf("venice","openai","anthropic","writing-server")) { "平台名称或协议无效" }
            val urlViolation = ProviderUrlPolicy.violation(provider)
            require(urlViolation == null) { "平台地址无效：$urlViolation" }
            val lanViolation = ProviderUrlPolicy.lanViolation(provider)
            require(lanViolation == null) { lanViolation.orEmpty() }
            val normalized = provider.copy(baseUrl=provider.baseUrl.trim().trimEnd('/'),name=provider.name.trim(),
                lanBaseUrl=if (provider.kind.equals("writing-server", ignoreCase = true)) provider.lanBaseUrl.trim().trimEnd('/') else "")
            pcRouter.forget()
            val previous=data.providers.find { it.id==provider.id }
            require(previous==null || credentialId(previous)==credentialId(normalized) || key.isNotBlank()) { "平台地址或协议已更改，请重新填写 API key" }
            if(key.isNotBlank()) app.secrets.put(credentialId(normalized),key.trim())
            change(data.copy(providers=data.providers.filterNot { it.id==provider.id } + normalized))
            mutable.value = mutable.value.copy(hasKeys=data.providers.filter { app.secrets.has(credentialId(it)) }.map { it.id }.toSet())
            if (previous != null && (credentialId(previous) != credentialId(normalized) || key.isNotBlank())) clearVeniceBalance(normalized.id)
            status("平台已保存")
            // The sticker packs live on the PC: read them now, not only at the next app start.
            if (chosenPcProvider(data.providers, pcStore.providerId)?.id == normalized.id) refreshPcStickers()
        }.onFailure { status(it.message ?: "保存失败") }
    }
    override fun deleteProvider(id: String) {
        if(mutable.value.busy) { status("请先停止当前任务"); return }
        data.providers.find { it.id==id }?.let { app.secrets.delete(credentialId(it)) }; change(data.copy(providers=data.providers.filterNot { it.id==id }))
        clearVeniceBalance(id)
        mutable.value=mutable.value.copy(hasKeys=mutable.value.hasKeys-id)
    }

    private fun clearVeniceBalance(providerId: String) {
        veniceBalanceJobs.remove(providerId)?.cancel()
        if (mutable.value.veniceBalances.containsKey(providerId)) {
            mutable.value = mutable.value.copy(veniceBalances = mutable.value.veniceBalances - providerId)
        }
    }
    override fun refreshModels(id: String) = action {
        val targets=if(id=="*") data.providers.toList() else listOf(provider(id))
        val reports=mutableListOf<String>()
        for(p in targets) {
            try {
                val models=providerClient.models(routed(p),key(p.id))
                change(data.copy(providers=data.providers.map { if(it.id==p.id) it.copy(models=models,refreshedAt=System.currentTimeMillis()) else it }))
                reports+="${p.name}：${models.size} 个模型"
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                if(id!="*") throw error
                reports+="${p.name}：${error.message ?: "刷新失败"}"
            }
        }
        status(reports.joinToString("；"))
    }

    override fun refreshVeniceBalance(providerId: String) {
        if (mutable.value.loading || loadFailed) { status("本地数据尚未就绪"); return }
        val p = runCatching { provider(providerId) }.getOrElse { status(it.message ?: "找不到平台"); return }
        if (!p.kind.equals("venice", ignoreCase = true)) { status("余额查询仅支持 Venice 平台"); return }
        val previous = mutable.value.veniceBalances[providerId]
        if (previous?.loading == true) return
        mutable.value = mutable.value.copy(veniceBalances = mutable.value.veniceBalances + (providerId to (previous ?: VeniceBalanceState()).copy(loading = true, error = "")))
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(Dispatchers.IO) { veniceBalanceClient.fetch(p, key(providerId)) }
                if (veniceBalanceJobs[providerId] === coroutineContext[Job]) {
                    mutable.value = mutable.value.copy(veniceBalances = mutable.value.veniceBalances + (providerId to VeniceBalanceState(balance = result)))
                    status("${p.name}余额已刷新")
                }
            } catch (error: CancellationException) {
                if (veniceBalanceJobs[providerId] === coroutineContext[Job]) {
                    mutable.value = mutable.value.copy(veniceBalances = mutable.value.veniceBalances + (providerId to (mutable.value.veniceBalances[providerId] ?: VeniceBalanceState()).copy(loading = false)))
                }
            } catch (error: Exception) {
                val message = error.message?.take(500) ?: "余额查询失败"
                if (veniceBalanceJobs[providerId] === coroutineContext[Job]) {
                    val old = mutable.value.veniceBalances[providerId]
                    mutable.value = mutable.value.copy(veniceBalances = mutable.value.veniceBalances + (providerId to VeniceBalanceState(balance = old?.balance, loading = false, error = message)))
                }
            } finally {
                if (veniceBalanceJobs[providerId] === coroutineContext[Job]) veniceBalanceJobs.remove(providerId)
            }
        }
        veniceBalanceJobs[providerId] = job
        job.start()
    }
    override fun createChat(characterId: String) {
        if (mutable.value.loading) { status("正在读取本地数据，请稍候"); return }
        if (loadFailed) { status("本地数据读取失败，已阻止创建会话"); return }
        val inherited = selected?.let { currentChat ->
            data.providers.firstOrNull { it.id == currentChat.providerId }?.let { currentProvider ->
                currentProvider.models.firstOrNull { model ->
                    model.id == currentChat.modelId && model.type == "text" && !model.offline
                }?.let { model -> currentProvider to model }
            }
        }
        val p=data.providers.firstOrNull { it.id in mutable.value.hasKeys && it.models.any { m -> m.type=="text" && !m.offline } }
            ?: data.providers.firstOrNull { it.models.any { m -> m.type=="text" && !m.offline } } ?: data.providers.firstOrNull()
        val selectedProvider = inherited?.first ?: p
        val character=data.characters.find { it.id==characterId }
        val modelId = inherited?.second?.id ?: selectedProvider?.models?.firstOrNull { it.type=="text" && !it.offline }?.id.orEmpty()
        val presetId = BuiltinScenarios.presetIdForCharacter(character, data.presets)
        val chat=Chat(title=character?.name?.takeIf { it.isNotBlank() } ?: "新会话",providerId=selectedProvider?.id ?: "",modelId=modelId,presetId=presetId,characterId=characterId,messages=character?.firstMessage?.takeIf { it.isNotBlank() }?.let { listOf(Message(role="assistant",content=it)) } ?: emptyList())
        change(data.copy(chats=listOf(chat)+data.chats)); rememberSelectedChat(chat.id); mutable.value=mutable.value.copy(selectedChatId=chat.id,chatOpen=true,pendingAttachments=emptyList())
    }
    override fun selectChat(id: String) {
        if (data.chats.none { it.id == id }) { status("会话不存在"); return }
        rememberSelectedChat(id)
        mutable.value=mutable.value.copy(selectedChatId=id,chatOpen=true,pendingAttachments=emptyList())
        markOpenChatRead()
    }
    override fun closeChat() { mutable.value=mutable.value.copy(chatOpen=false,pendingAttachments=emptyList()) }

    /** The chat on screen has nothing unread. Opening a chat must not move it in the list, so its time stays. */
    private fun markOpenChatRead() {
        if (mutable.value.loading || loadFailed || !mutable.value.chatOpen) return
        val id = mutable.value.selectedChatId
        if (data.chats.any { it.id == id && it.unread != 0 }) change(data.copy(chats=data.chats.map { if (it.id == id) it.copy(unread=0) else it }))
    }

    /** A reply that finished while the user was elsewhere shows as unread in the chat list. */
    private fun markUnreadIfAway(chatId: String) {
        val watching = uiVisible && mutable.value.chatOpen && mutable.value.selectedChatId == chatId
        if (!watching) change(data.copy(chats=data.chats.map { if (it.id == chatId) it.copy(unread=it.unread+1) else it }))
    }
    override fun updateChat(chat: Chat) {
        if(chatLocked(chat.id)) return
        val blocked=if(data.settings.adultSkillsConfirmed) emptySet() else data.skills.filter { it.adult }.map { it.id }.toSet()
        if(chat.skillIds.any { it in blocked }) status("成人向 Skill 需要先确认已年满 18 岁，并仅用于成年角色")
        changeChat(chat.id) { chat.copy(skillIds=chat.skillIds.filterNot { it in blocked }) }
    }
    override fun saveScenarioState(chatId: String, state: ScenarioState) {
        if (chatLocked(chatId)) return
        if (data.chats.none { it.id == chatId }) { status("会话不存在"); return }
        changeChat(chatId) { it.copy(scenarioState = state.copy(updatedAt = System.currentTimeMillis(), source = state.source.ifBlank { "用户确认" })) }
        status("剧本状态已保存")
    }
    override fun saveScenarioRoster(chatId: String, roster: List<ScenarioPerson>) {
        if (chatLocked(chatId)) return
        if (data.chats.none { it.id == chatId }) { status("会话不存在"); return }
        changeChat(chatId) { it.copy(scenarioRoster = roster) }
        status("人物名册已保存")
    }
    override fun organizeScenarioFromHistory(chatId: String) {
        if (data.chats.none { it.id == chatId }) { status("会话不存在"); return }
        chatTask(chatId, ChatTask.ORGANIZE, onStarted = { status("正在读取历史对话，整理剧本状态和人物关系…") }) {
            val changed = compacting { organizeScenarioFromHistoryInternal(chatId) }
            status(if (changed) "已根据历史对话整理剧本状态和人物关系" else "历史中没有发现可确认的新状态，原记录保持不变")
        }
    }
    private suspend fun organizeScenarioFromHistoryInternal(chatId: String): Boolean {
        val original = current(chatId)
        val sources = ScenarioHistoryOrganizer.messages(original)
        require(sources.isNotEmpty()) { "当前会话没有可整理的有效历史对话" }
        val active = preset(original)
        val p = provider(active.summaryProviderId.ifBlank { original.providerId })
        val modelId = active.summaryModelId.ifBlank { original.modelId }
        require(modelId.isNotBlank()) { "请先选择可用的整理模型" }
        val model = p.models.find { it.id == modelId }
        require(model != null && model.type == "text" && !model.offline) { "整理模型不可用，请刷新模型目录后重试" }
        val window = MemoryRebuilder.effectiveWindow(model.contextTokens, data.settings.contextOverride)
        val output = HistoryCompressor.outputLimit(window)
        val organizePreset = DeepSeekWriting.summaryPreset(active, p, model, output)
        val secret = key(p.id)
        val sourceHash = ContextEngine.sourceHash(original.messages)
        val systemTokens = ContextEngine.estimateTokens(ScenarioHistoryOrganizer.systemPrompt())
        val inputBudget = (window - output - HistoryCompressor.safetyReserve(window) - systemTokens - 256).coerceAtLeast(1024)
        // Existing compact notes are evidence too.  Without this index the
        // organizer only sees surviving raw turns and silently drops facts that
        // were already moved into the memory vault by an earlier compression.
        // Bound the injected index by the same request budget; full note text
        // remains available to history_read and in the Obsidian export.
        val memoryEvidenceBudget = minOf(12_000, (inputBudget / 5).coerceAtLeast(256))
        val existingMemory = ScenarioHistoryOrganizer.memoryEvidence(original, memoryEvidenceBudget)
        // Materialize each source exactly once. The old planner serialized the
        // growing prefix for every candidate end, which made long histories
        // quadratic in both CPU and temporary memory before the first request.
        val sourceCharacters = sources.sumOf { message ->
            message.content.length.toLong() + message.attachments.sumOf { it.dataUrl.length.toLong() }
        }
        require(sourceCharacters <= 8_000_000L && sources.all { message ->
            message.content.length.toLong() + message.attachments.sumOf { it.dataUrl.length.toLong() } <= 2_000_000L
        }) {
            "历史原文过大，整理前请先分支会话或拆分超长附件（当前约 ${sourceCharacters / 1_000_000} MB 文本）"
        }
        val sourceTexts = sources.map { HistorySource.memoryText(it) }
        val sourceCosts = sourceTexts.map { text ->
            ContextEngine.estimateTokens(JSONObject().put("source", text).toString()) + 4
        }
        var draft = ScenarioHistoryPatch(state = original.scenarioState, people = original.scenarioRoster)
        var index = 0
        var calls = 0
        while (index < sources.size) {
            currentCoroutineContext().ensureActive()
            var end = index
            var estimated = ContextEngine.estimateTokens(ScenarioHistoryOrganizer.requestText(draft, emptyList(), existingMemory))
            while (end < sources.size) {
                if (estimated + sourceCosts[end] > inputBudget) break
                estimated += sourceCosts[end]
                end++
            }
            require(end > index) {
                "历史中有一条消息超过整理模型可用窗口，请先拆分该条消息后重试"
            }
            var lastGood = sources.subList(index, end)
            // The additive estimate above intentionally overestimates JSON
            // framing. Verify the one actual payload once and trim only if
            // escaping pushed it over the bound.
            while (lastGood.isNotEmpty() && systemTokens + ContextEngine.estimateTokens(ScenarioHistoryOrganizer.requestText(draft, lastGood, existingMemory)) > inputBudget) {
                lastGood = lastGood.dropLast(1)
                end--
            }
            require(lastGood.isNotEmpty() && end > index) {
                "历史中有一条消息超过整理模型可用窗口，请先拆分该条消息后重试"
            }
            val allowedIds = (lastGood.map { it.id } + existingMemory.flatMap { it.sourceMessageIds }).toMutableSet()
            val allowedSummaryIds = existingMemory.filter { it.sourceKind == "summary" }.map { it.id }.toSet()
            val request = JSONArray()
                .put(JSONObject().put("role", "system").put("content", ScenarioHistoryOrganizer.systemPrompt()))
                .put(JSONObject().put("role", "user").put("content", ScenarioHistoryOrganizer.requestText(draft, lastGood, existingMemory))).toString()
            requestPurpose = "scenario_organize"
            val response = compactClient.chat(routed(p), secret, modelId, request, organizePreset, toolsJson = "[]", finalAnswerOnly = true)
            require(response.content.isNotBlank() && response.finishReason.lowercase() in setOf("stop", "end_turn", "stop_sequence")) {
                "历史整理未完成（finish_reason=${response.finishReason.ifBlank { "未知" }}），原剧本状态未修改"
            }
            val patch = ScenarioHistoryOrganizer.parse(response.content, allowedIds, allowedSummaryIds)
            draft = ScenarioHistoryOrganizer.merge(draft, patch)
            index = end
            calls++
            status("正在整理历史对话：已完成 $index/${sources.size} 条，已请求 $calls 次")
        }
        val latest = current(chatId)
        require(ContextEngine.sourceHash(latest.messages) == sourceHash) {
            "整理期间会话新增或修改了消息，未写回整理结果；请重新点击整理"
        }
        val unchanged = draft.state == original.scenarioState && draft.people == original.scenarioRoster
        // A panel can predate the knowledge-vault integration.  Even when the
        // visible values are unchanged, write the missing source-bound notes;
        // repeated整理 of the same source remains idempotent.
        val generatedNotes = ScenarioHistoryOrganizer.scenarioNotes(original, draft)
        val notesAlreadySaved = generatedNotes.isNotEmpty() && generatedNotes.all { generated ->
            original.knowledgeNotes.any { saved ->
                saved.sourceKind == "conversation" && saved.title == generated.title &&
                    saved.kind == generated.kind && saved.entity == generated.entity &&
                    saved.sourceHash == generated.sourceHash
            }
        }
        if (unchanged && (generatedNotes.isEmpty() || notesAlreadySaved)) return false
        changeChat(chatId) { ScenarioHistoryOrganizer.mergeInto(it, draft) }
        return true
    }
    override fun deleteChat(id: String) {
        if(mutable.value.loading || loadFailed) { status("请等待本地数据读取完成"); return }
        if(chatLocked(id)) return
        val next = data.copy(chats=data.chats.filterNot { it.id==id },memories=data.memories.filterNot { it.scope=="chat" && it.targetId==id })
        change(next)
        val nextSelected = if(mutable.value.selectedChatId==id) preferredChatId(next.chats, "") else mutable.value.selectedChatId
        rememberSelectedChat(nextSelected)
        mutable.value=mutable.value.copy(
            selectedChatId=nextSelected,
            chatOpen=mutable.value.chatOpen && mutable.value.selectedChatId != id,
            repairBackupChatIds=mutable.value.repairBackupChatIds
        )
        viewModelScope.launch {
            runCatching {
                // Other chats may have streamed text since: save what is current, not the earlier snapshot.
                saves.flush(data)
                val deleted = withContext(Dispatchers.IO) {
                    compactCheckpoints.clear(id, "compression")
                    compactCheckpoints.clear(id, "memory_rebuild")
                    deleteRepairBackup(id)
                }
                mutable.value=mutable.value.copy(repairBackupChatIds=mutable.value.repairBackupChatIds-id)
                if (!deleted) status("会话已删除；遗留的修复快照会在下次启动时清理")
            }.onFailure { status("会话已从当前界面删除，但本地保存失败；修复快照仍保留，重启后可恢复：${it.message ?: "未知错误"}") }
        }
    }
    override fun repairMessages(chatId: String, messageIds: Set<String>) {
        if(mutable.value.loading || loadFailed) { status("请等待本地数据读取完成后再修复"); return }
        if(chatLocked(chatId)) return
        val originalData = data
        val originalChat = originalData.chats.firstOrNull { it.id == chatId } ?: run { status("找不到要修复的会话"); return }
        viewModelScope.launch {
            runCatching {
                val cleaned = ConversationRepair.remove(originalChat, messageIds)
                val discardedOldSnapshots = withContext(Dispatchers.IO) {
                    val discarded = finishPendingRepairBackupDiscard(originalData)
                    require(!repairBackupDiscardMarker().exists()) { "旧修复快照仍在清理，请重启应用完成清理后再修复会话" }
                    writeRepairBackup(originalChat, cleaned)
                    discarded
                }
                // Other chats may be streaming meanwhile: only this chat has to be unchanged, and only it is written.
                if (data.chats.firstOrNull { it.id == chatId } !== originalChat) error("修复期间会话数据发生变化，已保留修复前快照，请重新打开预览")
                change(data.copy(chats=data.chats.map { if (it.id == chatId) cleaned else it }))
                mutable.value = mutable.value.copy(repairBackupChatIds=(if (discardedOldSnapshots) emptySet() else mutable.value.repairBackupChatIds) + chatId)
                status("已清理 ${originalChat.messages.size - cleaned.messages.size} 条消息；当前摘要和历史知识索引已重新对齐，可从原剧情继续，修复前快照可撤销")
            }.onFailure { status("会话修复未应用：${it.message ?: "未知错误"}") }
        }
    }
    override fun undoLastChatRepair(chatId: String) {
        if (mutable.value.loading || loadFailed) { status("请等待本地数据读取完成后再撤销"); return }
        if (chatLocked(chatId)) return
        val originalChat = data.chats.firstOrNull { it.id == chatId } ?: run { status("找不到会话"); return }
        viewModelScope.launch {
            runCatching {
                val previous = withContext(Dispatchers.IO) { readRepairBackup(chatId) }
                require(previous.id == chatId) { "备份与当前会话身份不匹配" }
                // Other chats may be streaming meanwhile: only this chat has to be unchanged, and only it is written.
                val currentChat = data.chats.firstOrNull { it.id == chatId }?.takeIf { it === originalChat } ?: error("撤销期间会话数据发生变化，请重试")
                require(ConversationRepair.snapshotAppliesTo(previous, currentChat)) { "修复快照不属于当前会话状态，已拒绝恢复" }
                val restored = ConversationRepair.restore(previous, currentChat)
                change(data.copy(chats=data.chats.map { if (it.id == chatId) restored else it }))
                saves.flush(data)
                val backupDeleted = withContext(Dispatchers.IO) { repairBackupFile(chatId).let { it.delete() || !it.exists() } }
                mutable.value = mutable.value.copy(repairBackupChatIds=mutable.value.repairBackupChatIds - chatId)
                status(if (backupDeleted) {
                    "已恢复修复前的完整历史；修复后新发送和新生成的消息已接在原历史后面，可继续当前会话"
                } else {
                    "历史已恢复并可继续；遗留快照无法立即清理，下次启动会自动处理"
                })
            }.onFailure { status("无法恢复修复前历史：${it.message ?: "备份不可用"}") }
        }
    }
    private fun repairBackupDirectory(): File = File(app.filesDir, "conversation-repair")
    private fun repairBackupDiscardMarker(): File = File(app.filesDir, "conversation-repair-discard.json")
    private fun writeRepairBackupDiscardMarker(intent: RepairBackupDiscardMarker) {
        val destination = repairBackupDiscardMarker()
        val bytes = intent.encode().toByteArray(Charsets.UTF_8)
        val temporary = File.createTempFile("conversation-repair-discard-", ".tmp", app.filesDir)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }
    /** Returns true when old repair snapshots belong to a full backup that is now durably active. */
    private fun finishPendingRepairBackupDiscard(loaded: AppData): Boolean {
        val marker = repairBackupDiscardMarker()
        if (!marker.isFile) return false
        val intent = runCatching {
            require(marker.length() <= 1024) { "修复快照清理标记过大" }
            RepairBackupDiscardMarker.decode(marker.readText(Charsets.UTF_8))
        }.getOrNull()
        if (intent == null || !intent.isApplicableTo(DataCodec.fingerprint(loaded))) {
            marker.delete()
            return false
        }
        if (!intent.isCommitted) writeRepairBackupDiscardMarker(intent.committed())
        val cleaned = runCatching { clearRepairBackups() }.isSuccess
        if (cleaned) marker.delete()
        return true
    }
    private fun cancelRepairBackupDiscardMarker() {
        repairBackupDiscardMarker().delete()
    }
    private fun deleteRepairBackup(chatId: String): Boolean {
        if (!chatId.matches(Regex("[A-Za-z0-9._-]{1,100}"))) return true
        return repairBackupFile(chatId).let { it.delete() || !it.exists() }
    }
    private fun clearRepairBackups() {
        val directory = repairBackupDirectory()
        require(!directory.exists() || directory.deleteRecursively()) { "无法清理旧的会话修复快照" }
    }
    private fun repairBackupFile(chatId: String): File {
        require(chatId.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "会话 ID 格式无效" }
        return File(repairBackupDirectory(), "$chatId.json")
    }
    private fun writeRepairBackup(original: Chat, repaired: Chat) {
        require(!repairBackupDiscardMarker().exists()) { "旧修复快照仍在清理，请重启应用完成清理后再修复会话" }
        val directory = repairBackupDirectory()
        require(directory.isDirectory || directory.mkdirs()) { "无法创建会话备份目录" }
        val destination = repairBackupFile(original.id)
        val snapshot = if (destination.isFile) {
            ConversationRepair.extendSnapshot(readRepairBackup(original.id), original)
        } else original
        val bytes = JSONObject().put("originalChat", DataCodec.gson.toJson(snapshot)).put("repairAppliedAt", repaired.updatedAt).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= DataCodec.MAX_JSON_UTF8_BYTES) { "会话快照超过备份上限" }
        val temporary = File.createTempFile("${original.id.take(20)}-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }
    private fun readRepairBackup(chatId: String): Chat {
        val file = repairBackupFile(chatId)
        require(file.isFile && file.length() <= DataCodec.MAX_JSON_UTF8_BYTES) { "没有可用的修复前快照" }
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val restored = DataCodec.gson.fromJson(root.getString("originalChat"), Chat::class.java)
        require(restored.id == chatId) { "备份会话 ID 不匹配" }
        return restored
    }
    override fun chooseModel(providerId: String, modelId: String) {
        if(mutable.value.loading || loadFailed) { status("本地数据尚未就绪"); return }
        if(chatLocked(selected?.id)) return
        val candidate=data.providers.find { it.id==providerId }?.models?.find { it.id==modelId }
        if(candidate==null || candidate.type!="text" || candidate.offline) { status("请选择目录中可用的聊天模型，必要时先刷新"); return }
        if(selected==null) createChat()
        val chat = selected ?: return
        runCatching {
            val updated = DeepSeekWriting.selectModel(data,chat.id,providerId,modelId)
            val adapted = updated.chats.first { it.id == chat.id }.presetId != chat.presetId
            change(updated)
            status(if (adapted) "已选择 $modelId；已建立兼容预设，思考档位或预算按新模型调整，原写作预设保留" else "已选择 $modelId")
        }.onFailure { status(it.message ?: "切换模型失败") }
    }
    override fun send(text: String): Boolean = sendWithIntent(text, "auto")
    override fun sendWithIntent(text: String, intent: String): Boolean {
        if(text.isBlank() && mutable.value.pendingAttachments.isEmpty()) return false
        if(mutable.value.loading || loadFailed) { status("本地数据尚未就绪，消息未发送"); return false }
        if(selected==null) createChat()
        val chat=selected ?: return false
        chatTaskRefusal(chat.id, ChatTask.REPLY)?.let { status(it); return false }
        if(chat.modelId.isBlank()) { status("请先在模型页面刷新并选择聊天模型"); return false }
        val attachments=mutable.value.pendingAttachments
        val model=data.providers.find { it.id==chat.providerId }?.models?.find { it.id==chat.modelId }
        if(attachments.any { it.mime.startsWith("image/") } && model?.supportsVision!=true) { status("当前模型未声明视觉能力，请选择支持图片的模型"); return false }
        return try {
            val message=Message(content=text.trim(),attachments=attachments,intent=ConversationTurns.resolveIntent(text,intent))
            changeChat(chat.id) { ConversationTurns.append(it,message).copy(title=if(it.messages.isEmpty() && it.title=="新会话") text.trim().take(24).ifBlank { "附件会话" } else it.title) }
            disableExcludedMemories(chat.id)
            mutable.value=mutable.value.copy(pendingAttachments=emptyList())
            chatTask(chat.id) { generate(chat.id) }
            true
        } catch (error: Exception) {
            // Sending is invoked directly from a Compose click handler. A
            // malformed legacy/imported state must become an actionable status,
            // rather than an uncaught exception that closes the Activity.
            status("消息未发送：${error.message?.take(500) ?: "本地会话数据无效，请导出后修复"}")
            false
        }
    }
    private suspend fun generate(chatId: String) {
        var liveId=""
        val generationId=newId()
        val run = currentCoroutineContext()[ChatRun] ?: error("生成任务没有绑定会话")
        try {
            var chat=current(chatId)
            var context=buildContext(data, chat)
            recordContextBudget(context)
            if(context.needsCompression && data.settings.autoCompress) { summarize(chatId); chat=current(chatId); context=buildContext(data, chat) }
            require(context.estimatedTokens<=context.budget) { overBudgetMessage("上下文超出预算：请压缩历史、减少固定内容或调整上下文窗口",context) }
            val p=provider(chat.providerId); val secret=key(p.id); val model=p.models.find { it.id==chat.modelId }
            require(model!=null && model.type=="text" && !model.offline) { "模型不在可用聊天目录中，请刷新目录或手动添加模型" }
            val toolPolicy = ToolTurnPolicy()
            val maxToolSteps = data.settings.maxToolSteps.coerceIn(1, 16)
            // Tool rounds have a separate budget; always reserve a final response after them.
            for(step in 0..maxToolSteps) {
                currentCoroutineContext().ensureActive()
                val finalAnswerOnly = toolPolicy.finalAnswerRequired(step, maxToolSteps)
                fun requestTokens(built: ContextBuild): Int = built.estimatedTokens + if (finalAnswerOnly) {
                    ContextEngine.estimateTokens(JSONObject().put("role", "system").put("content", toolPolicy.finalInstruction).toString()) -
                        (if (built.toolDefinitions == "[]") 0 else ContextEngine.estimateTokens(built.toolDefinitions))
                } else 0
                var before=current(chatId)
                var built=buildContext(data, before)
                if (step > 0 && requestTokens(built) > built.budget && built.needsCompression && data.settings.autoCompress) {
                    val latestUser = before.messages.indexOfLast { it.role == "user" && !it.contextExcluded }
                    val boundary = built.compression?.throughMessageId?.let { id -> before.messages.indexOfFirst { it.id == id } } ?: -1
                    // Only older complete turns may be compacted here. Current lookup evidence
                    // must reach the model intact, never disappear inside a prose-only summary.
                    if (boundary in 0 until latestUser) {
                        summarize(chatId)
                        before = current(chatId)
                        built = buildContext(data, before)
                    }
                }
                val outgoing = if (finalAnswerOnly) toolPolicy.finalMessages(built.messagesJson) else built.messagesJson
                val toolDefinitions = if (finalAnswerOnly) "[]" else built.toolDefinitions
                val outgoingTokens = requestTokens(built)
                require(outgoingTokens <= built.budget) { overBudgetMessage("本轮工具结果已保存，但最终回答的上下文超出预算", built) }
                val userTurn=before.messages.lastOrNull { it.role=="user" && !it.contextExcluded }
                val assistant=Message(role="assistant",status="streaming",intent=if(userTurn?.intent=="correction") "correction" else "dialogue",generationId=generationId,revisionOf=userTurn?.revisionOf.orEmpty(),
                    skillSnapshotJson=SkillRuntimeSnapshot.manifest(ToolRuntime.activeSkills(data.skills,data.settings,before),model.supportsTools))
                liveId=assistant.id
                changeChat(chatId) { it.copy(messages=it.messages+assistant) }
                var streamed=StreamDelta(); var lastSave=System.currentTimeMillis()
                val updates = Channel<StreamDelta>(Channel.CONFLATED)
                val collector = viewModelScope.launch {
                    for (snapshot in updates) {
                        val persist=System.currentTimeMillis()-lastSave>900
                        if(persist) lastSave=System.currentTimeMillis()
                        changeChat(chatId,persist,touch=false) { c -> c.copy(messages=c.messages.map { if(it.id==assistant.id && it.status=="streaming") it.copy(content=snapshot.content,reasoning=snapshot.reasoning) else it }) }
                    }
                }
                val result = try {
                    run.purpose.set(if (finalAnswerOnly) "chat_final" else "chat")
                    run.client.chat(routed(p),secret,before.modelId,outgoing,preset(before),toolDefinitions,
                        metadata = if (p.kind.equals("writing-server", ignoreCase = true)) mapOf(
                            "pocket_tavern_chat_id" to chatId,
                            "pocket_tavern_purpose" to "chat"
                        ) else emptyMap(),
                        finalAnswerOnly=finalAnswerOnly) { delta ->
                        streamed=delta.appendTo(streamed)
                        updates.trySend(streamed)
                    }
                } finally {
                    updates.close()
                    // Drain the last received text before cancellation marks this message.
                    withContext(NonCancellable) { collector.join() }
                }
                val calls=JSONArray(result.toolCallsJson)
                // A provider may include the state marker alongside a tool call.
                // Apply only when the marker is present; ordinary tool-planning
                // prose remains unchanged and still proceeds through the tool loop.
                val synced = ScenarioStateSync.apply(before, result.content)
                // The app, not the model, decides whether this turn may carry a sticker:
                // extra, unknown or disallowed markers are dropped before saving.
                val stickerList = stickersFor(before)
                val stickerTurn = StickerGate.decide(before, stickerList.isNotEmpty())
                val visible = StickerMarkup.normalize(synced.visibleContent, stickerTurn != StickerGate.Turn.NONE, stickerList)
                changeChat(chatId) { c -> c.copy(
                    scenarioState = synced.chat.scenarioState,
                    scenarioRoster = synced.chat.scenarioRoster,
                    messages=c.messages.map { if(it.id==assistant.id) it.copy(content=visible,reasoning=result.reasoning,toolCallsJson=result.toolCallsJson,status="complete",finishReason=result.finishReason,requestId=result.requestId,
                        qualityNotice=before.messages.firstOrNull { old -> old.id==assistant.revisionOf }?.let { old -> ConversationTurns.rewriteNotice(old.content,visible) }.orEmpty()) else it }) }
                if(calls.length()==0) return
                require(!finalAnswerOnly) { "模型在最终回答阶段仍返回工具调用；已停止继续执行，已有结果保留，可重新生成本轮。" }
                require(model?.supportsTools==true) { "模型返回了未启用的工具调用" }
                for(i in 0 until calls.length()) {
                    currentCoroutineContext().ensureActive()
                    val call=calls.getJSONObject(i); val function=call.getJSONObject("function")
                    val name = function.getString("name")
                    val arguments = function.optString("arguments", "{}")
                    val decision = toolPolicy.beforeCall(name, arguments)
                    val toolResult=if (!decision.execute) ToolResult(decision.response.orEmpty()) else try {
                        if (name in setOf("history_search", "history_read")) {
                            val toolContext = buildContext(data, current(chatId))
                            val remaining = (toolContext.budget - toolContext.estimatedTokens - 512).coerceAtLeast(0)
                            val allowance = minOf(4096, (remaining / (calls.length() - i) - 96).coerceAtLeast(0))
                            ToolResult(withContext(Dispatchers.Default) {
                                KnowledgeVault.toolResponse(current(chatId),name,arguments,allowance)
                            })
                        } else if (name == DshTool.NAME) runDsh(arguments, current(chatId))
                        else if (name == ReachTool.NAME) runReach(arguments, current(chatId))
                        else if (name == CharacterCardTool.NAME) saveCharacterCard(arguments, current(chatId))
                        else tools.execute(name,arguments,data.skills,data,current(chatId))
                    }
                    catch(error: Exception) { if(error is CancellationException) throw error; ToolResult("工具执行失败：${error.message}") }
                    if (decision.execute) toolPolicy.recordResult(name, arguments, toolResult.content)
                    toolResult.memories?.let { added -> change(data.copy(memories=(data.memories+added).associateBy { it.id }.values.toList())) }
                    changeChat(chatId) { c -> c.copy(messages=c.messages+Message(role="tool",content=toolResult.content,toolCallId=call.getString("id"),generationId=generationId,intent=assistant.intent)) }
                }
                toolPolicy.completedRound((0 until calls.length()).map { calls.getJSONObject(it).getJSONObject("function").getString("name") })
                if (toolPolicy.shouldFinalize || step + 1 >= maxToolSteps) {
                    runCatching { requestLogs.record(JSONObject().put("event", "tool_turn_finished").put("purpose", "tool_control")
                        .put("phase", "final_answer").put("outcome", toolPolicy.reason.ifBlank { "tool_step_limit" })
                        .put("tool_round", step + 1).toString()) }
                }
            }
            error("本轮未收到最终回复；工具结果已保存，可重新生成本轮。")
        } catch(error: Exception) {
            val cancelled = error is CancellationException || !currentCoroutineContext().isActive
            val failure = error as? ProviderException
            if (failure?.kind in setOf(ProviderException.Kind.NETWORK, ProviderException.Kind.TIMEOUT)) pcRouter.forget()
            val partial = failure?.partial
            val errorText = if(cancelled) "" else error.message?.take(800) ?: "生成失败，请查看请求日志"
            if(liveId.isNotEmpty()) changeChat(chatId) { c -> c.copy(messages=c.messages.map { if(it.id==liveId) it.copy(
                status=if(cancelled) "stopped" else "error", errorMessage=errorText,
                content=partial?.content?.takeIf(String::isNotBlank) ?: it.content,
                reasoning=partial?.reasoning?.takeIf(String::isNotBlank) ?: it.reasoning,
                finishReason=failure?.finishReason?.ifBlank { it.finishReason } ?: it.finishReason,
                requestId=failure?.requestId?.ifBlank { it.requestId } ?: it.requestId
            ) else it }) }
            else changeChat(chatId) { c ->
                val userTurn=c.messages.lastOrNull { it.role=="user" && !it.contextExcluded }
                c.copy(messages=c.messages+Message(role="assistant",generationId=generationId,
                intent=if(userTurn?.intent=="correction") "correction" else "dialogue",revisionOf=userTurn?.revisionOf.orEmpty(),
                status=if(cancelled) "stopped" else "error", errorMessage=errorText,
                finishReason=failure?.finishReason.orEmpty(),requestId=failure?.requestId.orEmpty())) }
            repairPendingTools(chatId)
            throw error
        }
    }
    private fun recordContextBudget(context: ContextBuild) {
        val budget = context.budgetBreakdown ?: return
        runCatching { requestLogs.record(JSONObject().put("event", "context_budget").put("purpose", "context_budget")
            .put("phase", "planning").put("outcome", budget.blockedReason.ifBlank { budget.triggerReason })
            .put("context_input_tokens", budget.estimatedInputTokens).put("context_input_budget", budget.inputBudget)
            .put("context_trigger_tokens", budget.triggerTokens).put("context_skill_tokens", budget.skillTokens)
            .put("context_summary_tokens", budget.summaryTokens).put("context_recent_tokens", budget.recentTokens)
            .put("context_tool_tokens", budget.toolTokens).put("context_model_window", budget.modelWindow)
            .put("context_daily_limit", budget.configuredInputLimit).toString()) }
    }
    private fun overBudgetMessage(base: String, context: ContextBuild): String {
        val heavy=context.skillTokens.sortedByDescending { it.second }.take(3).joinToString("、") { "${it.first} 约 ${it.second}" }
        val detail="（估算 ${context.estimatedTokens}/${context.budget} tokens"+(if(heavy.isBlank()) "" else "；已选 Skills：$heavy tokens")+"；可减少固定内容、保留轮数，或到设置提高日常输入预算；预算仍受模型容量限制）"
        return base+detail+context.budgetBreakdown?.let { "\n${CompressionScope.describeBudget(it)}" }.orEmpty()
    }
    private fun repairPendingTools(chatId: String) {
        val chat=data.chats.find { it.id==chatId } ?: return
        val result=mutableListOf<Message>()
        var index=0
        while(index<chat.messages.size) {
            val message=chat.messages[index++]; result+=message
            if(message.role!="assistant" || message.toolCallsJson=="[]") continue
            val calls=runCatching { JSONArray(message.toolCallsJson) }.getOrNull() ?: continue
            val ids=(0 until calls.length()).map { calls.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
            val answered=mutableSetOf<String>()
            while(index<chat.messages.size && chat.messages[index].role=="tool") { val tool=chat.messages[index++]; result+=tool; answered+=tool.toolCallId }
            (ids-answered).forEach { result+=Message(role="tool",toolCallId=it,content="该工具在执行完成前被停止或失败，未确认完成。",generationId=message.generationId,intent=message.intent,contextExcluded=message.contextExcluded) }
        }
        if(result!=chat.messages) changeChat(chatId) { it.copy(messages=result) }
    }
    /** Stops what the open chat is doing; other chats keep going. */
    override fun stop() = stopChat(mutable.value.selectedChatId)
    override fun stopChat(chatId: String) {
        val run = chatRuns[chatId] ?: return
        stopRun(run, if (run.kind == ChatTask.REBUILD) "已停止记忆重建" else "已停止；已收到的内容已保留")
    }
    override fun stopAppTask() = cancelOperation("已停止")
    private fun cancelOperation(reason: String) {
        stopReason = reason
        operation?.cancel(); providerClient.cancel(); billingClient.cancel(); mediaClient.cancel(); installationCall?.cancel()
    }
    private fun disableExcludedMemories(chatId: String) {
        val memories=ConversationTurns.disableExcludedMemories(current(chatId),data.memories)
        if(memories!=data.memories) change(data.copy(memories=memories))
    }
    override fun regenerate() {
        val chat=selected ?: return
        if(mutable.value.loading || loadFailed) { status("本地数据尚未就绪，未重新生成"); return }
        chatTaskRefusal(chat.id, ChatTask.REPLY)?.let { status(it); return }
        if(chat.messages.none { it.role=="user" && !it.contextExcluded }) { status("请先发送一条消息"); return }
        changeChat(chat.id) { ConversationTurns.regenerate(it) }
        disableExcludedMemories(chat.id)
        chatTask(chat.id) { generate(chat.id) }
    }
    override fun setMessageContext(id: String, included: Boolean) {
        val chat=selected ?: return
        if(chatLocked(chat.id)) return
        changeChat(chat.id) { ConversationTurns.setIncluded(it,id,included) }
        disableExcludedMemories(chat.id)
        status(if(included) "已恢复该轮参与上下文；请确认与后续剧情一致" else "已保留原文并排除该轮；相关旧摘要会自动失效")
    }
    override fun saveContinuityNotes(chatId: String, text: String) {
        if(chatLocked(chatId)) return
        if(text.length>8000) { status("当前场景最多 8000 字符"); return }
        if(data.chats.none { it.id==chatId }) { status("会话已不存在"); return }
        changeChat(chatId) { it.copy(continuityNotes=text.trim()) }
        status("当前场景已保存，下一轮生效")
    }
    override fun saveResearchSearchKey(key: String) {
        if (mutable.value.busy || mutable.value.loading || loadFailed) { status("请等待当前任务结束后保存搜索 Key"); return }
        val secret = key.trim()
        if (secret.isBlank()) { status("请输入豆包搜索专用 Key"); return }
        runCatching { app.secrets.put(DoubaoSearchClient.CREDENTIAL_ID, secret) }
            .onSuccess { mutable.value = mutable.value.copy(hasResearchSearchKey = true); status("豆包搜索 Key 已加密保存") }
            .onFailure { status("搜索 Key 保存失败，请重试") }
    }

    override fun openDoubaoWebResearch(chatId: String, subject: String, categories: List<String>, allowFiction: Boolean) {
        if (mutable.value.loading || loadFailed) { status("本地数据尚未就绪"); return }
        if (mutable.value.busy) { status("请先停止当前任务"); return }
        runCatching {
            require(data.chats.any { it.id == chatId }) { "目标会话不存在" }
            val normalized = categories.mapNotNull { ResearchCategory.fromWire(it)?.wireName }.distinct()
            require(normalized.isNotEmpty()) { "至少选择一个研究范围" }
            val request = DoubaoWebRequest(newId(), chatId, subject.trim(), normalized, allowFiction)
            host?.openDoubaoWebResearch(request.id, chatId, request.subject, normalized, allowFiction, DoubaoWebResearch.prompt(request))
                ?: error("当前页面无法打开豆包网页")
            status("已打开豆包网页；登录后会自动提交研究问题并回传结果")
        }.onFailure { status(it.message ?: "无法打开豆包网页") }
    }

    override fun importDoubaoWebAnswer(
        requestId: String, chatId: String, subject: String, categories: List<String>, allowFiction: Boolean,
        text: String, pageUrl: String, sourceLinks: List<String>, mediaDescription: String
    ) {
        var savedNoteId = ""
        action(
            onStarted = { status("正在保存豆包网页版原始回答…") },
            onFinished = { error, cancelled ->
                if (error == null && !cancelled && savedNoteId.isNotBlank()) status("豆包网页版回答已保存到会话知识库；已标记为原始回答，未逐项核验")
            },
            verifySavedState = {
                val restored = withContext(Dispatchers.IO) { app.database.state().load()?.let { DataCodec.decode(it.json) } }
                check(restored?.chats?.find { it.id == chatId }?.knowledgeNotes?.any { it.id == savedNoteId } == true) { "未能从数据库读回豆包网页版研究记录" }
            }
        ) {
            val request = DoubaoWebRequest(requestId, chatId, subject, categories, allowFiction)
            val answer = DoubaoWebAnswer(requestId, text, pageUrl, sourceLinks, mediaDescription)
            val saved = DoubaoWebResearch.save(current(chatId), request, answer)
            change(data.copy(chats = data.chats.map { if (it.id == chatId) saved.chat.copy(updatedAt = System.currentTimeMillis()) else it }))
            savedNoteId = saved.note.id
        }
    }
    override fun researchCharacter(
        chatId: String,
        subject: String,
        categories: List<String>,
        useOnline: Boolean,
        allowFiction: Boolean,
        saveToKnowledgeBase: Boolean,
        researchProviderId: String,
        researchModelId: String,
        searchMode: String
    ) {
        if (subject.isBlank()) { status("请填写人物或主题"); return }
        if (categories.isEmpty()) { status("至少选择一个研究范围"); return }
        var savedNoteId = ""
        var completionNotice = ""
        chatTask(
            chatId, ChatTask.RESEARCH,
            onStarted = { status("正在调用豆包联网检索并整理人物资料…") },
            onFinished = { error, cancelled ->
                if (error == null && !cancelled && savedNoteId.isNotBlank()) status(completionNotice)
            },
            verifySavedState = {
                val restored = withContext(Dispatchers.IO) {
                    app.database.state().load()?.let { DataCodec.decode(it.json) }
                }
                check(restored?.chats?.find { it.id == chatId }?.knowledgeNotes?.any { it.id == savedNoteId && it.sourceKind == "research" } == true) {
                    "未能从数据库读回本次研究记录"
                }
            }
        ) {
            val chat = current(chatId)
            require(useOnline) { "人物研究需要开启豆包联网搜索" }
            val researchProvider = provider(researchProviderId)
            require(DoubaoResearchClient.isSupportedBaseUrl(researchProvider.baseUrl)) {
                "请配置豆包·火山方舟官方 API 平台"
            }
            val parsedCategories = categories.mapNotNull { ResearchCategory.fromWire(it) }.distinct()
            require(parsedCategories.isNotEmpty() && parsedCategories.size == categories.distinct().size) { "研究范围无效，请重新选择" }
            val request = PublicResearchRequest(subject.trim(), parsedCategories, allowFiction = allowFiction)
            require(request.validate().isEmpty()) { request.validate().joinToString("；") }
            require(researchModelId.isNotBlank() && researchModelId.length <= 200) { "请填写已开通联网搜索的豆包模型 ID 或推理接入点" }
            val previous = chat.knowledgeNotes.filter { it.sourceKind == "research" }
            require(previous.size < KnowledgeVault.MAX_RESEARCH_NOTES && previous.sumOf { it.text.length } < KnowledgeVault.MAX_RESEARCH_TOTAL_CHARS) {
                "研究资料容量已满，请先整理已有资料"
            }
            require(searchMode in setOf("direct", "native")) { "联网方式无效" }
            val targetProfile = PublicResearch.profileUrlSubject(request.subject)
            val result = if (searchMode == "direct") {
                val searchKey = app.secrets.get(DoubaoSearchClient.CREDENTIAL_ID)
                require(searchKey.isNotBlank()) { "请先配置豆包搜索专用 Key" }
                val sources = try {
                    directSearchClient.search(searchKey, request)
                        .filter { PublicResearch.isRelevantSourceHit(it, request) }
                } catch (error: ProviderException) {
                    if (targetProfile != null && error.kind == ProviderException.Kind.EMPTY_RESPONSE) emptyList() else throw error
                }
                currentCoroutineContext().ensureActive()
                val observedAt = java.time.Instant.now().toString()
                if (targetProfile != null && sources.isEmpty()) {
                    PublicResearchResult(request.subject, observedAt, emptyList(),
                        warnings = listOf("本次豆包搜索没有找到可关联到该主页的资料。"), networkStatus = "unverified")
                } else {
                    require(sources.isNotEmpty()) { "搜索结果与查询对象无可靠对应关系，未保存资料；请补充姓名、职业或作品名后重试" }
                    status("已获取 ${sources.size} 条来源，正在让豆包整理六类资料…")
                    val run = currentCoroutineContext()[ChatRun] ?: error("研究任务没有绑定会话")
                    run.purpose.set("research_summary")
                    val summary = run.client.chat(routed(researchProvider), key(researchProviderId), researchModelId.trim(),
                        ResearchWorkflow.summaryMessages(request, sources, observedAt), Preset(temperature = 0.3, maxOutput = 10_000, generationBudgetEnabled = true),
                        metadata = if (researchProvider.kind.equals("writing-server", ignoreCase = true)) mapOf(
                            "pocket_tavern_chat_id" to chatId,
                            "pocket_tavern_purpose" to "summary"
                        ) else emptyMap())
                    require(summary.finishReason.isBlank() || summary.finishReason == "stop") { "豆包资料总结未完整结束，本次未保存，请重试" }
                    ResearchWorkflow.parseSummary(summary.content, request, sources, observedAt)
                }
            } else researchClient.research(researchProvider.baseUrl, key(researchProviderId), researchModelId.trim(), request)
            currentCoroutineContext().ensureActive()
            val unverifiedProfile = targetProfile != null && result.networkStatus == "unverified"
            val saved = if (unverifiedProfile) {
                ResearchWorkflow.saveUnverifiedProfile(current(chatId), requireNotNull(targetProfile),
                    result.warnings.joinToString("；"), researchModelId.trim())
            } else ResearchWorkflow.save(current(chatId), result, researchModelId.trim(), saveToKnowledgeBase)
            change(data.copy(chats = data.chats.map { if (it.id == chatId) saved.chat.copy(updatedAt = System.currentTimeMillis()) else it },
                settings = data.settings.copy(researchProviderId = researchProviderId, researchModelId = researchModelId.trim(), researchSearchMode = searchMode)))
            savedNoteId = saved.note.id
            completionNotice = when {
                unverifiedProfile -> "主页链接已保存为待核验资料，可在研究面板查看；本次未获取到可核验的人物信息"
                !saveToKnowledgeBase && saved.knowledgeOnly -> "研究已保存到会话知识库；当前场景放不下完整资料，原场景已保留。可在研究面板查看全文"
                saved.knowledgeOnly -> "豆包研究完成并已保存；可在研究面板查看全文与来源，继续聊天时可检索调用"
                else -> "豆包研究完成并已保存；资料已加入当前场景，下一轮生效"
            }
            if (!unverifiedProfile && result.warnings.isNotEmpty()) completionNotice += "。部分资料存在缺项或待核实提示，请查看研究记录"
        }
    }
    override fun applyDeepSeekWriting(chatId: String, effort: String, maxOutput: Int, includePlatformPrompt: Boolean, reasoningBudget: Int, generationBudgetEnabled: Boolean) {
        if (mutable.value.loading || loadFailed) { status("请等待本地数据读取完成后再应用"); return }
        if (chatLocked(chatId)) return
        runCatching {
            change(DeepSeekWriting.apply(data, chatId, effort, maxOutput, includePlatformPrompt, reasoningBudget, generationBudgetEnabled))
            status("DeepSeek 写作参数已应用，下一轮生效；可在预设中选回原方案")
        }.onFailure { status(it.message ?: "写作参数应用失败") }
    }
    override fun editMessage(id: String, text: String) {
        val chat=selected ?: return
        if(chatLocked(chat.id)) return
        changeChat(chat.id) { c -> c.copy(messages=c.messages.map { if(it.id==id) it.copy(content=text) else it }) }
        change(data.copy(memories=data.memories.map { memory ->
            if (memory.scope=="chat" && memory.targetId==chat.id && memory.sourceMessageId==id) memory.copy(enabled=false) else memory
        }))
    }
    override fun branchAt(messageId: String) {
        val chat=selected ?: return; val index=chat.messages.indexOfFirst { it.id==messageId }; if(index<0) return
        if(chatLocked(chat.id)) return
        val branch=chat.copy(id=newId(),title=chat.title+" · 分支",messages=chat.messages.take(index+1),activeSummaryId="",summaries=emptyList())
        val copiedMemories=data.memories.filter { it.scope=="chat" && it.targetId==chat.id && (it.sourceMessageId.isBlank() || branch.messages.any { m -> m.id==it.sourceMessageId }) }.map { it.copy(id=newId(),targetId=branch.id) }
        change(data.copy(chats=listOf(branch)+data.chats,memories=data.memories+copiedMemories)); repairPendingTools(branch.id); selectChat(branch.id)
    }
    override fun savePreset(preset: Preset) {
        if(preset.name.isBlank() || preset.maxOutput !in 1..131072 || preset.reasoningBudget !in 0..131072 || preset.temperature !in 0.0..2.0 || preset.reasoningEffort !in PresetOptions.reasoningEfforts) { status("预设名称或生成参数无效"); return }
        change(data.copy(presets=data.presets.filterNot { it.id==preset.id }+preset))
    }
    override fun deletePreset(id: String) { if(data.presets.size<=1) { status("至少保留一个预设"); return }; change(data.copy(presets=data.presets.filterNot { it.id==id })) }
    override fun saveCharacter(character: Character) {
        if(character.name.isBlank()) { status("请填写角色名称"); return }
        // The avatar changes through its own buttons; a dialog opened earlier must not undo that.
        val avatar = data.characters.firstOrNull { it.id == character.id }?.avatar ?: character.avatar
        change(data.copy(characters=data.characters.map { if (it.id == character.id) character.copy(avatar = avatar) else it }.let { list -> if (list.any { it.id == character.id }) list else list + character.copy(avatar = avatar) }))
    }

    private val avatarCache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(24)

    /** A character's picture, decoded small; blocking, so the UI calls it off the main thread. */
    override fun characterAvatar(avatar: String): androidx.compose.ui.graphics.ImageBitmap? {
        if (avatar.isBlank()) return null
        avatarCache.get(avatar)?.let { return it }
        val bytes = CharacterAvatars.assetPath(avatar)?.let { path -> runCatching { app.assets.open(path).use { it.readBytes() } }.getOrNull() }
            ?: CharacterAvatars.file(app.filesDir, avatar)?.takeIf { it.isFile }?.readBytes() ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bitmap.asImageBitmap().also { avatarCache.put(avatar, it) }
    }

    override fun pickCharacterAvatar(characterId: String) { host?.pickAvatar(characterId) }

    /** Stores the chosen picture as a square 512 px PNG and points the character at it. */
    internal fun setCharacterAvatar(characterId: String, bytes: ByteArray) {
        val character = data.characters.firstOrNull { it.id == characterId } ?: return status("角色不存在")
        runCatching {
            val avatar = storeAvatar(characterId, bytes)
            CharacterAvatars.file(app.filesDir, character.avatar)?.delete()
            change(data.copy(characters = data.characters.map { if (it.id == characterId) it.copy(avatar = avatar) else it }))
            status("头像已更新")
        }.onFailure { status("头像设置失败：${it.message ?: "图片无法读取"}") }
    }

    override fun removeCharacterAvatar(characterId: String) {
        val character = data.characters.firstOrNull { it.id == characterId } ?: return
        CharacterAvatars.file(app.filesDir, character.avatar)?.delete()
        change(data.copy(characters = data.characters.map { if (it.id == characterId) it.copy(avatar = "") else it }))
    }

    private fun storeAvatar(characterId: String, bytes: ByteArray): String {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "不是可识别的图片" }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
        val decoded = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })) { "图片解码失败" }
        val side = minOf(decoded.width, decoded.height)
        // Portraits keep the top of the picture, where the face usually is.
        val top = if (decoded.height > decoded.width) ((decoded.height - side) * 0.15).toInt() else (decoded.height - side) / 2
        val square = android.graphics.Bitmap.createBitmap(decoded, (decoded.width - side) / 2, top, side, side)
        val scaled = square.scale(512, 512)
        val name = CharacterAvatars.newFileName(characterId, System.currentTimeMillis())
        val target = File(File(app.filesDir, CharacterAvatars.DIR).apply { mkdirs() }, name)
        target.outputStream().use { require(scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) { "图片保存失败" } }
        return "file:${CharacterAvatars.DIR}/$name"
    }
    override fun deleteCharacter(id: String) {
        data.characters.firstOrNull { it.id == id }?.let { CharacterAvatars.file(app.filesDir, it.avatar)?.delete() }
        change(data.copy(characters=data.characters.filterNot { it.id==id }))
    }
    override fun saveMemory(memory: Memory) {
        runCatching {
            require(memory.text.isNotBlank() && memory.text.length<=8000) { "记忆内容应为 1–8000 字符" }
            require(memory.scope in listOf("global","chat","character")) { "记忆作用域无效" }
            val target=memory.targetId.ifBlank { when(memory.scope) { "chat"->mutable.value.selectedChatId; "character"->selected?.characterId.orEmpty(); else->"" } }
            require(memory.scope=="global" || target.isNotBlank()) { "请先选择目标会话或角色" }
            require(memory.scope!="chat" || data.chats.any { it.id==target }) { "目标会话不存在" }
            require(memory.scope!="character" || data.characters.any { it.id==target }) { "目标角色不存在" }
            val m=memory.copy(targetId=target,updatedAt=System.currentTimeMillis())
            val entries=data.memories.filterNot { it.id==m.id }+m
            require(entries.size<=2000 && entries.sumOf { it.text.length }<=4*1024*1024) { "记忆容量已满，请整理后重试" }
            change(data.copy(memories=entries))
        }.onFailure { status(it.message ?: "记忆保存失败") }
    }
    override fun deleteMemory(id: String) { change(data.copy(memories=data.memories.filterNot { it.id==id })) }
    override fun saveWorldEntry(entry: WorldEntry) { change(data.copy(worldEntries=data.worldEntries.filterNot { it.id==entry.id }+entry)) }
    override fun deleteWorldEntry(id: String) { change(data.copy(worldEntries=data.worldEntries.filterNot { it.id==id })) }
    override fun saveSkill(skill: SkillRecord) { change(data.copy(skills=data.skills.filterNot { it.id==skill.id }+skill)) }
    override fun setSkillForCurrentChat(id: String, selected: Boolean) {
        if(mutable.value.loading || loadFailed) { status("本地数据尚未就绪"); return }
        val chat=this.selected ?: run { status("请先创建或选择会话"); return }
        if(chatLocked(chat.id)) return
        runCatching {
            change(BuiltinSkills.selectForChat(data,chat.id,id,selected))
            status(if(selected) "Skill 已启用并加入当前会话" else "Skill 已移出当前会话，其它会话不受影响")
        }.onFailure { status(it.message ?: "无法更新 Skill 选择") }
    }
    override fun updateChatSkills(chatId: String, skillIds: List<String>, summaries: List<Summary>) {
        if(mutable.value.loading || loadFailed) { status("本地数据尚未就绪"); return }
        if(chatLocked(chatId)) return
        val blocked = if(data.settings.adultSkillsConfirmed) emptySet() else data.skills.filter { it.adult }.map { it.id }.toSet()
        runCatching {
            val updated = applyChatSkillUpdate(data,chatId,skillIds,summaries)
            change(updated)
            status(if(skillIds.any { it in blocked }) "成人向 Skill 需要先确认已年满 18 岁，并仅用于成年角色" else "会话 Skills 与注入顺序已保存")
        }.onFailure { status(it.message ?: "无法保存会话 Skills") }
    }
    override fun installSkillUrl(url: String) = action {
        val uri=Uri.parse(url.trim())
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null) { "请输入 HTTPS 的 SKILL.md 或 ZIP 下载地址" }
        val bytes=withContext(Dispatchers.IO) {
            val client=okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(40,java.util.concurrent.TimeUnit.SECONDS).build()
            val call=client.newCall(okhttp3.Request.Builder().url(url.trim()).get().build())
            installationCall=call
            try { call.execute().use { response ->
                require(response.isSuccessful) { "下载失败 HTTP ${response.code}，请使用直接下载地址" }
                val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                response.body?.byteStream()?.use { input ->
                    while(true) { val n=input.read(buffer); if(n<0) break; require(out.size()+n<=25*1024*1024) { "技能包超过 25 MB" }; out.write(buffer,0,n) }
                } ?: error("下载内容为空")
                out.toByteArray()
            } } finally { installationCall=null }
        }
        val skills=SkillImporter.importFile(uri.lastPathSegment ?: "SKILL.md",bytes).map { it.copy(source=url.trim()) }
        change(data.copy(skills=data.skills+skills)); status("已安装 ${skills.size} 个 Skill；查看内容并授予所需权限后启用")
    }
    override fun deleteSkill(id: String) { change(data.copy(skills=data.skills.filterNot { it.id==id },chats=data.chats.map { it.copy(skillIds=it.skillIds-id) })) }
    override fun restoreBuiltinSkill(id: String) {
        if(mutable.value.loading || loadFailed) { status("本地数据尚未就绪"); return }
        if(mutable.value.busy) { status("请先停止当前任务再安装 Skills"); return }
        runCatching {
            change(BuiltinSkills.restore(data,builtinSkills,id))
            status("已安装内置原版；尚未启用，也未加入会话")
        }.onFailure { status(it.message ?: "安装内置原版失败") }
    }
    override fun saveSettings(settings: AppSettings) {
        if(settings.keepRecentTurns !in 1..100 || settings.compressionThreshold !in 0.2..0.95 || settings.maxToolSteps !in 1..16 || settings.contextOverride !in 0..2000000 || settings.maxContextInputTokens !in 0..2000000) { status("设置超出允许范围"); return }
        change(data.copy(settings=settings.copy(builtinSkillsSeen=data.settings.builtinSkillsSeen,builtinScenariosSeen=data.settings.builtinScenariosSeen,adultSkillsConfirmed=data.settings.adultSkillsConfirmed,builtinSkillsRevision=data.settings.builtinSkillsRevision))); status("设置已保存")
    }
    override fun confirmAdultSkills(confirmed: Boolean) {
        if(mutable.value.busy) { status("请先停止当前任务"); return }
        val adultIds=data.skills.filter { it.adult }.map { it.id }.toSet()
        val chats=if(confirmed) data.chats else data.chats.map { chat -> chat.copy(skillIds=chat.skillIds.filterNot { it in adultIds }) }
        change(data.copy(chats=chats,settings=data.settings.copy(adultSkillsConfirmed=confirmed)))
        status(if(confirmed) "已确认：成人向 Skills 仅用于成年角色" else "已关闭成人向 Skills，并从所有会话中移除")
    }
    override fun compress() { selected?.id?.let { id -> chatTask(id, ChatTask.COMPRESS) { summarize(id); status("摘要已保存，原始消息完整保留") } } }
    override fun rebuildHistory() {
        selected?.id?.let { id ->
            var expectedNoteIds: List<String>? = null
            chatTask(
                id, ChatTask.REBUILD,
                onStarted = { mutable.value = mutable.value.copy(memoryRebuild = MemoryRebuildState(chatId = id)) },
                verifySavedState = {
                    val result = mutable.value.memoryRebuild?.takeIf { it.chatId == id }
                        ?: error("本次重建记录缺失，请导出记忆诊断")
                    val noteIds = expectedNoteIds ?: error("本次分段记忆记录缺失，请导出记忆诊断")
                    withContext(Dispatchers.IO) {
                        val persisted = app.database.state().load()?.let { DataCodec.decode(it.json) }
                            ?.chats?.singleOrNull { it.id == id }
                            ?: error("数据库中未找到本次会话，请导出记忆诊断")
                        MemoryRebuilder.verifyApplied(persisted, result.summaryId, result.sourceMessageCount, noteIds)
                    }
                    MemoryRebuilder.verifyApplied(current(id), result.summaryId, result.sourceMessageCount, noteIds)
                },
                onFinished = { error, cancelled ->
                    val progress = mutable.value.memoryRebuild?.takeIf { it.chatId == id }
                    if (progress != null) {
                        val message = when {
                            error == null -> "重建完成并已保存：覆盖 ${progress.sourceMessageCount} 条原文、${progress.noteCount} 段记忆，新摘要已启用并通过保存后校验。可继续当前会话。"
                            progress.noteCount > 0 -> "记忆已生成，但未能确认保存或启用结果；请先导出记忆诊断。\n$error"
                            cancelled -> "重建已中断，原摘要与分段记忆未替换。\n$error"
                            else -> "重建失败，原摘要与分段记忆未替换。\n$error"
                        }
                        mutable.value = mutable.value.copy(memoryRebuild = progress.copy(
                            phase = if (error == null) "success" else if (cancelled) "cancelled" else "error",
                            message = message,
                            finishedAt = System.currentTimeMillis()
                        ))
                        status(message)
                    }
                }
            ) { expectedNoteIds = compacting { rebuildHistory(id) } }
        }
    }
    private suspend fun rebuildHistory(chatId: String): List<String> {
        val chat=current(chatId)
        val slice=MemoryRebuilder.snapshot(chat)
        val active=preset(chat)
        val p=provider(active.summaryProviderId.ifBlank { chat.providerId })
        val modelId=active.summaryModelId.ifBlank { chat.modelId }
        require(modelId.isNotBlank()) { "请选择摘要模型" }
        val summaryModel=p.models.find { it.id==modelId }
        require(summaryModel!=null && summaryModel.type=="text" && !summaryModel.offline) { "摘要模型不可用，请刷新目录并重新选择" }
        val window=MemoryRebuilder.effectiveWindow(summaryModel.contextTokens,data.settings.contextOverride)
        val output=HistoryCompressor.outputLimit(window)
        val secret=key(p.id)
        val summaryPreset=DeepSeekWriting.summaryPreset(active,p,summaryModel,output)
        val checkpointIdentity=compactIdentity(p,modelId,summaryPreset)
        val checkpoint=withContext(Dispatchers.IO) { compactCheckpoints.load(chatId,"memory_rebuild",checkpointIdentity) }
        val completed=HistoryCompressor.compress(slice,window,active.summaryPrompt,onProgress = { done, total ->
            val message = if (done == total) "已完成 $total 次请求，正在校验并保存续聊摘要与知识…"
                else "重建记忆：已完成 $done 次请求，预计共 $total 次\n${p.name} · $modelId · 窗口 $window tokens · ${slice.messages.size} 条原文"
            mutable.value = mutable.value.copy(memoryRebuild = MemoryRebuildState(
                chatId=chatId, completed=done, total=total, message=message,
                sourceMessageCount=slice.messages.size
            ))
        }, resume = checkpoint, onCheckpoint = { saved ->
            withContext(Dispatchers.IO) { compactCheckpoints.save(chatId,"memory_rebuild",checkpointIdentity,saved) }
        }) { text ->
            val payload=JSONArray()
                .put(JSONObject().put("role","system").put("content",HistoryCompressor.systemPrompt(active.summaryPrompt)))
                .put(JSONObject().put("role","user").put("content",text)).toString()
            requestPurpose="memory_rebuild"
            compactClient.chat(routed(p),secret,modelId,payload,summaryPreset,
                metadata = if (p.kind.equals("writing-server", ignoreCase = true)) mapOf(
                    "pocket_tavern_chat_id" to chatId,
                    "pocket_tavern_purpose" to "rebuild"
                ) else emptyMap())
        }
        currentCoroutineContext().ensureActive()
        val rebuilt = MemoryRebuilder.commit(current(chatId),slice,completed,modelId)
        changeChat(chatId) { rebuilt }
        mutable.value = mutable.value.copy(memoryRebuild = mutable.value.memoryRebuild?.copy(
            noteCount=completed.notes.size, summaryId=rebuilt.activeSummaryId,
            sourceMessageCount=slice.messages.size,
            message="${completed.requestCount} 次请求已完成，正在保存续聊摘要与 ${completed.notes.size} 条知识记录…"
        ))
        saves.flush(data)
        withContext(Dispatchers.IO) { compactCheckpoints.clear(chatId,"memory_rebuild") }
        return rebuilt.knowledgeNotes.map { it.id }
    }
    private fun compactIdentity(provider: ProviderConfig, modelId: String, summaryPreset: Preset): String =
        JSONObject().put("providerId", provider.id).put("baseUrl", provider.baseUrl).put("kind", provider.kind)
            .put("modelId", modelId).put("maxOutput", summaryPreset.maxOutput).put("temperature", summaryPreset.temperature)
            .put("reasoningEffort", summaryPreset.reasoningEffort).put("reasoningBudget", summaryPreset.reasoningBudget)
            .put("includeVeniceSystemPrompt", summaryPreset.includeVeniceSystemPrompt).toString()
    override fun exportMemoryDiagnostics() {
        if (mutable.value.loading || loadFailed) { status("本地数据尚未就绪"); return }
        val chat = selected ?: run { status("请先选择会话"); return }
        val rebuild = mutable.value.memoryRebuild
        val busy = mutable.value.busy || chatBusy(chat.id)
        viewModelScope.launch {
            runCatching {
                val report = withContext(Dispatchers.IO) {
                    val metadata = JSONObject(MemoryDiagnostics.report(chat, rebuild))
                        .put("appVersion", installedVersion)
                        .put("androidSdk", Build.VERSION.SDK_INT)
                        .put("exportedAt", System.currentTimeMillis())
                        .put("operationRunningAtCapture", busy)
                    // Read without flushing so a mismatch between UI and storage remains observable.
                    try {
                        val document = app.database.state().load()
                        val persisted = document?.let { DataCodec.decode(it.json) }?.chats?.singleOrNull { it.id == chat.id }
                        metadata.put("persistedReadStatus", when {
                            document == null -> "no_document"
                            persisted == null -> "chat_missing"
                            else -> "ok"
                        })
                        if (persisted != null) {
                            metadata.put("persisted", JSONObject(MemoryDiagnostics.report(persisted, null)))
                                .put("persistedUpdatedAt", document!!.updatedAt)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Exception messages may contain input fragments; export only a fixed status.
                        metadata.put("persistedReadStatus", "read_error")
                    }
                    metadata.toString(2)
                }
                val exportHost = host ?: error("请返回应用后再导出")
                exportHost.exportText("pocket-tavern-memory-diagnostics.json", "application/json", report)
            }.onFailure { status("记忆诊断导出失败：${it.message ?: "无法导出"}") }
        }
    }
    private suspend fun summarize(chatId: String) = compacting { summarizeHoldingGate(chatId) }
    private suspend fun summarizeHoldingGate(chatId: String) {
        val chat=current(chatId)
        val built = buildContext(data, chat)
        val slice = built.compression ?: error(overBudgetMessage("没有可安全压缩的旧消息", built))
        val active=preset(chat)
        val p=provider(active.summaryProviderId.ifBlank { chat.providerId })
        val modelId=active.summaryModelId.ifBlank { chat.modelId }
        require(modelId.isNotBlank()) { "请选择摘要模型" }
        val summaryModel=p.models.find { it.id==modelId }
        require(summaryModel!=null && summaryModel.type=="text" && !summaryModel.offline) { "摘要模型不可用，请刷新目录并重新选择" }
        val window=MemoryRebuilder.effectiveWindow(summaryModel.contextTokens,data.settings.contextOverride)
        val output=HistoryCompressor.outputLimit(window)
        val summaryPreset=DeepSeekWriting.summaryPreset(active,p,summaryModel,output)
        val secret=key(p.id)
        val checkpointIdentity=compactIdentity(p,modelId,summaryPreset)
        val checkpoint=withContext(Dispatchers.IO) { compactCheckpoints.load(chatId,"compression",checkpointIdentity) }
        val compressionRecord = CompressionScope.record(chat, slice, budget = built.budgetBreakdown)
        val scopeDescription = CompressionScope.describe(chat, compressionRecord)
        fun progress(message: String, completed: Int, total: Int) {
            mutable.value = mutable.value.copy(compressionProgress = CompressionProgress(
                chatId, scopeDescription, completed, total, message
            ))
        }
        var activeSegment = 0
        var totalSegments = 0
        var segmentStartedAt = 0L
        val compressionStartedAt = System.currentTimeMillis()
        progress("正在准备本次新增记忆…", 0, 0)
        try {
            val compressed = try {
                HistoryCompressor.compress(
                    slice,
                    window,
                    active.summaryPrompt,
                    onProgress = { done, total ->
                        totalSegments = total
                        val elapsed = ((System.currentTimeMillis() - compressionStartedAt) / 1000).coerceAtLeast(0)
                        progress(if (done == total) {
                            "本次 $done 次请求已完成，正在校验并保存…（已用 ${elapsed}s）"
                        } else {
                            "本次新增记忆：已完成 $done 次请求，预计共 $total 次（已用 ${elapsed}s）"
                        }, done, total)
                    },
                    onSegmentStart = { segment, total ->
                        activeSegment = segment
                        totalSegments = total
                        segmentStartedAt = System.currentTimeMillis()
                        progress("正在更新续聊摘要与知识：第 $segment 次请求，预计共 $total 次", segment - 1, total)
                    },
                    resume = checkpoint,
                    onCheckpoint = { saved ->
                        withContext(Dispatchers.IO) { compactCheckpoints.save(chatId,"compression",checkpointIdentity,saved) }
                    }
                ) { text ->
                    val segment = activeSegment
                    val segmentTotal = totalSegments
                    val startedAt = segmentStartedAt
                    coroutineScope {
                        val ticker = launch {
                            while (isActive) {
                                delay(10_000)
                                val waited = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
                                progress("正在更新续聊摘要与知识：第 $segment 次请求，预计共 $segmentTotal 次（已等待 ${waited}s）", segment - 1, segmentTotal)
                            }
                        }
                        try {
                            val messages=JSONArray()
                                .put(JSONObject().put("role","system").put("content",HistoryCompressor.systemPrompt(active.summaryPrompt)))
                                .put(JSONObject().put("role","user").put("content",text))
                            requestPurpose = "compression"
                            try {
                                withTimeout(COMPACT_REQUEST_DEADLINE_MS) {
                                    compactClient.chat(routed(p),secret,modelId,messages.toString(),summaryPreset,
                                        metadata = if (p.kind.equals("writing-server", ignoreCase = true)) mapOf(
                                            "pocket_tavern_chat_id" to chatId,
                                            "pocket_tavern_purpose" to "compact"
                                        ) else emptyMap())
                                }
                            } catch (_: TimeoutCancellationException) {
                                compactClient.cancel()
                                throw ProviderException(
                                    ProviderException.Kind.TIMEOUT,
                                    "记忆压缩第 $segment 次请求等待超过 ${COMPACT_REQUEST_DEADLINE_MS / 1000} 秒",
                                    finishReason = "timeout"
                                )
                            }
                        } finally {
                            ticker.cancel()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (activeSegment > 0 && totalSegments > 0) {
                    throw IllegalStateException(
                        "$scopeDescription\n本次第 $activeSegment 次请求失败（预计共 $totalSegments 次）：${error.message?.take(500) ?: "摘要请求未完成"}",
                        error
                    )
                }
                throw error
            }
            currentCoroutineContext().ensureActive()
            val updated = IncrementalMemory.commit(current(chatId), slice, compressed, modelId,
                compressionRecord.copy(segmentCount = compressed.requestCount, requestCount = compressed.requestCount))
            changeChat(chatId) { updated }
            // Finish saving the boundary before a following generation can be interrupted.
            saves.flush(data)
            withContext(Dispatchers.IO) { compactCheckpoints.clear(chatId,"compression") }
        } finally {
            mutable.value = mutable.value.copy(compressionProgress = null)
        }
    }
    override fun restoreSummary(id: String) {
        if(chatLocked(selected?.id)) return
        selected?.let { changeChat(it.id) { c -> c.copy(activeSummaryId=id) } }
        status(if(id.isEmpty()) "已恢复完整原始上下文" else "已切换摘要版本")
    }
    override fun previewContext() { runCatching { selected?.let { mutable.value=mutable.value.copy(contextPreview=buildContext(data, it).preview) } }.onFailure { status(it.message ?: "无法组装上下文") } }
    override fun dismissPreview() { mutable.value=mutable.value.copy(contextPreview="") }
    override fun showDiagnostics() {
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) { requestLogs.latestSummary() }
            mutable.value=mutable.value.copy(diagnostics=summary)
        }
    }
    override fun dismissDiagnostics() { mutable.value=mutable.value.copy(diagnostics="") }
    override fun clearDiagnostics() {
        if(mutable.value.busy || chatRuns.isNotEmpty()) { status("请等待当前任务和正在回复的会话完成后清空日志"); return }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { requestLogs.clear() }
            status("本机请求日志已清空")
        }
    }
    override fun checkBilling() = action {
        val raw = withContext(Dispatchers.IO) { requestLogs.latestRequestJson() }
        if(raw == null) {
            mutable.value=mutable.value.copy(diagnostics="尚无可核对的请求日志。旧版本没有记录请求 ID，无法回查截图中的那一轮；升级后生成一次，再核对。")
            return@action
        }
        val log = JSONObject(raw)
        val p = data.providers.find { it.id == log.optString("provider_id") }
        if(p == null || p.kind != "venice" || Uri.parse(p.baseUrl).host != "api.venice.ai" || log.optString("api_host") != "api.venice.ai") {
            mutable.value=mutable.value.copy(diagnostics="最近一次请求不属于当前配置的官方 Venice 平台，无法用 Venice 账本核对。可先查看或导出请求日志。")
            return@action
        }
        val ids = setOf(log.optString("request_id"),log.optString("response_id")).filter { it.isNotBlank() }.toSet()
        val started = log.optLong("started_at_ms")
        val ended = started + log.optLong("elapsed_ms").coerceAtLeast(0)
        val result = billingClient.lookup(p,key(p.id),ids,(started-300_000).coerceAtLeast(0),ended+300_000)
        val summary = withContext(Dispatchers.IO) { requestLogs.latestSummary() }
        mutable.value=mutable.value.copy(diagnostics="$summary\n\n$result")
    }
    override fun importFile(kind: String) { host?.importFile(kind) }
    override fun pickAttachment() { host?.pickAttachment() }
    override fun removeAttachment(id: String) { mutable.value=mutable.value.copy(pendingAttachments=mutable.value.pendingAttachments.filterNot { it.id==id }) }
    fun addAttachment(name: String, mime: String, bytes: ByteArray) {
        runCatching {
            require(bytes.size<=8*1024*1024) { "单个附件限 8 MB" }
            require(mutable.value.pendingAttachments.size<4) { "单次最多 4 个附件" }
            val attachment=if(mime.startsWith("image/") || mime.startsWith("audio/")) Attachment(name=name,mime=mime,dataUrl="data:$mime;base64,"+java.util.Base64.getEncoder().encodeToString(bytes)) else {
                require(mime.startsWith("text/") || name.substringAfterLast('.').lowercase() in listOf("txt","md","json","csv","log")) { "当前聊天附件支持图片、音频和文本文件" }
                Attachment(name=name,mime="text/plain",dataUrl=bytes.toString(Charsets.UTF_8))
            }
            mutable.value=mutable.value.copy(pendingAttachments=mutable.value.pendingAttachments+attachment)
        }.onFailure { status(it.message ?: "附件读取失败") }
    }
    /** The phone reads the user's ZIP, then the pack is stored on the PC; the phone keeps none of the images. */
    private fun importStickers(name: String, bytes: ByteArray) = stickerJob { provider, key ->
        val existing = pcClient.stickerPacks(provider, key)
        require(existing.size < MAX_STICKER_PACKS) { "电脑上的表情包已达 $MAX_STICKER_PACKS 个，请先删除不用的" }
        val taken = existing.flatMap { pack -> pack.stickers.map { it.key } }.toSet()
        val pack = withContext(Dispatchers.IO) { StickerImporter.read(name, bytes, taken) }
        val stored = pcClient.uploadStickerPack(provider, key, pack, name)
        updatePcLink { it.copy(stickerPacks = existing + stored, stickersError = "") }
        status("已把表情包“${stored.name}”存到电脑，共 ${stored.stickers.size} 张。用电脑写作服务的会话打开表情包后，模型才会发图")
    }

    override fun deleteStickerPack(id: String) {
        if (mutable.value.pcLink.stickerPacks.orEmpty().any { it.id == id }) {
            stickerJob { provider, key ->
                pcClient.deleteStickerPack(provider, key, id)
                updatePcLink { it.copy(stickerPacks = it.stickerPacks?.filterNot { pack -> pack.id == id }) }
                withContext(Dispatchers.IO) { File(pcStickerCache, id).deleteRecursively() }
                status("已从电脑删除表情包")
            }
            return
        }
        // A pack still on the phone from before 0.1.71.
        if (data.stickerPacks.none { it.id == id }) return
        change(data.copy(stickerPacks = data.stickerPacks.filterNot { it.id == id }))
        viewModelScope.launch(Dispatchers.IO) { StickerStore.delete(app.filesDir, id) }
        status("已删除手机上的旧表情包")
    }

    override fun setStickerPackEnabled(id: String, enabled: Boolean) = stickerJob { provider, key ->
        val pack = pcClient.setStickerPackEnabled(provider, key, id, enabled)
        updatePcLink { it.copy(stickerPacks = it.stickerPacks?.map { old -> if (old.id == id) pack else old }) }
    }

    /**
     * Moves packs imported before 0.1.71 from the phone to the PC. Each phone copy is removed once
     * the PC has it; a name already used on the PC gets a number, so older replies using it lose that sticker.
     */
    override fun migrateLocalStickers() {
        if (data.stickerPacks.isEmpty()) return
        stickerJob { provider, key ->
            var existing = pcClient.stickerPacks(provider, key)
            var moved = 0
            for (pack in data.stickerPacks) {
                require(existing.size < MAX_STICKER_PACKS) { "电脑上的表情包已达 $MAX_STICKER_PACKS 个，已移动 $moved 个" }
                val taken = existing.flatMap { old -> old.stickers.map { it.key } }.toSet()
                val upload = withContext(Dispatchers.IO) { StickerStore.toUpload(app.filesDir, pack, taken) }
                if (upload.items.isNotEmpty()) existing = existing + pcClient.uploadStickerPack(provider, key, upload, pack.source.ifBlank { pack.name })
                change(data.copy(stickerPacks = data.stickerPacks.filterNot { it.id == pack.id }))
                withContext(Dispatchers.IO) { StickerStore.delete(app.filesDir, pack.id) }
                moved++
                updatePcLink { it.copy(stickerPacks = existing, stickersError = "") }
            }
            status("已把 $moved 个表情包移到电脑，手机上的副本已删除")
        }
    }

    /** Sticker work on the PC; failures go to the status line and the sticker card. */
    private fun stickerJob(block: suspend (ProviderConfig, String) -> Unit) {
        if (pcProvider() == null) return status("表情包存放在电脑写作服务上，请先在模型页添加电脑写作服务平台")
        pcJob("stickers") { provider, key ->
            try { block(provider, key) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                status("表情包操作失败：${error.message ?: "未知错误"}")
                updatePcLink { it.copy(stickersError = error.message ?: "表情包操作失败") }
            }
        }
    }

    /** Reads the PC's packs in the background. A failure keeps the last list, so names in chats still resolve. */
    private fun refreshPcStickers() {
        val provider = pcProvider() ?: return
        val key = app.secrets.get(credentialId(provider)).takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            try {
                val packs = pcClient.stickerPacks(provider, key)
                updatePcLink { it.copy(stickerPacks = packs, stickersError = "") }
                // Cached images of packs deleted on the PC go too.
                withContext(Dispatchers.IO) { pcStickerCache.listFiles()?.filter { dir -> packs.none { it.id == dir.name } }?.forEach { it.deleteRecursively() } }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { updatePcLink { it.copy(stickersError = error.message ?: "读取电脑上的表情包失败") } }
        }
    }

    /** Stickers live on the PC, so only chats talking to the PC writing server get them. */
    private fun stickersFor(chat: Chat): List<Sticker> = StickerGate.forChat(chat, data.providers, mutable.value.pcLink.stickerPacks, data.characters)

    private fun buildContext(source: AppData, chat: Chat) = ContextEngine.build(source, chat, stickersFor(chat), onPc = onPc(chat))

    /**
     * A PC writing server with a home-network address goes over the LAN while this phone is at home.
     * The token stays keyed to the provider as saved, never to the address chosen for a request.
     */
    private suspend fun routed(provider: ProviderConfig): ProviderConfig {
        if (!provider.kind.equals("writing-server", ignoreCase = true) || provider.lanBaseUrl.isBlank()) return provider
        val route = pcRouter.resolve(provider, app.secrets.get(credentialId(provider)))
        if (provider.id == pcProvider()?.id) updatePcLink { it.copy(route = if (route.baseUrl == provider.baseUrl) "remote" else "lan") }
        return route
    }

    private fun onPc(chat: Chat) = data.providers.firstOrNull { it.id == chat.providerId }?.kind.equals("writing-server", ignoreCase = true)

    /** A DSH engine call from the model, run by the PC this chat talks to. The engine's own JSON goes back. */
    private suspend fun runDsh(arguments: String, chat: Chat): ToolResult {
        fun failure(code: String, message: String) = ToolResult(JSONObject().put("ok", false).put("data", JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message)).toString())
        val provider = data.providers.firstOrNull { it.id == chat.providerId }?.takeIf { onPc(chat) }
            ?: return failure("NOT_ON_PC", "DSH 引擎只能在使用电脑写作服务的会话里调用")
        if (ToolRuntime.activeSkills(data.skills, data.settings, chat).none { it.id == DshTool.SKILL_ID }) return failure("SKILL_OFF", "本会话没有启用 DSH 成人互动")
        val args = runCatching { JSONObject(arguments) }.getOrNull() ?: return failure("INVALID_INPUT", "工具参数不是 JSON")
        val key = app.secrets.get(credentialId(provider)).takeIf { it.isNotBlank() } ?: return failure("PC_UNREACHABLE", "没有填写电脑写作服务的 Token")
        return try {
            val envelope = pcClient.runDsh(routed(provider), key, args.optString("command"), args.optJSONObject("input") ?: JSONObject())
            if (envelope.length > MAX_DSH_RESULT_CHARS) failure("RESULT_TOO_LARGE", "引擎返回内容过大；请改用 brief 等更短的查询") else ToolResult(envelope)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure("PC_UNREACHABLE", error.message ?: "连不上电脑上的 DSH 引擎") }
    }

    private suspend fun runReach(arguments: String, chat: Chat): ToolResult {
        fun failure(code: String, message: String) = ToolResult(JSONObject().put("ok", false).put("items", JSONArray())
            .put("error", JSONObject().put("code", code).put("message", message)).toString())
        val provider = data.providers.firstOrNull { it.id == chat.providerId }?.takeIf { onPc(chat) }
            ?: return failure("NOT_ON_PC", "联网搜索只能在使用电脑写作服务的会话里调用")
        val args = runCatching { JSONObject(arguments) }.getOrNull() ?: return failure("BAD_INPUT", "工具参数不是 JSON")
        val key = app.secrets.get(credentialId(provider)).takeIf { it.isNotBlank() } ?: return failure("PC_UNREACHABLE", "没有填写电脑写作服务的 Token")
        return try {
            val result = pcClient.reach(routed(provider), key, args)
            if (result.length > MAX_REACH_RESULT_CHARS) failure("RESULT_TOO_LARGE", "返回内容过大；请换更具体的关键词或少读几条") else ToolResult(result)
        } catch (error: CancellationException) { throw error }
        catch (error: PcLinkException) {
            // The PC refuses a malformed call with 400; the model can fix its arguments and try again.
            val code = when (error.httpCode) { 400 -> "BAD_INPUT"; 503 -> "NOT_INSTALLED"; 504 -> "TIMEOUT"; 0 -> "PC_UNREACHABLE"; else -> "PC_ERROR" }
            if (error.httpCode == 0) pcRouter.forget()
            failure(code, error.message ?: "联网搜索失败")
        }
        catch (error: Exception) { failure("PC_UNREACHABLE", error.message ?: "连不上电脑上的联网搜索") }
    }

    private val pcStickerCache get() = File(app.cacheDir, "pc-stickers")

    /** A PC sticker image, cached on the phone after the first fetch. Blocking: the image loader calls it off the main thread. */
    override fun pcStickerFile(sticker: Sticker): File? {
        if (!PC_STICKER_FILE.matches(sticker.file)) return null
        val target = File(pcStickerCache, sticker.file)
        if (target.isFile) return target
        // Whichever PC platform answers: at home the LAN address, away the remote one.
        val servers = data.providers.filter { it.kind.equals("writing-server", ignoreCase = true) }.sortedByDescending { it.id == pcStore.providerId }
        for (provider in servers) {
            val key = app.secrets.get(credentialId(provider)).takeIf { it.isNotBlank() } ?: continue
            val bytes = (listOf(pcRouter.current(provider), provider).distinct()).firstNotNullOfOrNull { route ->
                runCatching { pcClient.stickerImage(route, key, sticker.file) }.getOrNull()
            } ?: continue
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, "${target.name}.part")
            part.writeBytes(bytes)
            if (part.renameTo(target)) return target
        }
        return null
    }

    override fun setChatStickerFrequency(chatId: String, frequency: String) {
        changeChat(chatId) { it.copy(stickerFrequency = StickerFrequency.of(frequency).id) }
    }

    override fun setChatBrowse(chatId: String, perDay: Int) {
        changeChat(chatId) { it.copy(browsePerDay = perDay.coerceIn(0, VideoBrowse.MAX_PER_DAY)) }
        VideoBrowseWorker.schedule(app, data.chats.any { it.browsePerDay > 0 })
    }

    override fun browseNow(chatId: String) { startBrowse(chatId, manual = true) { } }

    override fun startCharacterBuilder(): Boolean {
        if (mutable.value.loading || loadFailed) { status("本地数据尚未就绪，请稍候"); return false }
        val provider = pcProvider()
        val model = provider?.models?.filter { it.type == "text" && !it.offline && it.supportsTools }
            ?.let { models -> models.firstOrNull { it.id == "pocket-direct" } ?: models.firstOrNull() }
        if (provider == null || model == null) { status("角色卡助手需要电脑写作服务平台和支持工具的模型；请先在模型页添加并刷新"); return false }
        val chat = Chat(title = "角色卡助手", providerId = provider.id, modelId = model.id, task = CharacterCardTool.TASK,
            messages = listOf(Message(role = "assistant", content = CharacterCardTool.GREETING)))
        change(data.copy(chats = listOf(chat) + data.chats))
        selectChat(chat.id)
        return true
    }

    private fun saveCharacterCard(arguments: String, chat: Chat): ToolResult {
        fun answer(ok: Boolean, extra: JSONObject.() -> Unit) = ToolResult(JSONObject().put("ok", ok).apply(extra).toString())
        if (chat.task != CharacterCardTool.TASK) return answer(false) { put("error", "只有角色卡助手会话可以保存角色卡") }
        val args = runCatching { JSONObject(arguments) }.getOrNull() ?: return answer(false) { put("error", "工具参数不是 JSON") }
        val character = CharacterCardTool.character(args).getOrElse { return answer(false) { put("error", it.message ?: "角色卡无效") } }
        change(data.copy(characters = data.characters + character))
        return answer(true) { put("id", character.id); put("name", character.name); put("saved_to", "角色页") }
    }

    private var pendingOpenChatId: String? = null
    internal fun openChatWhenReady(id: String) {
        if (mutable.value.loading) pendingOpenChatId = id else if (data.chats.any { it.id == id }) selectChat(id)
    }

    private fun browseBlocker(chat: Chat): String? = when {
        !onPc(chat) -> "自己刷视频只能在使用电脑写作服务的会话里用"
        !VideoBrowse.eligible(chat, data.providers) -> "当前模型不支持工具调用，没法刷视频；换一个支持工具的模型"
        else -> null
    }

    /**
     * Adds the browse turn and generates the character's share. [onDone] gets "" on success, or why
     * it did not run or failed. A manual run keeps going in the background like a sent message.
     */
    private fun startBrowse(chatId: String, manual: Boolean, onDone: (String) -> Unit): Boolean {
        val chat = data.chats.firstOrNull { it.id == chatId } ?: run { onDone("会话不存在"); return false }
        val refusal = chatTaskRefusal(chatId, ChatTask.REPLY) ?: browseBlocker(chat)
        if (refusal != null) { if (manual) status(refusal); onDone(refusal); return false }
        val now = System.currentTimeMillis()
        val turn = Message(role = "user", content = VideoBrowse.instruction(now, java.time.ZoneId.systemDefault()), intent = VideoBrowse.INTENT)
        changeChat(chatId) { it.copy(messages = it.messages + turn, browseLog = (it.browseLog + now).takeLast(20)) }
        return chatTask(chatId, background = manual, onFinished = { error, cancelled -> onDone(error ?: if (cancelled) "已中断" else "") }) { generate(chatId) }
    }

    /**
     * Called by [VideoBrowseWorker] on the main thread: runs at most one chat whose browse is due and
     * returns that chat with its new reply, or null when nothing ran or the run failed.
     */
    internal suspend fun runDueBrowse(): Pair<Chat, String>? {
        withTimeoutOrNull(60_000) { while (mutable.value.loading) delay(500) }
        if (mutable.value.loading || loadFailed) return null
        val now = System.currentTimeMillis()
        val chat = data.chats.firstOrNull { it.browsePerDay > 0 && !chatBusy(it.id) && browseBlocker(it) == null && VideoBrowse.due(it, now, java.time.ZoneId.systemDefault()) } ?: return null
        val done = CompletableDeferred<String>()
        if (!startBrowse(chat.id, manual = false) { done.complete(it) }) return null
        val error = withTimeoutOrNull(9 * 60_000L) { done.await() } ?: run { chatRuns[chat.id]?.let { stopRun(it, "自己刷视频超时") }; return null }
        if (error.isNotEmpty()) return null
        val reply = current(chat.id).messages.lastOrNull()?.takeIf { it.role == "assistant" && it.status == "complete" && it.content.isNotBlank() } ?: return null
        return current(chat.id) to StickerMarkup.strip(reply.content)
    }

    override fun stickerFile(sticker: Sticker): java.io.File? = StickerStore.file(app.filesDir, sticker)

    private fun pcLinkFromStore(link: PcLinkState) = link.copy(providerId = pcStore.providerId, autoBackup = pcStore.autoBackup,
        lastBackupAt = pcStore.lastBackupAt, lastBackupSize = pcStore.lastBackupSize, lastBackupError = pcStore.lastError)

    private fun updatePcLink(transform: (PcLinkState) -> PcLinkState) { mutable.value = mutable.value.copy(pcLink = transform(mutable.value.pcLink)) }

    /** The writing-server platform chosen for this phone, or the only one there is. */
    private fun pcProvider(): ProviderConfig? = chosenPcProvider(data.providers, pcStore.providerId)

    /** One PC job at a time. A quiet job (the automatic backup) skips silently when nothing is set up. */
    private fun pcJob(name: String, quiet: Boolean = false, block: suspend (ProviderConfig, String) -> Unit) {
        if (mutable.value.pcLink.busy.isNotBlank()) return
        val provider = pcProvider()
        val key = provider?.let { app.secrets.get(credentialId(it)) }.orEmpty()
        if (provider == null || key.isBlank()) {
            if (!quiet) updatePcLink { it.copy(error = if (provider == null) "还没有添加电脑写作服务平台" else "请先在平台页填写电脑写作服务的 Token") }
            return
        }
        updatePcLink { it.copy(busy = name, error = if (quiet) it.error else "") }
        viewModelScope.launch {
            try { block(routed(provider), key) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (error is PcLinkException && error.httpCode == 0) pcRouter.forget()
                if (!quiet) updatePcLink { it.copy(error = error.message ?: "电脑连接失败") }
            }
            finally { updatePcLink { pcLinkFromStore(it).copy(busy = "") } }
        }
    }

    override fun checkPcLink() = pcJob("check") { provider, key ->
        val status = pcClient.status(provider, key)
        updatePcLink { it.copy(status = status) }
        refreshPcStickers()
    }

    override fun backupToPc() {
        if (loadFailed || mutable.value.loading) return updatePcLink { it.copy(error = "本机数据还没有正常读取，暂时不能备份") }
        pcJob("backup") { provider, key ->
            val result = pcBackups.upload(provider, key, data, "manual") as PcBackups.Upload.Stored
            status(if (result.duplicate) "电脑上的最新备份与当前数据相同" else "已备份到电脑（${readableSize(result.size)}）")
            val latest = runCatching { pcClient.status(provider, key) }.getOrNull()
            updatePcLink { it.copy(backups = null, status = latest ?: it.status) }
        }
    }

    override fun loadPcBackups() = pcJob("list") { provider, key ->
        val backups = pcClient.backups(provider, key)
        updatePcLink { it.copy(backups = backups) }
    }

    override fun restoreFromPc(backupId: String) {
        val backup = mutable.value.pcLink.backups?.firstOrNull { it.id == backupId } ?: return updatePcLink { it.copy(error = "请先刷新电脑上的备份列表") }
        if (chatRuns.isNotEmpty()) return updatePcLink { it.copy(error = restoreBlockedByChats()) }
        if (mutable.value.busy || mutable.value.loading) return updatePcLink { it.copy(error = "请等待当前任务完成后再恢复") }
        pcJob("restore") { provider, key ->
            // What is about to be replaced goes to the PC first; unreadable local data has nothing worth keeping.
            if (!loadFailed) pcBackups.upload(provider, key, data, "pre-restore")
            val bytes = pcBackups.fetch(provider, key, backup)
            updatePcLink { it.copy(backups = null) }
            importBytes("backup", "pc-${backup.id}.zip", bytes)
        }
    }

    override fun setPcAutoBackup(enabled: Boolean) {
        pcStore.autoBackup = enabled
        updatePcLink { it.copy(autoBackup = enabled) }
    }

    override fun choosePcProvider(providerId: String) {
        pcStore.providerId = providerId
        updatePcLink { it.copy(providerId = providerId, status = null, backups = null, settings = null, error = "") }
        refreshPcStickers()
    }

    override fun loadPcSettings() = pcJob("settings") { provider, key ->
        val settings = pcClient.settings(provider, key)
        updatePcLink { it.copy(settings = settings) }
    }

    override fun savePcSettings(original: PcSettings, edited: PcSettings) {
        val changes = edited.changesFrom(original)
        if (changes.isEmpty()) return
        pcJob("settings") { provider, key ->
            val saved = pcClient.saveSettings(provider, key, changes)
            val latest = runCatching { pcClient.status(provider, key) }.getOrNull()
            updatePcLink { it.copy(settings = saved, status = latest ?: it.status) }
            status("电脑设置已保存，立即生效")
            // The default-model entries carry the model's name and price, so the catalog is refetched.
            if ("upstreamModel" in changes) refreshModels(provider.id)
        }
    }

    /** The app left the screen: the moment for the automatic PC backup, when one is due. */
    fun onBackgrounded() {
        if (loadFailed || mutable.value.loading || mutable.value.busy || chatRuns.isNotEmpty() || !pcBackups.autoBackupDue()) return
        pcJob("backup", quiet = true) { provider, key -> pcBackups.upload(provider, key, data, "auto") }
    }

    private fun restoreBlockedByChats() = "有 ${chatRuns.size} 个会话正在回复或整理，请先停止或等它们完成后再恢复备份"

    fun importBytes(kind: String, name: String, bytes: ByteArray) {
        runCatching {
            require(!mutable.value.busy && !mutable.value.loading) { "请等待当前任务和本地数据读取完成后再导入" }
            when(kind) {
                "backup" -> {
                    // A restore replaces everything, so no chat may be writing into the data meanwhile.
                    require(chatRuns.isEmpty()) { restoreBlockedByChats() }
                    val currentData = data
                    mutable.value=mutable.value.copy(loading=true)
                    viewModelScope.launch {
                        var databaseRestored = false
                        var mayCancelPreparedMarker = false
                        try {
                            withContext(Dispatchers.IO) {
                                finishPendingRepairBackupDiscard(currentData)
                                require(!repairBackupDiscardMarker().exists()) { "旧修复快照仍在清理，请重启应用完成清理后再导入备份" }
                            }
                            mayCancelPreparedMarker = true
                            val restored = withContext(Dispatchers.IO) {
                                BackupArchive.restore(bytes,app.filesDir,app.cacheDir).let {
                                    val withSkills = BuiltinSkills.merge(it.copy(skills=it.skills.map { s -> s.copy(enabled=false,permissions=emptyList()) }),builtinSkills)
                                    BuiltinScenarios.merge(withSkills,builtinScenarios)
                                }
                            }
                            val discardIntent = RepairBackupDiscardMarker.prepared(DataCodec.fingerprint(restored))
                            withContext(Dispatchers.IO) { writeRepairBackupDiscardMarker(discardIntent) }
                            saves.flush(restored)
                            databaseRestored = true
                            withContext(Dispatchers.IO) { writeRepairBackupDiscardMarker(discardIntent.committed()) }
                            val cleanupComplete = withContext(Dispatchers.IO) { finishPendingRepairBackupDiscard(restored) }
                            loadFailed=false
                            val restoredSelection = preferredChatId(restored.chats, sessionPreferences.getString(LAST_SELECTED_CHAT_ID, "").orEmpty())
                            rememberSelectedChat(restoredSelection)
                            mutable.value=mutable.value.copy(data=restored,selectedChatId=restoredSelection,chatOpen=false,loading=false,hasResearchSearchKey=app.secrets.has(DoubaoSearchClient.CREDENTIAL_ID),hasKeys=restored.providers.filter { app.secrets.has(credentialId(it)) }.map { it.id }.toSet(),missingBuiltinSkills=BuiltinSkills.missing(restored,builtinSkills),repairBackupChatIds=emptySet(),memoryRebuild=null)
                            status(if (cleanupComplete && !repairBackupDiscardMarker().exists()) "备份已恢复；技能权限需重新授予，未配置平台请重新填写密钥" else "备份已恢复；旧修复快照已停用，下次启动会继续清理")
                        } catch (error: Throwable) {
                            if (!databaseRestored) {
                                if (mayCancelPreparedMarker) withContext(Dispatchers.IO) { cancelRepairBackupDiscardMarker() }
                                mutable.value=mutable.value.copy(loading=false)
                                status("导入失败：${error.message ?: "无法保存恢复的数据"}；原数据和会话修复快照仍保留")
                            } else {
                                status("备份已写入，但旧修复快照的清理状态无法持久化。请重启应用后再继续使用")
                            }
                        }
                    }
                }
                "skill" -> { val skills=SkillImporter.importFile(name,bytes); change(data.copy(skills=data.skills+skills)); status("已安装 ${skills.size} 个 Skill。请检查权限并在会话中启用") }
                "character" -> { val c=Exchange.character(bytes).let { card ->
                    // A PNG card's picture becomes the character's avatar.
                    if (bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) card.copy(avatar = runCatching { storeAvatar(card.id, bytes) }.getOrDefault("")) else card
                }; saveCharacter(c); val root=runCatching { JSONObject(c.originalJson).optJSONObject("data")?.optJSONObject("character_book") }.getOrNull(); if(root!=null) change(data.copy(worldEntries=data.worldEntries+Exchange.worldbook(root.toString(),c.id))); status("角色卡已导入") }
                "worldbook" -> { val entries=Exchange.worldbook(bytes.toString(Charsets.UTF_8)); change(data.copy(worldEntries=data.worldEntries+entries)); status("已导入 ${entries.size} 个世界书条目") }
                "chat" -> { val p=data.providers.firstOrNull(); val chat=Exchange.chat(bytes.toString(Charsets.UTF_8),p?.id ?: "",p?.models?.firstOrNull { it.type=="text" }?.id ?: ""); change(data.copy(chats=listOf(chat)+data.chats)); selectChat(chat.id); status("会话已导入并已打开") }
                "preset" -> { val p=Exchange.preset(bytes.toString(Charsets.UTF_8),name.substringBeforeLast('.')); savePreset(p); status("预设已导入；不支持的 ST 扩展字段未执行") }
                "stickers" -> importStickers(name, bytes)
                else -> error("未知导入格式")
            }
        }.onFailure { status("导入失败：${it.message}") }
    }
    override fun exportFile(kind: String, id: String) {
        if(kind=="diagnostics") {
            viewModelScope.launch {
                runCatching {
                    val logs = withContext(Dispatchers.IO) { requestLogs.exportJsonl() }
                    require(logs.isNotBlank()) { "还没有请求日志；升级后生成一次会开始记录" }
                    host?.exportText("pocket-tavern-diagnostics.jsonl","application/x-ndjson",logs)
                }.onFailure { status(it.message ?: "日志导出失败") }
            }
            return
        }
        if(loadFailed && kind=="backup") {
            viewModelScope.launch {
                runCatching { val original=withContext(Dispatchers.IO) { app.database.state().load()?.json ?: error("未找到可恢复的数据") }; host?.exportText("pocket-tavern-recovery.json","application/json",original) }.onFailure { status("原始数据导出失败：${it.message}") }
            }
            return
        }
        runCatching {
            when(kind) {
                "backup" -> { val file=File(app.cacheDir,"exports/backup-${newId()}.zip"); BackupArchive.write(data,app.filesDir,file); host?.exportBinary("pocket-tavern-backup.zip","application/zip",file) }
                "chat" -> host?.exportText("conversation.md","text/markdown",VaultExport.conversation(data.chats.first { it.id==id.ifBlank { mutable.value.selectedChatId } }))
                "chat_jsonl" -> host?.exportText("conversation.jsonl","application/jsonl",Exchange.chatJsonl(data.chats.first { it.id==id.ifBlank { mutable.value.selectedChatId } }))
                "chat_vault" -> {
                    val file=File(app.cacheDir,"exports/vault-${newId()}.zip")
                    VaultExport.write(data.chats.first { it.id==id.ifBlank { mutable.value.selectedChatId } },file)
                    host?.exportBinary("conversation-vault.zip","application/zip",file)
                }
                "character" -> host?.exportText("character.json","application/json",Exchange.characterJson(data.characters.first { it.id==id }))
                "preset" -> host?.exportText("preset.json","application/json",Exchange.presetJson(data.presets.first { it.id==id }))
                "skill" -> host?.exportText("SKILL.md","text/markdown",data.skills.first { it.id==id }.let { "---\nname: ${it.name}\ndescription: ${JSONObject.quote(it.description)}\n---\n\n${it.content}" })
                "worldbook" -> { val entries=JSONObject(); data.worldEntries.forEachIndexed { i,e -> entries.put(i.toString(),JSONObject().put("comment",e.name).put("content",e.content).put("key",JSONArray(e.keywords)).put("constant",e.always).put("disable",!e.enabled).put("order",e.priority).put("searchRange",e.searchRange)) }; host?.exportText("worldbook.json","application/json",JSONObject().put("entries",entries).toString(2)) }
            }
        }.onFailure { status(it.message ?: "导出失败") }
    }
    override fun runMedia(providerId: String, modelId: String, requestJson: String) = action {
        val p=provider(providerId); val model=p.models.find { it.id==modelId } ?: error("模型不在当前目录中，请刷新")
        val body=JSONObject(requestJson)
        val attachment=mutable.value.pendingAttachments.firstOrNull()
        if(attachment!=null) for(field in body.keys().asSequence().toList()) {
            if(body.optString(field)=="@attachment") body.put(field,if(field.endsWith("_url")) attachment.dataUrl else attachment.dataUrl.substringAfter("base64,",attachment.dataUrl))
        }
        val pending=MediaJob(providerId=p.id,modelId=model.id,type=model.type,requestJson=body.toString(),status="submitting")
        change(data.copy(mediaJobs=listOf(pending)+data.mediaJobs))
        try {
            val job=mediaClient.execute(p,key(p.id),model,body.toString(),File(app.filesDir,"media").apply { mkdirs() }).copy(id=pending.id)
            change(data.copy(mediaJobs=data.mediaJobs.map { if(it.id==pending.id) job else it })); status("媒体请求完成：${job.status}")
        } catch(error: Exception) {
            change(data.copy(mediaJobs=data.mediaJobs.map { if(it.id==pending.id) it.copy(status="unknown",resultJson="请求状态未确认，请勿重复提交：${error.message}") else it }))
            throw error
        }
    }
    override fun pollMedia(id: String) = action {
        val job=data.mediaJobs.first { it.id==id }; val p=provider(job.providerId)
        val updated=mediaClient.retrieve(p,key(p.id),job,File(app.filesDir,"media").apply { mkdirs() })
        change(data.copy(mediaJobs=data.mediaJobs.map { if(it.id==id) updated.copy(id=id) else it })); status("任务状态：${updated.status}")
    }
    override fun openMedia(id: String) { mediaFile(id)?.let { host?.openFile(it,mimeFor(it)) } }
    override fun saveMedia(id: String) { mediaFile(id)?.let { host?.exportBinary(it.name,mimeFor(it),it) } }
    private fun mediaFile(id: String): File? {
        val path=data.mediaJobs.find { it.id==id }?.filePath ?: return null
        val file=File(path)
        if(!file.isFile || !file.canonicalPath.startsWith(File(app.filesDir,"media").canonicalPath+File.separator)) { status("本地媒体文件不存在或路径无效"); return null }
        return file
    }
    private fun mimeFor(file: File): String = when(file.extension.lowercase()) { "png"->"image/png";"jpg","jpeg"->"image/jpeg";"webp"->"image/webp";"mp3"->"audio/mpeg";"wav"->"audio/wav";"mp4"->"video/mp4";else->"application/octet-stream" }
    private fun rememberSelectedChat(id: String) {
        sessionPreferences.edit().putString(LAST_SELECTED_CHAT_ID, id).apply()
    }
    override fun clearStatus() { status("") }
    fun reportError(text: String) { status(text) }
    override fun onCleared() {
        runCatching { app.getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(networkWatch) }
        veniceBalanceJobs.values.forEach { it.cancel() }
        veniceBalanceJobs.clear()
        chatRuns.values.toList().forEach { stopRun(it, "应用已关闭") }
        cancelOperation("应用已关闭")
        super.onCleared()
    }

    private companion object {
        const val SESSION_PREFERENCES = "session-state"
        const val LAST_SELECTED_CHAT_ID = "last-selected-chat-id"
        /**
         * ProviderClient has a read-idle timeout, but a server that keeps a stream alive
         * with heartbeat events could otherwise leave one history segment pending forever.
         */
        const val COMPACT_REQUEST_DEADLINE_MS = 900_000L
        const val MAX_STICKER_PACKS = 20
        private const val MAX_DSH_RESULT_CHARS = 200_000
        private const val MAX_REACH_RESULT_CHARS = 60_000
        private val PC_STICKER_FILE = Regex("[0-9a-f]{12}/\\d{3}\\.(png|jpg|gif|webp)")
    }
}
