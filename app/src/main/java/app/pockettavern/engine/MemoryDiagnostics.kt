package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.ContextBudgetBreakdown
import app.pockettavern.domain.MemoryRebuildState
import org.json.JSONArray
import org.json.JSONObject

/** Metadata-only diagnostics. Keep the JSON field whitelist explicit; never serialize domain objects. */
object MemoryDiagnostics {
    /**
     * Positions are zero-based; -1 means no usable cutoff. Coverage describes the first matching
     * prefix used by the memory browser, but ambiguous or blank source IDs can never be valid.
     * Unique and duplicate ID counts exclude blank IDs; duplicates count occurrences after the first.
     */
    fun report(chat: Chat, rebuild: MemoryRebuildState?): String {
        val idCounts = chat.messages.groupingBy { it.id }.eachCount()
        val firstIndices = mutableMapOf<String, Int>()
        val unsafePrefix = IntArray(chat.messages.size + 1)
        chat.messages.forEachIndexed { index, message ->
            firstIndices.putIfAbsent(message.id, index)
            val unsafe = message.id.isBlank() || idCounts[message.id] != 1
            unsafePrefix[index + 1] = unsafePrefix[index] + if (unsafe) 1 else 0
        }
        val prefixHashes = mutableMapOf<Int, String>()
        val summaries = JSONArray()
        chat.summaries.forEach { summary ->
            val end = if (summary.throughMessageId.isBlank()) -1 else firstIndices[summary.throughMessageId] ?: -1
            val valid = end >= 0 && unsafePrefix[end + 1] == 0 && summary.sourceHash.isNotBlank() &&
                prefixHashes.getOrPut(end) { ContextEngine.sourceHash(chat.messages.take(end + 1)) } == summary.sourceHash
            val item = JSONObject()
                .put("id", summary.id)
                .put("createdAt", summary.createdAt)
                .put("modelId", summary.modelId)
                .put("format", if (summary.format == "compact-v1") "compact-v1" else "legacy")
                .put("throughMessageId", summary.throughMessageId)
                .put("endIndex", end)
                .put("coverageCount", end + 1)
                .put("sourceValid", valid)
                .put("isActive", chat.activeSummaryId.isNotBlank() && summary.id == chat.activeSummaryId)
                .put("cutoffMatchCount", if (summary.throughMessageId.isBlank()) 0 else idCounts[summary.throughMessageId] ?: 0)
            summary.compression?.let { increment ->
                item.put("compression", JSONObject()
                    .put("baseSummaryId", increment.baseSummaryId)
                    .put("reusedThroughMessageId", increment.reusedThroughMessageId)
                    .put("firstMessageId", increment.firstMessageId)
                    .put("lastMessageId", increment.lastMessageId)
                    .put("startIndex", firstIndices[increment.firstMessageId] ?: -1)
                    .put("endIndex", firstIndices[increment.lastMessageId] ?: -1)
                    .put("messageCount", increment.messageCount)
                    .put("estimatedSourceTokens", increment.estimatedSourceTokens)
                    .put("segmentCount", increment.segmentCount)
                    .put("requestCount", increment.requestCount ?: JSONObject.NULL)
                    .put("reason", increment.reason.takeIf { reason -> reason == "FULL_REBUILD" || SummaryResolutionReason.entries.any { it.name == reason } } ?: "unknown")
                    .apply { increment.budgetBreakdown?.let { put("budgetBreakdown", budgetMetadata(it)) } })
            }
            summaries.put(item)
        }

        val noteHashes = mutableMapOf<List<String>, String>()
        val validNoteCount = chat.knowledgeNotes.count { note ->
            if (note.isResearch) return@count KnowledgeVault.validResearchNote(note)
            val ids = note.sourceMessageIds
            ids.isNotEmpty() && note.sourceHash.isNotBlank() && ids.distinct().size == ids.size &&
                ids.all { it.isNotBlank() && idCounts[it] == 1 } &&
                noteHashes.getOrPut(ids) {
                    ContextEngine.sourceHash(ids.map { chat.messages[firstIndices.getValue(it)] })
                } == note.sourceHash
        }
        val newestCreatedAt = chat.summaries.maxOfOrNull { it.createdAt }
        val latest = chat.summaries.lastOrNull { it.createdAt == newestCreatedAt }
        val activeFound = chat.activeSummaryId.isNotBlank() && chat.summaries.any { it.id == chat.activeSummaryId }
        val lastMessageId = chat.messages.lastOrNull()?.id.orEmpty()
        val resolution = ContextEngine.resolveSummary(chat)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("chatId", chat.id)
            .put("messageCount", chat.messages.size)
            .put("uniqueMessageIdCount", idCounts.count { (id, _) -> id.isNotBlank() })
            .put("duplicateMessageIdCount", idCounts.entries.sumOf { (id, count) -> if (id.isBlank()) 0 else count - 1 })
            .put("blankMessageIdCount", chat.messages.count { it.id.isBlank() })
            .put("lastMessageId", lastMessageId)
            .put("lastMessageIdFirstIndex", if (lastMessageId.isBlank()) -1 else firstIndices[lastMessageId] ?: -1)
            .put("activeSummaryId", chat.activeSummaryId)
            .put("activeSummaryFound", activeFound)
            .put("effectiveSummaryId", resolution.effective?.id.orEmpty())
            .put("summaryResolution", resolution.reason.name)
            .put("latestSummaryId", latest?.id.orEmpty())
            .put("activeMatchesLatestSummary", activeFound && latest?.id == chat.activeSummaryId)
            .put("summaryCount", chat.summaries.size)
            .put("summaries", summaries)
            .put("noteCount", chat.knowledgeNotes.size)
            .put("validNoteCount", validNoteCount)
            .put("researchNoteCount", chat.knowledgeNotes.count { it.isResearch })

        rebuild?.takeIf { it.chatId == chat.id }?.let { state ->
            report.put("rebuild", JSONObject()
                .put("chatId", state.chatId)
                .put("phase", state.phase.takeIf { it in setOf("running", "success", "cancelled", "error") } ?: "unknown")
                .put("completed", state.completed)
                .put("total", state.total)
                .put("noteCount", state.noteCount)
                .put("summaryId", state.summaryId)
                .put("sourceMessageCount", state.sourceMessageCount)
                .put("finishedAt", state.finishedAt))
        }
        return report.toString(2)
    }

