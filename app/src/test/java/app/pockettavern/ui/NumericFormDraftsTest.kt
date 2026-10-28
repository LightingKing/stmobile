package app.pockettavern.ui

import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Preset
import org.junit.Assert.*
import org.junit.Test

class NumericFormDraftsTest {
    @Test fun compressionBudgetCanDeleteEveryCharacterAndThenReplaceTheValue() {
        val saved = AppSettings(maxContextInputTokens = 65536)
        var draft = CompressionSettingsDraft.from(saved)
        listOf("6553", "655", "65", "6", "").forEach { typed ->
            draft = draft.copy(inputBudget = typed)
            assertEquals(typed, draft.inputBudget)
        }
        assertNotNull(draft.validationError())
        assertTrue(runCatching { draft.applyTo(saved) }.isFailure)
        assertEquals(65536, saved.maxContextInputTokens)

        draft = draft.copy(inputBudget = "1000000")
        assertNull(draft.validationError())
        assertEquals(1000000, draft.applyTo(saved).maxContextInputTokens)
    }

    @Test fun decimalEditingKeepsZeroAndTrailingPointUntilTheUserFinishes() {
        val saved = AppSettings(compressionThreshold = 0.8)
        var draft = CompressionSettingsDraft.from(saved)
        listOf("", "0", "0.", "0.9", "0.95").forEach { typed ->
            draft = draft.copy(threshold = typed)
            assertEquals(typed, draft.threshold)
            assertEquals(0.8, saved.compressionThreshold, 0.0)
        }
        assertNull(draft.validationError())
        assertEquals(0.95, draft.applyTo(saved).compressionThreshold, 0.0)
        assertEquals("0.95", draft.threshold)

        var presetDraft = PresetNumbersDraft.from(Preset(generationBudgetEnabled = true))
        listOf("", "0", "0.", "0.95").forEach { typed ->
            presetDraft = presetDraft.copy(temperature = typed)
            assertEquals(typed, presetDraft.temperature)
        }
        assertEquals(0.95, presetDraft.applyTo(Preset(generationBudgetEnabled = true)).temperature, 0.0)
    }

    @Test fun eachCompressionFieldRejectsEmptySaveWithoutRestoringItsText() {
        val original = CompressionSettingsDraft.from(AppSettings())
        val emptyDrafts = listOf(
            original.copy(inputBudget = ""), original.copy(threshold = ""),
            original.copy(keepRecent = ""), original.copy(contextOverride = ""),
            original.copy(toolSteps = "")
        )
        emptyDrafts.forEach { draft ->
            assertNotNull(draft.validationError())
            assertTrue(runCatching { draft.applyTo(AppSettings()) }.isFailure)
        }
        assertEquals("", emptyDrafts[0].inputBudget)
        assertEquals("", emptyDrafts[1].threshold)
        assertEquals("", emptyDrafts[2].keepRecent)
        assertEquals("", emptyDrafts[3].contextOverride)
        assertEquals("", emptyDrafts[4].toolSteps)
    }

    @Test fun compressionRejectsOverflowOutOfRangeAndNonFiniteNumbers() {
        val draft = CompressionSettingsDraft.from(AppSettings())
        val invalid = listOf(
            draft.copy(inputBudget = "2147483648"), draft.copy(inputBudget = "-1"),
            draft.copy(inputBudget = "2000001"), draft.copy(contextOverride = "-1"),
            draft.copy(contextOverride = "2000001"), draft.copy(keepRecent = "0"),
            draft.copy(keepRecent = "101"), draft.copy(toolSteps = "0"), draft.copy(toolSteps = "17"),
            draft.copy(threshold = "0.19"), draft.copy(threshold = "0.951"),
            draft.copy(threshold = "NaN"), draft.copy(threshold = "Infinity"),
            draft.copy(threshold = "-Infinity"), draft.copy(threshold = "1e999")
        )
        invalid.forEach {
            assertNotNull(it.toString(), it.validationError())
            assertTrue(it.toString(), runCatching { it.applyTo(AppSettings()) }.isFailure)
        }
    }

    @Test fun validCompressionBoundsAndZeroValuesAreKeptExactly() {
        val settings = AppSettings()
        val lower = CompressionSettingsDraft("0", "0.2", "1", "0", "1").applyTo(settings)
        assertEquals(0, lower.maxContextInputTokens)
        assertEquals(0, lower.contextOverride)
        assertEquals(0.2, lower.compressionThreshold, 0.0)
        assertEquals(1, lower.keepRecentTurns)
        assertEquals(1, lower.maxToolSteps)
        val upper = CompressionSettingsDraft("2000000", "0.95", "100", "2000000", "16").applyTo(settings)
        assertEquals(2000000, upper.maxContextInputTokens)
        assertEquals(2000000, upper.contextOverride)
        assertEquals(0.95, upper.compressionThreshold, 0.0)
        assertEquals(100, upper.keepRecentTurns)
        assertEquals(16, upper.maxToolSteps)
    }

