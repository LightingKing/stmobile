package app.pockettavern.engine

import app.pockettavern.domain.*
import org.json.JSONArray

data class RepairCandidate(val messageId: String, val reason: String, val recommended: Boolean)

/** Conservative, inspectable suggestions for cleaning internal debris from a chat. */
object ConversationRepair {
    fun snapshotAppliesTo(snapshot: Chat, current: Chat): Boolean {
        if (snapshot.id != current.id) return false
        val snapshotIds = snapshot.messages.mapTo(mutableSetOf()) { it.id }
        val currentIds = current.messages.mapTo(mutableSetOf()) { it.id }
        return snapshotIds.any { it in currentIds } && snapshotIds.any { it !in currentIds }
    }

    fun extendSnapshot(snapshot: Chat, current: Chat): Chat {
        require(snapshot.id == current.id) { "备份与当前会话身份不匹配" }
        val capturedIds = snapshot.messages.mapTo(mutableSetOf()) { it.id }
        return snapshot.copy(messages = snapshot.messages + current.messages.filterNot { it.id in capturedIds })
    }

    fun restore(original: Chat, current: Chat, updatedAt: Long = System.currentTimeMillis()): Chat {
        require(original.id == current.id) { "备份与当前会话身份不匹配" }
        val originalIds = original.messages.mapTo(mutableSetOf()) { it.id }
        val currentById = current.messages.associateBy { it.id }
        val messagesCreatedAfterRepair = current.messages.filterNot { it.id in originalIds }
        val restoredMessages = original.messages.map { currentById[it.id] ?: it } + messagesCreatedAfterRepair

        val originalSummaries = original.summaries.associateBy { it.id }
        val currentSummaries = current.summaries.associateBy { it.id }
        val summaries = (original.summaries.map { it.id } + current.summaries.map { it.id })
            .distinct()
            .mapNotNull { id ->
                currentSummaries[id]?.takeIf { validSummarySource(restoredMessages, it) != null }
                    ?: originalSummaries[id]?.takeIf { validSummarySource(restoredMessages, it) != null }
            }
        val summaryIds = summaries.mapTo(mutableSetOf()) { it.id }
        val activeSummaryId = when {
            current.activeSummaryId.isBlank() -> ""
            current.activeSummaryId in summaryIds -> current.activeSummaryId
            original.activeSummaryId in summaryIds -> original.activeSummaryId
            else -> ""
        }

        val originalNotes = original.knowledgeNotes.associateBy { it.id }
        val currentNotes = current.knowledgeNotes.associateBy { it.id }
        val notes = (original.knowledgeNotes.map { it.id } + current.knowledgeNotes.map { it.id })
            .distinct()
            .mapNotNull { id ->
                currentNotes[id]?.takeIf { it.isResearch || validNoteSources(restoredMessages, it) != null }
                    ?: originalNotes[id]?.takeIf { it.isResearch || validNoteSources(restoredMessages, it) != null }
            }
        return current.copy(
            messages = restoredMessages,
            summaries = summaries,
            activeSummaryId = activeSummaryId,
            knowledgeNotes = notes,
            updatedAt = updatedAt
        )
    }

    fun candidates(chat: Chat): List<RepairCandidate> {
        val reasons = linkedMapOf<String, Pair<String, Boolean>>()
        val answeredCallIds = mutableSetOf<String>()
        chat.messages.forEachIndexed { index, message ->
            if (message.role != "assistant") return@forEachIndexed
            val callIds = toolCallIds(message)
            if (callIds.isEmpty()) return@forEachIndexed
            val tools = mutableListOf<Message>()
            var cursor = index + 1
            while (cursor < chat.messages.size && chat.messages[cursor].role == "tool") {
                tools += chat.messages[cursor++]
            }
            val toolIds = tools.map { it.toolCallId }.toSet()
            if (toolIds.containsAll(callIds)) {
                reasons[message.id] = "成对的内部工具调用记录（已执行的文件/API操作不会回滚）" to true
                tools.filter { it.toolCallId in callIds }.forEach {
                    reasons[it.id] = "成对的内部工具结果（已执行的文件/API操作不会回滚）" to true
                }
                answeredCallIds += callIds
            }
        }

        chat.messages.forEachIndexed { index, message ->
            if (message.role == "tool" && message.toolCallId !in answeredCallIds) {
                reasons.putIfAbsent(message.id, "未关联调用的工具记录" to true)
            }
            if (message.role == "assistant" && message.content.isBlank() && message.reasoning.isNotBlank() &&
                message.errorMessage.isBlank() && message.finishReason.isBlank() && message.toolCallsJson == "[]") {
                reasons.putIfAbsent(message.id, "只有思考过程、没有回复正文" to true)
            }
            if (index > 0 && message.role == "assistant" && chat.messages[index - 1].role == "assistant" &&
                message.content.isNotBlank() && message.content == chat.messages[index - 1].content &&
                message.attachments.isEmpty() && chat.messages[index - 1].attachments.isEmpty()) {
                reasons.putIfAbsent(message.id, "与紧邻的上一条助手回复完全重复" to true)
            }
            if (message.role == "assistant" && message.content.isBlank() &&
                (message.errorMessage.isNotBlank() || message.finishReason.isNotBlank())) {
                reasons.putIfAbsent(message.id, "空回复诊断记录；默认保留，删除后会丢失这条错误提示" to false)
            }
        }
        val byId = chat.messages.associateBy { it.id }
        return reasons.mapNotNull { (id, detail) -> byId[id]?.let { RepairCandidate(id, detail.first, detail.second) } }
    }

