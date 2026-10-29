package app.pockettavern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.SkillRecord

private val SkillFiltersSaver = listSaver<SkillFilters, String>(
    save = { listOf(it.query, it.use.name, it.content.name, it.status.name, it.scope.name) },
    restore = {
        SkillFilters(
            query = it[0],
            use = SkillUse.valueOf(it[1]),
            content = SkillContentFilter.valueOf(it[2]),
            status = SkillStatusFilter.valueOf(it[3]),
            // Backward compatible with the four-field saver used before catalog scopes existed.
            scope = it.getOrNull(4)?.let(SkillCatalogScope::valueOf) ?: SkillCatalogScope.ALL
        )
    }
)

@Composable
internal fun rememberSkillFilters(initial: SkillFilters = SkillFilters()): MutableState<SkillFilters> =
    rememberSaveable(stateSaver = SkillFiltersSaver) { mutableStateOf(initial) }

/** Shared by the installed library and the chat picker; selections live outside these filters. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillFilterPanel(
    skills: List<SkillRecord>,
    filters: SkillFilters,
    onChange: (SkillFilters) -> Unit,
    modifier: Modifier = Modifier,
    selectedIds: Set<String> = emptySet(),
    compact: Boolean = false
) {
    val useCounts = skillUseCounts(skills, filters, selectedIds)
    // Purposes with nothing to show (the trimmed catalog has no outline or card tools) stay out of
    // the menus; the current choice and “all” remain so a saved filter is never stranded.
    val shownUses = SkillUse.values().filter { it == SkillUse.ALL || it == filters.use || (useCounts[it] ?: 0) > 0 }
    val contentCounts = skillContentCounts(skills, filters, selectedIds)
    var usesOpen by rememberSaveable { mutableStateOf(false) }
    var statusOpen by rememberSaveable { mutableStateOf(false) }
    var showAllUses by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = filters.query,
            onValueChange = { onChange(filters.copy(query = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索 Skills") },
            placeholder = { Text("名称、简介、GitHub 仓库") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (filters.query.isNotEmpty()) ({
                IconButton(onClick = { onChange(filters.copy(query = "")) }) {
                    Icon(Icons.Outlined.Close, "清空搜索", Modifier.size(18.dp))
                }
            }) else null
        )
        Text("目录", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SkillCatalogScope.values().forEach { scope ->
                FilterChip(
                    selected = filters.scope == scope,
                    onClick = { onChange(filters.copy(scope = scope)) },
                    label = { Text(scope.label) }
                )
            }
        }
        Text("内容", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SkillContentFilter.values().forEach { content ->
                FilterChip(
                    selected = filters.content == content,
                    onClick = { onChange(filters.copy(content = content)) },
                    label = { Text("${content.label} ${contentCounts[content] ?: 0}") }
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { usesOpen = true }) {
                        Text("用途：${filters.use.label} ${useCounts[filters.use] ?: 0}")
                    }
                    DropdownMenu(expanded = usesOpen, onDismissRequest = { usesOpen = false }) {
                        shownUses.forEach { use ->
                            DropdownMenuItem(text = { Text("${use.label} · ${useCounts[use] ?: 0}") }, onClick = {
                                onChange(filters.copy(use = use)); usesOpen = false
                            })
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { statusOpen = true }) {
                        Text("状态：${filters.status.label} ${filterSkills(skills, filters, selectedIds).size}")
                    }
                    DropdownMenu(expanded = statusOpen, onDismissRequest = { statusOpen = false }) {
                        SkillStatusFilter.values().forEach { status ->
                            val count = filterSkills(skills, filters.copy(status = status), selectedIds).size
                            DropdownMenuItem(text = { Text("${status.label} · $count") }, onClick = {
                                onChange(filters.copy(status = status)); statusOpen = false
                            })
                        }
                    }
                }
        }
        if (!compact) {
            TextButton(onClick = { showAllUses = !showAllUses }) { Text(if (showAllUses) "收起用途分类" else "展开全部用途分类") }
        }
        if (!compact && showAllUses) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shownUses.forEach { use ->
                    FilterChip(
                        selected = filters.use == use,
                        onClick = { onChange(filters.copy(use = use)) },
                        label = { Text("${use.label} ${useCounts[use] ?: 0}") }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "匹配 ${filterSkills(skills, filters, selectedIds).size} / 已安装 ${skills.size}",
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (filters != SkillFilters(scope = filters.scope)) {
                TextButton(onClick = { onChange(SkillFilters(scope = filters.scope)) }) { Text("清空筛选") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillMetadataLabels(skill: SkillRecord) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
            Text(
                skillUse(skill).label, Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        if (skill.adult) AdultBadge()
    }
}
