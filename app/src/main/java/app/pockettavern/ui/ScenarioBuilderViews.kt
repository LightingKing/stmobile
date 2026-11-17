package app.pockettavern.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.Chat
import app.pockettavern.domain.ScenarioDraft
import app.pockettavern.domain.ScenarioDraftEntry
import app.pockettavern.domain.UiSession
import app.pockettavern.engine.ScenarioBuilder
import app.pockettavern.engine.ToolRuntime

/** Models that can call tools, as (provider id, model id, label); the 剧本助手 saves its draft with tools. */
internal fun scenarioModelChoices(state: UiSession): List<Triple<String, String, String>> =
    state.data.providers.filter { it.id in state.hasKeys }.flatMap { provider ->
        provider.models.filter { it.type == "text" && !it.offline && it.supportsTools }.map { Triple(provider.id, it.id, "${it.name.ifBlank { it.id }} · ${provider.name}") }
    }

/** The open chat's model when it can call tools, else the PC's main model, else the first that can. */
internal fun defaultScenarioModel(state: UiSession, choices: List<Triple<String, String, String>>): Pair<String, String>? {
    val chat = state.data.chats.firstOrNull { it.id == state.selectedChatId }
    choices.firstOrNull { it.first == chat?.providerId && it.second == chat.modelId }?.let { return it.first to it.second }
    choices.firstOrNull { it.second == "pocket-direct" }?.let { return it.first to it.second }
    return choices.firstOrNull()?.let { it.first to it.second }
}

/** Before a 剧本助手 (or, with [world], 世界助手) chat opens: which model, which Skills, and an idea to start from. */
@Composable
internal fun ScenarioBuilderStartDialog(state: UiSession, actions: AppActions, onDismiss: () -> Unit, onStarted: () -> Unit = {}, world: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val choices = remember(state.data.providers, state.hasKeys) { scenarioModelChoices(state) }
    var model by remember { mutableStateOf(defaultScenarioModel(state, choices)) }
    var skillIds by remember { mutableStateOf(ToolRuntime.skillsInOrder(state.data.skills, state.data.settings.scenarioSkillIds.ifEmpty { ScenarioBuilder.DEFAULT_BUILDER_SKILL_IDS }).map { it.id }) }
    var idea by rememberSaveable { mutableStateOf("") }
    var modelsOpen by remember { mutableStateOf(false) }
    var skillsOpen by remember { mutableStateOf(false) }
    val skills = remember(state.data.skills, skillIds) { ToolRuntime.skillsInOrder(state.data.skills, skillIds) }
    val hasPc = state.data.providers.any { it.kind.equals("writing-server", ignoreCase = true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (world) "世界助手" else "剧本助手") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (world) "单独开一个聊天，和助手一起定世界、你的身份和最多 8 位居民（性格、作息、目标、关系）。定下来的内容记在“世界草稿”里，说可以了就在电脑上建成小世界（先暂停，不花钱）。"
                else "单独开一个聊天，和助手一起定世界观、人物和开场。定下来的内容记在“剧本草稿”里，生成剧本后再开始正式聊天。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (world && !hasPc) Text("小世界在电脑写作服务上运行：可以先聊，建成前要在模型页添加“本机写作服务”平台。", style = MaterialTheme.typography.bodySmall, color = colors.error)
            Text("模型", style = MaterialTheme.typography.labelLarge)
            if (choices.isEmpty()) Text("需要一个支持工具调用的模型：先在“模型”页添加平台并刷新。", style = MaterialTheme.typography.bodySmall, color = colors.error)
            else Column {
                val label = choices.firstOrNull { it.first == model?.first && it.second == model?.second }?.third ?: "选择模型"
                TextButton(onClick = { modelsOpen = true }) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Outlined.ExpandMore, "换一个模型", Modifier.size(18.dp))
                }
                DropdownMenu(modelsOpen, { modelsOpen = false }) {
                    choices.take(60).forEach { (providerId, modelId, text) ->
                        DropdownMenuItem(text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { model = providerId to modelId; modelsOpen = false })
                    }
                }
                Text("只列出能调用工具的模型。用电脑写作服务时，助手还能联网找资料、看视频。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = "选择剧本助手用的 Skills", role = Role.Button) { skillsOpen = true }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (skills.isEmpty()) "使用 Skills" else "使用 Skills（${skills.size}）", style = MaterialTheme.typography.labelLarge)
                    Text(if (skills.isEmpty()) "可以选世界观、人物、剧情相关的 Skill；之后在会话设置里也能改。" else skills.joinToString("、") { it.name.ifBlank { "未命名 Skill" } },
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.ExpandMore, null)
            }
            OutlinedTextField(idea, { idea = it.take(2000) }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("想法（可以不写）") },
                placeholder = { Text(if (world) "例如：海边小镇的民宿，我是新来的帮工，想和老板娘、她女儿和两个长住的客人一起生活"
                    else "例如：民国上海的悬疑故事，我是刚到任的探长，想要几个立场不同的嫌疑人") })
        }
    }, confirmButton = {
        Button(onClick = { model?.let { (providerId, modelId) ->
            val started = if (world) actions.startWorldBuilder(providerId, modelId, skillIds, idea) else actions.startScenarioBuilder(providerId, modelId, skillIds, idea)
            if (started) { onDismiss(); onStarted() } } },
            enabled = model != null && !state.loading) { Text("开始") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    if (skillsOpen) SkillPickDialog(state, actions, title = if (world) "世界助手用的 Skills" else "剧本助手用的 Skills",
        note = "所选 Skill 会加入这个剧本助手聊天，和普通聊天一样使用；带参考文件的，模型可以按需读取。",
        scope = "剧本助手", scenario = SkillScenario.NOVEL, initial = skillIds, warnAbove = 30_000,
        onSave = { skillIds = it }, onDismiss = { skillsOpen = false })
}

/** Under a 剧本助手 chat's title: the draft, and the play button once the scenario is built. */
@Composable
internal fun ScenarioBuilderChips(chat: Chat, state: UiSession, actions: AppActions, onDraft: () -> Unit) {
    val draft = chat.scenarioDraft
    val world = draft?.worldMode == true
    val built = if (world) draft?.worldId?.takeIf { it.isNotBlank() } else draft?.scenarioId?.takeIf { id -> state.data.characters.any { it.id == id } }
    val busy = state.chatBusy(chat.id)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (world) "世界助手" else "剧本助手", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val count = (draft?.world?.size ?: 0) + (draft?.people?.size ?: 0)
        val label = if (world) "世界草稿" else "剧本草稿"
        AssistChip(onClick = onDraft, label = { Text(if (draft == null || draft.isEmpty) label else "$label · $count 条") },
            leadingIcon = { Icon(Icons.Outlined.AutoStories, null, Modifier.size(16.dp)) })
        if (built != null) AssistChip(onClick = { actions.startScenarioPlay(chat.id) }, enabled = !busy, label = { Text(if (world) "打开小世界" else "开始正式聊天") },
            leadingIcon = { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp)) })
    }
}

