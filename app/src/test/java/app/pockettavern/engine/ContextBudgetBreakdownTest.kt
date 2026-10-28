package app.pockettavern.engine

import app.pockettavern.domain.*
import org.junit.Assert.*
import org.junit.Test

class ContextBudgetBreakdownTest {
    private val model = ModelInfo(id = "m", contextTokens = 1_000_000, supportsTools = true)
    private val provider = ProviderConfig(id = "p", kind = "venice", models = listOf(model))
    private val preset = Preset(id = "preset", maxOutput = 10_000, reasoningBudget = 16_384, reasoningEffort = "high", generationBudgetEnabled = true)
    private fun data(settings: AppSettings = AppSettings(), preset: Preset = this.preset) =
        AppData(providers = listOf(provider), presets = listOf(preset), settings = settings)
    private fun history(turns: Int = 30, size: Int = 200): List<Message> = (1..turns).flatMap { turn ->
        listOf(Message(id = "u$turn", content = "请求$turn"), Message(id = "a$turn", role = "assistant", content = "叙".repeat(size)))
    }
    private fun chat(messages: List<Message>) = Chat(id = "c", providerId = "p", modelId = "m", presetId = "preset", messages = messages)

    @Test fun disabledGenerationBudgetDoesNotSubtractDormantSavedLimitsFromTheInputWindow() {
        val saved = preset.copy(maxOutput = 100_000, reasoningBudget = 100_000, generationBudgetEnabled = false)
        val settings = AppSettings(maxContextInputTokens = 0)
        val built = ContextEngine.build(data(settings, saved), chat(history(1)))
        val budget = built.budgetBreakdown!!
        assertEquals(0, budget.outputReserve)
        assertEquals(0, budget.reasoningReserve)
        assertEquals(950_000, budget.modelInputBudget)
        assertEquals(built.budget, ContextEngine.build(data(settings, saved.copy(maxOutput = 1, reasoningBudget = 0)), chat(history(1))).budget)
        assertEquals(750_000, ContextEngine.build(data(settings, saved.copy(generationBudgetEnabled = true)), chat(history(1))).budget)
    }

    @Test fun archivedSourceCountIsNotTheFullPromptThatReachedTheDailyTrigger() {
        val original = Message(id = "old", content = "早期原文")
        val summary = Summary(id = "s", text = "记".repeat(3000), throughMessageId = original.id,
            sourceHash = ContextEngine.sourceHash(listOf(original)), format = "compact-v1")
        val skill = SkillRecord(id = "large-skill", name = "写作资料", content = "设".repeat(24_000), enabled = true)
        val chat = chat(listOf(original) + history(45, 220) + Message(id = "current", content = "继续"))
            .copy(summaries = listOf(summary), activeSummaryId = summary.id, skillIds = listOf(skill.id))
        val built = ContextEngine.build(data().copy(skills = listOf(skill)), chat)
        assertTrue(built.needsCompression)
        val budget = built.budgetBreakdown!!
        val scope = CompressionScope.record(chat, built.compression!!, budget = budget)
        assertTrue(scope.estimatedSourceTokens < built.estimatedTokens)
        assertTrue(scope.estimatedSourceTokens < 25_000)
        assertEquals(65_536, budget.inputBudget)
        assertEquals(52_428, budget.triggerTokens)
        assertTrue(budget.estimatedInputTokens >= budget.triggerTokens)
        assertEquals("INPUT_LIMIT_THRESHOLD", budget.triggerReason)
        assertTrue(budget.skillTokens > 35_000)
        assertTrue(budget.summaryTokens > 4_000)
        assertEquals(1_000_000, budget.modelWindow)
        assertEquals(budget, scope.budgetBreakdown)
        val description = CompressionScope.describe(chat, scope)
        assertTrue(description.contains("整轮输入"))
        assertTrue(description.lineSequence().first().contains("整轮输入"))
        assertTrue(description.lineSequence().first().contains("触发线"))
        assertEquals(1, description.lineSequence().count { it.startsWith("整轮输入约") })
        assertTrue(description.contains("日常输入上限"))
        assertFalse(description.contains(skill.content))
        assertTrue(built.preview.contains(CompressionScope.describeBudget(budget)))
        val withoutSkill = ContextEngine.build(data(), chat.copy(skillIds = emptyList()))
        assertFalse("The same new dialogue alone has not reached the trigger", withoutSkill.needsCompression)
        assertTrue(withoutSkill.estimatedTokens < budget.triggerTokens)
    }

