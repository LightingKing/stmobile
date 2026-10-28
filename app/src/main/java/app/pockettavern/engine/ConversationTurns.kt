package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.Memory

/** Reversible draft management. Stored originals are never removed by retry or rewrite. */
object ConversationTurns {
    fun resolveIntent(text: String, requested: String): String {
        if (requested in setOf("dialogue", "correction", "rewrite")) return requested
        val trimmed = text.trim()
        val bracketed = (trimmed.startsWith("（") && trimmed.endsWith("）")) ||
            (trimmed.startsWith("(") && trimmed.endsWith(")"))
        val control = if (bracketed) trimmed.substring(1, trimmed.length - 1).trim() else trimmed
        if (Regex("^(?:请)?(?:完全)?(?:重写|重新写|重新回答|重新生成)(?:[，,。！!\\s]|一|这|吧|$)").containsMatchIn(control)) return "rewrite"
        // Only unambiguous author controls work without brackets. Quoted speech and
        // ordinary actions such as “暂停脚步” must keep their dialogue interpretation.
        if (Regex("^(?:请)?(?:先)?(?:暂停续写|停下续写|别续写|不要续写|设定修正)(?:[，,。！!：:\\s]|$)|^OOC(?:[，,。！!：:\\s]|$)", RegexOption.IGNORE_CASE).containsMatchIn(control)) return "correction"
        if (bracketed && Regex("^(?:先)?(?:暂停|停下|纠正|纠错|不对|又错|设定修正|别续写|不要续写)").containsMatchIn(control)) return "correction"
        return "dialogue"
    }

    /** Latest story task; correction exchanges do not replace the task being rewritten. */
    fun rewriteTarget(chat: Chat): Message? {
        val task = chat.messages.indexOfLast { it.role == "user" && !it.contextExcluded && it.intent != "correction" }
        if (task < 0) return null
        val end = (task + 1 until chat.messages.size).firstOrNull {
            chat.messages[it].role == "user" && !chat.messages[it].contextExcluded
        } ?: chat.messages.size
        return chat.messages.subList(task + 1, end).lastOrNull { it.role == "assistant" && !it.contextExcluded }
    }

    fun append(chat: Chat, message: Message): Chat {
        if (message.intent != "rewrite") return chat.copy(messages = chat.messages + message)
        val target = rewriteTarget(chat)
        val revised = target?.let { setIncluded(chat, it.id, false) } ?: chat
        return revised.copy(messages = revised.messages + message.copy(revisionOf = target?.id.orEmpty()))
    }

    /** Message beside which the UI offers regeneration of the current user turn. */
    fun regenerationAnchor(chat: Chat): Message? {
        val index = chat.messages.indexOfLast { it.role == "user" && !it.contextExcluded }
        if (index < 0) return null
        // Regeneration appends a new reply after any later excluded turns. Look
        // across the whole tail for that active reply, as regenerate() does.
        val active = chat.messages.drop(index + 1).lastOrNull { it.role == "assistant" && !it.contextExcluded }
        if (active != null) return active.takeIf { it.status != "streaming" }
        // Exiting during service startup/compression can leave only a saved
        // discarded draft. Keep retry available without restoring its content.
        val end = (index + 1 until chat.messages.size).firstOrNull { chat.messages[it].role == "user" }
            ?: chat.messages.size
        return chat.messages.subList(index + 1, end).lastOrNull { it.role == "assistant" }
            ?: chat.messages[index]
    }

    fun regenerate(chat: Chat): Chat {
        val index = chat.messages.indexOfLast { it.role == "user" && !it.contextExcluded }
        if (index < 0) return chat
        val target = chat.messages.drop(index + 1).lastOrNull { it.role == "assistant" && !it.contextExcluded }
        val revised = chat.messages.mapIndexed { i, message ->
            when {
                i > index -> message.copy(contextExcluded = true)
                i == index -> message.copy(revisionOf = target?.id ?: message.revisionOf)
                else -> message
            }
        }
        return chat.copy(messages = revised)
    }

    /** A generation is atomic, including assistant calls and their tool results. */
    fun setIncluded(chat: Chat, messageId: String, included: Boolean): Chat {
        val index = chat.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return chat
        val target = chat.messages[index]
        val userIndex = if (target.role == "user") index else chat.messages.take(index).indexOfLast { it.role == "user" }
        val start = userIndex + 1
        val end = (start until chat.messages.size).firstOrNull { chat.messages[it].role == "user" } ?: chat.messages.size
        val selectedIndex = if (target.role == "user") {
            (end - 1 downTo start).firstOrNull { chat.messages[it].role == "assistant" }
        } else index
        val selectedGroup = selectedIndex?.let { generationIndices(chat.messages, it, start, end) }.orEmpty()
        return chat.copy(messages = chat.messages.mapIndexed { i, message ->
            when {
                target.role == "user" && !included && i in index until end -> message.copy(contextExcluded = true)
                included && i == userIndex -> message.copy(contextExcluded = false)
                // Restoring a version selects one generation, not all competing drafts in this turn.
                included && i in start until end -> message.copy(contextExcluded = i !in selectedGroup)
                !included && i in selectedGroup -> message.copy(contextExcluded = true)
                else -> message
            }
        })
    }

    private fun generationIndices(messages: List<Message>, index: Int, start: Int, end: Int): Set<Int> {
        val generation = messages[index].generationId
        if (generation.isNotBlank()) return (start until end).filter { messages[it].generationId == generation }.toSet()
        // Legacy messages had no generation ID. Only their contiguous legacy block belongs together;
        // never include a later version generated after the app upgrade.
        var first = index
        var last = index + 1
        while (first > start && messages[first - 1].generationId.isBlank()) first--
        while (last < end && messages[last].generationId.isBlank()) last++
        return (first until last).toSet()
    }

    /** Branches share source message IDs, but their chat memories have independent validity. */
    fun disableExcludedMemories(chat: Chat, memories: List<Memory>): List<Memory> {
        val excluded = chat.messages.filter { it.contextExcluded }.mapTo(hashSetOf()) { it.id }
        if (excluded.isEmpty()) return memories
        return memories.map { memory ->
            if (memory.scope == "chat" && memory.targetId == chat.id && memory.sourceMessageId.isNotBlank() && memory.sourceMessageId in excluded) {
                memory.copy(enabled = false)
            } else memory
        }
    }

    /** A local warning only; never retries a billable generation automatically. */
    fun rewriteNotice(previous: String, current: String): String {
        fun normalized(text: String) = text.filter { it.isLetterOrDigit() }.take(24_000)
        val old = normalized(previous)
        val fresh = normalized(current)
        if (old.length < 80 || fresh.length < 80) return ""
        val oldGrams = old.windowed(8, 4).toSet()
        val newGrams = fresh.windowed(8, 4).toSet()
        val overlap = newGrams.count { it in oldGrams }.toDouble() / newGrams.size.coerceAtLeast(1)
        return if (old == fresh || (overlap >= 0.85 && fresh.length >= old.length * 0.75))
            "这次重写与上一稿高度相似，可能没有重新构思。上一稿已保留，可展开对照或再次重写。"
        else ""
    }
}