    /** Explicit whitelist: no reflection, prompt text, model errors or arbitrary enum strings. */
    private fun budgetMetadata(value: ContextBudgetBreakdown): JSONObject = JSONObject()
        .put("modelWindow", value.modelWindow.coerceAtLeast(0))
        .put("effectiveWindow", value.effectiveWindow.coerceAtLeast(0))
        .put("contextOverride", value.contextOverride.coerceAtLeast(0))
        .put("configuredInputLimit", value.configuredInputLimit.coerceAtLeast(0))
        .put("modelInputBudget", value.modelInputBudget.coerceAtLeast(0))
        .put("inputBudget", value.inputBudget.coerceAtLeast(0))
        .put("triggerTokens", value.triggerTokens.coerceAtLeast(0))
        .put("threshold", value.threshold.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0)
        .put("safetyReserve", value.safetyReserve.coerceAtLeast(0))
        .put("outputReserve", value.outputReserve.coerceAtLeast(0))
        .put("reasoningReserve", value.reasoningReserve.coerceAtLeast(0))
        .put("estimatedInputTokens", value.estimatedInputTokens.coerceAtLeast(0))
        .put("presetTokens", value.presetTokens.coerceAtLeast(0))
        .put("characterTokens", value.characterTokens.coerceAtLeast(0))
        .put("memoryTokens", value.memoryTokens.coerceAtLeast(0))
        .put("worldTokens", value.worldTokens.coerceAtLeast(0))
        .put("skillTokens", value.skillTokens.coerceAtLeast(0))
        .put("policyTokens", value.policyTokens.coerceAtLeast(0))
        .put("toolTokens", value.toolTokens.coerceAtLeast(0))
        .put("summaryTokens", value.summaryTokens.coerceAtLeast(0))
        .put("recentTokens", value.recentTokens.coerceAtLeast(0))
        .put("projectedTokens", value.projectedTokens?.coerceAtLeast(0) ?: JSONObject.NULL)
        .put("triggerReason", value.triggerReason.orEmpty().takeIf { it in ContextBudgetBreakdown.TRIGGER_REASONS } ?: "UNKNOWN")
        .put("blockedReason", value.blockedReason.orEmpty().takeIf { it in ContextBudgetBreakdown.BLOCKED_REASONS } ?: "UNKNOWN")
}