    @Test fun presetCanClearEveryFieldAndReplaceWithoutFallbackOrClamping() {
        val saved = Preset(temperature = 0.8, maxOutput = 8, reasoningBudget = 0, generationBudgetEnabled = true)
        val original = PresetNumbersDraft.from(saved)
        listOf(original.copy(temperature = ""), original.copy(maxOutput = ""), original.copy(reasoningBudget = "")).forEach {
            assertNotNull(it.validationError())
            assertTrue(runCatching { it.applyTo(saved) }.isFailure)
        }
        var draft = original.copy(maxOutput = "")
        listOf("1", "10", "100", "1000", "10000").forEach { typed ->
            draft = draft.copy(maxOutput = typed)
            assertEquals(typed, draft.maxOutput)
        }
        assertEquals(10000, draft.applyTo(saved).maxOutput)
        assertEquals(0, draft.applyTo(saved).reasoningBudget)
    }

    @Test fun presetRejectsInvalidNumbersInsteadOfSilentlySavingOldOrClampedValues() {
        val saved = Preset(generationBudgetEnabled = true)
        val draft = PresetNumbersDraft.from(saved)
        val invalid = listOf(
            draft.copy(temperature = "-0.1"), draft.copy(temperature = "2.01"),
            draft.copy(temperature = "NaN"), draft.copy(temperature = "Infinity"),
            draft.copy(temperature = "-Infinity"), draft.copy(temperature = "1e999"),
            draft.copy(maxOutput = "0"), draft.copy(maxOutput = "131073"),
            draft.copy(maxOutput = "2147483648"), draft.copy(maxOutput = "1.5"),
            draft.copy(reasoningBudget = "-1"), draft.copy(reasoningBudget = "131073")
        )
        invalid.forEach {
            assertNotNull(it.toString(), it.validationError())
            assertTrue(it.toString(), runCatching { it.applyTo(saved) }.isFailure)
        }
        assertEquals(0.0, PresetNumbersDraft("0", "1", "0", true).applyTo(saved).temperature, 0.0)
        val upper = PresetNumbersDraft("2", "131072", "131072", true).applyTo(saved)
        assertEquals(2.0, upper.temperature, 0.0)
        assertEquals(131072, upper.maxOutput)
        assertEquals(131072, upper.reasoningBudget)
    }

    @Test fun savingAppliesOnlyNumericFieldsToTheLatestSettingsAndPreset() {
        val settingsDraft = CompressionSettingsDraft.from(AppSettings()).copy(inputBudget = "123456", toolSteps = "3")
        val latestSettings = AppSettings(autoCompress = false, darkMode = "dark", userPersona = "新人物描述",
            researchProviderId = "new-provider", researchModelId = "new-model", researchSearchMode = "custom",
            builtinSkillsSeen = listOf("skill-a"), builtinScenariosSeen = listOf("scenario-a"),
            adultSkillsConfirmed = true, builtinSkillsRevision = 17)
        assertEquals(latestSettings.copy(maxContextInputTokens = 123456, maxToolSteps = 3), settingsDraft.applyTo(latestSettings))

        val originalPreset = Preset(id = "preset-id", generationBudgetEnabled = true)
        val presetDraft = PresetNumbersDraft.from(originalPreset).copy(maxOutput = "10000", reasoningBudget = "16000", generationBudgetEnabled = true)
        val latestPreset = originalPreset.copy(name = "新名称", systemPrompt = "新提示词", summaryPrompt = "新摘要规则",
            summaryProviderId = "summary-provider", summaryModelId = "summary-model",
            reasoningEffort = "high", includeVeniceSystemPrompt = false)
        assertEquals(latestPreset.copy(maxOutput = 10000, reasoningBudget = 16000), presetDraft.applyTo(latestPreset))
    }

    @Test fun disabledGenerationBudgetPreservesValuesAndAllowsEmptyHiddenDrafts() {
        val saved = Preset(temperature = 0.8, maxOutput = 4096, reasoningBudget = 2048, generationBudgetEnabled = false)
        val draft = PresetNumbersDraft.from(saved).copy(maxOutput = "", reasoningBudget = "")

        assertNull(draft.validationError())
        val applied = draft.applyTo(saved)
        assertFalse(applied.generationBudgetEnabled)
        assertEquals(4096, applied.maxOutput)
        assertEquals(2048, applied.reasoningBudget)
    }

    @Test fun integerValidationSupportsTheFullPriorityRangeAndNamesTheInvalidField() {
        assertNull(integerInputError(Int.MIN_VALUE.toString(), "优先级", Int.MIN_VALUE..Int.MAX_VALUE))
        assertNull(integerInputError(Int.MAX_VALUE.toString(), "优先级", Int.MIN_VALUE..Int.MAX_VALUE))
        listOf("", " ", "-", "2147483648", "-2147483649", "1.5").forEach { typed ->
            val error = integerInputError(typed, "优先级", Int.MIN_VALUE..Int.MAX_VALUE)
            assertNotNull(error)
            assertTrue(error!!.contains("优先级"))
        }
    }
}
