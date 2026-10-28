package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.CompressionSlice
import app.pockettavern.domain.Message
import app.pockettavern.domain.Summary

/** Plans a full rebuild and applies a complete result without replacing the original conversation. */
object MemoryRebuilder {
    fun effectiveWindow(modelWindow: Int, contextOverride: Int): Int {
        val window = modelWindow.takeIf { it > 0 } ?: 32_768
        return contextOverride.takeIf { it > 0 }?.let { minOf(it, window) } ?: window
    }

    fun snapshot(chat: Chat): CompressionSlice {
        requireUnambiguousMessageIds(chat.messages)
        require(chat.messages.any { message ->
            !message.contextExcluded && message.role in setOf("user", "assistant") &&
                (message.content.isNotBlank() || message.attachments.any { attachment ->
                    HistorySource.isTextAttachment(attachment) && attachment.dataUrl.isNotBlank()
                })
        }) { "当前会话没有可重建的对话或文本附件原文" }
        val messages = chat.messages.toList()
        return CompressionSlice(messages, messages.last().id, ContextEngine.sourceHash(messages))
    }

    fun commit(current: Chat, snapshot: CompressionSlice, completed: HistoryCompression, modelId: String): Chat {
        requireUnambiguousMessageIds(snapshot.messages)
        requireUnambiguousMessageIds(current.messages)
        require(current.messages.map { it.id } == snapshot.messages.map { it.id } &&
            ContextEngine.sourceHash(current.messages) == snapshot.sourceHash) {
            "会话原文在重建期间发生变化；现有摘要与知识库未修改"
        }
        require(snapshot.messages.isNotEmpty() && snapshot.throughMessageId == snapshot.messages.last().id) {
            "重建范围不完整；现有摘要与知识库未修改"
        }
        require(modelId.isNotBlank() && completed.summaryText.isNotBlank() && completed.notes.isNotEmpty()) {
            "重建结果为空；现有摘要与知识库未修改"
        }
        val messages = current.messages.associateBy { it.id }
        val sourceHashes = mutableMapOf<List<String>, String>()
        require(completed.notes.all { note ->
            val hash = sourceHashes.getOrPut(note.sourceMessageIds) {
                val sources = note.sourceMessageIds.mapNotNull(messages::get)
                if (sources.isNotEmpty() && sources.size == note.sourceMessageIds.size) ContextEngine.sourceHash(sources) else ""
            }
            note.sourceKind == "conversation" && note.text.isNotBlank() && hash.isNotEmpty() && hash == note.sourceHash
        } && completed.notes.flatMap { it.sourceMessageIds }.toSet() == messages.keys &&
            completed.coveredSourceMessageIds.toSet() == messages.keys &&
            completed.coveredSourceMessageIds.distinct().size == completed.coveredSourceMessageIds.size) {
            "重建结果未覆盖完整原文，或笔记来源不匹配；现有摘要与知识库未修改"
        }
        val notes = completed.notes.map { it.copy(modelId = modelId) }
        val summary = Summary(
            text = completed.summaryText,
            throughMessageId = snapshot.throughMessageId,
            sourceHash = snapshot.sourceHash,
            modelId = modelId,
            compression = CompressionScope.record(current, snapshot, fullRebuild = true).copy(segmentCount = completed.requestCount, requestCount = completed.requestCount),
            format = HistoryCompressor.FORMAT
        )
        return current.copy(
            summaries = current.summaries + summary,
            activeSummaryId = summary.id,
            knowledgeNotes = notes + current.knowledgeNotes.filter { it.isResearch }
        ).also { verifyApplied(it, summary.id, snapshot.messages.size) }
    }

    /** Verifies the generated summary and complete notes in the current or freshly loaded conversation. */
    fun verifyApplied(chat: Chat, summaryId: String, expectedMessageCount: Int, expectedNoteIds: List<String>? = null) {
        require(summaryId.isNotBlank()) { "无法确认重建结果：未记录新摘要 ID" }
        require(expectedMessageCount > 0 && chat.messages.size == expectedMessageCount) {
            "无法确认重建结果：原文条数与本次重建不一致"
        }
        requireUnambiguousMessageIds(chat.messages)
        require(chat.activeSummaryId == summaryId) {
            "无法确认重建结果：当前活动摘要不是本次生成的新摘要"
        }
        val matches = chat.summaries.filter { it.id == summaryId }
        require(matches.size == 1) { "无法确认重建结果：新摘要缺失或摘要 ID 重复" }
        val summary = matches.single()
        require(summary.text.isNotBlank()) { "无法确认重建结果：新摘要内容为空" }
        require(summary.throughMessageId == chat.messages.last().id) {
            "无法确认重建结果：新摘要未覆盖到最后一条原文"
        }
        require(summary.sourceHash.isNotBlank() && summary.sourceHash == ContextEngine.sourceHash(chat.messages)) {
            "无法确认重建结果：新摘要的完整原文校验不一致"
        }
        val notes = chat.knowledgeNotes.filterNot { it.isResearch }
        require(notes.isNotEmpty()) { "无法确认重建结果：分段记忆缺失" }
        val noteIds = chat.knowledgeNotes.map { it.id }
        require(noteIds.none { it.isBlank() } && noteIds.distinct().size == noteIds.size) {
            "无法确认重建结果：分段记忆 ID 缺失或重复"
        }
        require(expectedNoteIds == null || chat.knowledgeNotes.map { it.id } == expectedNoteIds) {
            "无法确认重建结果：保存的分段记忆与本次重建不一致"
        }
        require(chat.knowledgeNotes.filter { it.isResearch }.all(KnowledgeVault::validResearchNote)) {
            "无法确认重建结果：外部研究资料的来源类型或内容无效"
        }
        val messages = chat.messages.associateBy { it.id }
        val sourceHashes = mutableMapOf<List<String>, String>()
        require(notes.all { note ->
            val ids = note.sourceMessageIds
            note.sourceKind == "conversation" && note.text.isNotBlank() && ids.isNotEmpty() && ids.distinct().size == ids.size &&
                ids.all { it in messages } && note.sourceHash.isNotBlank() &&
                sourceHashes.getOrPut(ids) { ContextEngine.sourceHash(ids.map(messages::getValue)) } == note.sourceHash
        }) { "无法确认重建结果：分段记忆为空、来源缺失或原文校验不一致" }
        require(notes.flatMap { it.sourceMessageIds }.toSet() == messages.keys) {
            "无法确认重建结果：分段记忆未覆盖全部原文"
        }
    }

    private fun requireUnambiguousMessageIds(messages: List<Message>) {
        require(messages.none { it.id.isBlank() }) {
            "原文存在空白消息 ID，无法确认完整重建范围；原文未修改，请导出记忆诊断后排查"
        }
        require(messages.map { it.id }.distinct().size == messages.size) {
            "原文存在重复消息 ID，无法确认完整重建范围；原文未修改，请导出记忆诊断后排查"
        }
    }
}
