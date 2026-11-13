package app.pockettavern.ui

import app.pockettavern.engine.CharacterDrafts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
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
import app.pockettavern.engine.KnowledgeVault
import app.pockettavern.engine.ToolRuntime
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.unit.dp
import app.pockettavern.data.BuiltinSkills
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Attachment
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.GitHubStars
import app.pockettavern.domain.MediaJob
import app.pockettavern.domain.Memory
import app.pockettavern.domain.ModelCatalog
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.SkillRecord
import app.pockettavern.domain.UiSession
import app.pockettavern.domain.VeniceBalance
import app.pockettavern.domain.VeniceBalanceScope
import app.pockettavern.domain.WorldEntry
import app.pockettavern.provider.ModelPricing
import app.pockettavern.provider.ProviderUrlPolicy
import app.pockettavern.provider.VeniceModelCapabilities
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
        if (state.diagnostics.isNotBlank()) DiagnosticsDialog(state.diagnostics, actions)
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
                        // An app-wide task such as a model refresh. Chats do not wait for it.
                        if (state.busy) {
                            LinearProgressIndicator(Modifier.width(56.dp))
                            TextButton(onClick = { actions.stopAppTask() }) { Text("停止") }
                        }
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
                        AppTab.Characters -> CharactersTab(state, actions) { tab = AppTab.Chats.name }
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

internal fun moveSkillInOrder(skillIds: List<String>, skillId: String, offset: Int): List<String> {
    val from = skillIds.indexOf(skillId)
    val target = from + offset
    if (from < 0 || target !in skillIds.indices) return skillIds
    return skillIds.toMutableList().apply { add(target, removeAt(from)) }
}

