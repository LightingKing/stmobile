package app.pockettavern

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pockettavern.data.*
import app.pockettavern.domain.*
import app.pockettavern.engine.*
import app.pockettavern.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface NativeHost {
    fun importFile(kind: String)
    fun exportText(name: String, mime: String, content: String)
    fun exportBinary(name: String, mime: String, file: File)
    fun pickAttachment()
    fun openFile(file: File, mime: String)
}

class AppController(application: Application) : AndroidViewModel(application), AppActions {
    private val app = application as PocketApplication
    private val mutable = MutableStateFlow(UiSession())
    val state = mutable.asStateFlow()
    var host: NativeHost? = null
    private val providerClient = ProviderClient()
    private val mediaClient = MediaClient()
    private val tools = ToolRuntime(File(app.filesDir,"workspace").apply { mkdirs() }) { id -> key(id) }
    private val saves = Channel<AppData>(Channel.CONFLATED)
    private var loadFailed = false
    private var operation: Job? = null
    private var installationCall: okhttp3.Call? = null
    private val data get() = mutable.value.data
    private val selected get() = data.chats.find { it.id == mutable.value.selectedChatId }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (snapshot in saves) try { app.database.state().save(StateDocument(json=DataCodec.encode(snapshot))) } catch (error: Exception) {
                withContext(Dispatchers.Main) { status("保存失败：${error.message ?: "存储不可用"}") }
            }
        }
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { app.database.state().load()?.let { DataCodec.decode(it.json) } ?: AppData() }
                val restored = loaded.copy(chats=loaded.chats.map { chat -> chat.copy(messages=chat.messages.map { if(it.status=="streaming") it.copy(status="interrupted") else it }) })
                mutable.value = mutable.value.copy(data=restored,selectedChatId=restored.chats.firstOrNull()?.id ?: "",loading=false,hasKeys=restored.providers.filter { app.secrets.has(credentialId(it)) }.map { it.id }.toSet())
                restored.chats.forEach { repairPendingTools(it.id) }
            } catch (error: Exception) {
                loadFailed = true
                mutable.value = mutable.value.copy(loading=false,status="读取本地数据失败，原数据库已保留。已阻止写入；可到设置导出原始数据或恢复备份：${error.message}")
            }
        }
    }

    private fun change(value: AppData, persist: Boolean = true) {
        if(mutable.value.loading) { status("正在读取本地数据，请稍候"); return }
        if(loadFailed) { status("本地数据读取失败，已阻止覆盖。请在设置导出原始数据或恢复备份"); return }
        mutable.value = mutable.value.copy(data=value)
        if (persist) saves.trySend(value)
    }
    private fun status(text: String) { mutable.value = mutable.value.copy(status=text) }
    private fun changeChat(id: String, persist: Boolean = true, transform: (Chat)->Chat) {
        change(data.copy(chats=data.chats.map { if(it.id==id) transform(it).copy(updatedAt=System.currentTimeMillis()) else it }),persist)
    }
    private fun current(id: String): Chat = data.chats.find { it.id == id } ?: error("会话已删除")
    private fun provider(id: String): ProviderConfig = data.providers.find { it.id==id } ?: error("请先配置模型平台")
    private fun credentialId(provider: ProviderConfig): String = java.security.MessageDigest.getInstance("SHA-256").digest("${provider.id}|${provider.kind}|${provider.baseUrl.trim().trimEnd('/')}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun key(id: String): String = app.secrets.get(credentialId(provider(id))).also { require(it.isNotBlank()) { "请在模型页面填写 API key" } }
    private fun preset(chat: Chat): Preset = data.presets.find { it.id==chat.presetId } ?: data.presets.first()
    private fun action(block: suspend ()->Unit) {
        if(mutable.value.loading) { status("正在读取本地数据，请稍候"); return }
        if(loadFailed) { status("请先恢复本地数据，已阻止新任务覆盖原数据库"); return }
        if(mutable.value.busy) { status("当前任务进行中，请先停止或等待完成"); return }
        mutable.value = mutable.value.copy(busy=true,status="")
        operation = viewModelScope.launch {
            try { block() }
            catch (_: CancellationException) { status("已停止；已收到的内容已保留") }
            catch (error: Exception) { status(error.message?.take(800) ?: "操作失败") }
            finally { mutable.value = mutable.value.copy(busy=false); saves.trySend(data) }
        }
    }

    override fun saveProvider(provider: ProviderConfig, key: String) {
        runCatching {
            require(!mutable.value.busy) { "请先停止当前任务再修改平台" }
            val uri = Uri.parse(provider.baseUrl.trim())
            require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null) { "平台地址必须是有效 HTTPS API 地址，不含密钥或查询参数" }
            require(provider.name.isNotBlank() && provider.kind in listOf("venice","openai","anthropic")) { "平台名称或协议无效" }
            val normalized = provider.copy(baseUrl=provider.baseUrl.trim().trimEnd('/'),name=provider.name.trim())
            val previous=data.providers.find { it.id==provider.id }
            require(previous==null || credentialId(previous)==credentialId(normalized) || key.isNotBlank()) { "平台地址或协议已更改，请重新填写 API key" }
            if(key.isNotBlank()) app.secrets.put(credentialId(normalized),key.trim())
            change(data.copy(providers=data.providers.filterNot { it.id==provider.id } + normalized))
            mutable.value = mutable.value.copy(hasKeys=data.providers.filter { app.secrets.has(credentialId(it)) }.map { it.id }.toSet())
            status("平台已保存")
        }.onFailure { status(it.message ?: "保存失败") }
    }
    override fun deleteProvider(id: String) {
        if(mutable.value.busy) { status("请先停止当前任务"); return }
        data.providers.find { it.id==id }?.let { app.secrets.delete(credentialId(it)) }; change(data.copy(providers=data.providers.filterNot { it.id==id }))
        mutable.value=mutable.value.copy(hasKeys=mutable.value.hasKeys-id)
    }
    override fun refreshModels(id: String) = action {
        val targets=if(id=="*") data.providers.toList() else listOf(provider(id))
        val reports=mutableListOf<String>()
        for(p in targets) {
            try {
                val models=providerClient.models(p,key(p.id))
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
    override fun createChat(characterId: String) {
        val p=data.providers.firstOrNull { it.id in mutable.value.hasKeys && it.models.any { m -> m.type=="text" && !m.offline } }
            ?: data.providers.firstOrNull { it.models.any { m -> m.type=="text" && !m.offline } } ?: data.providers.firstOrNull()
        val character=data.characters.find { it.id==characterId }
        val chat=Chat(title=character?.name?.takeIf { it.isNotBlank() } ?: "新会话",providerId=p?.id ?: "",modelId=p?.models?.firstOrNull { it.type=="text" && !it.offline }?.id ?: "",presetId=if(character!=null && data.presets.any { it.id=="roleplay" }) "roleplay" else data.presets.first().id,characterId=characterId,messages=character?.firstMessage?.takeIf { it.isNotBlank() }?.let { listOf(Message(role="assistant",content=it)) } ?: emptyList())
        change(data.copy(chats=listOf(chat)+data.chats)); mutable.value=mutable.value.copy(selectedChatId=chat.id,pendingAttachments=emptyList())
    }
    override fun selectChat(id: String) { mutable.value=mutable.value.copy(selectedChatId=id,pendingAttachments=emptyList()) }
    override fun updateChat(chat: Chat) { if(mutable.value.busy) { status("请先停止当前任务再修改会话设置"); return }; changeChat(chat.id) { chat } }
    override fun deleteChat(id: String) {
        if(mutable.value.busy) { status("请先停止当前任务"); return }
        change(data.copy(chats=data.chats.filterNot { it.id==id },memories=data.memories.filterNot { it.scope=="chat" && it.targetId==id }))
        if(mutable.value.selectedChatId==id) mutable.value=mutable.value.copy(selectedChatId=data.chats.firstOrNull()?.id ?: "")
    }
    override fun chooseModel(providerId: String, modelId: String) {
        if(mutable.value.busy) { status("请先停止当前任务再切换模型"); return }
        val candidate=data.providers.find { it.id==providerId }?.models?.find { it.id==modelId }
        if(candidate==null || candidate.type!="text" || candidate.offline) { status("请选择目录中可用的聊天模型，必要时先刷新"); return }
        if(selected==null) createChat()
        selected?.let { changeChat(it.id) { c -> c.copy(providerId=providerId,modelId=modelId) } }
        status("已选择 $modelId")
    }
    override fun send(text: String): Boolean {
        if(text.isBlank() && mutable.value.pendingAttachments.isEmpty()) return false
        if(mutable.value.loading || loadFailed) { status("本地数据尚未就绪，消息未发送"); return false }
        if(mutable.value.busy) { status("当前任务进行中"); return false }
        if(selected==null) createChat()
        val chat=selected ?: return false
        if(chat.modelId.isBlank()) { status("请先在模型页面刷新并选择聊天模型"); return false }
        val attachments=mutable.value.pendingAttachments
        val model=data.providers.find { it.id==chat.providerId }?.models?.find { it.id==chat.modelId }
        if(attachments.any { it.mime.startsWith("image/") } && model?.supportsVision!=true) { status("当前模型未声明视觉能力，请选择支持图片的模型"); return false }
        val message=Message(content=text.trim(),attachments=attachments)
        changeChat(chat.id) { it.copy(title=if(it.messages.isEmpty() && it.title=="新会话") text.trim().take(24).ifBlank { "附件会话" } else it.title,messages=it.messages+message) }
        mutable.value=mutable.value.copy(pendingAttachments=emptyList())
        action { generate(chat.id) }
        return true
    }
    private suspend fun generate(chatId: String) {
        var liveId=""
        try {
            var chat=current(chatId)
            var context=ContextEngine.build(data,chat)
            if(context.needsCompression && data.settings.autoCompress && ContextEngine.compressionSlice(data,chat)!=null) { summarize(chatId); chat=current(chatId); context=ContextEngine.build(data,chat) }
            require(context.estimatedTokens<=context.budget) { "上下文超出预算：请压缩历史、减少固定内容或调整上下文窗口" }
            val p=provider(chat.providerId); val secret=key(p.id); val model=p.models.find { it.id==chat.modelId }
            require(model!=null && model.type=="text" && !model.offline) { "模型不在可用聊天目录中，请刷新目录或手动添加模型" }
            val activeSkills=data.skills.filter { it.id in chat.skillIds && it.enabled }
            val toolDefinitions=if(model?.supportsTools==true) tools.definitions(activeSkills.flatMap { it.permissions }.distinct()) else "[]"
            for(step in 0 until data.settings.maxToolSteps) {
                currentCoroutineContext().ensureActive()
                val before=current(chatId)
                val built=ContextEngine.build(data,before)
                require(built.estimatedTokens<=built.budget) { "工具结果使上下文超限，请压缩后重试" }
                val outgoing=built.messagesJson
                val assistant=Message(role="assistant",status="streaming")
                liveId=assistant.id
                changeChat(chatId) { it.copy(messages=it.messages+assistant) }
                var content=""; var reasoning=""; var lastSave=System.currentTimeMillis()
                val result=providerClient.chat(p,secret,before.modelId,outgoing,preset(before),toolDefinitions) { delta ->
                    content+=delta.content; reasoning+=delta.reasoning
                    val textCopy=content; val reasoningCopy=reasoning
                    viewModelScope.launch {
                        val persist=System.currentTimeMillis()-lastSave>900
                        if(persist) lastSave=System.currentTimeMillis()
                        changeChat(chatId,persist) { c -> c.copy(messages=c.messages.map { if(it.id==assistant.id && it.status=="streaming") it.copy(content=textCopy,reasoning=reasoningCopy) else it }) }
                    }
                }
                changeChat(chatId) { c -> c.copy(messages=c.messages.map { if(it.id==assistant.id) it.copy(content=result.content,reasoning=result.reasoning,toolCallsJson=result.toolCallsJson,status="complete") else it }) }
                liveId=""
                val calls=JSONArray(result.toolCallsJson)
                if(calls.length()==0) return
                require(model?.supportsTools==true) { "模型返回了未启用的工具调用" }
                for(i in 0 until calls.length()) {
                    currentCoroutineContext().ensureActive()
                    val call=calls.getJSONObject(i); val function=call.getJSONObject("function")
                    val liveSkills=data.skills.filter { it.enabled && it.id in current(chatId).skillIds }
                    val toolResult=try { tools.execute(function.getString("name"),function.optString("arguments","{}"),liveSkills,data,current(chatId)) }
                    catch(error: Exception) { if(error is CancellationException) throw error; ToolResult("工具执行失败：${error.message}") }
                    toolResult.memories?.let { added -> change(data.copy(memories=(data.memories+added).associateBy { it.id }.values.toList())) }
                    changeChat(chatId) { c -> c.copy(messages=c.messages+Message(role="tool",content=toolResult.content,toolCallId=call.getString("id"))) }
                }
            }
            status("已达到工具执行步数上限。结果已保存，可继续发送消息")
        } catch(error: Exception) {
            if(liveId.isNotEmpty()) changeChat(chatId) { c -> c.copy(messages=c.messages.map { if(it.id==liveId) it.copy(status=if(error is CancellationException) "stopped" else "error") else it }) }
            repairPendingTools(chatId)
            throw error
        }
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
            (ids-answered).forEach { result+=Message(role="tool",toolCallId=it,content="该工具在执行完成前被停止或失败，未确认完成。") }
        }
        if(result!=chat.messages) changeChat(chatId) { it.copy(messages=result) }
    }
    override fun stop() { operation?.cancel(); providerClient.cancel(); mediaClient.cancel(); tools.cancel(); installationCall?.cancel() }
    override fun regenerate() {
        val chat=selected ?: return
        if(mutable.value.busy) return
        val lastUser=chat.messages.indexOfLast { it.role=="user" }
        if(lastUser<0) { status("请先发送一条消息"); return }
        val retainedMessageIds=chat.messages.take(lastUser+1).map { it.id }.toSet()
        changeChat(chat.id) { it.copy(messages=it.messages.take(lastUser+1),activeSummaryId="") }
        change(data.copy(memories=data.memories.map { memory ->
            if (memory.scope=="chat" && memory.targetId==chat.id && memory.sourceMessageId.isNotBlank() && memory.sourceMessageId !in retainedMessageIds) {
                memory.copy(enabled=false)
            } else memory
        }))
        action { generate(chat.id) }
    }
    override fun editMessage(id: String, text: String) {
        if(mutable.value.busy) { status("请先停止当前生成"); return }
        val chat=selected ?: return
        changeChat(chat.id) { c -> c.copy(messages=c.messages.map { if(it.id==id) it.copy(content=text) else it },activeSummaryId="") }
        change(data.copy(memories=data.memories.map { memory ->
            if (memory.scope=="chat" && memory.targetId==chat.id && memory.sourceMessageId==id) memory.copy(enabled=false) else memory
        }))
    }
    override fun branchAt(messageId: String) {
        val chat=selected ?: return; val index=chat.messages.indexOfFirst { it.id==messageId }; if(index<0) return
        val branch=chat.copy(id=newId(),title=chat.title+" · 分支",messages=chat.messages.take(index+1),activeSummaryId="",summaries=emptyList())
        val copiedMemories=data.memories.filter { it.scope=="chat" && it.targetId==chat.id && (it.sourceMessageId.isBlank() || branch.messages.any { m -> m.id==it.sourceMessageId }) }.map { it.copy(id=newId(),targetId=branch.id) }
        change(data.copy(chats=listOf(branch)+data.chats,memories=data.memories+copiedMemories)); repairPendingTools(branch.id); selectChat(branch.id)
    }
    override fun savePreset(preset: Preset) {
        if(preset.name.isBlank() || preset.maxOutput !in 1..131072 || preset.temperature !in 0.0..2.0) { status("预设名称或生成参数无效"); return }
        change(data.copy(presets=data.presets.filterNot { it.id==preset.id }+preset))
    }
    override fun deletePreset(id: String) { if(data.presets.size<=1) { status("至少保留一个预设"); return }; change(data.copy(presets=data.presets.filterNot { it.id==id })) }
    override fun saveCharacter(character: Character) { if(character.name.isBlank()) { status("请填写角色名称"); return }; change(data.copy(characters=data.characters.filterNot { it.id==character.id }+character)) }
    override fun deleteCharacter(id: String) { change(data.copy(characters=data.characters.filterNot { it.id==id })) }
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
    override fun saveSettings(settings: AppSettings) {
        if(settings.keepRecentTurns !in 1..100 || settings.compressionThreshold !in 0.2..0.95 || settings.maxToolSteps !in 1..16 || settings.contextOverride !in 0..2000000) { status("设置超出允许范围"); return }
        change(data.copy(settings=settings)); status("设置已保存")
    }
    override fun compress() { selected?.id?.let { id -> action { summarize(id); status("摘要已保存，原始消息完整保留") } } }
    private suspend fun summarize(chatId: String) {
        val chat=current(chatId)
        val slice=ContextEngine.compressionSlice(data,chat) ?: error("可压缩的旧消息不足，可减少保留轮数后重试")
        val active=preset(chat)
        val p=provider(active.summaryProviderId.ifBlank { chat.providerId })
        val modelId=active.summaryModelId.ifBlank { chat.modelId }
        require(modelId.isNotBlank()) { "请选择摘要模型" }
        val summaryModel=p.models.find { it.id==modelId }
        require(summaryModel!=null && summaryModel.type=="text" && !summaryModel.offline) { "摘要模型不可用，请刷新目录并重新选择" }
        val window=summaryModel.contextTokens
        val output=minOf(2048,window/4).coerceAtLeast(256)
        val summaryPreset=active.copy(maxOutput=output,temperature=0.2)
        val cap=(window-output-2048).coerceAtLeast(1024)
        val secret=key(p.id)
        var pieces=ContextEngine.summarySource(slice.messages)
        suspend fun reduce(input: List<String>): List<String> {
            val chunks=mutableListOf<String>(); val buffer=StringBuilder()
            input.forEach { text ->
                for(part in text.chunked(maxOf(256,cap/2))) {
                    if(ContextEngine.estimateTokens(buffer.toString()+part)>cap && buffer.isNotEmpty()) { chunks+=buffer.toString(); buffer.clear() }
                    buffer.append(part).append('\n')
                }
            }
            if(buffer.isNotEmpty()) chunks+=buffer.toString()
            return chunks.mapIndexed { index,text ->
                status("正在压缩历史 ${index+1}/${chunks.size}")
                val messages=JSONArray().put(JSONObject().put("role","system").put("content",active.summaryPrompt)).put(JSONObject().put("role","user").put("content",text))
                providerClient.chat(p,secret,modelId,messages.toString(),summaryPreset).content.also { require(it.isNotBlank()) { "摘要模型返回空内容，原摘要未修改" } }
            }
        }
        pieces=reduce(pieces)
        var rounds=0
        while(pieces.size>1 && rounds++<5) pieces=reduce(pieces)
        require(pieces.size==1 && ContextEngine.estimateTokens(pieces.first())<cap) { "摘要仍过长，请提高摘要模型窗口或降低输出长度" }
        val now=current(chatId)
        val end=now.messages.indexOfFirst { it.id==slice.throughMessageId }
        require(end>=0 && ContextEngine.sourceHash(now.messages.take(end+1))==slice.sourceHash) { "消息已修改，本次摘要未应用" }
        val summary=Summary(text=pieces.first(),throughMessageId=slice.throughMessageId,sourceHash=slice.sourceHash,modelId=modelId)
        changeChat(chatId) { it.copy(summaries=it.summaries+summary,activeSummaryId=summary.id) }
    }
    override fun restoreSummary(id: String) { selected?.let { changeChat(it.id) { c -> c.copy(activeSummaryId=id) } }; status(if(id.isEmpty()) "已恢复完整原始上下文" else "已切换摘要版本") }
    override fun previewContext() { runCatching { selected?.let { mutable.value=mutable.value.copy(contextPreview=ContextEngine.build(data,it).preview) } }.onFailure { status(it.message ?: "无法组装上下文") } }
    override fun dismissPreview() { mutable.value=mutable.value.copy(contextPreview="") }
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
    fun importBytes(kind: String, name: String, bytes: ByteArray) {
        runCatching {
            require(!mutable.value.busy) { "请先停止当前任务再导入" }
            when(kind) {
                "backup" -> { val restored=BackupArchive.restore(bytes,app.filesDir,app.cacheDir).let { it.copy(skills=it.skills.map { s -> s.copy(enabled=false,permissions=emptyList()) }) }; loadFailed=false; change(restored); mutable.value=mutable.value.copy(selectedChatId=restored.chats.firstOrNull()?.id ?: "",hasKeys=restored.providers.filter { app.secrets.has(credentialId(it)) }.map { it.id }.toSet()); status("备份已恢复；技能权限需重新授予，未配置平台请重新填写密钥") }
                "skill" -> { val skills=SkillImporter.importFile(name,bytes); change(data.copy(skills=data.skills+skills)); status("已安装 ${skills.size} 个 Skill。请检查权限并在会话中启用") }
                "character" -> { val c=Exchange.character(bytes); saveCharacter(c); val root=runCatching { JSONObject(c.originalJson).optJSONObject("data")?.optJSONObject("character_book") }.getOrNull(); if(root!=null) change(data.copy(worldEntries=data.worldEntries+Exchange.worldbook(root.toString(),c.id))); status("角色卡已导入") }
                "worldbook" -> { val entries=Exchange.worldbook(bytes.toString(Charsets.UTF_8)); change(data.copy(worldEntries=data.worldEntries+entries)); status("已导入 ${entries.size} 个世界书条目") }
                "chat" -> { val p=data.providers.firstOrNull(); val chat=Exchange.chat(bytes.toString(Charsets.UTF_8),p?.id ?: "",p?.models?.firstOrNull { it.type=="text" }?.id ?: ""); change(data.copy(chats=listOf(chat)+data.chats)); selectChat(chat.id); status("会话已导入") }
                "preset" -> { val p=Exchange.preset(bytes.toString(Charsets.UTF_8),name.substringBeforeLast('.')); savePreset(p); status("预设已导入；不支持的 ST 扩展字段未执行") }
                else -> error("未知导入格式")
            }
        }.onFailure { status("导入失败：${it.message}") }
    }
    override fun exportFile(kind: String, id: String) {
        if(loadFailed && kind=="backup") {
            viewModelScope.launch {
                runCatching { val original=withContext(Dispatchers.IO) { app.database.state().load()?.json ?: error("未找到可恢复的数据") }; host?.exportText("pocket-tavern-recovery.json","application/json",original) }.onFailure { status("原始数据导出失败：${it.message}") }
            }
            return
        }
        runCatching {
            when(kind) {
                "backup" -> { val file=File(app.cacheDir,"exports/backup-${newId()}.zip"); BackupArchive.write(data,app.filesDir,file); host?.exportBinary("pocket-tavern-backup.zip","application/zip",file) }
                "chat" -> host?.exportText("conversation.jsonl","application/octet-stream",Exchange.chatJsonl(data.chats.first { it.id==id.ifBlank { mutable.value.selectedChatId } }))
                "character" -> host?.exportText("character.json","application/json",Exchange.characterJson(data.characters.first { it.id==id }))
                "preset" -> host?.exportText("preset.json","application/json",Exchange.presetJson(data.presets.first { it.id==id }))
                "skill" -> host?.exportText("SKILL.md","text/markdown",data.skills.first { it.id==id }.let { "---\nname: ${it.name}\ndescription: ${JSONObject.quote(it.description)}\n---\n\n${it.content}" })
                "worldbook" -> { val entries=JSONObject(); data.worldEntries.forEachIndexed { i,e -> entries.put(i.toString(),JSONObject().put("comment",e.name).put("content",e.content).put("key",JSONArray(e.keywords)).put("constant",e.always).put("disable",!e.enabled).put("order",e.priority)) }; host?.exportText("worldbook.json","application/json",JSONObject().put("entries",entries).toString(2)) }
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
    override fun clearStatus() { status("") }
    fun reportError(text: String) { status(text) }
    override fun onCleared() { stop(); saves.close(); super.onCleared() }
}
