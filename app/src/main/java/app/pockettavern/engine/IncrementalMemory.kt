package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.CompressionRecord
import app.pockettavern.domain.CompressionSlice
import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.domain.Message
import app.pockettavern.domain.Summary

/** Applies one complete increment without rewriting or discarding earlier memory. */
object IncrementalMemory {
    fun commit(
        current: Chat,
        slice: CompressionSlice,
        completed: HistoryCompression,
        modelId: String,
        compression: CompressionRecord? = null
    ): Chat {
        requireUniqueSources(current.messages)
        requireUniqueSources(slice.messages)
        val positions = current.messages.mapIndexed { index, message -> message.id to index }.toMap()
        val end = positions[slice.throughMessageId]
        require(end != null && slice.sourceHash.isNotBlank() &&
            ContextEngine.sourceHash(current.messages.take(end + 1)) == slice.sourceHash) {
            "消息已修改，本次增量摘要未应用；原摘要与知识库未修改"
        }
        val sources = current.messages.associateBy { it.id }
        val indices = slice.messages.map { positions[it.id] ?: -1 }
        require(slice.messages.isNotEmpty() && indices.all { it in 0..end } &&
            indices.zipWithNext().all { (before, after) -> before < after } &&
            ContextEngine.sourceHash(slice.messages) == ContextEngine.sourceHash(slice.messages.map { sources.getValue(it.id) })) {
            "增量原文范围不匹配；原摘要与知识库未修改"
        }
        require(modelId.isNotBlank() && completed.summaryText.isNotBlank() && completed.notes.isNotEmpty()) {
            "增量摘要结果为空；原摘要与知识库未修改"
        }
        val existingIds = current.knowledgeNotes.mapTo(hashSetOf()) { it.id }
        val newIds = completed.notes.map { it.id }
        require(newIds.none { it.isBlank() || it in existingIds } && newIds.distinct().size == newIds.size) {
            "增量笔记 ID 缺失或重复；原摘要与知识库未修改"
        }
        val inputIds = slice.messages.mapTo(hashSetOf()) { it.id }
        val hashes = mutableMapOf<List<String>, String>()
        require(completed.notes.all { note ->
            val ids = note.sourceMessageIds
            note.sourceKind == "conversation" && note.text.isNotBlank() && ids.isNotEmpty() &&
                ids.distinct().size == ids.size && ids.all { it in inputIds } &&
                note.sourceHash.isNotBlank() && hashes.getOrPut(ids) {
                    ContextEngine.sourceHash(ids.map(sources::getValue))
                } == note.sourceHash
        } && completed.notes.flatMap { it.sourceMessageIds }.toSet() == inputIds &&
            completed.coveredSourceMessageIds.toSet() == inputIds &&
            completed.coveredSourceMessageIds.distinct().size == completed.coveredSourceMessageIds.size) {
            "增量笔记未覆盖本次全部原文，或来源校验不一致；原摘要与知识库未修改"
        }

        // Old releases could save only a Summary; users may also edit that text.
        // A byte-for-byte standard overview needs no extra note. Any other valid
        // saved text is kept once, without guessing from its heading or rewriting it.
        val inherited = inheritedSummary(current, slice.previousSummary, indices.first(), positions)
        val notes = current.knowledgeNotes + listOfNotNull(inherited) + completed.notes.map { it.copy(modelId = modelId) }
        val summary = Summary(
            text = completed.summaryText,
            throughMessageId = slice.throughMessageId,
            sourceHash = slice.sourceHash,
            modelId = modelId,
            compression = compression?.copy(requestCount = completed.requestCount),
            format = HistoryCompressor.FORMAT
        )
        return current.copy(
            summaries = current.summaries + summary,
            activeSummaryId = summary.id,
            knowledgeNotes = notes
        )
    }

    private fun inheritedSummary(
        current: Chat,
        previousText: String,
        firstNewIndex: Int,
        positions: Map<String, Int>
    ): KnowledgeNote? {
        if (previousText.isBlank()) return null
        val previous = current.summaries.asSequence()
            .filter { it.text == previousText }
            .mapNotNull { summary -> positions[summary.throughMessageId]?.let { summary to it } }
            .filter { (summary, end) -> end < firstNewIndex && summary.sourceHash.isNotBlank() &&
                ContextEngine.sourceHash(current.messages.take(end + 1)) == summary.sourceHash }
            .maxByOrNull { it.second }
        require(previous != null) { "前次摘要已变化，本次增量摘要未应用；原摘要与知识库未修改" }
        val (summary, end) = previous
        // Native compact checkpoints remain individually searchable in summaries.
        // Duplicating each checkpoint as a note would turn every increment into a history copy.
        if (summary.format == HistoryCompressor.FORMAT) return null
        val prefix = current.messages.take(end + 1)
        val previousChat = current.copy(messages = prefix)
        if (HistoryCompressor.overview(previousChat) == summary.text) return null
        val ids = prefix.map { it.id }
        if (KnowledgeVault.validNotes(previousChat).any {
                it.sourceKind == "conversation" && it.text == summary.text &&
                    it.sourceMessageIds == ids && it.sourceHash == summary.sourceHash
            }) return null
        return KnowledgeNote(
            text = summary.text,
            sourceMessageIds = ids,
            sourceHash = summary.sourceHash,
            createdAt = summary.createdAt,
            modelId = summary.modelId
        )
    }

    private fun requireUniqueSources(messages: List<Message>) {
        require(messages.none { it.id.isBlank() } && messages.map { it.id }.distinct().size == messages.size) {
            "原文消息 ID 缺失或重复，无法确认增量范围；原摘要与知识库未修改"
        }
    }
}
