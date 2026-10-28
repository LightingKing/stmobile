package app.pockettavern.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.Chat
import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.UiSession
import app.pockettavern.provider.DoubaoResearchClient
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ResearchField(val id: String, val label: String, val hint: String)

private val researchFields = listOf(
    ResearchField("appearance", "长相", "五官、发型与有来源的外在特征"),
    ResearchField("body", "身材", "公开描述的体态与身形，不猜测私密数据"),
    ResearchField("style", "风格", "衣着、审美、表达与公众形象"),
    ResearchField("personality", "性格", "从公开访谈和行为整理线索，推测单独标明"),
    ResearchField("relationships", "人际关系", "公开身份、合作关系和角色关联"),
    ResearchField("social_sentiment", "社会舆论", "注明时间与来源的公开评价，不等同全民意见")
)

@Composable
internal fun ResearchDialog(chat: Chat, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ResearchContent(
            chat, state,
            onConfigure = { editingProvider = it },
            onStart = { subject, categories, fiction, knowledge, providerId, modelId, searchMode ->
                actions.researchCharacter(chat.id, subject, categories, true, fiction, knowledge, providerId, modelId, searchMode)
                onDismiss()
            },
            onWebStart = { subject, categories, fiction ->
                actions.openDoubaoWebResearch(chat.id, subject, categories, fiction)
                onDismiss()
            },
            onSaveSearchKey = actions::saveResearchSearchKey,
            onDismiss = onDismiss
        )
    }
    editingProvider?.let { provider ->
        ProviderDialog(provider, state, actions) { editingProvider = null }
    }
}

