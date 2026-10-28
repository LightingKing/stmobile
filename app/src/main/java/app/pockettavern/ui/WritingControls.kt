package app.pockettavern.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.Chat
import org.json.JSONObject

@Composable
internal fun WritingIntentSelector(intent: String, onSelect: (String) -> Unit, enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "对话", "correction" to "纠错", "rewrite" to "重写").forEach { (value, label) ->
                FilterChip(selected = intent == value, onClick = { onSelect(value) }, enabled = enabled, label = { Text(label) })
            }
        }
        val hint = when (intent) {
            "correction" -> "只纠正事实与写法，不推进剧情；发送后回到对话。"
            "rewrite" -> "重新写上一稿，旧稿保留但不再发送；发送后回到对话。"
            else -> ""
        }
        if (hint.isNotBlank()) Text(hint, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun WritingIntentLabel(intent: String) {
    val label = when (intent) {
        "correction" -> "纠错 · 不推进剧情"
        "rewrite" -> "重写"
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
internal fun ExcludedMessageNotice(assistant: Boolean, expanded: Boolean, canRestoreIndividually: Boolean = true, onExpand: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(if (assistant) "废稿 · 已排除后续上下文" else "已排除后续上下文", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (canRestoreIndividually) "原文仍在，可在消息菜单恢复。" else "原文仍在，随对应的会话消息一起恢复。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onExpand, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "收起原文" else "展开原文", style = MaterialTheme.typography.labelMedium)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
        }
    }
}

@Composable
internal fun WritingQualityNotice(text: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("重写提醒", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text("请先检查内容；应用不会自动发起新的模型请求。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
internal fun ContinuityNotesDialog(chat: Chat, busy: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(chat.id, chat.continuityNotes) { mutableStateOf(chat.continuityNotes) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("当前场景与连贯性") }, text = {
        ContinuityNotesEditor(text, { text = it }, enabled = !busy)
    }, confirmButton = {
        Button(onClick = { onSave(text); onDismiss() }, enabled = !busy && text.length <= 8000) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ContinuityNotesEditor(text: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("可选记录现在的时间、地点、在场人物及各自知道什么、当前状态和待解决线索。保存后会随之后的对话发送。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = text, onValueChange = onChange, enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 7, maxLines = 12,
            label = { Text("当前事实与边界") },
            placeholder = { Text("时间 / 地点：\n在场人物：\n各自已知与未知：\n当前状态：\n待解决线索：") },
            isError = text.length > 8000,
            supportingText = { Text(if (text.length > 8000) "${text.length} / 8000 字符，请缩短后保存" else "${text.length} / 8000 字符") })
        Text("记录已确认的最新状态；故事变化后可以随时更新。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SkillSnapshotDialog(snapshotJson: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("本轮 Skills") }, text = {
        SkillSnapshotContent(snapshotJson)
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
internal fun SkillSnapshotContent(snapshotJson: String) {
    val manifest = remember(snapshotJson) { runCatching { JSONObject(snapshotJson) }.getOrNull() }
    val skills = manifest?.optJSONArray("skills")
    Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (skills == null) {
            Text("这条回复的 Skills 记录无法读取。可在会话设置预览当前上下文。", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("App 记录 · 发送时共 ${skills.length()} 个", style = MaterialTheme.typography.titleSmall)
            Text("这是本轮请求实际注入的清单。正文已注入不代表技能已正确执行；引用是否读到，要看对应的成功工具结果。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (skills.length() == 0) Text("本轮没有启用 Skills。", style = MaterialTheme.typography.bodyMedium)
            for (index in 0 until skills.length()) {
                val skill = skills.optJSONObject(index) ?: continue
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${skill.optInt("order", index + 1)}. ${skill.optString("name").ifBlank { "未命名 Skill" }}", style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(skill.optString("id"), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        val refs = skill.optInt("referenceFileCount")
                        Text((if (skill.optBoolean("bodyInjected")) "正文已注入" else "正文未注入") + when {
                            refs == 0 -> " · 无引用文件"
                            skill.optBoolean("referencesCanBeRead") -> " · $refs 个引用可按需读取"
                            else -> " · $refs 个引用，本轮不可调用读取"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("此处只查看当时的记录。调整启用与顺序，请打开会话设置。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
