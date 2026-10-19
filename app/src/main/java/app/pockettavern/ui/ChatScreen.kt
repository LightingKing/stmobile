package app.pockettavern.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pockettavern.domain.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A reading-first conversation surface. Persistence and generation stay in AppActions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTab(state: UiSession, actions: AppActions) {
    val chats = state.data.chats.sortedByDescending { it.updatedAt }
    val chat = chats.firstOrNull { it.id == state.selectedChatId } ?: chats.firstOrNull()
    val conversationStates = rememberSaveableStateHolder()
    var historyOpen by remember { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember(chat?.id) { mutableStateOf(false) }
    var contextOpen by remember(chat?.id) { mutableStateOf(false) }
    var renameOpen by remember(chat?.id) { mutableStateOf(false) }
    var deleteOpen by remember(chat?.id) { mutableStateOf(false) }
    var editMessage by remember(chat?.id) { mutableStateOf<Message?>(null) }
    var branchMessage by remember(chat?.id) { mutableStateOf<Message?>(null) }

    Column(Modifier.fillMaxSize()) {
        ConversationHeader(chat, state, actions, onHistory = { historyOpen = true }, onModels = { modelsOpen = true },
            onOptions = { optionsOpen = true }, onRename = { renameOpen = true }, onDelete = { deleteOpen = true })
        if (chat == null) {
            ConversationWelcome(Modifier.weight(1f), "给想象，留一点空间。", "聊天、创作，或继续一个故事。\n你的会话会保存在这台设备上。") {
                Button(onClick = { actions.createChat() }, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp), enabled = !state.busy) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("开始新对话")
                }
            }
        } else {
            conversationStates.SaveableStateProvider(chat.id) {
                ConversationBody(chat, state, actions, Modifier.weight(1f), onEdit = { editMessage = it }, onBranch = { branchMessage = it }, onModels = { modelsOpen = true })
            }
        }
    }

    if (historyOpen) ConversationHistory(chats, chat?.id, state.busy, onDismiss = { historyOpen = false }, onSelect = {
        actions.selectChat(it); historyOpen = false
    }, onCreate = { actions.createChat(); historyOpen = false })
    if (modelsOpen) ConversationModels(chat, state, actions) { modelsOpen = false }
    if (chat != null) {
        if (optionsOpen) ChatOptionsDialog(chat, state, actions, onDismiss = { optionsOpen = false }, onPreview = {
            actions.previewContext(); optionsOpen = false; contextOpen = true
        })
        if (renameOpen) RenameChatDialog(chat, actions) { renameOpen = false }
        if (deleteOpen) ConfirmDeleteDialog("会话“${chat.title}”", {
            actions.deleteChat(chat.id); deleteOpen = false
        }) { deleteOpen = false }
        editMessage?.let { EditMessageDialog(it, actions) { editMessage = null } }
        branchMessage?.let { message ->
            AlertDialog(onDismissRequest = { branchMessage = null }, title = { Text("从这里继续") },
                text = { Text("保留这条消息及之前的内容，创建一个独立的会话分支。原会话会保留。") },
                confirmButton = { Button(onClick = { actions.branchAt(message.id); branchMessage = null }, enabled = !state.busy) { Text("创建分支") } },
                dismissButton = { TextButton(onClick = { branchMessage = null }) { Text("取消") } })
        }
    }
    if (contextOpen) ContextPreviewDialog(state, actions) { contextOpen = false }
}