    /** Expands a manual selection so an assistant tool call and every corresponding result stay atomic. */
    fun expandSelection(chat: Chat, messageIds: Set<String>): Set<String> {
        require(chat.messages.map { it.id }.containsAll(messageIds)) { "会话消息已变化，请重新预览清理项" }
        if (messageIds.isEmpty()) return emptySet()
        val expanded = messageIds.toMutableSet()
        val toolsByCallId = chat.messages.filter { it.role == "tool" && it.toolCallId.isNotBlank() }.groupBy { it.toolCallId }
        val calls = chat.messages.filter { it.role == "assistant" }.mapNotNull { assistant ->
            toolCallIds(assistant).takeIf { it.isNotEmpty() }?.let { assistant to it }
        }
        var changed: Boolean
        do {
            changed = false
            calls.forEach { (assistant, callIds) ->
                val results = callIds.flatMap { toolsByCallId[it].orEmpty() }
                if (assistant.id in expanded || results.any { it.id in expanded }) {
                    if (expanded.add(assistant.id)) changed = true
                    results.forEach { if (expanded.add(it.id)) changed = true }
                }
            }
        } while (changed)
        return expanded
    }

    fun remove(chat: Chat, messageIds: Set<String>, updatedAt: Long = System.currentTimeMillis()): Chat {
        require(messageIds.isNotEmpty()) { "请先选择要清理的消息" }
        val expandedIds = expandSelection(chat, messageIds)
        val kept = chat.messages.filterNot { it.id in expandedIds }
        require(kept.any { it.role == "user" && (it.content.isNotBlank() || it.attachments.isNotEmpty()) }) { "至少保留一条用户消息" }
        require(kept.any { it.role == "assistant" && (it.content.isNotBlank() || it.toolCallsJson != "[]") }) { "至少保留一条助手回复或工具调用" }
        val byId = chat.messages.associateBy { it.id }
        val summaries = chat.summaries.mapNotNull { summary ->
            val originalEnd = chat.messages.indexOfFirst { it.id == summary.throughMessageId }
            if (originalEnd < 0 || ContextEngine.sourceHash(chat.messages.take(originalEnd + 1)) != summary.sourceHash) return@mapNotNull null
            val survivingSource = chat.messages.take(originalEnd + 1).filterNot { it.id in expandedIds }
            survivingSource.lastOrNull()?.let { through ->
                summary.copy(throughMessageId = through.id, sourceHash = ContextEngine.sourceHash(survivingSource))
            }
        }
        val notes = chat.knowledgeNotes.mapNotNull { note ->
            if (note.isResearch) return@mapNotNull note
            val originalSources = note.sourceMessageIds.mapNotNull(byId::get)
            if (note.sourceMessageIds.isEmpty() || originalSources.size != note.sourceMessageIds.size ||
                ContextEngine.sourceHash(originalSources) != note.sourceHash) return@mapNotNull null
            val survivingSources = originalSources.filterNot { it.id in expandedIds }
            survivingSources.takeIf { it.isNotEmpty() }?.let { sources ->
                note.copy(sourceMessageIds = sources.map { it.id }, sourceHash = ContextEngine.sourceHash(sources))
            }
        }
        val active = chat.activeSummaryId.takeIf { id -> summaries.any { it.id == id } }.orEmpty()
        return chat.copy(messages = kept, summaries = summaries, activeSummaryId = active,
            knowledgeNotes = notes, updatedAt = updatedAt)
    }

    private fun toolCallIds(message: Message): Set<String> = runCatching {
        val calls = JSONArray(message.toolCallsJson)
        (0 until calls.length()).mapNotNull { index ->
            calls.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
        }.toSet()
    }.getOrDefault(emptySet())

    private fun validSummarySource(messages: List<Message>, summary: Summary): List<Message>? {
        val end = messages.indexOfFirst { it.id == summary.throughMessageId }
        if (end < 0) return null
        val source = messages.take(end + 1)
        return source.takeIf { ContextEngine.sourceHash(it) == summary.sourceHash }
    }

    private fun validNoteSources(messages: List<Message>, note: KnowledgeNote): List<Message>? {
        if (note.sourceMessageIds.isEmpty()) return null
        val byId = messages.associateBy { it.id }
        val sources = note.sourceMessageIds.mapNotNull(byId::get)
        if (sources.size != note.sourceMessageIds.size) return null
        return sources.takeIf { ContextEngine.sourceHash(it) == note.sourceHash }
    }
}
