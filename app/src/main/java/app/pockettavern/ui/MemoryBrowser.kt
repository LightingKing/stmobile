package app.pockettavern.ui

import app.pockettavern.domain.Chat
import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.domain.Message
import app.pockettavern.domain.MemoryRebuildState
import app.pockettavern.domain.Summary
import app.pockettavern.engine.ContextEngine
import app.pockettavern.engine.CompressionScope
import app.pockettavern.engine.HistorySource
import app.pockettavern.engine.KnowledgeOrganization

/** A read-only projection. Reading a historical version never changes the active memory. */
internal data class MemorySourceMessage(val number: Int, val message: Message)
internal data class MemorySource(val messages: List<MemorySourceMessage>, val valid: Boolean, val warning: String = "", val external: Boolean = false) {
    val label: String get() {
        if (external) return "外部研究资料 · 来源链接与事实/推测/虚构标注见正文"
        if (messages.isEmpty()) return "没有可读取的来源原文"
        val numbers = messages.map { it.number }
        val range = if (numbers.zipWithNext().all { (a, b) -> b == a + 1 }) {
            if (numbers.size == 1) "第 ${numbers.first()} 条" else "第 ${numbers.first()}–${numbers.last()} 条"
        } else "第 ${numbers.take(5).joinToString("、")} 条${if (numbers.size > 5) "等" else ""}"
        return "$range · 共 ${messages.size} 条消息"
    }
}

internal enum class MemoryEntryKind(val section: Int) { SUMMARY(0), NOTE(1), ORIGINAL(2) }
internal data class MemoryEntry(
    val id: String,
    val kind: MemoryEntryKind,
    val title: String,
    val text: String,
    val createdAt: Long,
    val modelId: String = "",
    val source: (() -> MemorySource)? = null,
    val active: Boolean = false,
    val external: Boolean = false,
    val metadata: String = "",
    val category: String = "",
    val keywords: List<String> = emptyList(),
    val compact: Boolean = false
) {
    val key: String get() = "${kind.section}:$id"
}
internal class MemoryIndex(val entries: List<MemoryEntry>) {
    private val byKey = entries.associateBy { it.key }
    fun resolve(key: String?): MemoryEntry? = byKey[key]
}
internal data class MemorySearchResult(val entry: MemoryEntry, val excerpt: String)
internal data class MemoryRebuildResult(val key: String?, val active: Boolean, val title: String)

internal object MemoryBrowser {
    const val ALL = -1

    /** A completed job identifies its own result; viewing it never restores that version. */
    fun rebuildResult(chat: Chat, status: MemoryRebuildState): MemoryRebuildResult? {
        if (status.chatId != chat.id || status.phase != "success") return null
        val summary = chat.summaries.firstOrNull { status.summaryId.isNotBlank() && it.id == status.summaryId }
        val selected = summary != null && summary.id == chat.activeSummaryId
        val sources = if (selected) source(chat, summary!!) else null
        val active = selected && sources?.valid == true && sources.messages.size == status.sourceMessageCount
        val title = when {
            status.summaryId.isBlank() -> "上次重建已完成，结果记录不完整"
            summary == null -> "上次重建已完成，结果摘要已不存在"
            active -> "记忆重建完成 · 当前正在使用"
            selected -> "上次重建已完成，但对应原文已变动，结果已失效"
            chat.activeSummaryId.isBlank() -> "上次重建已完成，当前未启用摘要"
            else -> "上次重建已完成，当前使用另一份摘要"
        }
        return MemoryRebuildResult(summary?.let { "0:${it.id}" }, active, title)
    }

    /** Build once off the UI thread. Decoded attachment text is reused across searches and detail views. */
    fun index(chat: Chat): MemoryIndex {
        val resolution = ContextEngine.resolveSummary(chat)
        return MemoryIndex(buildList {
        chat.summaries.sortedByDescending { it.createdAt }.forEach { summary ->
            add(MemoryEntry(summary.id, MemoryEntryKind.SUMMARY,
                when (summary.id) {
                    resolution.effective?.id -> if (summary.format == "compact-v1") "当前续聊摘要" else "当前摘要导览"
                    resolution.selected?.id -> "原选摘要（已失效）"
                    else -> if (summary.format == "compact-v1") "历史续聊摘要" else "历史摘要导览"
                }, summary.text,
                summary.createdAt, summary.modelId, { source(chat, summary) }, summary.id == resolution.effective?.id,
                metadata = summary.compression?.let { CompressionScope.describe(chat, it) }
                    ?: "旧版本未记录本次增量范围", compact = summary.format == "compact-v1"))
        }
        chat.knowledgeNotes.mapIndexed { index, note ->
            MemoryEntry(note.id, MemoryEntryKind.NOTE, KnowledgeOrganization.title(note, index + 1), note.text, note.createdAt,
                note.modelId, { source(chat, note) }, external = note.isResearch,
                category = KnowledgeOrganization.category(note), keywords = note.aliases + note.entity,
                metadata = if (note.kind == "legacy" && note.entity.isBlank() && note.aliases.isEmpty()) "" else buildString {
                    append(KnowledgeOrganization.label(KnowledgeOrganization.category(note)))
                    if (note.entity.isNotBlank()) append(" · ").append(note.entity.substringAfter(':'))
                    if (note.aliases.isNotEmpty()) append("\n别名：").append(note.aliases.joinToString("、"))
                    if (note.kind == "archive") append("\n这里只记录归档范围，不表示已经提取出全部事实。")
                })
        }.asReversed().forEach(::add)
        chat.messages.forEachIndexed { index, message ->
            add(MemoryEntry(message.id, MemoryEntryKind.ORIGINAL,
                "原文第 ${index + 1} 条 · ${role(message.role)}", messageText(message), message.createdAt))
        }
        })
    }