@Composable
private fun ConversationHeader(chat: Chat?, state: UiSession, actions: AppActions, onHistory: () -> Unit, onModels: () -> Unit, onOptions: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember(chat?.id) { mutableStateOf(false) }
    var presetOpen by remember(chat?.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onHistory) { Icon(Icons.Outlined.Menu, "历史会话", Modifier.size(24.dp)) }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(chat?.title ?: "口袋酒馆", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (chat == null) "随身的创作空间" else state.data.characters.firstOrNull { it.id == chat.characterId }?.name?.let { "与 $it 对话" } ?: "口袋酒馆 · 对话", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { actions.createChat() }, enabled = !state.busy) { Icon(Icons.Outlined.Add, "新建会话") }
            if (chat != null) Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreHoriz, "会话菜单") }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("记忆、压缩与 Skills") }, leadingIcon = { Icon(Icons.Outlined.Tune, null) }, enabled = !state.busy, onClick = { menuOpen = false; onOptions() })
                    DropdownMenuItem(text = { Text("重命名会话") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, enabled = !state.busy, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text("导出会话") }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null) }, onClick = { menuOpen = false; actions.exportFile("chat", chat.id) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("删除会话", color = colors.error) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = colors.error) }, enabled = !state.busy, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
        if (chat != null) {
            val provider = state.data.providers.firstOrNull { it.id == chat.providerId }
            val model = provider?.models?.firstOrNull { it.id == chat.modelId }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(onClick = onModels, enabled = !state.busy, shape = CircleShape, color = colors.primaryContainer, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(horizontal = 12.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(14.dp), tint = colors.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(model?.name?.ifBlank { model.id } ?: chat.modelId.ifBlank { "选择模型" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = colors.primary)
                    }
                }
                Box(Modifier.widthIn(max = 132.dp)) {
                    TextButton(onClick = { presetOpen = true }, enabled = !state.busy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp))
                        Text(state.data.presets.firstOrNull { it.id == chat.presetId }?.name ?: "预设", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    }
                    DropdownMenu(presetOpen, { presetOpen = false }) {
                        state.data.presets.forEach { preset ->
                            DropdownMenuItem(text = { Text(preset.name) }, trailingIcon = { if (preset.id == chat.presetId) Icon(Icons.Outlined.Check, null) }, enabled = !state.busy,
                                onClick = { actions.updateChat(chat.copy(presetId = preset.id)); presetOpen = false })
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
    }
}

@Composable
private fun ConversationBody(chat: Chat, state: UiSession, actions: AppActions, modifier: Modifier, onEdit: (Message) -> Unit, onBranch: (Message) -> Unit, onModels: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followLatest by rememberSaveable { mutableStateOf(true) }
    val showLatest by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 160 } }
    val latest = chat.messages.lastOrNull()
    val assistantName = state.data.characters.firstOrNull { it.id == chat.characterId }?.name?.ifBlank { null } ?: "酒馆助手"
    val lastUserIndex = chat.messages.indexOfLast { it.role == "user" }
    val lastAssistant = chat.messages.drop(lastUserIndex + 1).lastOrNull { it.role == "assistant" }?.id

    // Only follow the stream while the reader is at the bottom; reading history never jumps away.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollBackward }.collect { (scrolling, away) ->
            if (scrolling) followLatest = !away
        }
    }
    LaunchedEffect(latest?.id, latest?.content, latest?.reasoning, latest?.status) {
        if (followLatest && chat.messages.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (chat.messages.isEmpty()) {
                ConversationWelcome(Modifier.fillMaxSize(), "这一页，由你开始。", "一个想法、一段对白，\n或一个尚未讲完的故事。") {
                    if (chat.modelId.isBlank()) TextButton(onClick = onModels, enabled = !state.busy) {
                        Text("先选择聊天模型"); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp))
                    }
                }
            } else {
                LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    items(chat.messages.asReversed(), key = { it.id }) { message ->
                        ConversationMessage(message, assistantName, state.busy, message.id == lastAssistant && lastUserIndex >= 0, actions, onEdit, onBranch)
                    }
                }
                if (showLatest) FilledTonalIconButton(onClick = { followLatest = true; scope.launch { listState.animateScrollToItem(0) } }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) {
                    Icon(Icons.Outlined.ArrowDownward, "回到最新消息")
                }
            }
        }
        if (state.busy && latest?.status != "streaming") Row(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text("任务进行中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ConversationComposer(draft, { draft = it }, state.pendingAttachments, state.busy, onAttach = { actions.pickAttachment() }, onRemove = { actions.removeAttachment(it) }, onSend = {
            if (state.busy) actions.stop() else if (actions.send(draft.trim())) {
                draft = ""; followLatest = true
                scope.launch { listState.scrollToItem(0) }
            }
        })
    }
}

