package app.pockettavern.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.ContextBudgetBreakdown
import app.pockettavern.domain.ContextInspection
import app.pockettavern.domain.Memory
import app.pockettavern.engine.LongMemory

internal data class OverviewRow(val label: String, val tokens: Int)

/** The wording of the memory page's overview. Pure, so it is tested without a screen. */
internal object MemoryOverviewModel {
    /** Where the next request's tokens go, largest share first; empty parts are left out. */
    fun rows(value: ContextBudgetBreakdown): List<OverviewRow> = listOf(
        OverviewRow("Skills", value.skillTokens),
        OverviewRow("角色卡与预设", value.characterTokens + value.presetTokens),
        OverviewRow("人物档案", value.profileTokens),
        OverviewRow("章节摘要", value.chapterTokens),
        OverviewRow("续聊摘要", value.summaryTokens),
        OverviewRow("置顶事实与世界书", value.memoryTokens + value.worldTokens),
        OverviewRow(if (value.recentTurns > 0) "最近原文（${value.recentTurns} 轮）" else "最近原文", value.recentTokens),
        OverviewRow("工具说明", value.toolTokens),
        OverviewRow("其他规则", value.policyTokens)
    ).filter { it.tokens > 0 }.sortedByDescending { it.tokens }

    fun layers(value: ContextInspection): String = buildList {
        add(if (value.chapters > 0) "已整理 ${value.chapters} 章" else "还没有章节")
        if (value.profiles.isNotEmpty()) add("${value.profiles.size} 份人物档案")
        add("置顶事实 ${value.pinned} 条")
    }.joinToString(" · ")

    /** Turns waiting for the user to start a fold, or null when nothing waits on them. */
    fun backlog(value: ContextInspection): Int? =
        value.breakdown.foldTurns.takeIf { value.breakdown.triggerReason == "HISTORY_BACKLOG" && it > 0 }

    fun backlogText(turns: Int): String =
        "还有 $turns 轮没整理成章节。整理后每条消息发的内容会小很多，原文一字不动；需要调用模型约 ${LongMemory.foldRequests(turns)} 次。"

    /** What happens next on its own, for the overview card. */
    fun next(value: ContextBudgetBreakdown): String = when (value.triggerReason) {
        "HISTORY_LENGTH" -> "下一条消息前，会先把较早的 ${value.foldTurns} 轮整理成章节。"
        "HISTORY_BACKLOG" -> ""
        "INPUT_LIMIT_REACHED", "MODEL_BUDGET_REACHED", "INPUT_LIMIT_THRESHOLD", "MODEL_THRESHOLD" ->
            if (value.blockedReason.isBlank()) "已接近输入上限，下一条消息前会先压缩较早的历史。" else "已接近输入上限，但压缩历史帮不上忙：固定内容（多为 Skills）太多。"
        else -> "最近的对话以原文发送；更早的历史每满 ${LongMemory.CHAPTER_TURNS} 轮自动整理成一章。"
    }
}

@Composable
internal fun MemoryOverviewCard(value: ContextInspection?, locked: Boolean, onOrganize: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (value == null) {
                Text("正在计算每条消息发送的内容…", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                return@Column
            }
            val breakdown = value.breakdown
            Text("每条消息约发送 ${formatTokens(breakdown.estimatedInputTokens)} tokens", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(MemoryOverviewModel.layers(value), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            val rows = MemoryOverviewModel.rows(breakdown)
            val total = rows.sumOf { it.tokens }.coerceAtLeast(1)
            rows.forEach { row ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(formatTokens(row.tokens), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(row.tokens.toFloat() / total).height(4.dp).background(colors.primary))
                    }
                }
            }
            if (value.profiles.isNotEmpty()) Text("人物档案：${value.profiles.joinToString("、")}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            MemoryOverviewModel.next(breakdown).takeIf(String::isNotEmpty)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            MemoryOverviewModel.backlog(value)?.let { turns ->
                Text(MemoryOverviewModel.backlogText(turns), style = MaterialTheme.typography.bodySmall, color = colors.primary)
                Button(onClick = onOrganize, enabled = !locked, modifier = Modifier.fillMaxWidth()) { Text("整理记忆") }
            }
        }
    }
}

/** The facts the user pins: sent with every message, never folded away. */
@Composable
internal fun PinnedFactsCard(facts: List<Memory>, locked: Boolean, onSave: (Memory?, String) -> Unit, onDelete: (Memory) -> Unit) {
    var editing by remember { mutableStateOf<Memory?>(null) }
    var adding by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("置顶事实", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("你认为绝不能错的事。每条消息都会带上，不会被整理掉。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                IconButton(onClick = { adding = true }, enabled = !locked) { Icon(Icons.Outlined.Add, "添加置顶事实") }
            }
            if (facts.isEmpty()) Text("还没有。例如：“林岚左手有旧伤，不能提重物”“庄园地下室的钥匙在管家手里”。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            facts.forEach { fact ->
                Surface(shape = RoundedCornerShape(10.dp), color = colors.surfaceVariant.copy(alpha = 0.5f)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text((if (fact.enabled) "" else "（已停用）") + fact.text, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { editing = fact }, enabled = !locked) { Icon(Icons.Outlined.Edit, "编辑", Modifier.size(18.dp)) }
                        IconButton(onClick = { onDelete(fact) }, enabled = !locked) { Icon(Icons.Outlined.DeleteOutline, "删除", Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
    if (adding || editing != null) PinnedFactDialog(editing?.text.orEmpty(), onDismiss = { adding = false; editing = null }) { text ->
        onSave(editing, text); adding = false; editing = null
    }
}

@Composable
private fun PinnedFactDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.isEmpty()) "添加置顶事实" else "编辑置顶事实") },
        text = { OutlinedTextField(text, { text = it.take(8000) }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("一条事实") }) },
        confirmButton = { Button(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** Shown in a chat whose unfolded history is too long to fold without asking. */
@Composable
internal fun MemoryBacklogBanner(turns: Int, onOrganize: () -> Unit, onLater: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), color = colors.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(MemoryOverviewModel.backlogText(turns), style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onLater) { Text("以后再说") }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = onOrganize) { Text("整理记忆") }
            }
        }
    }
}