    @Test fun everyInputCategoryAddsToTheActualPromptEstimateAndReservesAreSeparate() {
        val chat = chat(history(3)).copy(characterId = "character", skillIds = listOf("skill"))
        val populated = data().copy(
            presets = listOf(preset.copy(systemPrompt = "预设内容")),
            characters = listOf(Character(id = "character", name = "测试人物", description = "人物资料")),
            skills = listOf(SkillRecord(id = "skill", name = "测试技能", content = "技能内容", enabled = true, permissions = listOf("network"))),
            memories = listOf(Memory(text = "已确认事实", scope = "chat", targetId = chat.id)),
            worldEntries = listOf(WorldEntry(name = "地点", content = "世界资料", always = true))
        )
        val built = ContextEngine.build(populated, chat)
        val b = built.budgetBreakdown!!
        assertEquals(built.estimatedTokens, b.presetTokens + b.characterTokens + b.memoryTokens + b.worldTokens +
            b.skillTokens + b.policyTokens + b.toolTokens + b.summaryTokens + b.recentTokens)
        assertTrue(listOf(b.presetTokens, b.characterTokens, b.memoryTokens, b.worldTokens, b.skillTokens, b.policyTokens, b.toolTokens, b.recentTokens).all { it > 0 })
        assertEquals(10_000, b.outputReserve)
        assertEquals(16_384, b.reasoningReserve)
        assertEquals(50_000, b.safetyReserve)
        assertEquals(923_616, b.modelInputBudget)
        assertEquals(65_536, b.inputBudget)
        assertEquals("NONE", b.triggerReason)
    }

    @Test fun aLargeSkillThatAloneExceedsBudgetDoesNotTriggerPaidRepeatedCompression() {
        val skill = SkillRecord(id = "skill", name = "超大资料", content = "设".repeat(50_000), enabled = true)
        val chat = chat(history(10)).copy(skillIds = listOf(skill.id))
        val built = ContextEngine.build(data().copy(skills = listOf(skill)), chat)
        assertTrue(built.estimatedTokens > built.budget)
        assertFalse("Compacting dialogue cannot fix a fixed prompt that already exceeds the cap", built.needsCompression)
        assertEquals("FIXED_INPUT_EXCEEDS_BUDGET", built.budgetBreakdown!!.blockedReason)
    }

    @Test fun theOnlyKeptCurrentTurnCannotBeFixedByRepeatedlySummarizingOlderTurns() {
        val messages = history(4, 100) + Message(id = "large-current", content = "当".repeat(50_000))
        val built = ContextEngine.build(data(), chat(messages))
        assertTrue(built.estimatedTokens > built.budget)
        assertFalse(built.needsCompression)
        assertEquals("RECENT_TURN_EXCEEDS_BUDGET", built.budgetBreakdown!!.blockedReason)
    }

    @Test fun removingTheDailyLimitUsesActualModelWindowAndPreservesTheOverride() {
        val chat = chat(history(30, 2000))
        val full = ContextEngine.build(data(settings = AppSettings(maxContextInputTokens = 0)), chat)
        assertEquals(923_616, full.budget)
        assertFalse(full.needsCompression)
        val limited = ContextEngine.build(data(settings = AppSettings(maxContextInputTokens = 0, contextOverride = 100_000)), chat)
        val b = limited.budgetBreakdown!!
        assertEquals(1_000_000, b.modelWindow)
        assertEquals(100_000, b.effectiveWindow)
        assertEquals(100_000, b.contextOverride)
        assertEquals(68_616, b.inputBudget)
        assertEquals("MODEL_BUDGET_REACHED", b.triggerReason)
    }
}
