package app.pockettavern.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Attachment
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.MediaJob
import app.pockettavern.domain.Memory
import app.pockettavern.domain.ModelCatalog
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.SkillRecord
import app.pockettavern.domain.UiSession
import app.pockettavern.domain.WorldEntry
import app.pockettavern.provider.ModelPricing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

private enum class AppTab(val label: String, val icon: ImageVector) {
    Chats("会话", Icons.Outlined.ChatBubbleOutline),
    Characters("角色", Icons.Outlined.Group),
    Models("模型", Icons.Outlined.Cloud),
    Skills("Skills", Icons.Outlined.AutoAwesome),
    Settings("设置", Icons.Outlined.Settings)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PocketApp(state: UiSession, actions: AppActions) {
    var tab by rememberSaveable { mutableStateOf(AppTab.Chats.name) }
    val selectedTab = AppTab.valueOf(tab)
    val tabState = rememberSaveableStateHolder()
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.status) {
        if (state.status.isNotBlank()) {
            snackbar.showSnackbar(state.status)
            actions.clearStatus()
        }
    }
    PocketTheme(state.data.settings.darkMode) {
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (selectedTab != AppTab.Chats || state.loading) {
                TopAppBar(
                    title = {
                        Column {
                            Text("口袋酒馆", fontWeight = FontWeight.Bold)
                            Text(selectedTab.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    actions = {
                        if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        if (state.busy) LinearProgressIndicator(Modifier.width(56.dp).padding(end = 12.dp))
                    }
                )
                }
            },
            bottomBar = {
                if (!state.loading && !keyboardOpen) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                        AppTab.values().forEach { item ->
                            val selected = selectedTab == item
                                Column(
                                    Modifier
                                        .weight(1f).fillMaxSize()
                                        .selectable(selected = selected, role = Role.Tab, onClick = { tab = item.name }),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Surface(shape = RoundedCornerShape(12.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                                        Icon(item.icon, null, Modifier.padding(horizontal = 15.dp, vertical = 3.dp).size(22.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(item.label, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                        }
                    }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (state.loading) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(14.dp))
                        Text("正在加载本地数据…", style = MaterialTheme.typography.bodyLarge)
                        Text("数据加载完成后才能开始操作", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    tabState.SaveableStateProvider(selectedTab.name) { when (selectedTab) {
                        AppTab.Chats -> ChatTab(state, actions)
                        AppTab.Characters -> CharactersTab(state, actions)
                        AppTab.Models -> ModelsTab(state, actions)
                        AppTab.Skills -> SkillsTab(state, actions)
                        AppTab.Settings -> SettingsTab(state, actions)
                    } }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun Chip(text: String, icon: ImageVector? = null) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer); Spacer(Modifier.width(4.dp)) }
            Text(text, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
internal fun ChatOptionsDialog(chat: Chat, state: UiSession, actions: AppActions, onDismiss: () -> Unit, onPreview: () -> Unit) {
    var selected by remember { mutableStateOf(chat.skillIds.toSet()) }
    val skills = state.data.skills
    val activeSummary = chat.summaries.firstOrNull { it.id == chat.activeSummaryId }
    var summaryText by remember(chat.id, activeSummary?.id) { mutableStateOf(activeSummary?.text.orEmpty()) }
    var summaryError by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("会话设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("上下文与记忆", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onPreview, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Visibility, null); Spacer(Modifier.width(6.dp)); Text("预览实际上下文") }
            OutlinedButton(onClick = { actions.compress() }, Modifier.fillMaxWidth(), enabled = !state.busy) { Icon(Icons.Outlined.Cached, null); Spacer(Modifier.width(6.dp)); Text("压缩当前会话") }
            Text("当前摘要", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (activeSummary == null) {
                Text(if (chat.summaries.isEmpty()) "当前会话还没有摘要；执行压缩后会在这里出现。" else "当前没有激活的摘要版本；可从历史版本中恢复。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("覆盖至：${activeSummary.throughMessageId.ifBlank { "当前历史" }} · 模型：${activeSummary.modelId.ifBlank { "未记录" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(summaryText, { summaryText = it; summaryError = null }, Modifier.fillMaxWidth().height(160.dp), label = { Text("摘要内容") })
                summaryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Text("摘要历史版本", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (chat.summaries.isEmpty()) {
                Text("暂无可回退版本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                chat.summaries.sortedByDescending { it.createdAt }.forEach { summary ->
                    val coveredCount = chat.messages.indexOfFirst { it.id == summary.throughMessageId }.let { if (it < 0) "覆盖消息范围未知" else "覆盖至第 ${it + 1} 条消息" }
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (summary.id == chat.activeSummaryId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (summary.id == chat.activeSummaryId) "当前版本" else "历史版本", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(summaryTime(summary.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("$coveredCount · 模型：${summary.modelId.ifBlank { "未记录" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(summary.text.replace("\n", " ").ifBlank { "（空摘要）" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            if (summary.id != chat.activeSummaryId) TextButton(onClick = { actions.restoreSummary(summary.id) }, enabled = !state.busy) { Icon(Icons.Outlined.History, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("恢复此版本") }
                        }
                    }
                }
                OutlinedButton(onClick = { actions.restoreSummary("") }, Modifier.fillMaxWidth(), enabled = !state.busy) { Icon(Icons.Outlined.History, null); Spacer(Modifier.width(6.dp)); Text("恢复全部原文") }
            }
            Divider()
            Text("本会话 Skills", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("新勾选的 Skill 会自动打开“允许启用”并加入本会话；已经在本会话但总开关后来被关闭的 Skill 不会被悄然重新启用，请到 Skills 页明确打开。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (skills.isEmpty()) Text("还没有已安装的 Skill。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            skills.forEach { skill ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(selected.contains(skill.id), { selected = if (it) selected + skill.id else selected - skill.id }, enabled = !state.busy)
                    Column(Modifier.weight(1f)) {
                        Text(skill.name)
                        Text(skill.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (selected.contains(skill.id) && !skill.enabled) Text("总开关已关闭，请到 Skills 页明确启用。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }, confirmButton = { Button(enabled = !state.busy, onClick = {
        if (activeSummary != null && summaryText.isBlank()) {
            summaryError = "摘要内容不能为空；如需重新生成，请使用“压缩当前会话”。"
        } else {
            val summaries = if (activeSummary == null) chat.summaries else chat.summaries.map { if (it.id == activeSummary.id) it.copy(text = summaryText) else it }
            val newlySelected = selected - chat.skillIds.toSet()
            actions.updateChat(chat.copy(skillIds = selected.toList(), summaries = summaries))
            newlySelected.forEach { id -> actions.setSkillForCurrentChat(id, true) }
            onDismiss()
        }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun summaryTime(timestamp: Long): String = if (timestamp <= 0L) "时间未知" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))

@Composable
internal fun RenameChatDialog(chat: Chat, actions: AppActions, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(chat.title) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("重命名会话") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedTextField(title, { title = it; error = null }, label = { Text("名称") }, singleLine = true, isError = error != null); error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } } }, confirmButton = { Button(onClick = { if (title.isBlank()) error = "请输入会话名称。" else { actions.updateChat(chat.copy(title = title.trim())); onDismiss() } }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun EditMessageDialog(message: Message, actions: AppActions, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(message.content) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑消息") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedTextField(text, { text = it; error = null }, Modifier.fillMaxWidth().height(180.dp), label = { Text("内容") }, isError = error != null); error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } } }, confirmButton = { Button(onClick = { if (text.isBlank()) error = "消息内容不能为空。" else { actions.editMessage(message.id, text); onDismiss() } }) { Text("保存并重新整理上下文") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ContextPreviewDialog(state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { actions.dismissPreview(); onDismiss() }, title = { Text("上下文预览") }, text = {
        SelectionContainer { Text(state.contextPreview.ifBlank { "正在准备上下文预览…" }, Modifier.verticalScroll(rememberScrollState())) }
    }, confirmButton = { Button({ actions.dismissPreview(); onDismiss() }) { Text("关闭") } })
}

@Composable
private fun CharactersTab(state: UiSession, actions: AppActions) {
    var characterDialog by remember { mutableStateOf<Character?>(null) }
    var worldDialog by remember { mutableStateOf<WorldEntry?>(null) }
    var memoryDialog by remember { mutableStateOf<Memory?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val characters = state.data.characters.filter { query.isBlank() || it.name.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("搜索角色") })
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { actions.importFile("character") }) { Icon(Icons.Outlined.FileUpload, "导入角色卡") }
                IconButton(onClick = { characterDialog = Character() }) { Icon(Icons.Outlined.Add, "新建角色") }
            }
        }
        if (characters.isEmpty()) item { EmptyState(Icons.Outlined.Group, "还没有角色卡", "可以创建角色，或导入常见的 ST JSON / PNG 角色卡。", "新建角色") { characterDialog = Character() } }
        items(characters, key = { it.id }) { character ->
            CharacterCard(character, state, actions, onEdit = { characterDialog = it })
        }
        item {
            SectionTitle("世界书", action = { IconButton(onClick = { worldDialog = WorldEntry() }) { Icon(Icons.Outlined.Add, "新建世界书条目") } })
        }
        if (state.data.worldEntries.isEmpty()) item { Text("没有世界书条目。常驻条目和关键词触发条目都会在上下文预览中显示。", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.data.worldEntries, key = { it.id }) { entry -> WorldEntryCard(entry, actions) { worldDialog = it } }
        item {
            SectionTitle("记忆", action = { IconButton(onClick = { memoryDialog = Memory() }) { Icon(Icons.Outlined.Add, "新建记忆") } })
        }
        if (state.data.memories.isEmpty()) item { Text("没有已保存的记忆。你可以把稳定信息固定到全局或当前会话。", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.data.memories, key = { it.id }) { memory -> MemoryCard(memory, actions) { memoryDialog = it } }
    }
    characterDialog?.let { CharacterDialog(it, actions) { characterDialog = null } }
    worldDialog?.let { WorldEntryDialog(it, state, actions) { worldDialog = null } }
    memoryDialog?.let { MemoryDialog(it, state, actions) { memoryDialog = null } }
}

@Composable
private fun CharacterCard(character: Character, state: UiSession, actions: AppActions, onEdit: (Character) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(character.name.ifBlank { "未命名角色" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { onEdit(character) }) { Icon(Icons.Outlined.Edit, "编辑角色") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "删除角色") }
            }
            Text(character.description.ifBlank { "没有角色简介" }, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (character.personality.isNotBlank()) Chip("有性格设定")
                if (character.firstMessage.isNotBlank()) Chip("有开场白")
                OutlinedButton(onClick = { actions.createChat(character.id) }) { Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("创建会话") }
            }
        }
    }
    if (confirmDelete) ConfirmDeleteDialog("角色“${character.name.ifBlank { "未命名角色" }}”", { actions.deleteCharacter(character.id); confirmDelete = false }) { confirmDelete = false }
}

@Composable
private fun WorldEntryCard(entry: WorldEntry, actions: AppActions, onEdit: (WorldEntry) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.name.ifBlank { "未命名条目" }, fontWeight = FontWeight.SemiBold)
                Text(if (entry.always) "常驻 · " else "关键词：${entry.keywords.joinToString("、")} · ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(entry.content, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { onEdit(entry) }) { Icon(Icons.Outlined.Edit, "编辑世界书条目") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "删除世界书条目") }
        }
    }
    if (confirmDelete) ConfirmDeleteDialog("世界书条目“${entry.name.ifBlank { "未命名条目" }}”", { actions.deleteWorldEntry(entry.id); confirmDelete = false }) { confirmDelete = false }
}

@Composable
private fun MemoryCard(memory: Memory, actions: AppActions, onEdit: (Memory) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(memory.text.ifBlank { "空记忆" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("作用域：${memory.scope}${if (memory.locked) " · 已锁定" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(memory.enabled, { actions.saveMemory(memory.copy(enabled = it)) }, modifier = Modifier.padding(horizontal = 4.dp))
            IconButton(onClick = { onEdit(memory) }) { Icon(Icons.Outlined.Edit, "编辑记忆") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "删除记忆") }
        }
    }
    if (confirmDelete) ConfirmDeleteDialog("这条记忆", { actions.deleteMemory(memory.id); confirmDelete = false }) { confirmDelete = false }
}

@Composable
private fun CharacterDialog(character: Character, actions: AppActions, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(character) }
    FormDialog("${if (character.name.isBlank()) "新建" else "编辑"}角色", onDismiss, validate = { if (value.name.isBlank()) "请输入角色名称。" else null }, confirm = { actions.saveCharacter(value); onDismiss() }) {
        FormText("名称", value.name) { value = value.copy(name = it) }
        FormText("简介", value.description, 3) { value = value.copy(description = it) }
        FormText("性格", value.personality, 3) { value = value.copy(personality = it) }
        FormText("场景", value.scenario, 3) { value = value.copy(scenario = it) }
        FormText("开场白", value.firstMessage, 4) { value = value.copy(firstMessage = it) }
        FormText("示例对话", value.examples, 4) { value = value.copy(examples = it) }
        FormText("角色系统提示词", value.systemPrompt, 4) { value = value.copy(systemPrompt = it) }
        FormText("用户人设（角色专属）", value.userPersona, 3) { value = value.copy(userPersona = it) }
        if (character.originalJson.isNotBlank()) Text("已保留导入文件中的原始字段。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun WorldEntryDialog(entry: WorldEntry, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(entry) }
    FormDialog("${if (entry.name.isBlank()) "新建" else "编辑"}世界书条目", onDismiss, validate = { when { value.name.isBlank() -> "请输入条目名称。"; value.content.isBlank() -> "请输入条目内容。"; else -> null } }, confirm = { actions.saveWorldEntry(value); onDismiss() }) {
        FormText("名称", value.name) { value = value.copy(name = it) }
        FormText("内容", value.content, 6) { value = value.copy(content = it) }
        FormText("关键词（用逗号分隔）", value.keywords.joinToString(",")) { value = value.copy(keywords = it.split(",", "，").map(String::trim).filter(String::isNotBlank)) }
        FormNumber("优先级", value.priority.toString()) { value = value.copy(priority = it.toIntOrNull() ?: 0) }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(value.always, { value = value.copy(always = it) }); Text("常驻注入") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(value.enabled, { value = value.copy(enabled = it) }); Text("启用") }
        val characterChoices = listOf("" to "全局") + state.data.characters.map { it.id to it.name.ifBlank { "未命名角色" } }
        ChoiceRow("角色绑定", characterChoices, value.characterId) { value = value.copy(characterId = it) }
    }
}

@Composable
private fun MemoryDialog(memory: Memory, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    val currentChatId = state.selectedChatId.ifBlank { state.data.chats.firstOrNull()?.id.orEmpty() }
    val currentCharacterId = state.data.chats.firstOrNull { it.id == currentChatId }?.characterId?.takeIf { it.isNotBlank() }
        ?: state.data.characters.firstOrNull()?.id.orEmpty()
    val initial = remember(memory.id, currentChatId, currentCharacterId) {
        if (memory.targetId.isBlank() && memory.scope == "chat") memory.copy(targetId = currentChatId)
        else if (memory.targetId.isBlank() && memory.scope == "character") memory.copy(targetId = currentCharacterId)
        else memory
    }
    var value by remember { mutableStateOf(initial) }
    val targetChoices = when (value.scope) {
        "chat" -> state.data.chats.map { it.id to it.title.ifBlank { "新会话" } }
        "character" -> state.data.characters.map { it.id to it.name.ifBlank { "未命名角色" } }
        else -> emptyList()
    }
    FormDialog("${if (memory.text.isBlank()) "新建" else "编辑"}记忆", onDismiss, validate = {
        when {
            value.text.isBlank() -> "请输入记忆内容。"
            value.scope != "global" && targetChoices.isEmpty() -> "请先创建可绑定的${if (value.scope == "chat") "会话" else "角色"}。"
            value.scope != "global" && value.targetId.isBlank() -> "请选择绑定对象。"
            else -> null
        }
    }, confirm = { actions.saveMemory(value); onDismiss() }) {
        FormText("记忆内容", value.text, 5) { value = value.copy(text = it) }
        ChoiceRow("作用域", listOf("global" to "全局", "chat" to "会话", "character" to "角色"), value.scope) {
            val nextTarget = when (it) { "chat" -> currentChatId; "character" -> currentCharacterId; else -> "" }
            value = value.copy(scope = it, targetId = nextTarget)
        }
        if (value.scope != "global") {
            if (targetChoices.isEmpty()) Text("目前没有可选择的${if (value.scope == "chat") "会话" else "角色"}，请先创建对象。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else ChoiceRow("绑定对象", targetChoices, value.targetId) { value = value.copy(targetId = it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(value.enabled, { value = value.copy(enabled = it) }); Text("启用") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(value.locked, { value = value.copy(locked = it) }); Text("锁定，避免自动修改") }
    }
}

@Composable
private fun ModelsTab(state: UiSession, actions: AppActions) {
    var providerDialog by remember { mutableStateOf<ProviderConfig?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var providerId by rememberSaveable { mutableStateOf("") }
    var mediaModel by remember { mutableStateOf<ModelSelection?>(null) }
    val visibleProviders = state.data.providers
    LaunchedEffect(visibleProviders.map { it.id }) {
        if (providerId.isNotBlank() && visibleProviders.none { it.id == providerId }) providerId = ""
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            SectionTitle("平台账户", action = {
                IconButton(onClick = { providerDialog = ProviderConfig(name = "新平台", kind = "openai", baseUrl = "https://") }) { Icon(Icons.Outlined.Add, "添加平台") }
            })
        }
        if (visibleProviders.isEmpty()) item { EmptyState(Icons.Outlined.Cloud, "还没有平台", "添加 Venice、OpenAI-compatible 或 Anthropic 平台。") }
        items(visibleProviders, key = { it.id }) { provider -> ProviderCard(provider, state, actions) { providerDialog = it } }
        item {
            SectionTitle("模型目录", action = { IconButton(onClick = { actions.refreshModels("*") }) { Icon(Icons.Outlined.Refresh, "刷新所有模型") } })
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("搜索模型或平台（Provider）") })
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(providerId.isBlank(), { providerId = "" }, label = { Text("全部平台") })
                visibleProviders.forEach { provider ->
                    FilterChip(providerId == provider.id, { providerId = provider.id }, label = { Text("${provider.name}（${provider.models.size}）") })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("全部", "text", "image", "inpaint", "upscale", "tts", "asr", "video", "music", "embedding", "decision").forEach { type -> FilterChip(filter == type, { filter = type }, label = { Text(type) }) }
            }
        }
        val models = ModelCatalog.filter(visibleProviders, search, filter, providerId)
        if (models.isEmpty()) item {
            val hasFilter = search.isNotBlank() || filter != "全部" || providerId.isNotBlank()
            EmptyState(
                Icons.Outlined.Search,
                "没有匹配模型",
                if (hasFilter) "当前筛选条件没有结果，可清除筛选或刷新真实模型目录。" else "先刷新真实模型目录，或在平台卡片里手动添加模型。",
                if (hasFilter) "清除筛选" else null
            ) {
                search = ""
                filter = "全部"
                providerId = ""
            }
        }
        items(models, key = { "${it.first.id}:${it.second.type}:${it.second.id}" }) { (provider, model) -> ModelCard(provider, model, state, actions) { mediaModel = ModelSelection(provider, model) } }
        item {
            SectionTitle("媒体工具", action = { Text("选择 image / inpaint / upscale / tts / asr / video / music 模型进入") })
            if (state.data.mediaJobs.isEmpty()) Text("还没有媒体任务。视频和音乐会先 quote 再 queue，并保留队列状态。", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(state.data.mediaJobs, key = { it.id }) { job -> MediaJobCard(job, actions) }
    }
    providerDialog?.let { ProviderDialog(it, state, actions) { providerDialog = null } }
    mediaModel?.let { MediaDialog(it.provider, it.model, state, actions) { mediaModel = null } }
}

private data class ModelSelection(val provider: ProviderConfig, val model: ModelInfo)

@Composable
private fun ProviderCard(provider: ProviderConfig, state: UiSession, actions: AppActions, onEdit: (ProviderConfig) -> Unit) {
    val hasKey = state.hasKeys.contains(provider.id)
    var confirmDelete by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) { Text(provider.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(provider.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (hasKey) Chip("已配置") else Chip("未配置密钥")
            }
            Text(provider.baseUrl, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { actions.refreshModels(provider.id) }, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("刷新模型") }
                OutlinedButton(onClick = { onEdit(provider) }) { Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("编辑") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "删除平台") }
            }
            Text(
                if (provider.refreshedAt > 0) "${provider.models.size} 个已发现模型 · 目录及价格缓存于 ${summaryTime(provider.refreshedAt)}"
                else "${provider.models.size} 个已发现模型 · 尚未刷新，价格未缓存",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    if (confirmDelete) ConfirmDeleteDialog("平台“${provider.name}”", { actions.deleteProvider(provider.id); confirmDelete = false }) { confirmDelete = false }
}

@Composable
private fun ModelCard(provider: ProviderConfig, model: ModelInfo, state: UiSession, actions: AppActions, onMedia: () -> Unit) {
    var showRaw by remember { mutableStateOf(false) }
    val supportedMedia = setOf("image", "inpaint", "upscale", "tts", "asr", "video", "music", "embedding", "decision")
    val modelType = model.type.lowercase(Locale.ROOT)
    val isMedia = modelType in supportedMedia
    val isVenice = provider.kind.equals("venice", ignoreCase = true)
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(model.name.ifBlank { model.id }, fontWeight = FontWeight.SemiBold); Text("${provider.name} · ${model.type}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                if (model.type == "text") Button(onClick = { actions.chooseModel(provider.id, model.id) }) { Text("用于聊天") }
                else if (isMedia && isVenice) OutlinedButton(onClick = onMedia) { Text("打开工具") }
                else if (isMedia) Text("媒体工具仅支持 Venice", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text("仅展示 · 待适配", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Chip("${model.contextTokens / 1000}k 上下文")
                if (model.supportsTools) Chip("工具")
                if (model.supportsVision) Chip("视觉")
                if (model.supportsReasoning) Chip("推理")
                if (model.offline) Chip("离线")
            }
            ModelPriceBlock(model, provider.kind)
            TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "收起原始能力" else "查看原始能力") }
            if (showRaw) SelectionContainer { Text(model.rawJson, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (isMedia && !isVenice) Text("媒体工具目前仅支持 Venice；该模型可查看能力，但不会发送媒体请求。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else if (!isMedia && model.type != "text") Text("此模型类型仅保留在目录中，不会被应用发送请求。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MediaJobCard(job: MediaJob, actions: AppActions) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("${job.type.ifBlank { "媒体" }} · ${job.modelId}", fontWeight = FontWeight.SemiBold); Text("状态：${job.status.ifBlank { "待处理" }}${if (job.queueId.isNotBlank()) " · 队列 ${job.queueId}" else ""}", style = MaterialTheme.typography.bodySmall); if (job.resultJson.isNotBlank()) Text(job.resultJson, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            IconButton(onClick = { actions.pollMedia(job.id) }) { Icon(Icons.Outlined.Refresh, "查询任务") }
            IconButton(onClick = { actions.openMedia(job.id) }) { Icon(Icons.Outlined.FolderOpen, "打开结果") }
            IconButton(onClick = { actions.saveMedia(job.id) }) { Icon(Icons.Outlined.Save, "保存结果") }
        }
    }
}

@Composable
private fun ProviderDialog(provider: ProviderConfig, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(provider) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var modelId by remember { mutableStateOf("") }
    val credentialBindingChanged = value.baseUrl.trim() != provider.baseUrl.trim() || value.kind != provider.kind
    FormDialog("${if (provider.id.isBlank() || provider.name == "新平台") "添加" else "编辑"}平台", onDismiss, validate = { when { value.name.isBlank() -> "请输入平台名称。"; !value.baseUrl.trim().startsWith("https://", ignoreCase = true) -> "Base URL 必须使用 HTTPS。"; credentialBindingChanged && key.isBlank() -> "修改 Base URL 或协议后必须重新填写 API Key。"; else -> null } }, confirm = { actions.saveProvider(value, key); onDismiss() }) {
        FormText("名称", value.name) { value = value.copy(name = it) }
        ChoiceRow("协议类型", listOf("venice" to "Venice", "openai" to "OpenAI-compatible", "anthropic" to "Anthropic Messages"), value.kind) { value = value.copy(kind = it) }
        FormText("Base URL", value.baseUrl) { value = value.copy(baseUrl = it) }
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key（留空保持已有密钥）") }, visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton({ showKey = !showKey }) { Icon(if (showKey) Icons.Outlined.Visibility else Icons.Outlined.Security, "显示或隐藏密钥") } }, singleLine = true)
        Text("同一地址和协议留空会保留已有密钥；修改 Base URL 或协议后必须重新填写。凭据按地址/协议绑定，不会从备份或旧地址偷偷迁移。导出备份不包含密钥。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Divider()
        Text("手动添加模型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(modelId, { modelId = it }, Modifier.weight(1f), label = { Text("模型 ID") }, singleLine = true); Spacer(Modifier.width(6.dp)); IconButton(onClick = { if (modelId.isNotBlank()) { value = value.copy(models = value.models + ModelInfo(id = modelId.trim(), name = modelId.trim())); modelId = "" } }) { Icon(Icons.Outlined.Add, "添加模型") } }
        value.models.forEach { model -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(model.id, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); IconButton(onClick = { value = value.copy(models = value.models - model) }) { Icon(Icons.Outlined.RemoveCircleOutline, "移除模型") } } }
    }
}

@Composable
private fun MediaDialog(provider: ProviderConfig, model: ModelInfo, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    val type = model.type.lowercase(Locale.ROOT)
    val ttsDefaults = remember(model.id, model.rawJson) { readTtsDefaults(model.rawJson) }
    var request by remember(model.id, type, ttsDefaults) { mutableStateOf(mediaTemplate(type, ttsDefaults.first, ttsDefaults.second)) }
    var mode by remember { mutableStateOf(if (type == "video" || type == "music") "quote" else "queue") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${type.uppercase()} 工具") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${provider.name} · ${model.id}", fontWeight = FontWeight.SemiBold)
            ModelPriceBlock(model, provider.kind)
            Text("模型原始能力：${model.rawJson}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (type == "video" || type == "music") ChoiceRow("执行方式", listOf("quote" to "报价（不提交任务）", "queue" to "排队生成"), mode) { mode = it }
            if (type == "inpaint" || type == "upscale" || type == "asr") {
                Text("请求中写入 @attachment 的字段会使用下面选中的真实文件；不会把文件内容显示在聊天文本里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { actions.pickAttachment() }) { Icon(Icons.Outlined.AttachFile, null); Spacer(Modifier.width(5.dp)); Text("选择文件") }
                    if (state.pendingAttachments.isEmpty()) Text("尚未选择文件", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall)
                }
                if (state.pendingAttachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.pendingAttachments.forEach { attachment ->
                        AssistChip(onClick = { actions.removeAttachment(attachment.id) }, label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, trailingIcon = { Icon(Icons.Outlined.Close, "移除文件", Modifier.size(16.dp)) })
                    }
                }
            }
            OutlinedTextField(request, { request = it }, Modifier.fillMaxWidth().height(220.dp), label = { Text("请求 JSON") })
        }
    }, confirmButton = { Button({ actions.runMedia(provider.id, model.id, if (type == "video" || type == "music") request.replaceFirst("{", "{\"_action\":\"$mode\",") else request); onDismiss() }) { Text(if (mode == "quote") "获取报价" else "提交") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

@Composable
private fun ModelPriceBlock(model: ModelInfo, providerKind: String, modifier: Modifier = Modifier) {
    val pricing = remember(model.rawJson, model.type, providerKind) { ModelPricing.describe(model, providerKind) }
    var expanded by remember(model.rawJson, model.type, providerKind) { mutableStateOf(false) }
    val lines = pricing.lines
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("价格参考", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (lines.isEmpty()) {
                Text(
                    pricing.note.ifBlank { "价格未提供；可能按参数报价，请以平台实际返回为准。" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            } else {
                val visibleLines = if (expanded) lines else lines.take(3)
                visibleLines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                if (pricing.note.isNotBlank()) {
                    Text(pricing.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                if (lines.size > 3) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.align(Alignment.Start)
                    ) { Text(if (expanded) "收起价格详情" else "更多价格详情（${lines.size - 3}）") }
                }
            }
            Text("平台目录价格仅供参考；刷新模型目录可更新缓存。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

private fun mediaTemplate(type: String, ttsVoice: String = "af_sky", ttsFormat: String = "mp3"): String = when (type) {
    "image" -> "{\n  \"prompt\": \"\",\n  \"size\": \"1024x1024\"\n}"
    "inpaint" -> "{\n  \"image\": \"@attachment\",\n  \"mask\": \"@attachment\",\n  \"prompt\": \"\"\n}"
    "upscale" -> "{\n  \"image\": \"@attachment\",\n  \"scale\": 2\n}"
    "tts" -> "{\n  \"input\": \"\",\n  \"voice\": \"${jsonString(ttsVoice)}\",\n  \"response_format\": \"${jsonString(ttsFormat)}\",\n  \"speed\": 1.0\n}"
    "asr" -> "{\n  \"audio\": \"@attachment\",\n  \"language\": \"zh\"\n}"
    "video" -> "{\n  \"prompt\": \"\",\n  \"duration\": 5\n}"
    "music" -> "{\n  \"prompt\": \"\",\n  \"duration_seconds\": 30,\n  \"force_instrumental\": false\n}"
    "embedding" -> "{\n  \"input\": \"\"\n}"
    "decision" -> "{\n  \"state\": \"需要判断的内容\",\n  \"questions\": {\n    \"is_urgent\": {\n      \"type\": \"noul\",\n      \"instructions\": \"是否紧急？\"\n    }\n  }\n}"
    else -> "{}"
}

private fun jsonString(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

private fun readTtsDefaults(rawJson: String): Pair<String, String> {
    return runCatching {
        val spec = JSONObject(rawJson).optJSONObject("model_spec") ?: JSONObject(rawJson)
        val voices = spec.optJSONArray("voices")
        val first = voices?.opt(0)
        val voice = when (first) {
            is JSONObject -> first.optString("id").ifBlank { first.optString("voice_id") }.ifBlank { first.optString("name") }
            null -> ""
            else -> first.toString()
        }.ifBlank { spec.optString("default_voice") }.ifBlank { "af_sky" }
        val format = spec.optString("default_format").ifBlank { "mp3" }
        voice to format
    }.getOrDefault("af_sky" to "mp3")
}

@Composable
private fun SkillsTab(state: UiSession, actions: AppActions) {
    var selected by remember { mutableStateOf<SkillRecord?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var showUrlInstall by remember { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("all") }
    val locked = state.loading || state.busy
    val currentChat = state.data.chats.firstOrNull { it.id == state.selectedChatId }
    val categories = listOf("all" to "全部", "novel" to "小说写作", "relationship" to "言情与关系", "custom" to "自定义")
    val visibleSkills = state.data.skills.filter { skill ->
        when (category) {
            "novel" -> skill.source == "GitHub精选 · 小说写作"
            "relationship" -> skill.source == "GitHub精选 · 言情与关系"
            "custom" -> !skill.source.startsWith("GitHub精选 ·")
            else -> true
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            SectionTitle("已安装 Skills", action = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = !locked, onClick = { showUrlInstall = true }) { Icon(Icons.Outlined.Cloud, null); Spacer(Modifier.width(4.dp)); Text("从链接安装") }
                    OutlinedButton(enabled = !locked, onClick = { showImport = true }) { Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(4.dp)); Text("导入文件") }
                }
            })
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.Security, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Column { Text("工具型 Skill 权限", fontWeight = FontWeight.Bold); Text("每个 Skill 只允许 files.read、files.write、network、memory 四类权限。正文和引用作为上下文载入；本版不执行 Python、Bash、Node 或桌面 MCP 脚本。", style = MaterialTheme.typography.bodySmall) } }
                    Text("GitHub 精选内容保留上游 SKILL.md 原文；其中 Read、Grep 等工具声明是上游约定，手机端仍只按已授权项提供工具能力。GitHub 精选默认关闭，请逐项审核后启用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    if (currentChat == null) {
                        Text("当前没有会话。创建会话后，才能把 Skill 加入本会话。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Button(enabled = !locked, onClick = { actions.createChat() }) { Text("创建会话") }
                    } else {
                        Text("当前会话：${currentChat.title.ifBlank { "新会话" }}。勾选“用于本会话”只影响当前会话；关闭 Skill 的“允许启用”后，任何会话都不会实际加载它。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                categories.forEach { (value, label) -> FilterChip(selected = category == value, onClick = { category = value }, label = { Text(label) }) }
            }
        }
        if (state.data.skills.isEmpty()) item {
            if (locked) EmptyState(Icons.Outlined.AutoAwesome, "还没有 Skill", "当前操作进行中，请完成后再导入 SKILL.md 或 ZIP。")
            else EmptyState(Icons.Outlined.AutoAwesome, "还没有 Skill", "导入 SKILL.md、ZIP，或使用 HTTPS 链接安装后，在详情中确认权限并启用。", "导入 Skill") { showImport = true }
        }
        else if (visibleSkills.isEmpty()) item { EmptyState(Icons.Outlined.AutoAwesome, "此分类暂无 Skill", "可切换分类，或把本地导入的 Skill 放在“自定义”中查看。") }
        items(visibleSkills, key = { it.id }) { skill ->
            SkillCard(skill, currentChat, locked, actions, onDetails = { selected = skill })
        }
    }
    selected?.let { SkillDetailsDialog(it, state, actions) { selected = null } }
    if (showImport && !locked) ImportDialog("skill", actions) { showImport = false }
    if (showUrlInstall && !locked) SkillUrlDialog(actions) { showUrlInstall = false }
}

@Composable
private fun SkillCard(skill: SkillRecord, currentChat: Chat?, busy: Boolean, actions: AppActions, onDetails: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(skill.name.ifBlank { "未命名 Skill" }, fontWeight = FontWeight.Bold)
                    Text(skill.description.ifBlank { "没有说明" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("来源：${skill.source.ifBlank { "本地导入" }} · ${skill.files.size} 个文件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("权限：${skill.permissions.joinToString(", ").ifBlank { "未授予" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("允许启用", style = MaterialTheme.typography.labelSmall)
                    Switch(checked = skill.enabled, onCheckedChange = { if (!busy) actions.saveSkill(skill.copy(enabled = it)) }, enabled = !busy)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = currentChat?.skillIds?.contains(skill.id) == true,
                    onCheckedChange = { checked -> if (!busy && currentChat != null) actions.setSkillForCurrentChat(skill.id, checked) },
                    enabled = !busy && currentChat != null
                )
                Text("用于本会话", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = onDetails) { Icon(Icons.Outlined.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("查看详情") }
                IconButton(enabled = !busy, onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "卸载 Skill") }
            }
        }
    }
    if (confirmDelete && !busy) ConfirmDeleteDialog("Skill“${skill.name.ifBlank { "未命名" }}”", { actions.deleteSkill(skill.id); confirmDelete = false }) { confirmDelete = false }
}

@Composable
private fun SkillDetailsDialog(skill: SkillRecord, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    var permissions by remember { mutableStateOf(skill.permissions.toSet()) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var showLicense by rememberSaveable(skill.id) { mutableStateOf(false) }
    val locked = state.loading || state.busy
    val known = listOf("files.read", "files.write", "network", "memory")
    val uriHandler = LocalUriHandler.current
    val licenseText = skill.files["LICENSE"]
    AlertDialog(onDismissRequest = onDismiss, title = { Text(skill.name.ifBlank { "Skill 详情" }) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(skill.description.ifBlank { "没有说明" })
            Text("来源：${skill.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (skill.sourceUrl.isNotBlank()) {
                TextButton(enabled = !locked, onClick = {
                    runCatching { uriHandler.openUri(skill.sourceUrl) }.onFailure { linkError = "无法打开 GitHub 来源链接：${it.message ?: "未知错误"}" }
                }) { Icon(Icons.Outlined.Cloud, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("打开 GitHub 来源") }
            }
            Text("Commit：${skill.sourceCommit.ifBlank { "未记录" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("许可证：${skill.sourceLicense.ifBlank { "未记录" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            linkError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text("正文", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                SelectionContainer { Text(skill.content.ifBlank { "（此 Skill 没有正文）" }, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall) }
            }
            Text("工具权限", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            known.forEach { permission -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(permissions.contains(permission), { permissions = if (it) permissions + permission else permissions - permission }, enabled = !locked); Text(permission) } }
            Divider()
            Text("文件清单", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            skill.files.keys.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (!licenseText.isNullOrBlank()) {
                TextButton(enabled = !locked, onClick = { showLicense = !showLicense }) { Text(if (showLicense) "收起 LICENSE" else "展开 LICENSE") }
                if (showLicense) Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    SelectionContainer { Text(licenseText, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (skill.warnings.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp)); Column { skill.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } } }
            }
        }
    }, confirmButton = { Button(enabled = !locked, onClick = { actions.saveSkill(skill.copy(permissions = permissions.toList())); onDismiss() }) { Text("保存权限") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun SettingsTab(state: UiSession, actions: AppActions) {
    var presetDialog by remember { mutableStateOf<Preset?>(null) }
    var showImport by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(state.data.settings) }
    var showPersona by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)) {
        item {
            SectionTitle("显示与人格")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceRow("主题", listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), settings.darkMode) { settings = settings.copy(darkMode = it); actions.saveSettings(settings) }
                    OutlinedButton(onClick = { showPersona = true }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.PersonOutline, null); Spacer(Modifier.width(6.dp)); Text(if (settings.userPersona.isBlank()) "设置全局用户人设" else "编辑全局用户人设") }
                }
            }
        }
        item {
            SectionTitle("上下文压缩")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("接近预算时自动压缩", Modifier.weight(1f)); Switch(settings.autoCompress, { settings = settings.copy(autoCompress = it); actions.saveSettings(settings) }) }
                FormNumber("触发比例（0-1）", settings.compressionThreshold.toString()) { val next = settings.copy(compressionThreshold = it.toDoubleOrNull()?.coerceIn(0.1, 1.0) ?: settings.compressionThreshold); settings = next; actions.saveSettings(next) }
                FormNumber("保留最近轮数", settings.keepRecentTurns.toString()) { val next = settings.copy(keepRecentTurns = it.toIntOrNull()?.coerceAtLeast(1) ?: settings.keepRecentTurns); settings = next; actions.saveSettings(next) }
                FormNumber("上下文覆盖上限（0=模型默认）", settings.contextOverride.toString()) { val next = settings.copy(contextOverride = it.toIntOrNull()?.coerceAtLeast(0) ?: settings.contextOverride); settings = next; actions.saveSettings(next) }
                FormNumber("最大工具步数", settings.maxToolSteps.toString()) { val next = settings.copy(maxToolSteps = it.toIntOrNull()?.coerceIn(1, 64) ?: settings.maxToolSteps); settings = next; actions.saveSettings(next) }
            } }
        }
        item { SectionTitle("系统预设", action = { IconButton(onClick = { presetDialog = Preset() }) { Icon(Icons.Outlined.Add, "新建预设") } }) }
        items(state.data.presets, key = { it.id }) { preset -> PresetCard(preset, actions) { presetDialog = preset } }
        item {
            SectionTitle("数据备份与迁移")
            BackupButtons(actions, onImport = { showImport = it })
        }
        item { SectionTitle("快捷导入")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("preset" to "预设", "character" to "角色卡", "worldbook" to "世界书", "chat" to "会话", "skill" to "Skill").forEach { (kind, label) -> OutlinedButton(onClick = { actions.importFile(kind) }) { Text(label) } } }
        }
    }
    presetDialog?.let { PresetDialog(it, state, actions) { presetDialog = null } }
    showImport?.let { ImportDialog(it, actions) { showImport = null } }
    if (showPersona) {
        var persona by remember(showPersona, settings.userPersona) { mutableStateOf(settings.userPersona) }
        AlertDialog(onDismissRequest = { showPersona = false }, title = { Text("全局用户人设") }, text = { OutlinedTextField(persona, { persona = it }, Modifier.fillMaxWidth().height(180.dp), label = { Text("人设内容") }) }, confirmButton = { Button({ settings = settings.copy(userPersona = persona); actions.saveSettings(settings); showPersona = false }) { Text("保存") } }, dismissButton = { TextButton({ showPersona = false }) { Text("取消") } })
    }
}

@Composable
private fun PresetCard(preset: Preset, actions: AppActions, onEdit: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(preset.name, fontWeight = FontWeight.SemiBold); Text("温度 ${preset.temperature} · 最大输出 ${preset.maxOutput}", style = MaterialTheme.typography.labelSmall); Text(preset.systemPrompt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "编辑预设") }; IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "删除预设") }; IconButton(onClick = { actions.exportFile("preset", preset.id) }) { Icon(Icons.Outlined.FileDownload, "导出预设") } } }
    if (confirmDelete) ConfirmDeleteDialog("预设“${preset.name}”", { actions.deletePreset(preset.id); confirmDelete = false }) { confirmDelete = false }
}

@Composable
private fun BackupButtons(actions: AppActions, onImport: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("备份是 ZIP，包含会话、附件与媒体结果、角色、预设、世界书、记忆、Skill 配置和应用工作区，不包含 API Key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { actions.exportFile("backup") }) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(5.dp)); Text("导出备份") }; OutlinedButton(onClick = { onImport("backup") }) { Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(5.dp)); Text("恢复备份") } }
    } }
}

@Composable
private fun PresetDialog(preset: Preset, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(preset) }
    FormDialog("${if (preset.id == "" || preset.name == "新预设") "新建" else "编辑"}预设", onDismiss, validate = { if (value.name.isBlank()) "请输入预设名称。" else null }, confirm = { actions.savePreset(value); onDismiss() }) {
        FormText("名称", value.name) { value = value.copy(name = it) }
        FormText("系统提示词", value.systemPrompt, 5) { value = value.copy(systemPrompt = it) }
        FormNumber("温度", value.temperature.toString()) { value = value.copy(temperature = it.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: value.temperature) }
        FormNumber("最大输出长度", value.maxOutput.toString()) { value = value.copy(maxOutput = it.toIntOrNull()?.coerceAtLeast(1) ?: value.maxOutput) }
        FormText("摘要提示词", value.summaryPrompt, 4) { value = value.copy(summaryPrompt = it) }
        ProviderModelChoice(state, value.summaryProviderId, value.summaryModelId) { p, m -> value = value.copy(summaryProviderId = p, summaryModelId = m) }
    }
}

@Composable
private fun ImportDialog(kind: String, actions: AppActions, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("导入${kindLabel(kind)}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("选择文件后应用会校验格式，并在写入前保留现有数据。")
            Text("支持的类型：${if (kind == "backup") "口袋酒馆 ZIP 备份（兼容旧版 JSON）" else if (kind == "skill") "SKILL.md 或 ZIP" else "对应的 JSON 文件"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { Button({ actions.importFile(kind); onDismiss() }) { Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(5.dp)); Text("选择文件") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

@Composable
private fun SkillUrlDialog(actions: AppActions, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val validHttps = url.trim().startsWith("https://", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从 HTTPS 链接安装 Skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("SKILL.md 或 ZIP URL") },
                    placeholder = { Text("https://example.com/skill.zip") },
                    isError = url.isNotBlank() && !validHttps
                )
                if (url.isNotBlank() && !validHttps) Text("只允许 HTTPS 地址。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("应用会限制下载大小、拒绝路径穿越，并在安装后保持禁用且不授予权限；请在 Skill 详情中审核后启用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(enabled = validHttps, onClick = { actions.installSkillUrl(url.trim()); onDismiss() }) {
                Icon(Icons.Outlined.Cloud, null); Spacer(Modifier.width(5.dp)); Text("下载并安装")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun kindLabel(kind: String): String = when (kind) {
    "backup" -> "备份"
    "skill" -> "Skill"
    "character" -> "角色卡"
    "worldbook" -> "世界书"
    "chat" -> "会话"
    "preset" -> "预设"
    else -> kind
}

@Composable
internal fun ConfirmDeleteDialog(label: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认删除") },
        text = { Text("确定要删除${label}吗？此操作不会自动恢复。") },
        confirmButton = { Button(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModelChoice(state: UiSession, providerId: String, modelId: String, onChange: (String, String) -> Unit) {
    var providerOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    val provider = state.data.providers.firstOrNull { it.id == providerId } ?: state.data.providers.firstOrNull()
    val model = provider?.models?.firstOrNull { it.id == modelId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(providerOpen, { providerOpen = !providerOpen }) {
            OutlinedTextField(provider?.name ?: "未选择摘要平台", {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("摘要平台") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerOpen) })
            DropdownMenu(providerOpen, { providerOpen = false }) { state.data.providers.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { onChange(p.id, ""); providerOpen = false }) } }
        }
        ExposedDropdownMenuBox(modelOpen, { modelOpen = !modelOpen }) {
            OutlinedTextField(model?.name ?: modelId.ifBlank { "未选择摘要模型" }, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("摘要模型") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelOpen) })
            DropdownMenu(modelOpen, { modelOpen = false }) { provider?.models?.filter { it.type == "text" && !it.offline }?.forEach { m -> DropdownMenuItem(text = { Text(m.name.ifBlank { m.id }) }, onClick = { onChange(provider.id, m.id); modelOpen = false }) } }
        }
        if (provider == null || provider.models.none { it.type == "text" && !it.offline }) Text("尚未发现可用的 text 模型；摘要调用需要先刷新平台目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FormDialog(title: String, onDismiss: () -> Unit, validate: () -> String? = { null }, confirm: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var validationError by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
            validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { Button(onClick = { val error = validate(); if (error == null) confirm() else validationError = error }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun FormText(label: String, value: String, lines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().then(if (lines > 1) Modifier.height((lines * 58).dp) else Modifier), label = { Text(label) }, minLines = lines, maxLines = lines.coerceAtMost(8))
}

@Composable
private fun FormNumber(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

@Composable
private fun ChoiceRow(label: String, choices: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { choices.forEach { (value, text) -> FilterChip(selected == value, { onSelect(value) }, label = { Text(text) }) } }
    }
}