@Composable
private fun ConversationWelcome(modifier: Modifier, title: String, subtitle: String, action: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 8.dp)) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.padding(22.dp).size(32.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            action()
        }
    }
}

@Composable
private fun ConversationComposer(draft: String, onDraft: (String) -> Unit, attachments: List<Attachment>, busy: Boolean, onAttach: () -> Unit, onRemove: (String) -> Unit, onSend: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 10.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant), shadowElevation = 2.dp) {
            Column(Modifier.fillMaxWidth()) {
                if (attachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    attachments.forEach { attachment ->
                        InputChip(selected = false, onClick = { onRemove(attachment.id) }, enabled = !busy,
                            label = { Text(attachment.name, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Outlined.AttachFile, null, Modifier.size(14.dp)) },
                            trailingIcon = { Icon(Icons.Outlined.Close, "移除附件 ${attachment.name}", Modifier.size(16.dp)) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = onAttach, enabled = !busy) { Icon(Icons.Outlined.Add, "添加附件", tint = if (busy) colors.onSurface.copy(alpha = 0.38f) else colors.onSurfaceVariant) }
                    BasicTextField(value = draft, onValueChange = onDraft, modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 11.dp).semantics { contentDescription = "消息输入框" },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp, color = colors.onSurface), maxLines = 5,
                        cursorBrush = SolidColor(colors.primary), decorationBox = { inner ->
                            Box { if (draft.isEmpty()) Text("写下你的消息…", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp), color = colors.onSurfaceVariant); inner() }
                        })
                    Spacer(Modifier.width(6.dp))
                    FilledIconButton(onClick = onSend, enabled = busy || draft.isNotBlank() || attachments.isNotEmpty(), modifier = Modifier.size(48.dp), shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, disabledContainerColor = colors.surfaceContainerHigh, disabledContentColor = colors.onSurfaceVariant)) {
                        Icon(if (busy) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if (busy) "停止当前任务" else "发送消息", Modifier.size(23.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationMessage(message: Message, assistantName: String, busy: Boolean, canRegenerate: Boolean, actions: AppActions, onEdit: (Message) -> Unit, onBranch: (Message) -> Unit) {
    val user = message.role == "user"
    val tool = message.role == "tool"
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    var reasoningOpen by rememberSaveable(message.id) { mutableStateOf(false) }
    var toolOpen by rememberSaveable(message.id) { mutableStateOf(false) }
    val time = remember(message.createdAt) { SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(message.createdAt)) }
    val copyMessage = { clipboard.setText(AnnotatedString(message.content)) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Row(Modifier.fillMaxWidth(if (user) 0.9f else 1f), verticalAlignment = Alignment.CenterVertically) {
            if (!user) {
                Surface(shape = RoundedCornerShape(9.dp), color = if (tool) colors.surfaceContainerHigh else colors.primaryContainer) {
                    Icon(if (tool) Icons.Outlined.Code else Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.padding(6.dp).size(17.dp), tint = colors.primary)
                }
                Spacer(Modifier.width(9.dp))
            } else Spacer(Modifier.weight(1f))
            Text(if (user) "你" else if (tool) "工具结果" else if (message.role == "assistant") assistantName else "系统消息", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (user) Modifier else Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(time, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreHoriz, "消息操作", Modifier.size(18.dp), tint = colors.onSurfaceVariant) }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("复制文字") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { copyMessage(); menuOpen = false })
                    if (user) DropdownMenuItem(text = { Text("编辑消息") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, enabled = !busy, onClick = { menuOpen = false; onEdit(message) })
                    DropdownMenuItem(text = { Text("从这里创建分支") }, leadingIcon = { Icon(Icons.Outlined.AccountTree, null) }, enabled = !busy && message.status != "streaming", onClick = { menuOpen = false; onBranch(message) })
                }
            }
        }
        if (message.reasoning.isNotBlank()) {
            TextButton(onClick = { reasoningOpen = !reasoningOpen }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(if (reasoningOpen) "收起思考过程" else "查看思考过程", style = MaterialTheme.typography.labelMedium)
                Icon(if (reasoningOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
            }
            if (reasoningOpen) Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
                SelectionContainer { Text(message.reasoning, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            }
        }
        when {
            tool -> {
                TextButton(onClick = { toolOpen = !toolOpen }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (toolOpen) "收起结果" else "查看工具返回内容", style = MaterialTheme.typography.labelMedium)
                    Icon(if (toolOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                }
                if (toolOpen) Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) { ReadingMarkdown(message.content, Modifier.padding(12.dp)) }
            }
            user -> Surface(Modifier.fillMaxWidth(0.9f), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 20.dp), color = colors.primaryContainer) {
                ReadingMarkdown(message.content.ifBlank { if (message.attachments.isNotEmpty()) "已发送附件" else "（空消息）" }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            message.content.isNotBlank() -> ReadingMarkdown(message.content)
            message.status == "streaming" -> Text("正在构思…", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            message.toolCallsJson != "[]" -> Text("正在调用工具", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            else -> Text("（暂无回复内容）", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        if (message.attachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            message.attachments.forEach { attachment ->
                Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AttachFile, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                        Text(attachment.name, Modifier.widthIn(max = 220.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val status = when (message.status) { "streaming" -> "正在生成"; "stopped" -> "已停止 · 已保留生成内容"; "error" -> "生成中断 · 可重试"; "interrupted" -> "上次生成中断"; else -> "" }
        if (status.isNotBlank()) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (message.status == "streaming") CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text(status, style = MaterialTheme.typography.labelSmall, color = if (message.status == "error") colors.error else colors.onSurfaceVariant)
        }
        if (canRegenerate && message.status != "streaming") Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = copyMessage) { Icon(Icons.Outlined.ContentCopy, "复制回复", Modifier.size(17.dp), tint = colors.onSurfaceVariant) }
            TextButton(onClick = { actions.regenerate() }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("重新生成", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationHistory(chats: List<Chat>, selectedId: String?, busy: Boolean, onDismiss: () -> Unit, onSelect: (String) -> Unit, onCreate: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = chats.filter { query.isBlank() || it.title.contains(query.trim(), true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("你的会话", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                FilledTonalButton(onClick = onCreate, enabled = !busy) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("新建") }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(20.dp), placeholder = { Text("搜索会话名称") }, singleLine = true, shape = RoundedCornerShape(16.dp), leadingIcon = { Icon(Icons.Outlined.Search, null) })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (visible.isEmpty()) item { Text("没有找到会话", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(visible, key = { it.id }) { chat ->
                    Surface(onClick = { onSelect(chat.id) }, enabled = !busy, shape = RoundedCornerShape(16.dp), color = if (chat.id == selectedId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(chat.messages.lastOrNull { it.role != "tool" }?.content?.replace('\n', ' ')?.ifBlank { "暂无消息" } ?: "暂无消息", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (chat.id == selectedId) Icon(Icons.Outlined.Check, "当前会话", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationModels(
    chat: Chat?,
    state: UiSession,
    actions: AppActions,
    initialQuery: String = "",
    initialProviderId: String = "",
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            ConversationModelsContent(chat, state, actions, initialQuery, initialProviderId, onDismiss)
        }
    }
}

@Composable
internal fun ConversationModelsContent(
    chat: Chat?,
    state: UiSession,
    actions: AppActions,
    initialQuery: String = "",
    initialProviderId: String = "",
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var providerId by rememberSaveable { mutableStateOf(initialProviderId) }
    var localStatus by rememberSaveable { mutableStateOf(state.status) }
    LaunchedEffect(state.status) {
        if (state.status.isNotBlank()) localStatus = state.status
    }
    LaunchedEffect(state.data.providers.map { it.id }) {
        if (providerId.isNotBlank() && state.data.providers.none { it.id == providerId }) providerId = ""
    }
    val models = remember(state.data.providers, query, providerId) { ModelCatalog.filter(state.data.providers, query, "text", providerId).filter { !it.second.offline } }
    val scopedProviders = state.data.providers.filter { providerId.isBlank() || it.id == providerId }
    val scopedCatalog = scopedProviders.flatMap { it.models }
    val availableChatCount = { provider: ProviderConfig -> provider.models.count { it.type.equals("text", true) && !it.offline } }
    val hasFilter = query.trim().isNotEmpty() || providerId.isNotBlank()
    val hasAvailableInScope = scopedCatalog.any { it.type.equals("text", true) && !it.offline }
    val typeSummary = scopedCatalog.groupingBy { modelTypeLabel(it.type) }.eachCount().entries.joinToString("、") { "${it.key} ${it.value} 个" }
    val offlineCount = scopedCatalog.count { it.offline }
    val refresh = {
        localStatus = ""
        actions.refreshModels(providerId.ifBlank { "*" })
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("选择聊天模型", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                FilledTonalButton(onClick = refresh, enabled = !state.busy) {
                    Icon(Icons.Outlined.Refresh, "刷新模型目录", Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (state.busy) "请求中…" else "刷新目录")
                }
            }
            if (localStatus.isNotBlank()) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Text(localStatus, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), singleLine = true, shape = RoundedCornerShape(16.dp), leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("搜索模型或平台") })
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(providerId.isBlank(), { providerId = "" }, label = { Text("全部平台") })
                state.data.providers.forEach { provider -> FilterChip(providerId == provider.id, { providerId = provider.id }, label = { Text("${provider.name}（${availableChatCount(provider)}）") }) }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (models.isEmpty()) item {
                    val title: String
                    val description: String
                    if (scopedCatalog.isEmpty()) {
                        title = "这个平台还没有模型目录"
                        description = "点击“刷新目录”获取真实模型；如果刚刚修改了平台地址，请先保存平台设置。"
                    } else if (!hasAvailableInScope) {
                        title = "目录里还没有可用的聊天模型"
                        description = "当前目录：$typeSummary；其中离线模型 ${offlineCount} 个。聊天选择只展示在线文本模型，请刷新目录或检查平台配置。"
                    } else {
                        title = "筛选没有匹配结果"
                        description = "当前筛选没有找到在线文本模型，可清除筛选或刷新目录。"
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (hasFilter) OutlinedButton(onClick = { query = ""; providerId = "" }) { Text("清除筛选") }
                            Button(onClick = refresh, enabled = !state.busy) { Text(if (state.busy) "请求中…" else "刷新目录") }
                        }
                    }
                }
                items(models, key = { "${it.first.id}:${it.second.type}:${it.second.id}" }) { (provider, model) ->
                    val selected = chat?.providerId == provider.id && chat.modelId == model.id
                    Surface(onClick = { actions.chooseModel(provider.id, model.id); onDismiss() }, enabled = !state.busy, shape = RoundedCornerShape(14.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(model.name.ifBlank { model.id }, style = MaterialTheme.typography.titleSmall)
                                Text(provider.name + " · " + (model.contextTokens / 1024).toString() + "K 上下文", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (selected) Icon(Icons.Outlined.Check, "当前模型", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
    }
}

private fun modelTypeLabel(type: String): String = when (type.lowercase(Locale.ROOT)) {
    "text" -> "文本"
    "image" -> "图像"
    "inpaint" -> "局部重绘"
    "upscale" -> "超分辨率"
    "tts" -> "语音合成"
    "asr" -> "语音识别"
    "video" -> "视频"
    "music" -> "音乐"
    "embedding" -> "向量"
    "decision" -> "决策"
    else -> type.ifBlank { "未知" }
}
