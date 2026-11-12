package app.pockettavern.domain

/**
 * What the next request of one chat would send, worked out off the main thread for the memory
 * page and the chat's "整理记忆" prompt. Numbers and names only, never message text.
 */
data class ContextInspection(
    val chatId: String,
    val breakdown: ContextBudgetBreakdown,
    /** Selected Skills and their estimated tokens, in prompt order. */
    val skills: List<Pair<String, Int>> = emptyList(),
    /** Chapters and people files the next request carries. */
    val chapters: Int = 0,
    val profiles: List<String> = emptyList(),
    /** The user's pinned facts for this chat. */
    val pinned: Int = 0
)
