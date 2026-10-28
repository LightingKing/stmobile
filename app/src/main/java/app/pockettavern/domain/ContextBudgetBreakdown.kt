package app.pockettavern.domain

/** Numeric request diagnostics only: never includes prompts, dialogue text or credentials. */
data class ContextBudgetBreakdown(
    val modelWindow: Int = 0,
    val effectiveWindow: Int = 0,
    val contextOverride: Int = 0,
    val configuredInputLimit: Int = 0,
    val modelInputBudget: Int = 0,
    val inputBudget: Int = 0,
    val triggerTokens: Int = 0,
    val threshold: Double = 0.0,
    val safetyReserve: Int = 0,
    val outputReserve: Int = 0,
    val reasoningReserve: Int = 0,
    val estimatedInputTokens: Int = 0,
    val presetTokens: Int = 0,
    val characterTokens: Int = 0,
    val memoryTokens: Int = 0,
    val worldTokens: Int = 0,
    val skillTokens: Int = 0,
    val policyTokens: Int = 0,
    val toolTokens: Int = 0,
    val summaryTokens: Int = 0,
    val recentTokens: Int = 0,
    val projectedTokens: Int? = null,
    val triggerReason: String = "NONE",
    val blockedReason: String = ""
) {
    companion object {
        val TRIGGER_REASONS = setOf("NONE", "UNKNOWN", "INPUT_LIMIT_REACHED", "MODEL_BUDGET_REACHED", "INPUT_LIMIT_THRESHOLD", "MODEL_THRESHOLD")
        val BLOCKED_REASONS = setOf("", "UNKNOWN", "FIXED_INPUT_EXCEEDS_BUDGET", "RECENT_TURN_EXCEEDS_BUDGET", "NO_ARCHIVABLE_HISTORY", "NO_EXPECTED_REDUCTION")
    }
}