@Composable
internal fun ChatOptionsDialog(chat: Chat, state: UiSession, actions: AppActions, onDismiss: () -> Unit, onPreview: () -> Unit, onMemory: (() -> Unit)? = null) {
    var selectedOrder by remember(chat.id) {
        mutableStateOf(ToolRuntime.skillsInOrder(state.data.skills, chat.skillIds).map { it.id })
    }
    var pendingAdult by remember { mutableStateOf<String?>(null) }
    val skills = state.data.skills
    val skillToolsSupported = chatSupportsTools(state.data, chat)
    val skillCostById = remember(skills, skillToolsSupported) { skillCosts(skills, skillToolsSupported) }
    val activeSummary = chat.summaries.firstOrNull { it.id == chat.activeSummaryId }
    val recallToolsAvailable = state.data.providers.firstOrNull { it.id == chat.providerId }
        ?.models?.firstOrNull { it.id == chat.modelId }?.supportsTools == true
    var summaryText by remember(chat.id, activeSummary?.id, activeSummary?.text) { mutableStateOf(activeSummary?.text.orEmpty()) }
    var summaryError by remember { mutableStateOf<String?>(null) }
    var summaryControlsOpen by remember(chat.id) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("会话设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("上下文与记忆", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onPreview, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Visibility, null); Spacer(Modifier.width(6.dp)); Text("预览实际上下文") }
            OutlinedButton(onClick = { actions.compress() }, Modifier.fillMaxWidth(), enabled = !state.chatBusy(chat.id)) { Icon(Icons.Outlined.Cached, null); Spacer(Modifier.width(6.dp)); Text("压缩当前会话") }
            Text("每轮携带当前续聊摘要和近期对话；需要历史细节时，由支持工具的模型按需查询。知识库和完整原文保存在本机，可在会话记忆页查看与导出。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!recallToolsAvailable) Text("当前模型目录未标记工具支持，暂不能自主查记忆。可刷新模型目录或切换支持工具的模型；仍可使用当前摘要并手动查看原文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { onMemory?.invoke() }, Modifier.fillMaxWidth(), enabled = onMemory != null) {
                Icon(Icons.Outlined.History, null); Spacer(Modifier.width(6.dp)); Text("打开会话记忆")
            }
            TextButton(onClick = { summaryControlsOpen = !summaryControlsOpen }) { Text(if (summaryControlsOpen) "收起摘要管理" else "摘要编辑与版本切换") }
            if (summaryControlsOpen) {
                Text("这里的编辑和版本切换会改变后续对话使用的摘要。仅查看内容请使用“打开会话记忆”。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (activeSummary != null) {
                    OutlinedTextField(summaryText, { summaryText = it; summaryError = null }, Modifier.fillMaxWidth().height(160.dp), enabled = !state.chatBusy(chat.id) && !state.loading, label = { Text("编辑当前摘要（保存后生效）") })
                    summaryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                chat.summaries.sortedByDescending { it.createdAt }.forEach { summary ->
                    val through = chat.messages.indexOfFirst { it.id == summary.throughMessageId }
                    Text("${if (summary.id == chat.activeSummaryId) "当前版本" else "历史版本"} · ${summaryTime(summary.createdAt)} · ${if (through < 0) "来源范围缺失" else "至第 ${through + 1} 条消息"}", style = MaterialTheme.typography.bodySmall)
                    if (summary.id != chat.activeSummaryId) TextButton(onClick = { actions.restoreSummary(summary.id) }, enabled = !state.chatBusy(chat.id) && !state.loading) { Text("明确恢复此版本") }
                }
                OutlinedButton(onClick = { actions.restoreSummary("") }, Modifier.fillMaxWidth(), enabled = !state.chatBusy(chat.id) && !state.loading) { Text("停用摘要，恢复全部原文上下文") }
            }
            Divider()
            Text("本会话 Skills", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("模型会按下列顺序从上到下注入 Skill 正文，然后再加入历史摘要和对话。排序只影响当前会话；未打开总开关的 Skill 不会注入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val orderedSkills = ToolRuntime.skillsInOrder(skills, selectedOrder)
            if (orderedSkills.isNotEmpty()) Text("每次发消息时，会在你的消息旁提醒模型按这些 Skill 写，带参考文件的会先读文件。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (orderedSkills.isNotEmpty()) SkillCostSummaryLine(skillCostSummary(orderedSkills, skillCostById, chatInputLimit(state.data, chat), state.data.settings.adultSkillsConfirmed))
            if (orderedSkills.isEmpty()) {
                Text("当前会话还没有选择 Skill。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        orderedSkills.forEachIndexed { index, skill ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(skill.name.ifBlank { "未命名 Skill" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!skill.enabled) Text("总开关关闭 · 不会注入", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    else skillCostById[skill.id]?.let { SkillCostLine(it) }
                                }
                                IconButton(enabled = !state.chatBusy(chat.id) && index > 0, onClick = {
                                    selectedOrder = moveSkillInOrder(selectedOrder, skill.id, -1)
                                }) { Icon(Icons.Outlined.ArrowUpward, if (index > 0) "将“${skill.name.ifBlank { "未命名 Skill" }}”提前到第 $index 位" else "“${skill.name.ifBlank { "未命名 Skill" }}”已在第 1 位") }
                                IconButton(enabled = !state.chatBusy(chat.id) && index < orderedSkills.lastIndex, onClick = {
                                    selectedOrder = moveSkillInOrder(selectedOrder, skill.id, 1)
                                }) { Icon(Icons.Outlined.ArrowDownward, if (index < orderedSkills.lastIndex) "将“${skill.name.ifBlank { "未命名 Skill" }}”延后到第 ${index + 2} 位" else "“${skill.name.ifBlank { "未命名 Skill" }}”已在第 ${index + 1} 位") }
                            }
                        }
                    }
                }
            }
            Text("新勾选的 Skill 会追加到注入顺序末尾并自动打开“允许启用”；已经在本会话但总开关后来被关闭的 Skill 不会被悄然重新启用，请到 Skills 页明确打开。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChatSkillPicker(skills, selectedOrder.toSet(), state.chatBusy(chat.id) || state.loading, costs = skillCostById, onSelect = { skill, checked ->
                if (checked && skill.adult && !state.data.settings.adultSkillsConfirmed) pendingAdult = skill.id
                else selectedOrder = if (checked) (selectedOrder + skill.id).distinct() else selectedOrder.filterNot { it == skill.id }
            })
            if (state.missingBuiltinSkills.isNotEmpty()) {
                Text("另有 ${state.missingBuiltinSkills.size} 个内置 Skill 未安装，可到 Skills 页逐项恢复原版。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Divider()
            Text("表情包", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            // Read the live chat: the dialog's `chat` is a snapshot and this setting applies at once.
            val liveChat = state.data.chats.firstOrNull { it.id == chat.id } ?: chat
            val liveFrequency = liveChat.stickerFrequency
            // Stickers live on the PC, so only a chat using the PC writing server can send them.
            val onPc = state.data.providers.firstOrNull { it.id == liveChat.providerId }?.kind.equals("writing-server", ignoreCase = true)
            val stickerCount = if (onPc) app.pockettavern.engine.StickerGate.packsFor(state.data.characters.firstOrNull { it.id == liveChat.characterId }, state.pcLink.stickerPacks.orEmpty()).sumOf { it.stickers.size } else 0
            StickerFrequencyChooser(liveFrequency, stickerCount, !state.chatBusy(chat.id) && !state.loading, onPc = onPc) {
                actions.setChatStickerFrequency(chat.id, it)
            }
            Divider()
            Text("自己刷视频", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            VideoBrowseChooser(liveChat.browsePerDay, app.pockettavern.engine.VideoBrowse.eligible(liveChat, state.data.providers), onPc,
                !state.chatBusy(chat.id) && !state.loading, state.pcLink.status?.bilibiliLogin,
                onSelect = { actions.setChatBrowse(chat.id, it) }, onBrowseNow = { actions.browseNow(chat.id); onDismiss() })
        }
    }, confirmButton = { Button(enabled = !state.chatBusy(chat.id) && !state.loading, onClick = {
        if (activeSummary != null && summaryText.isBlank()) {
            summaryError = "摘要内容不能为空；如需重新生成，请在会话记忆页重建。"
            summaryControlsOpen = true
        } else {
            val summaries = if (activeSummary == null) chat.summaries else chat.summaries.map { if (it.id == activeSummary.id) it.copy(text = summaryText) else it }
            actions.updateChatSkills(chat.id, selectedOrder, summaries)
            onDismiss()
        }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    pendingAdult?.let { id ->
        AdultSkillConfirmDialog(onConfirm = { actions.confirmAdultSkills(true); selectedOrder = (selectedOrder + id).distinct(); pendingAdult = null }) { pendingAdult = null }
    }
}

@Composable
internal fun ChatSkillPicker(
    skills: List<SkillRecord>,
    selectedIds: Set<String>,
    locked: Boolean,
    onSelect: (SkillRecord, Boolean) -> Unit,
    initialFilters: SkillFilters = SkillFilters(),
    initialScenario: SkillScenario = SkillScenario.ROLEPLAY,
    costs: Map<String, Int> = emptyMap()
) {
    var filters by rememberSkillFilters(initialFilters)
    var guided by rememberSaveable { mutableStateOf(initialFilters == SkillFilters()) }
    var scenario by rememberSaveable { mutableStateOf(initialScenario) }
    val visible = filterSkills(skills, filters, selectedIds)
    val suggestions = suggestSkills(skills, scenario)
    val choices = if (guided) suggestions.map { it.skill } else visible
    val originalSelection = remember { selectedIds.toSet() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SkillSelectedSummary(skills, selectedIds, onShowSelected = {
            guided = false; filters = SkillFilters(status = SkillStatusFilter.SELECTED)
        }, pendingEnableIds = selectedIds - originalSelection)
        SkillChoicePanel(guided, { guided = it }, scenario, { scenario = it })
        if (!guided) SkillFilterPanel(skills, filters, { filters = it }, selectedIds = selectedIds, compact = true)
        if (choices.isEmpty()) {
            Text(when {
                skills.isEmpty() -> "还没有已安装的技能，可到 Skills 页安装原版或导入。"
                guided -> "此场景的推荐技能尚未安装，已有技能仍可在全部技能中选择。"
                else -> "没有匹配的技能，可清空筛选后查看全部。"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { guided = false; filters = SkillFilters() }) { Text("查看全部技能") }
        }
        choices.forEach { skill ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Checkbox(selectedIds.contains(skill.id), { onSelect(skill, it) }, enabled = !locked)
                Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (guided) suggestions.firstOrNull { it.skill.id == skill.id }?.let { SkillSuggestionLabel(it) }
                    Text(skill.name, fontWeight = FontWeight.SemiBold)
                    SkillMetadataLabels(skill)
                    costs[skill.id]?.let { SkillCostLine(it) }
                    SkillChoiceExplanation(skill, showEffect = guided)
                    if (!guided) Text(skill.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (selectedIds.contains(skill.id) && skill.id in originalSelection && !skill.enabled) Text("总开关已关闭，请到 Skills 页明确启用。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Divider()
        }
    }
}

@Composable
internal fun AdultBadge() {
    Text("NSFW · 仅限成年角色", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun AdultSkillConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("启用成人向 Skills") }, text = { AdultSkillNotice() },
        confirmButton = { Button(onClick = onConfirm) { Text("我已年满 18 岁，确认启用") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun AdultSkillNotice() {
    Text(
        "成人向 Skills 包含露骨性内容的写作指导。启用前请确认：\n" +
            "• 你已年满 18 岁，且当地法律允许阅读此类内容；\n" +
            "• 只用于所有角色都明确是成年人的虚构情节，绝不涉及未成年人；\n" +
            "• 仍需遵守所用模型服务的使用政策。\n" +
            "确认后可随时在“设置 → 成人向 Skills”关闭，关闭时会从所有会话移除。",
        style = MaterialTheme.typography.bodySmall
    )
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
private fun CharactersTab(state: UiSession, actions: AppActions, onOpenChat: () -> Unit) {
    var characterDialog by remember { mutableStateOf<Character?>(null) }
    var scenarioDelete by remember { mutableStateOf<Character?>(null) }
    var worldDialog by remember { mutableStateOf<WorldEntry?>(null) }
    var memoryDialog by remember { mutableStateOf<Memory?>(null) }
    var worldExpanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val locked = state.busy || state.loading
    val allScenarios = state.data.characters.filter { it.id.startsWith("builtin-scenario-") }
    val scenarios = allScenarios.filter { it.matchesCharacterSearch(query) }
    val characters = state.data.characters.filter { !it.id.startsWith("builtin-scenario-") && it.matchesCharacterSearch(query) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("搜索角色或剧本") })
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { actions.importFile("character") }, enabled = !locked) { Icon(Icons.Outlined.FileUpload, "导入角色卡") }
                IconButton(onClick = { if (actions.startCharacterBuilder()) onOpenChat() }, enabled = !locked) { Icon(Icons.Outlined.AutoAwesome, "AI 帮我做角色卡（可看视频）") }
                IconButton(onClick = { characterDialog = Character() }, enabled = !locked) { Icon(Icons.Outlined.Add, "新建角色") }
            }
        }
        item { SectionTitle("剧本", action = { Text("${scenarios.size} 个", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }) }
        if (scenarios.isEmpty()) {
            item {
                Text(
                    if (query.isBlank()) "暂无内置剧本。安装剧本后会显示在这里。" else "没有匹配的剧本。",
                    Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(scenarios, key = { it.id }) { scenario ->
            val recentChat = state.data.chats.filter { it.characterId == scenario.id }.maxByOrNull { it.updatedAt }
            ScenarioCard(
                character = scenario,
                worldEntryCount = state.data.worldEntries.count { it.characterId == scenario.id },
                hasRecentChat = recentChat != null,
                busy = locked,
                onContinue = {
                    recentChat?.let { actions.selectChat(it.id); onOpenChat() }
                },
                onNewGame = {
                    actions.createChat(scenario.id)
                    onOpenChat()
                },
                onEdit = { characterDialog = scenario },
                onDelete = { scenarioDelete = scenario }
            )
        }
        item { SectionTitle("角色卡", action = { Text("${characters.size} 个", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }) }
        if (characters.isEmpty()) item { EmptyState(Icons.Outlined.Group, if (query.isBlank()) "还没有角色卡" else "没有匹配的角色卡", "可以创建角色，或导入常见的 ST JSON / PNG 角色卡。", if (query.isBlank()) "新建角色" else null) { characterDialog = Character() } }
        items(characters, key = { it.id }) { character ->
            CharacterCard(character, state, actions, onEdit = { characterDialog = it }, onOpenChat = onOpenChat)
        }
        item {
            SectionTitle("世界书", action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.data.worldEntries.size} 条", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { worldExpanded = !worldExpanded }) { Text(if (worldExpanded) "收起" else "展开") }
                    IconButton(onClick = { worldDialog = WorldEntry() }, enabled = !locked) { Icon(Icons.Outlined.Add, "新建世界书条目") }
                }
            })
        }
        if (state.data.worldEntries.isEmpty()) item { Text("没有世界书条目。常驻条目和关键词触发条目都会在上下文预览中显示。", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.data.worldEntries.isNotEmpty() && !worldExpanded) item {
            Text("世界书列表已折叠，展开后可编辑全部条目。", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (worldExpanded) items(state.data.worldEntries, key = { it.id }) { entry -> WorldEntryCard(entry, actions) { worldDialog = it } }
        item {
            SectionTitle("记忆", action = { IconButton(onClick = { memoryDialog = Memory() }, enabled = !locked) { Icon(Icons.Outlined.Add, "新建记忆") } })
        }
        if (state.data.memories.isEmpty()) item { Text("没有已保存的记忆。你可以把稳定信息固定到全局或当前会话。", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.data.memories, key = { it.id }) { memory -> MemoryCard(memory, actions) { memoryDialog = it } }
    }
    characterDialog?.let { CharacterDialog(it, state, actions) { characterDialog = null } }
    scenarioDelete?.let { scenario -> ConfirmDeleteDialog("剧本“${scenario.name.ifBlank { "未命名剧本" }}”", { actions.deleteCharacter(scenario.id); scenarioDelete = null }) { scenarioDelete = null } }
    worldDialog?.let { WorldEntryDialog(it, state, actions) { worldDialog = null } }
    memoryDialog?.let { MemoryDialog(it, state, actions) { memoryDialog = null } }
}

private fun Character.matchesCharacterSearch(query: String): Boolean {
    if (query.isBlank()) return true
    return listOf(name, description, personality, scenario).any { it.contains(query, ignoreCase = true) }
}

@Composable
private fun CharacterCard(character: Character, state: UiSession, actions: AppActions, onEdit: (Character) -> Unit, onOpenChat: () -> Unit = {}) {
    var confirmDelete by remember { mutableStateOf(false) }
    val locked = state.busy || state.loading
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharacterAvatar(character.avatar, character.name, 44.dp, actions::characterAvatar)
                Spacer(Modifier.width(10.dp))
                Text(character.name.ifBlank { "未命名角色" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { onEdit(character) }) { Icon(Icons.Outlined.Edit, "编辑角色") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "删除角色") }
            }
            Text(character.description.ifBlank { "没有角色简介" }, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (character.personality.isNotBlank()) Chip("有性格设定")
                if (character.firstMessage.isNotBlank()) Chip("有开场白")
                // Creating the chat also opens it; before 0.1.83 the page stayed put and looked unresponsive.
                OutlinedButton(onClick = { actions.createChat(character.id); onOpenChat() }, enabled = !locked) { Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("创建会话") }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterDialog(character: Character, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(character) }
    FormDialog("${if (character.name.isBlank()) "新建" else "编辑"}角色", onDismiss, validate = { if (value.name.isBlank()) "请输入角色名称。" else null }, confirm = { actions.saveCharacter(value); onDismiss() }) {
        // Scenario cards are whole stories with their own world books; the drafter writes people.
        if (!character.id.startsWith("builtin-scenario-")) CharacterDraftPanel(state, actions, value) { draft -> value = CharacterDrafts.merge(value, draft) }
        // The avatar applies at once; it is only offered once the character exists.
        val saved = state.data.characters.firstOrNull { it.id == character.id }
        if (saved != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CharacterAvatar(saved.avatar, saved.name, 56.dp, actions::characterAvatar)
            OutlinedButton(onClick = { actions.pickCharacterAvatar(saved.id) }, enabled = !state.busy) { Text("更换头像") }
            if (saved.avatar.isNotBlank()) TextButton(onClick = { actions.removeCharacterAvatar(saved.id) }, enabled = !state.busy) { Text("移除") }
        }
        val packNames = state.pcLink.stickerPacks.orEmpty().map { it.name }.distinct()
        if (packNames.isNotEmpty()) {
            Text("专属表情包（不选则用全部启用的表情包）", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                packNames.forEach { name ->
                    FilterChip(selected = name in value.stickerPacks, onClick = {
                        value = value.copy(stickerPacks = if (name in value.stickerPacks) value.stickerPacks - name else value.stickerPacks + name)
                    }, label = { Text(name) })
                }
            }
        }
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
    var priority by rememberSaveable(entry.id) { mutableStateOf(entry.priority.toString()) }
    var keywords by rememberSaveable(entry.id) { mutableStateOf(entry.keywords.joinToString(",")) }
    FormDialog("${if (entry.name.isBlank()) "新建" else "编辑"}世界书条目", onDismiss, validate = { when { value.name.isBlank() -> "请输入条目名称。"; value.content.isBlank() -> "请输入条目内容。"; else -> integerInputError(priority, "优先级", Int.MIN_VALUE..Int.MAX_VALUE) } }, confirm = {
        actions.saveWorldEntry(value.copy(priority = priority.trim().toInt(), keywords = keywords.split(",", "，").map(String::trim).filter(String::isNotBlank))); onDismiss()
    }) {
        FormText("名称", value.name) { value = value.copy(name = it) }
        FormText("内容", value.content, 6) { value = value.copy(content = it) }
        FormText("关键词（用逗号分隔）", keywords) { keywords = it }
        FormNumber("优先级", priority, signed = true) { priority = it }
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
                Column(Modifier.weight(1f)) { Text(provider.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(providerKindLabel(provider.kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (hasKey) Chip("已配置") else Chip("未配置密钥")
            }
            Text(provider.baseUrl, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { actions.refreshModels(provider.id) }, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("刷新模型") }
                OutlinedButton(onClick = { onEdit(provider) }) { Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("编辑") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "删除平台") }
            }
            if (provider.kind.equals("venice", ignoreCase = true)) {
                VeniceBalanceBlock(provider.id, state, actions)
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
private fun VeniceBalanceBlock(providerId: String, state: UiSession, actions: AppActions) {
    val balanceState = state.veniceBalances[providerId]
    val currentBalance = balanceState?.balance
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Venice 余额", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { actions.refreshVeniceBalance(providerId) },
                enabled = balanceState?.loading != true
            ) {
                if (balanceState?.loading == true) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (balanceState?.balance == null) "查看余额" else "刷新余额")
            }
        }
        when {
            balanceState?.loading == true && balanceState.balance == null -> Text("正在读取 Venice 余额…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            currentBalance != null -> {
                if (!balanceState?.error.isNullOrBlank()) Text("刷新失败，显示上次结果：${balanceState?.error.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                VeniceBalanceDetails(currentBalance)
            }
            !balanceState?.error.isNullOrBlank() -> Text(balanceState?.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else -> Text("尚未查询；需要已配置 Venice API key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VeniceBalanceDetails(balance: VeniceBalance) {
    val scopeLabel = if (balance.scope == VeniceBalanceScope.ACCOUNT) "账户余额" else "当前 Key 可用额度"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(scopeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text("订阅积分：${formatVeniceAmount(balance.subscriptionCredits)}", style = MaterialTheme.typography.bodySmall)
        Text("充值积分：${formatVeniceAmount(balance.purchasedCredits)}", style = MaterialTheme.typography.bodySmall)
        Text("DIEM：${formatVeniceAmount(balance.diem)}", style = MaterialTheme.typography.bodySmall)
        Text("奖励积分：${formatVeniceAmount(balance.earnedCredits)}", style = MaterialTheme.typography.bodySmall)
        balance.canConsume?.let { Text(if (it) "当前状态：可消费" else "当前状态：不可消费", style = MaterialTheme.typography.labelSmall, color = if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        Text("查询于 ${summaryTime(balance.fetchedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("余额字段由 Venice 返回；未返回的项目显示“未返回”。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatVeniceAmount(value: java.math.BigDecimal?): String =
    value?.stripTrailingZeros()?.toPlainString() ?: "未返回"

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
internal fun ProviderDialog(provider: ProviderConfig, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(provider) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var modelId by remember { mutableStateOf("") }
    var modelSupportsVision by remember { mutableStateOf(false) }
    val credentialBindingChanged = value.baseUrl.trim() != provider.baseUrl.trim() || value.kind != provider.kind
    FormDialog("${if (state.data.providers.none { it.id == provider.id }) "添加" else "编辑"}平台", onDismiss, validate = { when {
        value.name.isBlank() -> "请输入平台名称。"
        ProviderUrlPolicy.violation(value) != null -> ProviderUrlPolicy.violation(value)
        ProviderUrlPolicy.lanViolation(value) != null -> ProviderUrlPolicy.lanViolation(value)
        credentialBindingChanged && key.isBlank() -> "修改 Base URL 或协议后必须重新填写 API Key。"
        else -> null
    } }, confirm = { actions.saveProvider(value, key); onDismiss() }) {
        FormText("名称", value.name) { value = value.copy(name = it) }
        ChoiceRow("协议类型", listOf("venice" to "Venice", "openai" to "OpenAI-compatible", "writing-server" to "本机写作服务（OpenAI-compatible）", "anthropic" to "Anthropic Messages"), value.kind) { value = value.copy(kind = it) }
        if (value.kind.equals("writing-server", ignoreCase = true)) {
            Text("Base URL 填电脑的远程地址（https://域名/v1）；只在家用的话，填电脑内网地址（例如 http://192.168.1.22:8791/v1）。API Key 填网关 Token。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FormText("Base URL", value.baseUrl) { value = value.copy(baseUrl = it) }
        if (value.kind.equals("writing-server", ignoreCase = true)) {
            FormText("家里的局域网地址（可选）", value.lanBaseUrl) { value = value.copy(lanBaseUrl = it) }
            Text("填了以后，手机在家时自动走这个内网地址，更快也不经过外网；出门自动改走上面的 Base URL。走内网前，App 会先确认对方确实是你的电脑，才会发送 Token。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key（留空保持已有密钥）") }, visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton({ showKey = !showKey }) { Icon(if (showKey) Icons.Outlined.Visibility else Icons.Outlined.Security, "显示或隐藏密钥") } }, singleLine = true)
        Text("同一地址和协议留空会保留已有密钥；修改 Base URL 或协议后必须重新填写。凭据按地址/协议绑定，不会从备份或旧地址偷偷迁移。导出备份不包含密钥。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Divider()
        Text("手动添加模型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(modelId, { modelId = it }, Modifier.weight(1f), label = { Text("模型 ID") }, singleLine = true); Spacer(Modifier.width(6.dp)); IconButton(onClick = { if (modelId.isNotBlank()) { value = value.copy(models = value.models + ModelInfo(id = modelId.trim(), name = modelId.trim(), supportsVision = modelSupportsVision)); modelId = ""; modelSupportsVision = false } }) { Icon(Icons.Outlined.Add, "添加模型") } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(modelSupportsVision, { modelSupportsVision = it })
            Text("支持图片识别（视觉）", style = MaterialTheme.typography.bodySmall)
        }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillsTab(state: UiSession, actions: AppActions, initialFilters: SkillFilters = defaultSkillLibraryFilters(), initialScenario: SkillScenario = SkillScenario.ROLEPLAY) {
    var selected by remember { mutableStateOf<SkillRecord?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var showUrlInstall by remember { mutableStateOf(false) }
    var showToolInfo by rememberSaveable { mutableStateOf(false) }
    var filters by rememberSkillFilters(initialFilters)
    // The library opens on the curated catalog. Scenario recommendations remain available via
    // the explicit “帮我选” chip and are never allowed to widen the default catalog silently.
    var guided by rememberSaveable { mutableStateOf(false) }
    var scenario by rememberSaveable { mutableStateOf(initialScenario) }
    val locked = state.loading || state.busy
    val currentChat = state.data.chats.firstOrNull { it.id == state.selectedChatId }
    val selectedIds = currentChat?.skillIds?.toSet().orEmpty()
    val visibleSkills = filterSkills(state.data.skills, filters, selectedIds)
    val missing = state.missingBuiltinSkills
    val visibleMissing = filterSkills(missing, filters.copy(status = SkillStatusFilter.ALL))
    val suggestions = suggestSkills(state.data.skills, scenario)
    val suggestedMissing = suggestSkills(missing, scenario)
    val displayedSkills = if (guided) suggestions.map { it.skill } else visibleSkills
    val displayedMissing = if (guided) suggestedMissing.map { it.skill } else visibleMissing
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("技能：按用途挑，不用全开", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("默认只显示精选写作和 NSFW；旧 Skill 保留在完整目录，不会影响已经选中的会话。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("已安装 ${state.data.skills.size} 项 · NSFW ${state.data.skills.count { it.adult }} 项", style = MaterialTheme.typography.labelMedium)
                if (missing.isNotEmpty()) Text("可恢复 ${missing.size} 项，其中 NSFW ${missing.count { it.adult }} 项；未计入已安装数量。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!guided) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !locked, onClick = { showUrlInstall = true }) { Icon(Icons.Outlined.Cloud, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("从链接安装") }
                    OutlinedButton(enabled = !locked, onClick = { showImport = true }) { Icon(Icons.Outlined.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("导入文件") }
                }
            }
        }
        item {
            SkillChoicePanel(guided, { guided = it }, scenario, { scenario = it }, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (!guided) item {
            SkillFilterPanel(state.data.skills, filters, { filters = it }, modifier = Modifier.padding(horizontal = 16.dp), selectedIds = selectedIds)
        }
        if (currentChat != null) item {
            SkillSelectedSummary(state.data.skills, selectedIds, onShowSelected = {
                guided = false; filters = defaultSkillLibraryFilters().copy(status = SkillStatusFilter.SELECTED)
            }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        item {
            SectionTitle(if (guided) "${scenario.label} · ${displayedSkills.size} 个已安装候选" else "已安装 · ${visibleSkills.size} 项匹配")
        }
        if (state.data.skills.isEmpty()) item {
            if (locked) EmptyState(Icons.Outlined.AutoAwesome, "还没有 Skill", "当前操作进行中，请完成后再导入 SKILL.md 或 ZIP。")
            else EmptyState(Icons.Outlined.AutoAwesome, "还没有 Skill", "导入 SKILL.md、ZIP，或使用 HTTPS 链接安装后，在详情中确认权限并启用。", "导入 Skill") { showImport = true }
        }
        else if (displayedSkills.isEmpty()) item { EmptyState(Icons.Outlined.AutoAwesome, if (guided) "此场景暂无已安装推荐" else "没有匹配的已安装技能", "可以查看全部技能，或安装下方可恢复的内置原版。", "查看全部技能") { guided = false; filters = SkillFilters() } }
        items(displayedSkills, key = { it.id }) { skill ->
            SkillCard(skill, currentChat, locked, state.data.settings.adultSkillsConfirmed, actions, stars = state.skillStars[skill.id], suggestion = if (guided) suggestions.firstOrNull { it.skill.id == skill.id } else null, onDetails = { selected = skill })
        }
        if (displayedMissing.isNotEmpty()) {
            item {
                SectionTitle("可恢复的内置原版 · ${displayedMissing.size} 项")
                Text(if (guided) "此场景还有以下候选尚未安装；逐项安装后默认关闭，不会自动加入会话。" else "以下项目尚未安装；按当前搜索、用途和内容筛选列出，不受启用状态筛选影响。逐项安装后默认关闭，不会自动加入会话。", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(displayedMissing, key = { "missing-${it.id}" }) { skill ->
                SkillRestoreCard(skill, locked, state.skillStars[skill.id], suggestion = if (guided) suggestedMissing.firstOrNull { it.skill.id == skill.id } else null) { actions.restoreBuiltinSkill(skill.id) }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (currentChat == null) {
                        Text("当前没有会话。创建会话后，才能把 Skill 加入本会话。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Button(enabled = !locked, onClick = { actions.createChat() }) { Text("创建会话") }
                    } else {
                        Text("当前会话：${currentChat.title.ifBlank { "新会话" }}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("勾选“用于本会话”会同时启用；总开关关闭时，该技能在所有会话都不生效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    TextButton(onClick = { showToolInfo = !showToolInfo }) { Icon(Icons.Outlined.Security, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (showToolInfo) "收起工具与原版说明" else "工具与原版说明") }
                    if (showToolInfo) {
                        Text("仅提供 files.read、files.write、network、memory 四类授权工具；本版不执行 Python、Bash、Node 或桌面 MCP 脚本。", style = MaterialTheme.typography.bodySmall)
                        Text("GitHub 精选保留上游原文，默认关闭。工具声明属于上游约定，实际能力取决于逐项授予的权限。用途标签与 NSFW 标记分开筛选，通用言情不会算作 NSFW。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
        if (guided) item {
            TextButton(modifier = Modifier.padding(horizontal = 12.dp), onClick = { guided = false; filters = SkillFilters() }) { Text("查看完整目录与所有可恢复技能") }
        }
    }
    selected?.let { SkillDetailsDialog(it, state, actions) { selected = null } }
    if (showImport && !locked) ImportDialog("skill", actions) { showImport = false }
    if (showUrlInstall && !locked) SkillUrlDialog(actions) { showUrlInstall = false }
}

@Composable
private fun SkillRestoreCard(skill: SkillRecord, locked: Boolean, stars: GitHubStars?, suggestion: SkillSuggestion? = null, onRestore: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            suggestion?.let { SkillSuggestionLabel(it) }
            Text(skill.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            SkillMetadataLabels(skill)
            Text(skill.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            stars?.let { Text(starsCardLine(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("未安装 · 内置 GitHub 原版", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(enabled = !locked, onClick = onRestore) { Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("安装原版") }
        }
    }
}

@Composable
internal fun SkillCard(skill: SkillRecord, currentChat: Chat?, busy: Boolean, adultConfirmed: Boolean, actions: AppActions, stars: GitHubStars? = null, suggestion: SkillSuggestion? = null, onDetails: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmAdult by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            suggestion?.let { SkillSuggestionLabel(it) }
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(skill.name.ifBlank { "未命名 Skill" }, fontWeight = FontWeight.Bold)
                    SkillMetadataLabels(skill)
                    stars?.let { Text(starsCardLine(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("允许启用", style = MaterialTheme.typography.labelSmall)
                    Switch(checked = skill.enabled, onCheckedChange = { if (!busy) actions.saveSkill(skill.copy(enabled = it)) }, enabled = !busy)
                }
            }
            SkillChoiceExplanation(skill)
            if (currentChat?.skillIds?.contains(skill.id) == true && !skill.enabled) Text("已选但总开关关闭，暂不生效。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = currentChat?.skillIds?.contains(skill.id) == true,
                    onCheckedChange = { checked ->
                        if (!busy && currentChat != null) {
                            if (checked && skill.adult && !adultConfirmed) confirmAdult = true
                            else actions.setSkillForCurrentChat(skill.id, checked)
                        }
                    },
                    enabled = !busy && currentChat != null
                )
                Text("用于本会话", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !busy, onClick = onDetails) { Icon(Icons.Outlined.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("查看详情") }
                IconButton(enabled = !busy, onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "卸载 Skill") }
            }
        }
    }
    if (confirmDelete && !busy) ConfirmDeleteDialog("Skill“${skill.name.ifBlank { "未命名" }}”", { actions.deleteSkill(skill.id); confirmDelete = false }) { confirmDelete = false }
    if (confirmAdult && !busy) AdultSkillConfirmDialog(onConfirm = {
        actions.confirmAdultSkills(true); actions.setSkillForCurrentChat(skill.id, true); confirmAdult = false
    }) { confirmAdult = false }
}

@Composable
internal fun SkillGuideCard(guide: SkillGuide) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("启用效果", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(guide.effect, style = MaterialTheme.typography.bodyMedium)
            guide.example?.let { (before, after) ->
                Text("示例", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text("关闭时：$before", style = MaterialTheme.typography.bodySmall)
                Text("开启后：$after", style = MaterialTheme.typography.bodySmall)
            }
            if (guide.note.isNotBlank()) {
                Text("使用说明", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(guide.note, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
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
            SkillMetadataLabels(skill)
            Text(skill.description.ifBlank { "没有说明" })
            SkillChoiceExplanation(skill, showEffect = false)
            skillGuide(skill.id)?.let { SkillGuideCard(it) }
            Text("来源：${skill.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (skill.sourceUrl.isNotBlank()) {
                TextButton(enabled = !locked, onClick = {
                    runCatching { uriHandler.openUri(skill.sourceUrl) }.onFailure { linkError = "无法打开 GitHub 来源链接：${it.message ?: "未知错误"}" }
                }) { Icon(Icons.Outlined.Cloud, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("打开 GitHub 来源") }
            }
            Text(if (skill.source.startsWith(BuiltinSkills.FIRST_PARTY_SOURCE)) "上游：无，本应用编写" else "Commit：${skill.sourceCommit.ifBlank { "未记录" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.skillStars[skill.id]?.let { Text(starsDetailLine(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            if (skill.files.keys.any { it != "SKILL.md" && it != "LICENSE" }) Text("正文随会话发送；其余引用文件在支持工具调用的模型上由 skill_read 按需读取，不会一次性塞进上下文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val settings = state.data.settings
    var numbers by rememberSaveable(settings.maxContextInputTokens, settings.compressionThreshold, settings.keepRecentTurns,
        settings.contextOverride, settings.maxToolSteps, stateSaver = listSaver<CompressionSettingsDraft, String>(
            save = { listOf(it.inputBudget, it.threshold, it.keepRecent, it.contextOverride, it.toolSteps) },
            restore = { CompressionSettingsDraft(it[0], it[1], it[2], it[3], it[4]) }
        )) { mutableStateOf(CompressionSettingsDraft.from(settings)) }
    var numberError by rememberSaveable { mutableStateOf<String?>(null) }
    var showPersona by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)) {
        item {
            SectionTitle("请求诊断")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前版本 ${state.appVersion.ifBlank { "未读取" }}", fontWeight = FontWeight.SemiBold)
                    Text("本机保留最近 200 条诊断事件，包括耗时、超时阶段、请求 ID 和平台返回的用量。不记录 API Key、聊天正文或系统提示词。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ actions.showDiagnostics() }, Modifier.fillMaxWidth()) { Text("查看最近请求") }
                    OutlinedButton({ actions.exportFile("diagnostics") }, Modifier.fillMaxWidth()) { Text("导出日志") }
                    OutlinedButton({ actions.checkBilling() }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("核对最近请求的 Venice 扣款") }
                    Text("扣款查询会读取 Venice 账本，仅按请求 ID 对照；查不到记录时无法确认是否扣款。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ actions.clearDiagnostics() }, enabled = !state.busy) { Text("清空本机日志") }
                }
            }
        }
        item {
            SectionTitle("显示与人格")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceRow("主题", listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), settings.darkMode) { actions.saveSettings(settings.copy(darkMode = it)) }
                    val loadUserAvatar = remember(actions) { { avatar: String -> actions.characterAvatar(avatar) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CharacterAvatar(settings.userAvatar, "我", 44.dp, loadUserAvatar)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("我的头像", style = MaterialTheme.typography.bodyLarge)
                            Text(when {
                                settings.userAvatar.isBlank() -> "可选；选好后显示在你的每条消息旁"
                                settings.showUserAvatar -> "显示在你的每条消息旁"
                                else -> "已设置；在会话的「聊天外观」里打开显示"
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { actions.pickUserAvatar() }) { Text(if (settings.userAvatar.isBlank()) "选择" else "更换") }
                        if (settings.userAvatar.isNotBlank()) TextButton(onClick = { actions.removeUserAvatar() }) { Text("移除") }
                    }
                    OutlinedButton(onClick = { showPersona = true }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.PersonOutline, null); Spacer(Modifier.width(6.dp)); Text(if (settings.userPersona.isBlank()) "设置全局用户人设" else "编辑全局用户人设") }
                }
            }
        }
        item {
            SectionTitle("上下文压缩")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("接近预算时自动压缩", Modifier.weight(1f)); Switch(settings.autoCompress, { actions.saveSettings(settings.copy(autoCompress = it)) }) }
                Text("数字设置修改后点击下方保存；可先清空再输入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FormNumber("日常输入预算 tokens（0=模型容量）", numbers.inputBudget) { numbers = numbers.copy(inputBudget = it); numberError = null }
                Text("默认 65,536 tokens，包含预设、Skills、摘要和近期对话。接近预算时只归档新增历史；首次整理长会话仍可使用摘要模型的完整窗口，不按这个预算拆段。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FormNumber("触发比例（0.2–0.95）", numbers.threshold, decimal = true) { numbers = numbers.copy(threshold = it); numberError = null }
                FormNumber("保留最近轮数", numbers.keepRecent) { numbers = numbers.copy(keepRecent = it); numberError = null }
                FormNumber("上下文覆盖上限（0=模型默认）", numbers.contextOverride) { numbers = numbers.copy(contextOverride = it); numberError = null }
                FormNumber("最大工具轮数", numbers.toolSteps) { numbers = numbers.copy(toolSteps = it); numberError = null }
                Text("达到上限后另请求一次最终回答。记忆查询最多 3 轮、6 次，重复或没有新证据时提前结束。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                numberError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    numberError = numbers.validationError()
                    if (numberError == null) {
                        val next = numbers.applyTo(settings)
                        actions.saveSettings(next)
                        numbers = CompressionSettingsDraft.from(next)
                    }
                }, enabled = !state.busy && !state.loading, modifier = Modifier.fillMaxWidth()) { Text("保存压缩设置") }
            } }
        }
        item {
            var confirmAdult by remember { mutableStateOf(false) }
            val adultConfirmed = state.data.settings.adultSkillsConfirmed
            SectionTitle("成人向 Skills")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("允许成人向 Skills（仅限成年角色）", Modifier.weight(1f))
                    Switch(adultConfirmed, { if (it) confirmAdult = true else actions.confirmAdultSkills(false) }, enabled = !state.busy)
                }
                Text(if (adultConfirmed) "已确认年满 18 岁。关闭后会从所有会话移除成人向 Skills，且不再发送给模型。" else "未确认时，成人向 Skills 不能加入会话，也不会发送给模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            if (confirmAdult) AdultSkillConfirmDialog(onConfirm = { actions.confirmAdultSkills(true); confirmAdult = false }) { confirmAdult = false }
        }
        item {
            SectionTitle("表情包")
            StickerPacksCard(state.pcLink.stickerPacks, state.busy || state.loading || state.pcLink.busy.isNotBlank(), onImport = { actions.importFile("stickers") },
                onEnable = actions::setStickerPackEnabled, onDelete = actions::deleteStickerPack,
                load = { sticker -> actions.pcStickerFile(sticker)?.let { decodeStickerFile(it, 160) } },
                pcAvailable = state.data.providers.any { it.kind.equals("writing-server", ignoreCase = true) },
                error = state.pcLink.stickersError, legacy = state.data.stickerPacks, onMigrate = actions::migrateLocalStickers)
        }
        item { SectionTitle("系统预设", action = { IconButton(onClick = { presetDialog = Preset() }) { Icon(Icons.Outlined.Add, "新建预设") } }) }
        items(state.data.presets, key = { it.id }) { preset -> PresetCard(preset, actions) { presetDialog = preset } }
        item {
            SectionTitle("电脑连接")
            val servers = state.data.providers.filter { it.kind.equals("writing-server", ignoreCase = true) }
            val server = servers.firstOrNull { it.id == state.pcLink.providerId } ?: servers.firstOrNull()
            PcLinkCard(state.pcLink, servers, locked = state.busy || state.loading,
                onCheck = actions::checkPcLink, onBackup = actions::backupToPc, onLoadBackups = actions::loadPcBackups, onRestore = actions::restoreFromPc,
                onAutoBackup = actions::setPcAutoBackup, onChooseProvider = actions::choosePcProvider,
                // The upstream models the PC offers, without its two default-model aliases.
                models = server?.models.orEmpty().filter { it.type == "text" && it.id !in setOf("pocket-direct", "pocket-writer") },
                onLoadSettings = actions::loadPcSettings, onSaveSettings = actions::savePcSettings,
                onRefreshModels = { server?.let { actions.refreshModels(it.id) } })
        }
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
        AlertDialog(onDismissRequest = { showPersona = false }, title = { Text("全局用户人设") }, text = { OutlinedTextField(persona, { persona = it }, Modifier.fillMaxWidth().height(180.dp), label = { Text("人设内容") }) }, confirmButton = { Button({ actions.saveSettings(settings.copy(userPersona = persona)); showPersona = false }) { Text("保存") } }, dismissButton = { TextButton({ showPersona = false }) { Text("取消") } })
    }
}

@Composable
internal fun DiagnosticsDialog(summary: String, actions: AppActions) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = { actions.dismissDiagnostics() },
        title = { Text("请求诊断") },
        text = { SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState())) { Text(summary, style = MaterialTheme.typography.bodySmall) } } },
        confirmButton = { TextButton({ actions.dismissDiagnostics() }) { Text("关闭") } },
        dismissButton = { TextButton({ clipboard.setText(AnnotatedString(summary)) }) { Text("复制诊断") } }
    )
}

@Composable
private fun PresetCard(preset: Preset, actions: AppActions, onEdit: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(preset.name, fontWeight = FontWeight.SemiBold)
            Text(
                if (preset.generationBudgetEnabled) {
                    "温度 ${preset.temperature} · 正文上限 ${preset.maxOutput} token · 思考预留 ${if (preset.reasoningEffort == "none") 0 else preset.reasoningBudget} token"
                } else {
                    "温度 ${preset.temperature} · 生成预算：跟随平台"
                },
                style = MaterialTheme.typography.labelSmall,
            )
            Text("Venice 思考：${reasoningEffortLabel(preset.reasoningEffort)}", style = MaterialTheme.typography.labelSmall)
            Text("Venice 平台附加提示：${if (preset.includeVeniceSystemPrompt) "开启" else "关闭"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(preset.systemPrompt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "编辑预设") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "删除预设") }
                IconButton(onClick = { actions.exportFile("preset", preset.id) }) { Icon(Icons.Outlined.FileDownload, "导出预设") }
            }
        }
    }
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
    var numbers by rememberSaveable(preset.id, preset.generationBudgetEnabled, stateSaver = listSaver<PresetNumbersDraft, String>(
        save = { listOf(it.temperature, it.maxOutput, it.reasoningBudget, it.generationBudgetEnabled.toString()) },
        restore = { PresetNumbersDraft(it[0], it[1], it[2], it.getOrNull(3)?.toBoolean() ?: preset.generationBudgetEnabled) }
    )) { mutableStateOf(PresetNumbersDraft.from(preset)) }
    val reasoningOptions = remember(state.data.providers) {
        state.data.providers.filter { it.kind == "venice" }.flatMap { provider ->
            provider.models.flatMap { VeniceModelCapabilities.from(it).reasoningEffortOptions }
        }.distinct()
    }
    FormDialog("${if (preset.id == "" || preset.name == "新预设") "新建" else "编辑"}预设", onDismiss, validate = { if (value.name.isBlank()) "请输入预设名称。" else numbers.validationError() }, confirm = { actions.savePreset(numbers.applyTo(value)); onDismiss() }) {
        FormText("名称", value.name) { value = value.copy(name = it) }
        FormText("系统提示词", value.systemPrompt, 5) { value = value.copy(systemPrompt = it) }
        FormNumber("温度", numbers.temperature, decimal = true) { numbers = numbers.copy(temperature = it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("自定义生成预算", Modifier.weight(1f))
            Switch(
                checked = numbers.generationBudgetEnabled,
                onCheckedChange = { numbers = numbers.copy(generationBudgetEnabled = it) },
            )
        }
        Text(
            if (numbers.generationBudgetEnabled) {
                "开启后编辑生成上限；平台仍有自身上限。"
            } else {
                "关闭时跟随平台，平台仍有自身上限；已保存的数值会保留，需要时重新开启预算编辑。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (numbers.generationBudgetEnabled) {
            FormNumber("正文上限（token）", numbers.maxOutput) { numbers = numbers.copy(maxOutput = it) }
            FormNumber("思考预留（token，Venice）", numbers.reasoningBudget) { numbers = numbers.copy(reasoningBudget = it) }
        }
        VeniceReasoningChoice(value.reasoningEffort, reasoningOptions, { value = value.copy(reasoningEffort = it) })
        if (reasoningOptions.isEmpty()) Text("尚无可用的 Venice 思考档位，请到模型页刷新目录。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.reasoningEffort != "default" && value.reasoningEffort !in reasoningOptions) Text("保留的档位暂未在本地 Venice 目录中找到。请刷新目录并核对会话模型的支持情况。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        Text("开启自定义预算时，Venice 会把思考和正文计入同一个总上限；这里将两者分开编辑，请求时按平台规则合并。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        VenicePlatformPromptToggle(value.includeVeniceSystemPrompt, { value = value.copy(includeVeniceSystemPrompt = it) })
        FormText("摘要提示词", value.summaryPrompt, 4) { value = value.copy(summaryPrompt = it) }
        ProviderModelChoice(state, value.summaryProviderId, value.summaryModelId) { p, m -> value = value.copy(summaryProviderId = p, summaryModelId = m) }
    }
}

@Composable
private fun ImportDialog(kind: String, actions: AppActions, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("导入${kindLabel(kind)}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("选择文件后应用会校验格式，并在写入前保留现有数据。")
            Text("支持的类型：${when (kind) { "backup" -> "口袋酒馆 ZIP 备份（兼容旧版 JSON）"; "skill" -> "SKILL.md 或 ZIP"; "chat" -> "会话 Markdown、JSONL 或酒馆导出文件"; else -> "对应的 JSON 文件" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun providerKindLabel(kind: String): String = when (kind.lowercase()) {
    "venice" -> "Venice"
    "openai" -> "OpenAI-compatible"
    "writing-server" -> "本机写作服务（OpenAI-compatible）"
    "anthropic" -> "Anthropic Messages"
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
private fun FormNumber(label: String, value: String, decimal: Boolean = false, signed: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else if (decimal) KeyboardType.Decimal else KeyboardType.Number))
}

@Composable
private fun ChoiceRow(label: String, choices: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { choices.forEach { (value, text) -> FilterChip(selected == value, { onSelect(value) }, label = { Text(text) }) } }
    }
}
