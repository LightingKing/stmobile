package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.CompressionRecord
import app.pockettavern.domain.CompressionSlice
import app.pockettavern.domain.ContextBudgetBreakdown
import java.util.Locale

/** One operation's source range, not the cumulative coverage of the resulting summary. */
object CompressionScope {
    fun record(chat: Chat, slice: CompressionSlice, fullRebuild: Boolean = false, budget: ContextBudgetBreakdown? = null): CompressionRecord {
        val resolution = if (fullRebuild) null else ContextEngine.resolveSummary(chat)
        val base = resolution?.effective
        return CompressionRecord(
            baseSummaryId = base?.id.orEmpty(),
            reusedThroughMessageId = base?.throughMessageId.orEmpty(),
            firstMessageId = slice.messages.firstOrNull()?.id.orEmpty(),
            lastMessageId = slice.throughMessageId,
            messageCount = slice.messages.count { !it.contextExcluded },
            estimatedSourceTokens = slice.messages.filterNot { it.contextExcluded }
                .sumOf { ContextEngine.estimateTokens(HistorySource.memoryText(it)) },
            reason = resolution?.reason?.name ?: "FULL_REBUILD",
            budgetBreakdown = budget
        )
    }

    fun describe(chat: Chat, record: CompressionRecord): String {
        val positions = chat.messages.mapIndexed { index, message -> message.id to index + 1 }.toMap()
        val reused = positions[record.reusedThroughMessageId]
        val start = positions[record.firstMessageId]
        val end = positions[record.lastMessageId]
        val prefix = when {
            record.reason == "FULL_REBUILD" -> "从全部原文重建（未沿用旧摘要）"
            record.reason == "FALLBACK_TO_EARLIER" -> "上一份摘要的原文已改动，沿用更早的有效摘要${reused?.let { "（截至第 $it 条）" }.orEmpty()}"
            record.baseSummaryId.isNotBlank() -> "沿用已有记忆${reused?.let { "（截至第 $it 条）" }.orEmpty()}"
            record.reason == "SOURCE_CHANGED" -> "旧摘要的原文已改动，无可复用版本，需重新整理"
            record.reason == "SELECTED_MISSING" || record.reason == "BOUNDARY_MISSING" -> "旧摘要或截止原文缺失，无法沿用，需重新整理"
            else -> "当前未启用摘要，从未压缩的原文开始整理"
        }
        val range = if (start != null && end != null) "原文第 $start–$end 条" else "原文范围已变动，请核对对应原文"
        val tokens = String.format(Locale.ROOT, "%,d", record.estimatedSourceTokens)
        val segments = record.requestCount?.let { " · 本次 $it 次压缩请求" }
            ?: if (record.segmentCount > 0) " · 本次${if (record.reason == "FULL_REBUILD") "生成" else "新增"} ${record.segmentCount} 段记忆" else ""
        val budget = record.budgetBreakdown?.let(::describeBudget)
        val lead = budget?.substringBefore('\n')?.let { "$it\n" }.orEmpty()
        val detail = budget?.substringAfter('\n', "")?.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
        return "$lead$prefix\n本次处理$range（${record.messageCount} 条有效原文，约 $tokens tokens）$segments$detail"
    }

    /** The range above is a subset; this explains the complete request that caused compaction. */
    fun describeBudget(value: ContextBudgetBreakdown): String {
        fun number(amount: Number) = String.format(Locale.ROOT, "%,d", amount)
        val capped = value.configuredInputLimit in 1 until value.modelInputBudget
        val limit = if (capped) "日常输入上限" else "模型可用输入容量"
        val reason = when (value.triggerReason) {
            "INPUT_LIMIT_REACHED" -> "整轮输入超过日常输入上限"
            "MODEL_BUDGET_REACHED" -> "整轮输入超过模型可用输入容量"
            "INPUT_LIMIT_THRESHOLD" -> "整轮输入达到日常输入上限的压缩比例"
            "MODEL_THRESHOLD" -> "整轮输入达到模型容量的压缩比例"
            else -> "尚未达到自动压缩触发线"
        }
        val fixedOther = listOf(value.presetTokens, value.characterTokens, value.memoryTokens, value.worldTokens, value.policyTokens).sumOf { it.toLong() }
        val override = if (value.contextOverride > 0) " · 窗口设置 ${number(value.contextOverride)}，实际采用 ${number(value.effectiveWindow)}" else ""
        val configuredLimit = if (value.configuredInputLimit > 0) "日常上限设置 ${number(value.configuredInputLimit)}" else "日常上限设置为不限制"
        val blocked = when (value.blockedReason) {
            "FIXED_INPUT_EXCEEDS_BUDGET" -> "\n未自动压缩：固定注入已达到输入上限，压缩对话无法解决；请减少启用的 Skills/预设/世界书，或提高日常输入上限。"
            "RECENT_TURN_EXCEEDS_BUDGET" -> "\n未自动压缩：保留的当前对话与固定注入已占满输入上限，只压缩更早历史无法解决；请缩短本轮输入或提高可用输入上限。"
            "NO_ARCHIVABLE_HISTORY" -> "\n未自动压缩：没有可安全归档的旧对话。"
            "NO_EXPECTED_REDUCTION" -> "\n未自动压缩：本次归档预计无法把整轮输入降到触发线以下。"
            else -> ""
        }
        return "整轮输入约 ${number(value.estimatedInputTokens)} tokens · 触发线 ${number(value.triggerTokens)}（${(value.threshold * 100).toInt()}%） · $limit ${number(value.inputBudget)}\n" +
            "其中 Skills ${number(value.skillTokens)} · 当前摘要 ${number(value.summaryTokens)} · 近期对话 ${number(value.recentTokens)} · 其他固定内容 ${number(fixedOther)} · 工具定义 ${number(value.toolTokens)}\n" +
            "模型窗口 ${number(value.modelWindow)}$override · $configuredLimit\n" +
            "正文预留 ${number(value.outputReserve)} · 思考预留 ${number(value.reasoningReserve)} · 安全余量 ${number(value.safetyReserve)}\n" +
            "$reason；待归档原文的 token 数只是整轮输入的一部分。$blocked"
    }
}
