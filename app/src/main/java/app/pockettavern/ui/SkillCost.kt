package app.pockettavern.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.SkillRecord
import app.pockettavern.engine.ContextEngine
import java.util.Locale

/** Selected Skills above this share of the daily input limit get a warning. */
internal const val SKILL_COST_WARN_PERCENT = 15

internal data class SkillCostSummary(val total: Int, val limit: Int, val percent: Int) {
    val heavy: Boolean get() = percent >= SKILL_COST_WARN_PERCENT
}

/** Tools decide whether reference lists are sent, so the estimate follows the chat's model. */
internal fun chatSupportsTools(data: AppData, chat: Chat): Boolean =
    data.providers.firstOrNull { it.id == chat.providerId }?.models?.firstOrNull { it.id == chat.modelId }?.supportsTools == true

/** The same estimate ContextEngine uses for budgeting, per Skill id. */
internal fun skillCosts(skills: List<SkillRecord>, toolsSupported: Boolean): Map<String, Int> =
    skills.associate { it.id to ContextEngine.skillPromptTokens(it, toolsSupported) }

/** Daily input limit from settings, capped by the model window when the catalog knows it. */
internal fun chatInputLimit(data: AppData, chat: Chat): Int {
    val window = data.providers.firstOrNull { it.id == chat.providerId }?.models?.firstOrNull { it.id == chat.modelId }
        ?.contextTokens?.takeIf { it > 0 }
    val configured = data.settings.maxContextInputTokens.takeIf { it > 0 }
    return listOfNotNull(configured, window).minOrNull() ?: 32_768
}

/** Only Skills that will actually be injected count: enabled, and adult ones only once confirmed. */
internal fun skillCostSummary(selected: List<SkillRecord>, costs: Map<String, Int>, limit: Int, adultConfirmed: Boolean): SkillCostSummary {
    val total = selected.filter { it.enabled && (!it.adult || adultConfirmed) }.sumOf { costs[it.id] ?: 0 }
    val safeLimit = limit.coerceAtLeast(1)
    return SkillCostSummary(total, safeLimit, (total * 100L / safeLimit).toInt())
}

internal fun formatTokens(tokens: Int): String = String.format(Locale.ROOT, "%,d", tokens)

@Composable
internal fun SkillCostLine(tokens: Int) {
    Text("每轮约 ${formatTokens(tokens)} tokens（估算）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SkillCostSummaryLine(summary: SkillCostSummary) {
    Text(
        "已选 Skill 每轮约 ${formatTokens(summary.total)} tokens，占日常输入上限 ${formatTokens(summary.limit)} 的 ${summary.percent}%" +
            if (summary.heavy) "。偏多：会更早触发压缩，可去掉用不上的 Skill。" else "。",
        style = MaterialTheme.typography.bodySmall,
        color = if (summary.heavy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
