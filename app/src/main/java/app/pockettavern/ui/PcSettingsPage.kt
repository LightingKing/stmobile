@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pockettavern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.PcLinkState
import app.pockettavern.domain.PcSettings

private val Efforts = listOf("default" to "默认", "none" to "关闭", "low" to "低", "medium" to "中", "high" to "高")
private val ReviewSkills = listOf("none" to "不用", "ss-stop-slop" to "stop-slop 去 AI 味")
private val Waits = listOf(300 to "5 分钟", 900 to "15 分钟", 1800 to "30 分钟", 3600 to "60 分钟", 0 to "不限")
private val Keeps = listOf(10, 20, 50, 100)

/** A model's one-line facts for pickers: id, context and what it can do. */
private fun modelFacts(model: ModelInfo): String = listOfNotNull(
    model.id,
    "${model.contextTokens / 1000}k 上下文",
    "思考".takeIf { model.supportsReasoning },
    "工具".takeIf { model.supportsTools },
    "识图".takeIf { model.supportsVision },
    "暂不可用".takeIf { model.offline }
).joinToString(" · ")

/** Settings → 电脑连接 → 电脑设置: the PC writing server's own settings, saved as a diff. */
@Composable
internal fun PcSettingsDialog(
    link: PcLinkState,
    models: List<ModelInfo>,
    onRetry: () -> Unit,
    onRefreshModels: () -> Unit,
    onSave: (PcSettings, PcSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val original = link.settings
    var edited by remember(original) { mutableStateOf(original) }
    var choosing by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("电脑设置") }, text = {
        val current = edited
        when {
            original == null && link.busy == "settings" -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在读取电脑设置…")
            }
            original == null || current == null -> Column {
                Text(link.error.ifBlank { "还没有读取到电脑设置。" },
                    color = if (link.error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onRetry, enabled = link.busy.isBlank()) { Text("重新读取") }
            }
            else -> Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                PcSettingsForm(current, models, onChange = { edited = it }, onChooseModel = { choosing = true }, onRefreshModels = onRefreshModels)
            }
        }
    }, confirmButton = {
        val changed = original != null && edited != null && edited!!.changesFrom(original).isNotEmpty()
        Button({ onSave(original!!, edited!!) }, enabled = changed && link.busy.isBlank()) { Text("保存") }
    }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
    val current = edited
    if (choosing && current != null) {
        PcModelChooser(models, current.upstreamModel, onChoose = { edited = current.copy(upstreamModel = it); choosing = false }) { choosing = false }
    }
}

@Composable
private fun SettingLabel(text: String) = Text(text, fontWeight = FontWeight.SemiBold)

@Composable
private fun SettingHint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun <T> SettingChips(options: List<Pair<T, String>>, selected: T, other: (T) -> String = { it.toString() }, onSelect: (T) -> Unit) {
    // A value set on the PC by hand still shows, even when it is not one of the usual choices.
    val shown = if (options.any { it.first == selected }) options else options + (selected to other(selected))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { (value, label) -> FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) }) }
    }
}

@Composable
internal fun PcSettingsForm(settings: PcSettings, models: List<ModelInfo>, onChange: (PcSettings) -> Unit, onChooseModel: () -> Unit, onRefreshModels: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingLabel("默认模型")
        val current = models.firstOrNull { it.id == settings.upstreamModel }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(current?.name ?: settings.upstreamModel)
                Text(current?.let(::modelFacts) ?: "不在当前模型目录里", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onChooseModel, enabled = models.isNotEmpty()) { Text("更换") }
        }
        if (models.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            SettingHint("模型目录里还没有电脑提供的模型。")
            TextButton(onRefreshModels) { Text("刷新模型目录") }
        }
        SettingHint("“默认模型”两个入口用它。在模型页直接选某个具体模型时，用的是你选的那个。")
        SettingLabel("思考强度")
        SettingChips(Efforts, settings.reasoningEffort) { onChange(settings.copy(reasoningEffort = it)) }
        SettingHint("“默认”表示不向上游传思考参数，由模型自己决定。不支持思考的模型会自动忽略这一项。")
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SettingLabel("审校建议模式")
        SettingChips(ReviewSkills, settings.reviewSkill) { onChange(settings.copy(reviewSkill = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("按审校意见自动改正文（试验）", Modifier.weight(1f))
            Switch(settings.autoRevise, { onChange(settings.copy(autoRevise = it)) })
        }
        SettingHint("只影响“默认模型 + 审校建议”入口。自动改正文每轮最多多花两次请求，之前实测有误判，建议保持关闭。")
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        if (settings.upstreamHost.isBlank() || settings.upstreamHost.equals("api.venice.ai", ignoreCase = true)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("附带 Venice 自带的系统提示", Modifier.weight(1f))
                Switch(settings.includeVeniceSystemPrompt, { onChange(settings.copy(includeVeniceSystemPrompt = it)) })
            }
            SettingHint("打开后，Venice 会在你的预设之前加上它的通用系统提示。写作和角色扮演时一般关闭。")
        }
        SettingLabel("上游最长等待")
        SettingChips(Waits, settings.upstreamIdleTimeoutSec, other = { if (it % 60 == 0) "${it / 60} 分钟" else "$it 秒" }) { onChange(settings.copy(upstreamIdleTimeoutSec = it)) }
        SettingHint("上游连续这么久没有任何输出才放弃本次请求。上下文很长时，开始输出前可能要等几分钟。")
        SettingLabel("电脑保留备份")
        SettingChips(Keeps.map { it to "$it 份" }, settings.backupKeep, other = { "$it 份" }) { onChange(settings.copy(backupKeep = it)) }
        SettingHint("上游地址${settings.upstreamHost.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}和 Venice Key 只能在电脑上修改。")
    }
}

@Composable
private fun PcModelChooser(models: List<ModelInfo>, selected: String, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = models.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择默认模型") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索模型") })
            PcModelList(shown, selected, onChoose)
        }
    }, confirmButton = { TextButton(onDismiss) { Text("取消") } })
}

@Composable
internal fun PcModelList(models: List<ModelInfo>, selected: String, onChoose: (String) -> Unit) {
    LazyColumn(Modifier.heightIn(max = 400.dp)) {
        items(models, key = { it.id }) { model ->
            Row(Modifier.fillMaxWidth().selectable(model.id == selected, role = Role.RadioButton) { onChoose(model.id) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(model.id == selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(model.name)
                    Text(modelFacts(model), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