/** The 剧本助手's draft: what has been settled so far, with building and playing. */
@Composable
internal fun ScenarioDraftDialog(chat: Chat, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val draft = chat.scenarioDraft ?: ScenarioDraft()
    if (draft.worldMode) return WorldDraftDialog(chat, draft, state, actions, onDismiss)
    val built = state.data.characters.firstOrNull { it.id == draft.scenarioId }
    val missing = ScenarioBuilder.missing(draft)
    val busy = state.chatBusy(chat.id)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("剧本草稿") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(when {
                built == null && draft.isEmpty -> "还是空的。和助手聊，它每定下一部分就会写到这里。"
                built == null -> "还没有生成剧本。"
                draft.changedSinceBuilt -> "已生成《${built.name}》，之后的改动还没有更新进去。"
                else -> "已生成《${built.name}》，在“角色”页的“剧本”里。"
            }, style = MaterialTheme.typography.bodySmall, color = if (built != null && !draft.changedSinceBuilt) colors.primary else colors.onSurfaceVariant)
            if (!draft.isEmpty) {
                DraftSection("概要")
                DraftField("剧本名", draft.title)
                DraftField("简介", draft.summary)
                DraftField("你扮演", draft.userRole)
                DraftField("核心设定", draft.setting, collapsed = true)
                DraftSection("世界观（${draft.world.size} 条）")
                if (draft.world.isEmpty()) DraftEmpty()
                draft.world.forEach { entry -> DraftEntryRow(entry, enabled = !busy) { actions.removeScenarioDraftEntry(chat.id, "world", entry.name) } }
                DraftSection("人物（${draft.people.size} 个）")
                if (draft.people.isEmpty()) DraftEmpty()
                draft.people.forEach { entry -> DraftEntryRow(entry, enabled = !busy) { actions.removeScenarioDraftEntry(chat.id, "people", entry.name) } }
                DraftSection("开场")
                DraftField("第一条消息", draft.opening, collapsed = true)
                DraftField("叙事规则", draft.rules, collapsed = true)
            }
            if (missing.isNotEmpty() && !draft.isEmpty) Text("生成前还缺：${missing.joinToString("、")}", style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
    }, confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (built != null) TextButton(onClick = { actions.startScenarioPlay(chat.id); onDismiss() }, enabled = !busy) { Text("开始正式聊天") }
            Button(onClick = { actions.finishScenario(chat.id) }, enabled = missing.isEmpty() && !busy && (built == null || draft.changedSinceBuilt)) {
                Text(if (built == null) "生成剧本" else "更新剧本")
            }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

/** The 世界助手's draft: the world, the user's place in it, its residents; building and opening the world. */
@Composable
private fun WorldDraftDialog(chat: Chat, draft: ScenarioDraft, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val missing = ScenarioBuilder.missing(draft)
    val busy = state.chatBusy(chat.id)
    val built = draft.worldId.isNotBlank()
    var startPaused by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("世界草稿") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(when {
                !built && draft.isEmpty -> "还是空的。和助手聊，它每定下一部分就会写到这里。"
                !built -> "还没有建成小世界。"
                draft.changedSinceBuilt -> "已建成小世界，之后的改动还没有更新进去。更新时居民按名字对应，改了名字的会当作新居民。"
                else -> "已建成小世界，和草稿一致。"
            }, style = MaterialTheme.typography.bodySmall, color = if (built && !draft.changedSinceBuilt) colors.primary else colors.onSurfaceVariant)
            if (!draft.isEmpty) {
                DraftSection("概要")
                DraftField("世界名", draft.title)
                DraftField("简介", draft.summary)
                DraftField("你在世界里的身份", draft.userRole)
                DraftField("居民怎么称呼你", draft.userName)
                DraftField("群名", draft.groupName)
                DraftField("核心设定", draft.setting, collapsed = true)
                DraftSection("居民（${draft.people.size}/${app.pockettavern.domain.WorldLimits.RESIDENTS} 位）")
                if (draft.people.isEmpty()) DraftEmpty()
                draft.people.forEach { entry -> DraftEntryRow(entry, enabled = !busy) { actions.removeScenarioDraftEntry(chat.id, "people", entry.name) } }
                DraftSection("世界观（${draft.world.size} 条）")
                if (draft.world.isEmpty()) DraftEmpty()
                draft.world.forEach { entry -> DraftEntryRow(entry, enabled = !busy) { actions.removeScenarioDraftEntry(chat.id, "world", entry.name) } }
                DraftSection("其他")
                DraftField("叙事规则", draft.rules, collapsed = true)
                DraftField("世界开始时的第一件事（可不写）", draft.opening, collapsed = true)
            }
            if (missing.isNotEmpty() && !draft.isEmpty) Text("建成前还缺：${missing.joinToString("、")}", style = MaterialTheme.typography.bodySmall, color = colors.error)
            if (!built && missing.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("建好后先暂停", style = MaterialTheme.typography.bodyMedium)
                    Text("看过设定再按 ▶，之前不花钱", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                Switch(startPaused, { startPaused = it })
            }
        }
    }, confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (built) TextButton(onClick = { actions.startScenarioPlay(chat.id); onDismiss() }, enabled = !busy) { Text("打开小世界") }
            Button(onClick = { actions.finishWorld(chat.id, startPaused); onDismiss() }, enabled = missing.isEmpty() && !busy && (!built || draft.changedSinceBuilt)) {
                Text(if (built) "更新小世界" else "建成小世界")
            }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun DraftSection(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun DraftEmpty() = Text("还没有。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun DraftField(label: String, value: String, collapsed: Boolean = false) {
    var open by remember { mutableStateOf(!collapsed) }
    Column(Modifier.fillMaxWidth().clickable(enabled = collapsed && value.isNotBlank()) { open = !open }) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "（未写）" }, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DraftEntryRow(entry: ScenarioDraftEntry, enabled: Boolean, onRemove: () -> Unit) {
    var open by remember(entry.name) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).clickable { open = !open }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (entry.always) { Spacer(Modifier.width(6.dp)); Text("常驻", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            }
            if (entry.keywords.isNotEmpty()) Text("关键词：" + entry.keywords.joinToString("、"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(entry.content, style = MaterialTheme.typography.bodySmall, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Outlined.DeleteOutline, "从草稿删掉“${entry.name}”") }
    }
}
