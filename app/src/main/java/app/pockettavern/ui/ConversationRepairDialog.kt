package app.pockettavern.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.*
import app.pockettavern.engine.ConversationRepair
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ConversationRepairDialog(chat: Chat, busy: Boolean, actions: AppActions, onDismiss: () -> Unit) {
    val candidates = remember(chat.id, chat.messages) { ConversationRepair.candidates(chat) }
    val candidateById = remember(candidates) { candidates.associateBy { it.messageId } }
    val recommendedIds = remember(chat.messages, candidates) {
        ConversationRepair.expandSelection(chat, candidates.filter { it.recommended }.map { it.messageId }.toSet())
    }
    var selectedIds by remember(chat.id) { mutableStateOf(recommendedIds) }
    var showAll by remember(chat.id) { mutableStateOf(false) }
    var query by remember(chat.id) { mutableStateOf("") }
    val visibleMessages = remember(chat.messages, showAll, query, candidateById) {
        chat.messages.mapIndexed { index, message -> index to message }
            .filter { (_, message) ->
                (showAll || message.id in candidateById) && (query.isBlank() ||
                    message.content.contains(query, ignoreCase = true) || message.reasoning.contains(query, ignoreCase = true) ||
                    message.role.contains(query, ignoreCase = true) || (candidateById[message.id]?.reason?.contains(query, ignoreCase = true) == true))
            }
    }
    fun selectMessage(messageId: String, selected: Boolean) {
        val group = ConversationRepair.expandSelection(chat, setOf(messageId))
        selectedIds = if (selected) selectedIds + group else selectedIds - group
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修复当前会话") },
        text = {
            Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("先预览再清理。完整会话会先备份到本机私有目录，不创建新会话，清理后会话 ID 保持不变。", style = MaterialTheme.typography.bodySmall)
                Text("自动候选只包含工具记录、没有正文的思考记录、紧邻的完全重复回复。空回复错误诊断默认保留。已执行的文件或 API 工具操作不会因隐藏对话记录而回滚。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("工具调用和对应结果会作为一组联动勾选，避免清理后留下无法继续使用的半组记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !showAll, onClick = { showAll = false }, label = { Text("可识别杂项 · ${candidates.size}") })
                    FilterChip(selected = showAll, onClick = { showAll = true }, label = { Text("全部消息 · ${chat.messages.size}") })
                }
                if (showAll) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索对话内容或角色") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已勾选 ${selectedIds.size} 条删除项", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { selectedIds = recommendedIds }) {
                        Icon(Icons.Outlined.SelectAll, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("重置为建议项")
                    }
                }
                if (visibleMessages.isEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                        Text(if (showAll) "没有匹配的消息。" else "没有识别到明显的内部杂项；可切到“全部消息”手动选择争论废文。",
                            Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(visibleMessages, key = { it.second.id }) { (index, message) ->
                            val candidate = candidateById[message.id]
                            val checked = message.id in selectedIds
                            val label = when (message.role) { "user" -> "用户"; "assistant" -> "助手"; "tool" -> "工具"; "system" -> "系统"; else -> message.role }
                            Surface(color = if (checked) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.38f) else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp), onClick = {
                                    selectMessage(message.id, !checked)
                                }) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
                                    Checkbox(checked, onCheckedChange = { value -> selectMessage(message.id, value) })
                                    Column(Modifier.weight(1f).padding(top = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("#${index + 1} · $label", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                            Text(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(message.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        candidate?.let { Text(it.reason, style = MaterialTheme.typography.labelSmall, color = if (it.recommended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary) }
                                        val preview = message.content.ifBlank {
                                            if (message.reasoning.isNotBlank()) "（仅含思考过程）" else message.errorMessage.ifBlank { "（空消息）" }
                                        }
                                        Text(preview.take(500), style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
                                        if (message.content.length > 500) Text("预览截断，原文不会被改写。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                Text("清理后会把现有摘要和知识笔记重新绑定到保留的消息，让模型从当前剧情继续。若删除的正文已经被旧摘要或知识笔记提到，可在会话设置中“从全部原文重建记忆”；选错时可从菜单恢复修复前历史。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { actions.repairMessages(chat.id, selectedIds); onDismiss() }, enabled = !busy && selectedIds.isNotEmpty()) {
                Icon(Icons.Outlined.CleaningServices, null); Spacer(Modifier.width(6.dp)); Text("备份、清理并继续 · ${selectedIds.size} 条")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
