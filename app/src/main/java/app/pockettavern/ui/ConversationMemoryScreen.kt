package app.pockettavern.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pockettavern.domain.*
import app.pockettavern.engine.KnowledgeOrganization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ConversationMemoryScreen(chat: Chat, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ConversationMemoryContent(chat, state, onRebuild = actions::rebuildHistory, onStop = actions::stop, onDismiss = onDismiss, onExportDiagnostics = actions::exportMemoryDiagnostics,
            onOrganize = actions::compress, onInspect = { actions.inspectContext(chat.id) }, onRepairDamage = { actions.repairDamagedText(chat.id) },
            onSavePinned = { old, text -> actions.saveMemory((old ?: app.pockettavern.domain.Memory(scope = "chat", targetId = chat.id)).copy(text = text)) },
            onDeletePinned = { actions.deleteMemory(it.id) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationMemoryContent(
    chat: Chat,
    state: UiSession,
    onRebuild: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onExportDiagnostics: () -> Unit = {},
    initialSection: Int = MemoryBrowser.ALL,
    initialSelectionId: String? = null,
    initialShowSources: Boolean = false,
    initialSource: MemorySource? = null,
    initialQuery: String = "",
    initialDetailQuery: String = "",
    initialCategory: String = "",
    onOrganize: () -> Unit = {},
    onInspect: () -> Unit = {},
    onSavePinned: (app.pockettavern.domain.Memory?, String) -> Unit = { _, _ -> },
    onDeletePinned: (app.pockettavern.domain.Memory) -> Unit = {},
    onRepairDamage: () -> Unit = {}
) {
    var section by rememberSaveable(chat.id) { mutableIntStateOf(initialSection) }
    var category by rememberSaveable(chat.id) { mutableStateOf(initialCategory) }
    var query by rememberSaveable(chat.id) { mutableStateOf(initialQuery) }
    var selectedKey by rememberSaveable(chat.id) { mutableStateOf(MemoryBrowser.initialKey(chat, initialSelectionId, initialSection)) }
    var showSources by rememberSaveable(chat.id) { mutableStateOf(initialShowSources) }
    var detailQuery by rememberSaveable(chat.id) { mutableStateOf(initialDetailQuery) }
    val inspection = LocalInspectionMode.current
    val rebuild = state.memoryRebuild?.takeIf { it.chatId == chat.id }
    // Source validation can hash a long conversation; keep it off the UI thread and never reuse
    // a result calculated for an older chat snapshot while the current one is being checked.
    val rebuildSnapshot = remember(chat.id, chat.messages, chat.summaries, chat.activeSummaryId, rebuild) { chat to rebuild }
    val rebuiltState by produceState<Pair<Pair<Chat, MemoryRebuildState?>, MemoryRebuildResult?>?>(null, rebuildSnapshot, inspection) {
        value = null
        val result = if (inspection) rebuild?.let { MemoryBrowser.rebuildResult(chat, it) }
            else withContext(Dispatchers.Default) { rebuild?.let { MemoryBrowser.rebuildResult(chat, it) } }
        value = rebuildSnapshot to result
    }
    val previewRebuiltResult = remember(rebuildSnapshot, inspection) { if (inspection) rebuild?.let { MemoryBrowser.rebuildResult(chat, it) } else null }
    val rebuiltResult = if (inspection) previewRebuiltResult else rebuiltState?.takeIf { it.first == rebuildSnapshot }?.second
    val locked = state.chatBusy(chat.id) || state.loading
    // Refreshed whenever the chat changes, so the numbers match what the next message sends.
    LaunchedEffect(chat.id, chat.messages.size, chat.summaries.size, chat.knowledgeNotes.size, state.data.memories.size, locked) { if (!locked) onInspect() }
    val inspected = state.contextInspection?.takeIf { it.chatId == chat.id }
    val pinned = state.data.memories.filter { it.scope == "chat" && it.targetId == chat.id }
    val damage = remember(chat.messages) { app.pockettavern.engine.DamagedText.count(chat) }
    val previewIndex = remember(chat, inspection) { if (inspection) MemoryBrowser.index(chat) else null }
    val index by produceState(previewIndex, chat.id, chat.summaries, chat.knowledgeNotes, chat.messages, chat.activeSummaryId) {
        value = previewIndex
        if (!inspection) value = withContext(Dispatchers.Default) { MemoryBrowser.index(chat) }
    }
    val selected = index?.resolve(selectedKey)
    val organized = chat.summaries.any { it.format == "compact-v1" } || chat.knowledgeNotes.any { it.kind !in setOf("", "legacy") }
    val availableCategories = remember(index) { KnowledgeOrganization.categories.filter { category -> index?.entries?.any { it.category == category.id } == true } }
    LaunchedEffect(index, selectedKey) { if (index != null && selectedKey != null && selected == null) selectedKey = null }
    // Detail search also checks the rest of the library so an omitted name has a useful next step.
    val searchQuery = if (selectedKey == null) query else detailQuery
    val searchSection = if (selectedKey == null) section else MemoryBrowser.ALL
    val searchCategory = if (selectedKey == null && section == 1) category else ""
    val searchRequest = remember(index, searchQuery, searchSection, searchCategory) { MemorySearchRequest(index, searchQuery, searchSection, searchCategory) }
    val searchState by produceState<MemorySearchState?>(
        if (inspection) index?.let { MemorySearchState(searchRequest, MemoryBrowser.search(it, searchQuery, searchSection, searchCategory)) } else null,
        searchRequest
    ) {
        if (inspection) {
            value = searchRequest.index?.let { MemorySearchState(searchRequest, MemoryBrowser.search(it, searchRequest.query, searchRequest.section, searchRequest.category)) }
        } else if (searchRequest.index != null) {
            value = null
            if (searchRequest.query.isNotBlank()) delay(160)
            value = withContext(Dispatchers.Default) {
                MemorySearchState(searchRequest, MemoryBrowser.search(searchRequest.index, searchRequest.query, searchRequest.section, searchRequest.category))
            }
        }
    }
    val results = searchState?.takeIf { it.request == searchRequest }?.results
    fun goBack() {
        if (showSources) showSources = false
        else if (selectedKey != null) selectedKey = null
        else onDismiss()
    }
    fun searchAll() {
        query = detailQuery
        section = MemoryBrowser.ALL
        showSources = false
        selectedKey = null
    }
    BackHandler { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (selected == null) "会话记忆" else if (showSources) "对应原文" else selected.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (rebuild != null) MemoryRebuildStatus(
                rebuild, rebuiltResult,
                canStop = !state.loading && rebuild.phase == "running" && state.chatBusy(chat.id),
                onStop = onStop,
                onViewResult = {
                    rebuiltResult?.key?.let {
                        selectedKey = it
                        showSources = false
                        detailQuery = ""
                    }
                }
            )
            if (selected == null) {
                Text(chat.title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(MemoryBrowser.ALL to "全部", 0 to "摘要 ${chat.summaries.size}", 1 to "${if (organized) "知识条目" else "分段记忆"} ${chat.knowledgeNotes.size}", 2 to "原文 ${chat.messages.size}").forEach { (value, title) ->
                        FilterChip(selected = section == value, onClick = { section = value; selectedKey = null; showSources = false }, label = { Text(title) })
                    }
                }
                if (section == 1 && organized) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = category.isBlank(), onClick = { category = "" }, label = { Text("全部类别") })
                    availableCategories.forEach { item ->
                        FilterChip(selected = category == item.id, onClick = { category = item.id }, label = { Text(item.label) })
                    }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text(if (section == MemoryBrowser.ALL) "搜索全部内容" else "搜索当前分类", maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if (query.isNotBlank()) TextButton(onClick = { query = "" }) { Text("清除") } })
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (query.isBlank() && section == MemoryBrowser.ALL) {
                        item { MemoryOverviewCard(inspected, locked, onOrganize) }
                        if (damage.first > 0) item { DamagedTextCard(damage.first, damage.second, locked, state.chatTasks[chat.id]?.kind == ChatTask.REPAIR, onRepairDamage) }
                        item { PinnedFactsCard(pinned, locked, onSavePinned, onDeletePinned) }
                    }
                    item {
                        Text(if (organized) "续聊使用当前摘要；旧人物、关系与事件保存在知识库，需要时再查。点开条目可核对来源原文；文本附件也会一并查找。" else "摘要是有长度上限的导览。查找早期人物与剧情，请搜索全部分段记忆和原文；文本附件也会一并查找。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        OutlinedButton(onClick = onExportDiagnostics, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("导出记忆诊断") }
                        Text("仅含条数、摘要 ID 和覆盖状态，不含聊天正文。无需重建即可导出。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (query.isBlank()) item {
                        OutlinedButton(onClick = onRebuild, enabled = !locked && chat.messages.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("从全部原文重建记忆") }
                        Text("重新读取本机会话并调用摘要模型；会产生模型费用。全部成功后才更新记忆，原文与当前剧情会保留。日常压缩只整理新增内容，无需反复重建。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { Text(if (results == null) "正在检索全文…" else "${results.size} 项 · 点开阅读全文与来源", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (results?.isEmpty() == true) item {
                        Text(if (query.isNotBlank()) "当前范围没有匹配内容。" else when (section) {
                            0 -> "还没有摘要。可以从全部原文重建记忆。"
                            1 -> if (category.isNotBlank()) "这个类别还没有知识条目，可切换到全部类别。" else "还没有记忆条目。压缩或重建成功后，会在这里显示内容与来源。"
                            2 -> "这个会话还没有原文消息。"
                            else -> "这个会话还没有可读取的记忆或原文。"
                        }, style = MaterialTheme.typography.bodyMedium)
                        if (section != MemoryBrowser.ALL && query.isNotBlank()) OutlinedButton(onClick = { section = MemoryBrowser.ALL }) { Text("在全部记忆与原文中查找") }
                    }
                    items(results.orEmpty(), key = { it.entry.key }) { result ->
                        MemoryEntryCard(result) {
                            selectedKey = result.entry.key
                            showSources = false
                            detailQuery = query.takeIf { result.entry.text.contains(it.trim(), ignoreCase = true) }.orEmpty()
                        }
                    }
                }
            } else {
                val clipboard = LocalClipboardManager.current
                val sourceState = remember(chat, selected.key) { mutableStateOf(initialSource.takeIf { selected.id == initialSelectionId }) }
                LaunchedEffect(chat, selected.key) {
                    sourceState.value = withContext(Dispatchers.Default) { selected.source?.invoke() }
                }
                val source = sourceState.value
                val sourceRows = remember(source, index) {
                    source?.messages.orEmpty().mapNotNull { message -> index?.resolve("2:${message.message.id}")?.let { MemorySourceRow(message.number, it) } }
                }
                val detailRequest = remember(selected.key, selected.text, detailQuery, sourceRows) { MemoryDetailRequest(selected.text, detailQuery, sourceRows) }
                val detailState by produceState<MemoryDetailState?>(if (inspection) detailRequest.find() else null, detailRequest) {
                    if (inspection) value = detailRequest.find()
                    else {
                        value = null
                        if (detailRequest.query.isNotBlank()) delay(160)
                        value = withContext(Dispatchers.Default) { detailRequest.find() }
                    }
                }
                val detailMatches = detailState?.takeIf { it.request == detailRequest }
                val sourceMessages = detailMatches?.sources.orEmpty()
                val bodyChunks = detailMatches?.bodyChunks.orEmpty()
                val matchingChunks = detailMatches?.chunks.orEmpty()
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (source != null && !source.external) {
                        FilterChip(!showSources, onClick = { showSources = false; detailQuery = "" }, label = { Text("记忆全文") })
                        FilterChip(showSources, onClick = { showSources = true; detailQuery = "" }, label = { Text("对应原文 ${source.messages.size}") })
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(if (showSources) sourceRows.joinToString("\n\n") { "${it.entry.title}\n${it.entry.text}" } else selected.text)) }) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(if (showSources) "复制对应原文" else "复制全文")
                    }
                }
                OutlinedTextField(detailQuery, { detailQuery = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text(if (showSources) "搜索对应原文" else if (selected.kind == MemoryEntryKind.SUMMARY) "仅搜索本摘要" else "仅搜索本条", maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
                    trailingIcon = { if (detailQuery.isNotBlank()) TextButton(onClick = { detailQuery = "" }) { Text("清除") } })
                key(selected.key, showSources) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Text(memoryDate(selected.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (selected.modelId.isNotBlank()) Text("生成模型：${selected.modelId}", style = MaterialTheme.typography.bodySmall)
                            if (selected.metadata.isNotBlank()) {
                                Text(if (selected.kind == MemoryEntryKind.NOTE) "分类与对象" else "本次整理范围", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(selected.metadata, style = MaterialTheme.typography.bodySmall)
                            }
                            if (selected.kind == MemoryEntryKind.SUMMARY) Text("累计覆盖原文", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (selected.source != null && source == null) Text("正在核对来源原文…", style = MaterialTheme.typography.bodySmall)
                            source?.let { MemorySourceStatus(it) }
                            if (!showSources && selected.kind == MemoryEntryKind.SUMMARY) Text(if (selected.compact) "这是供继续对话使用的状态摘要；更早的细节保存在知识条目和原文中，不会每轮全部发送。" else "这是有长度上限的摘要导览，不包含全部事实。完整内容请查分段记忆和原文。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (detailQuery.isNotBlank()) item {
                            Text("仅显示匹配段落；清除搜索可阅读全文。复制按钮始终复制完整内容。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (detailMatches == null) item { Text("正在搜索全文…") }
                        else if (showSources) {
                            if (sourceMessages.isEmpty()) item {
                                Text(if (source == null && selected.source != null) "正在读取对应原文…" else if (detailQuery.isBlank()) "没有可读取的对应原文。" else "这份记忆的对应原文中没有匹配内容。")
                                if (detailQuery.isNotBlank()) SearchAllMemoriesButton(results?.size, ::searchAll)
                            }
                            sourceMessages.forEach { matchedSource ->
                                val row = matchedSource.row
                                item(key = "head:${row.number}") {
                                    HorizontalDivider()
                                    Text(row.entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(memoryDate(row.entry.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                items(matchedSource.matches, key = { "message:${row.number}:$it" }) { index ->
                                    if (matchedSource.chunks.size > 1) Text("第 ${index + 1} / ${matchedSource.chunks.size} 段", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    MemoryParagraph(matchedSource.chunks[index])
                                }
                            }
                        } else {
                            if (matchingChunks.isEmpty()) item {
                                Text(if (selected.kind == MemoryEntryKind.SUMMARY) "这份摘要未记录“${detailQuery.trim()}”，不能据此判断原文没有。" else "这份内容中没有匹配段落。", style = MaterialTheme.typography.bodyLarge)
                                if (detailQuery.isNotBlank()) SearchAllMemoriesButton(results?.size, ::searchAll)
                            }
                            items(matchingChunks, key = { it }) { index ->
                                if (bodyChunks.size > 1) Text("第 ${index + 1} / ${bodyChunks.size} 段", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                MemoryParagraph(bodyChunks[index])
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class MemorySearchRequest(val index: MemoryIndex?, val query: String, val section: Int, val category: String)
private data class MemorySearchState(val request: MemorySearchRequest, val results: List<MemorySearchResult>)
private data class MemorySourceRow(val number: Int, val entry: MemoryEntry)
private data class MemorySourceMatch(val row: MemorySourceRow, val chunks: List<String>, val matches: List<Int>)
private data class MemoryDetailRequest(val text: String, val query: String, val sources: List<MemorySourceRow>) {
    fun find(): MemoryDetailState {
        val bodyChunks = MemoryBrowser.chunks(text)
        val matchingSources = sources.mapNotNull { row ->
            if (query.isNotBlank() && !row.entry.text.contains(query.trim(), true)) return@mapNotNull null
            val chunks = MemoryBrowser.chunks(row.entry.text)
            MemorySourceMatch(row, chunks, memoryMatchingChunks(chunks, query, row.entry.text))
        }
        return MemoryDetailState(this, bodyChunks, memoryMatchingChunks(bodyChunks, query, text), matchingSources)
    }
}
private data class MemoryDetailState(val request: MemoryDetailRequest, val bodyChunks: List<String>, val chunks: List<Int>, val sources: List<MemorySourceMatch>)

@Composable
private fun SearchAllMemoriesButton(matchCount: Int?, onSearch: () -> Unit) {
    if (matchCount != null && matchCount > 0) Text("全部记忆与原文中找到 $matchCount 项匹配。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    Button(onClick = onSearch, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("在全部记忆与原文中查找") }
}

@Composable
private fun MemoryEntryCard(result: MemorySearchResult, onOpen: () -> Unit) {
    val entry = result.entry
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (entry.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (entry.category.isNotBlank() && entry.category !in setOf("legacy", "research")) Text(KnowledgeOrganization.label(entry.category), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(memoryDate(entry.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.source != null) Text(if (entry.external) "打开后查看资料、来源链接与事实/推测/虚构标注" else "打开后核对来源原文是否变动", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(result.excerpt.ifBlank { "（正文为空）" }, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text("阅读全文${if (entry.source != null && !entry.external) "与对应原文" else ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MemorySourceStatus(source: MemorySource) {
    Text(source.label, style = MaterialTheme.typography.bodySmall)
    Text(if (source.external) source.warning else if (source.valid) "来源原文未变（不代表内容无遗漏）" else source.warning, style = MaterialTheme.typography.bodySmall, color = if (source.valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
}

@Composable
private fun MemoryParagraph(text: String) {
    SelectionContainer { Text(text.ifEmpty { "（正文为空）" }, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun MemoryRebuildStatus(status: MemoryRebuildState, result: MemoryRebuildResult?, canStop: Boolean, onStop: () -> Unit, onViewResult: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = if (status.phase == "error") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.heightIn(max = if (result != null) 288.dp else 208.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(result?.title ?: when (status.phase) { "running" -> "正在重建记忆"; "success" -> "正在核对重建结果…"; "cancelled" -> "重建已中断"; else -> "记忆重建失败" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (canStop) TextButton(onClick = onStop) { Text("取消") }
            }
            if (result == null && status.total > 0) Text("已完成 ${status.completed} 次请求 · 预计共 ${status.total} 次", style = MaterialTheme.typography.labelSmall)
            if (status.phase == "running") LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    if (result != null) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (status.sourceMessageCount > 0) "本次覆盖 ${status.sourceMessageCount} 条原文 · ${status.noteCount} 条记忆" else "本次覆盖条数未记录 · ${status.noteCount} 条记忆", style = MaterialTheme.typography.bodySmall)
                        Text(if (status.finishedAt > 0L) "完成于 ${memoryFinishedTime(status.finishedAt)}" else "完成时间未记录", style = MaterialTheme.typography.bodySmall)
                    } else Text(if (status.phase == "success") "正在检查这份摘要对应的原文是否仍然有效。" else status.message, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (result?.key != null) TextButton(onClick = onViewResult, modifier = Modifier.fillMaxWidth()) { Text("查看本次重建结果") }
        }
    }
}

private fun memoryDate(timestamp: Long): String = if (timestamp <= 0L) "时间未记录" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
private fun memoryFinishedTime(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestamp))

/** Include both paragraphs when a search hit crosses a rendering boundary. */
internal fun memoryMatchingChunks(chunks: List<String>, query: String, text: String = chunks.joinToString("")): List<Int> {
    val search = query.trim()
    if (search.isEmpty()) return chunks.indices.toList()
    val hits = mutableListOf<IntRange>()
    var from = 0
    while (from <= text.length - search.length) {
        val start = text.indexOf(search, from, ignoreCase = true)
        if (start < 0) break
        hits += start until start + search.length
        from = start + 1
    }
    var offset = 0
    var hitIndex = 0
    return chunks.mapIndexedNotNull { index, chunk ->
        val start = offset
        offset += chunk.length
        while (hitIndex < hits.size && hits[hitIndex].last < start) hitIndex++
        index.takeIf { hitIndex < hits.size && hits[hitIndex].first < offset }
    }
}
