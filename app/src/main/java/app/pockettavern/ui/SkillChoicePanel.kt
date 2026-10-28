package app.pockettavern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.SkillRecord

/** Only changes how the catalog is presented; never changes enabled or selected state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillChoicePanel(
    guided: Boolean,
    onGuidedChange: (Boolean) -> Unit,
    scenario: SkillScenario,
    onScenarioChange: (SkillScenario) -> Unit,
    modifier: Modifier = Modifier
) {
    var scenariosOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = guided, onClick = { onGuidedChange(true) }, label = { Text("帮我选") })
            FilterChip(selected = !guided, onClick = { onGuidedChange(false) }, label = { Text("全部技能") })
        }
        if (guided) {
            Box {
                OutlinedButton(onClick = { scenariosOpen = true }) { Text("这次想：${scenario.label} ▾") }
                DropdownMenu(expanded = scenariosOpen, onDismissRequest = { scenariosOpen = false }) {
                    SkillScenario.entries.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice.label) }, onClick = { onScenarioChange(choice); scenariosOpen = false })
                    }
                }
            }
            Text(scenario.description, style = MaterialTheme.typography.bodyMedium)
            Text("先试 1 个首选；备选和首选选一个，辅助按需加。切换场景不会改变勾选。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("按简介和用途整理，未做模型效果实测。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SkillSelectedSummary(
    skills: List<SkillRecord>,
    selectedIds: Set<String>,
    onShowSelected: () -> Unit,
    modifier: Modifier = Modifier,
    pendingEnableIds: Set<String> = emptySet()
) {
    val selected = skills.filter { it.id in selectedIds }
    val disabled = selected.count { !it.enabled && it.id !in pendingEnableIds }
    val missing = selectedIds.size - selected.size
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("本会话已选 ${selectedIds.size} 项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                selected.joinToString("、") { it.name.ifBlank { "未命名技能" } }.ifBlank { "还没选：从下面的首选开始即可。" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (disabled > 0) Text("其中 $disabled 项总开关关闭，暂不生效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (pendingEnableIds.isNotEmpty()) Text("新勾选 ${pendingEnableIds.size} 项，保存后启用并加入本会话。", style = MaterialTheme.typography.bodySmall)
            if (missing > 0) Text("另有 $missing 项已不在技能库中。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (selectedIds.isNotEmpty()) TextButton(onClick = onShowSelected) { Text("查看已选 / 调整勾选") }
        }
    }
}

@Composable
internal fun SkillSuggestionLabel(suggestion: SkillSuggestion) {
    Text(suggestion.role.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Text(suggestion.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SkillChoiceExplanation(skill: SkillRecord, showEffect: Boolean = true) {
    val hint = skillChoiceHint(skill)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showEffect) Text(skillGuide(skill.id)?.effect ?: skill.description.ifBlank { "尚无用途说明，可先查看详情。" }, style = MaterialTheme.typography.bodyMedium)
        Text("什么时候选：${hint.whenToUse}", style = MaterialTheme.typography.bodySmall)
        Text("怎么搭配：${hint.pairing}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