    /** Category-independent keys keep a global search result attached to its actual detail page. */
    fun initialKey(chat: Chat, id: String?, section: Int): String? {
        if (id == null) return null
        if (section in 0..2) return "$section:$id"
        return when {
            chat.summaries.any { it.id == id } -> "0:$id"
            chat.knowledgeNotes.any { it.id == id } -> "1:$id"
            chat.messages.any { it.id == id } -> "2:$id"
            else -> null
        }
    }

    fun search(index: MemoryIndex, query: String, section: Int = ALL, category: String = ""): List<MemorySearchResult> {
        val term = query.trim()
        return index.entries.mapNotNull { entry ->
            if (section != ALL && entry.kind.section != section) return@mapNotNull null
            if (category.isNotBlank() && entry.category != category) return@mapNotNull null
            val match = if (term.isEmpty()) 0 else entry.text.indexOf(term, ignoreCase = true)
            if (match < 0 && !entry.title.contains(term, ignoreCase = true) && entry.keywords.none { it.contains(term, ignoreCase = true) }) return@mapNotNull null
            MemorySearchResult(entry, excerpt(entry.text, match.coerceAtLeast(0), term.length))
        }
    }

    private fun excerpt(text: String, match: Int, length: Int): String {
        var start = (match - 35).coerceAtLeast(0)
        var end = (maxOf(match + length.coerceAtMost(160), start + 240)).coerceAtMost(text.length)
        if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end++
        // Result cards have three lines: whitespace must not hide the actual match.
        val visible = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return (if (start > 0) "…" else "") + visible + if (end < text.length) "…" else ""
    }

    fun source(chat: Chat, summary: Summary): MemorySource {
        val end = chat.messages.indexOfFirst { it.id == summary.throughMessageId }
        if (end < 0) return MemorySource(emptyList(), false, "摘要的结束消息已缺失，无法确定对应原文范围。")
        val messages = chat.messages.take(end + 1).mapIndexed { index, message -> MemorySourceMessage(index + 1, message) }
        return checked(messages, summary.sourceHash)
    }

    fun source(chat: Chat, note: KnowledgeNote): MemorySource {
        if (note.isResearch) return MemorySource(emptyList(), app.pockettavern.engine.KnowledgeVault.validResearchNote(note),
            "外部研究不对应聊天原文，也不代表剧情已经发生；请按正文中的来源和标注核对。", external = true)
        val indexed = chat.messages.mapIndexed { index, message -> message.id to MemorySourceMessage(index + 1, message) }.toMap()
        val messages = note.sourceMessageIds.mapNotNull(indexed::get)
        val missing = note.sourceMessageIds.size - messages.size
        if (missing > 0) return MemorySource(messages, false, "缺失 $missing 条来源消息；下方仅显示仍在本机的原文，不能核对完整来源。")
        if (note.sourceMessageIds.distinct().size != note.sourceMessageIds.size) return MemorySource(messages.distinctBy { it.message.id }, false, "来源记录存在重复消息，无法确认这份记忆的有效性。")
        return checked(messages, note.sourceHash)
    }

    private fun checked(messages: List<MemorySourceMessage>, hash: String): MemorySource {
        if (messages.isEmpty()) return MemorySource(messages, false, "这份记忆没有记录来源消息。")
        if (hash.isBlank()) return MemorySource(messages, false, "旧记录没有来源校验信息，不能确认摘要是否仍对应当前原文。")
        val valid = ContextEngine.sourceHash(messages.map { it.message }) == hash
        return MemorySource(messages, valid, if (valid) "" else "来源原文已经变动，这份记忆已失效；下方显示的是本机现存原文。")
    }

    fun role(role: String): String = when (role) {
        "user" -> "你"
        "assistant" -> "助手"
        "system" -> "系统"
        "tool" -> "工具结果"
        else -> role.ifBlank { "未知角色" }
    }

    fun messageText(message: Message): String = buildString {
        append(message.content)
        if (message.reasoning.isNotBlank()) append("\n\n【推理记录】\n${message.reasoning}")
        if (message.toolCallsJson.isNotBlank() && message.toolCallsJson != "[]") append("\n\n【工具调用】\n${message.toolCallsJson}")
        message.attachments.forEach { attachment ->
            append("\n\n【附件：${attachment.name.ifBlank { "未命名附件" }}】")
            HistorySource.attachmentText(attachment)?.let { append("\n").append(it) }
        }
        if (message.errorMessage.isNotBlank()) append("\n\n【错误记录】\n${message.errorMessage}")
    }

    /** Bounded Compose paragraphs with lossless UTF-16-safe reconstruction. */
    fun chunks(text: String, limit: Int = 3_000): List<String> {
        require(limit >= 2)
        if (text.isEmpty()) return listOf("")
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }
}
