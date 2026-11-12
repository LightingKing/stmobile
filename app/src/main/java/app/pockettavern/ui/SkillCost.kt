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
/** Selected Skills above this many tokens get a warning whatever the limit (a limit of 0 means none). */
internal const val SKILL_COST_WARN_TOKENS = 20_000
/** A Skill this large is a whole rule set of its own; two of them tend to contradict each other. */
internal const val LARGE_SKILL_TOKENS = 4_000

internal data class SkillCostSummary(val total: Int, val limit: Int, val percent: Int, val large: List<String> = emptyList()) {
    val heavy: Boolean get() = percent >= SKILL_COST_WARN_PERCENT || total >= SKILL_COST_WARN_TOKENS
    /** Two or more full rule sets selected together. */
    val conflicting: Boolean get() = large.size >= 2
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
    val injected = selected.filter { it.enabled && (!it.adult || adultConfirmed) }
    val total = injected.sumOf { costs[it.id] ?: 0 }
    val safeLimit = limit.coerceAtLeast(1)
    val large = injected.filter { (costs[it.id] ?: 0) >= LARGE_SKILL_TOKENS }.map { it.name.ifBlank { it.id } }
    return SkillCostSummary(total, safeLimit, (total * 100L / safeLimit).toInt(), large)
}

internal fun formatTokens(tokens: Int): String = String.format(Locale.ROOT, "%,d", tokens)

@Composable
internal fun SkillCostLine(tokens: Int) {
    Text("每轮约 ${formatTokens(tokens)} tokens（估算）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SkillCostSummaryLine(summary: SkillCostSummary) {
    Text(
        "已选 Skill 每条消息约 ${formatTokens(summary.total)} tokens，占日常输入上限 ${formatTokens(summary.limit)} 的 ${summary.percent}%" +
            if (summary.heavy) "。偏多：每条消息都要带着它们，花费更高，模型也更难兼顾；可去掉用不上的。" else "。",
        style = MaterialTheme.typography.bodySmall,
        color = if (summary.heavy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
    // Advice only: the user may well want both.
    if (summary.conflicting) Text(
        "同时开了 ${summary.large.size} 个大型 Skill（${summary.large.joinToString("、")}），每个都是一整套写作规则，" +
            "规则可能互相冲突，模型容易顾此失彼。建议只留一个主 Skill。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
}