/** The explicit research action does not grant unrelated Skill network permissions. */
@Composable
internal fun ResearchContent(
    chat: Chat,
    state: UiSession,
    onConfigure: (ProviderConfig) -> Unit,
    onStart: (String, List<String>, Boolean, Boolean, String, String, String) -> Unit,
    onWebStart: (String, List<String>, Boolean) -> Unit = { _, _, _ -> },
    onDismiss: () -> Unit,
    onSaveSearchKey: (String) -> Unit = {},
    initialSubject: String = "",
    initialSection: Int = 0,
    initialNoteId: String? = null
) {
    var subject by rememberSaveable(chat.id) { mutableStateOf(initialSubject) }
    var selected by rememberSaveable(chat.id) { mutableStateOf(researchFields.map { it.id }.toSet()) }
    var allowFiction by rememberSaveable(chat.id) { mutableStateOf(true) }
    var saveToKnowledgeBase by rememberSaveable(chat.id) { mutableStateOf(true) }
    var searchMode by rememberSaveable(chat.id) { mutableStateOf(state.data.settings.researchSearchMode.takeIf { it in setOf("direct", "native") } ?: "direct") }
    // Do not put a credential in saved-instance state, settings, or a preview.
    var searchKey by remember { mutableStateOf("") }
    val providers = state.data.providers.filter { it.kind == "openai" && DoubaoResearchClient.isSupportedBaseUrl(it.baseUrl) }
    var providerId by rememberSaveable(chat.id) {
        mutableStateOf(providers.firstOrNull { it.id == state.data.settings.researchProviderId }?.id
            ?: providers.firstOrNull { it.id in state.hasKeys }?.id ?: providers.firstOrNull()?.id.orEmpty())
    }
    val provider = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull()
    var modelId by rememberSaveable(chat.id, provider?.id) {
        mutableStateOf(state.data.settings.researchModelId.takeIf { it.isNotBlank() && provider?.id == state.data.settings.researchProviderId }
            ?: DoubaoResearchClient.DEFAULT_MODEL)
    }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var section by rememberSaveable(chat.id) { mutableIntStateOf(initialSection) }
    var noteId by rememberSaveable(chat.id) { mutableStateOf(initialNoteId) }
    val notes = remember(chat.knowledgeNotes) { chat.knowledgeNotes.filter { it.sourceKind == "research" }.sortedByDescending { it.createdAt } }
    val note = notes.firstOrNull { it.id == noteId }
    val locked = state.busy || state.loading
    val hasKey = provider != null && provider.id in state.hasKeys
    val subjectLength = subject.codePointCount(0, subject.length)
    val ready = !locked && subject.trim().isNotBlank() && subjectLength <= 200 && selected.isNotEmpty() && provider != null && hasKey && modelId.trim().isNotBlank() && modelId.trim().length <= 200 && (searchMode == "native" || state.hasResearchSearchKey)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Text("豆包联网研究", Modifier.padding(horizontal = 20.dp, vertical = 16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            TabRow(selectedTabIndex = section) {
                Tab(selected = section == 0, onClick = { section = 0 }, text = { Text("新建研究") })
                Tab(selected = section == 1, onClick = { section = 1 }, text = { Text("已保存（${notes.size}）") })
            }
            if (section == 0) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("让豆包搜索并整理资料，供当前会话写作时调用。事实、推测和小说补写分别标注。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        subject, { subject = it }, Modifier.fillMaxWidth(),
                        label = { Text("人物 / 主题") }, placeholder = { Text("姓名 + 职业或作品，避免同名混淆") },
                        enabled = !locked, minLines = 1, maxLines = 3, isError = subjectLength > 200,
                        supportingText = { Text("$subjectLength / 200") }
                    )
                    Text("搜索方式", style = MaterialTheme.typography.titleSmall)
                    ResearchSaveChoice("豆包搜索 Custom（独立接口）", "使用搜索专用 Key 获取资料，再交给下方模型总结", searchMode == "direct", !locked) { searchMode = "direct" }
                    ResearchSaveChoice("火山方舟内置搜索", "由豆包模型调用标准搜索，只需模型平台 Key", searchMode == "native", !locked) { searchMode = "native" }
                    if (searchMode == "direct") {
                        Text(if (state.hasResearchSearchKey) "搜索专用 Key 已保存" else "请填写独立搜索服务的 Key。它与模型平台 Key 分开保存。", style = MaterialTheme.typography.bodySmall, color = if (state.hasResearchSearchKey) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        OutlinedTextField(
                            searchKey, { searchKey = it }, Modifier.fillMaxWidth(), enabled = !locked,
                            label = { Text("搜索专用 Key") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            placeholder = { Text(if (state.hasResearchSearchKey) "填入新 Key 可替换" else "填写豆包搜索 Custom 的 Key") }
                        )
                        TextButton(onClick = { onSaveSearchKey(searchKey.trim()); searchKey = "" }, enabled = !locked && searchKey.isNotBlank()) { Text("保存搜索 Key") }
                    }
                    HorizontalDivider()
                    Text(if (searchMode == "direct") "总结模型平台" else "搜索与总结模型平台", style = MaterialTheme.typography.titleSmall)
                    Box {
                        OutlinedButton(onClick = { providerMenu = true }, enabled = !locked && providers.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text(provider?.name ?: "尚未配置豆包 API", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Outlined.ArrowDropDown, "选择研究平台")
                        }
                        DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                            providers.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.name) }, onClick = { providerId = candidate.id; providerMenu = false }) }
                        }
                    }
                    TextButton(
                        onClick = {
                            val target = provider ?: ProviderConfig(name = "豆包·火山方舟", kind = "openai", baseUrl = DoubaoResearchClient.DEFAULT_BASE_URL)
                            providerId = target.id
                            onConfigure(target)
                        }, enabled = !locked
                    ) { Text(if (provider == null) "添加豆包 API 配置" else "编辑平台 / API Key") }
                    if (provider != null && !hasKey) Text("请先填写这个平台的火山方舟 API Key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(modelId, { modelId = it }, Modifier.fillMaxWidth(), label = { Text("豆包模型 ID / 推理接入点 ID") }, enabled = !locked, singleLine = true, isError = modelId.trim().length > 200)
                    if (!provider?.models.isNullOrEmpty()) {
                        Box {
                            TextButton(onClick = { modelMenu = true }, enabled = !locked) { Text("从该平台的模型列表选择") }
                            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                provider?.models?.forEach { model -> DropdownMenuItem(text = { Text(model.id) }, onClick = { modelId = model.id; modelMenu = false }) }
                            }
                        }
                    }
                    Text(if (searchMode == "direct") "在火山方舟开通下方总结模型并填写模型 Key。搜索使用上方独立 Key，无需为搜索添加平台。" else "需在火山方舟开通所选模型及标准联网搜索。搜索和总结使用这个平台的模型 Key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    Text("研究范围（至少一项）", style = MaterialTheme.typography.titleSmall)
                    researchFields.forEach { field ->
                        val checked = field.id in selected
                        Row(Modifier.fillMaxWidth().clickable(enabled = !locked) { selected = if (checked) selected - field.id else selected + field.id }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = null, enabled = !locked)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(field.label, style = MaterialTheme.typography.bodyMedium)
                                Text(field.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().clickable(enabled = !locked) { allowFiction = !allowFiction }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allowFiction, onCheckedChange = null, enabled = !locked)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("允许合理虚构补全")
                            Text("资料缺口可补为小说设定，明确标注虚构。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("保存位置", style = MaterialTheme.typography.titleSmall)
                    ResearchSaveChoice("会话知识库", "保留来源，写作时按需检索", saveToKnowledgeBase, !locked) { saveToKnowledgeBase = true }
                    ResearchSaveChoice("当前场景与连贯性", "直接参与下一轮上下文；空间不足会提示", !saveToKnowledgeBase, !locked) { saveToKnowledgeBase = false }
                }
            } else if (note != null) {
                Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                    TextButton(onClick = { noteId = null }) { Text("返回全部研究记录") }
                    ResearchNoteContent(note, Modifier.weight(1f))
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (notes.isEmpty()) item { Text("还没有保存到知识库的研究记录。完成研究后，可在这里查看全文和来源链接。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(notes, key = { it.id }) { item ->
                        OutlinedCard(onClick = { noteId = item.id }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.text.lineSequence().firstOrNull().orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(researchDate(item.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(item.text.lineSequence().drop(1).joinToString("\n"), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                Text("查看全文与来源", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (section == 0) Text("发送研究主题、选项与检索资料供模型总结；API 和搜索按平台计费，与豆包会员分开。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    if (section == 0) {
                        OutlinedButton(
                            onClick = { onWebStart(subject.trim(), researchFields.filter { it.id in selected }.map { it.id }, allowFiction) },
                            enabled = !locked && subjectLength <= 200 && selected.isNotEmpty()
                        ) { Text("我的豆包网页") }
                        Button(onClick = { onStart(subject.trim(), researchFields.filter { it.id in selected }.map { it.id }, allowFiction, saveToKnowledgeBase, provider!!.id, modelId.trim(), searchMode) }, enabled = ready) { Text("开始研究") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResearchSaveChoice(title: String, description: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResearchNoteContent(note: KnowledgeNote, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    var linkError by remember(note.id) { mutableStateOf("") }
    val links = remember(note.text) {
        Regex("https://[^\\s<>\\\"]+").findAll(note.text).map { it.value.trimEnd(')', '）', ']', '】', ',', '，', '。', ';', '；') }
            .filter { url -> runCatching { val uri = URI(url); uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null }.getOrDefault(false) }.distinct().toList()
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(researchDate(note.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(note.text, style = MaterialTheme.typography.bodyLarge) }
        if (links.isNotEmpty()) {
            HorizontalDivider()
            Text("打开来源", style = MaterialTheme.typography.titleMedium)
            links.forEach { url ->
                OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) }.onFailure { linkError = "无法打开链接，可长按正文复制地址。" } }, modifier = Modifier.fillMaxWidth()) {
                    Text(url, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
                }
            }
        }
        if (linkError.isNotBlank()) Text(linkError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
    }
}

private fun researchDate(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
