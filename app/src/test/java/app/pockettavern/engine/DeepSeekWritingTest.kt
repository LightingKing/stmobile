package app.pockettavern.engine

import app.pockettavern.domain.*
import org.junit.Assert.*
import org.junit.Test

class DeepSeekWritingTest {
    private val model = ModelInfo(id = DeepSeekWriting.MODEL_ID, rawJson = """{"model_spec":{"maxCompletionTokens":131072,"capabilities":{"supportsReasoningEffort":true,"reasoningEffortOptions":["none","low","high","max"],"defaultReasoningEffort":"high"}}}""")
    private val provider = ProviderConfig(id = "venice", models = listOf(model))
    private val preset = Preset(id = "original", name = "港口故事", systemPrompt = "保持人物动机一致。",
        summaryPrompt = "保留事件先后和未解决的问题。", summaryProviderId = "summary-provider", summaryModelId = "summary-model")
    private val chat = Chat(id = "story", providerId = provider.id, modelId = model.id, presetId = preset.id,
        messages = listOf(Message(content = "他们来到港口。")), summaries = listOf(Summary(text = "两人约定次日见面。")),
        skillIds = listOf("writing"), continuityNotes = "时间：周三傍晚。")
    private val data = AppData(providers = listOf(provider), presets = listOf(preset), chats = listOf(chat, chat.copy(id = "other")),
        characters = listOf(Character(name = "林岚", defaultPresetId = preset.id)))

    @Test fun targetRequiresVeniceAndExactCatalogId() {
        assertTrue(DeepSeekWriting.isTarget(provider, model.id))
        assertFalse(DeepSeekWriting.isTarget(provider.copy(kind = "openai"), model.id))
        assertFalse(DeepSeekWriting.isTarget(provider, "deepseek-v4-flash"))
        assertFalse(DeepSeekWriting.isTarget(null, model.id))
    }

    @Test fun initialSuggestionsDoNotOverwriteSavedSmallBudgetsOrPlatformPromptChoice() {
        assertEquals(DeepSeekWriting.Selection("low", 8192, false, 8192, true), DeepSeekWriting.initialSelection(preset.copy(generationBudgetEnabled = true), 131072))
        val tuned = preset.copy(id = "deepseek-writing-saved", maxOutput = 1024, reasoningEffort = "none", includeVeniceSystemPrompt = true, generationBudgetEnabled = true)
        assertEquals(DeepSeekWriting.Selection("none", 1024, true, 0, true), DeepSeekWriting.initialSelection(tuned, 131072))
        assertEquals(512, DeepSeekWriting.initialSelection(tuned, 512).maxOutput)
    }

    @Test fun disabledInitialBudgetsPreserveSavedAmountsEvenAboveTheSelectedModelLimit() {
        assertEquals(DeepSeekWriting.Selection("low", 2048, false, 0), DeepSeekWriting.initialSelection(preset, 512))
        val tuned = preset.copy(id = "deepseek-writing-saved", maxOutput = 120000, reasoningBudget = 20000,
            reasoningEffort = "none", includeVeniceSystemPrompt = true)
        assertEquals(DeepSeekWriting.Selection("none", 120000, true, 20000), DeepSeekWriting.initialSelection(tuned, 512))
        assertFalse(DeepSeekWriting.initialSelection(null, null).generationBudgetEnabled)
    }

    @Test fun visibleOutputAndReasoningReserveAreStoredSeparately() {
        val applied = DeepSeekWriting.apply(data, chat.id, "low", 8192, false, 16384, generationBudgetEnabled = true)
        val tuned = applied.presets.single { it.id == applied.chats.first().presetId }
        assertEquals(8192, tuned.maxOutput)
        assertEquals(16384, tuned.reasoningBudget)
        assertTrue(tuned.generationBudgetEnabled)
        assertTrue(runCatching { DeepSeekWriting.apply(data, chat.id, "low", 120000, false, 20000, generationBudgetEnabled = true) }.isFailure)
    }

    @Test fun disabledBudgetDoesNotRequireCatalogLimitOrClampSavedAmounts() {
        val noLimit = model.copy(rawJson = model.rawJson.replace("\"maxCompletionTokens\":131072,", ""))
        val smaller = model.copy(rawJson = model.rawJson.replace("131072", "4096"))
        for (catalogModel in listOf(noLimit, smaller)) {
            val source = data.copy(providers = listOf(provider.copy(models = listOf(catalogModel))))
            val applied = DeepSeekWriting.apply(source, chat.id, "none", 120000, false, 20000)
            val tuned = applied.presets.single { it.id == applied.chats.first().presetId }
            assertFalse(tuned.generationBudgetEnabled)
            assertEquals(120000, tuned.maxOutput)
            assertEquals(20000, tuned.reasoningBudget)
        }
    }

    @Test fun disablingAnEnabledBudgetKeepsItsSavedAmounts() {
        val applied = DeepSeekWriting.apply(data, chat.id, "low", 8192, false, 16384, generationBudgetEnabled = true)
        val updated = DeepSeekWriting.apply(applied, chat.id, "none", 8192, false, 16384, generationBudgetEnabled = false)
        val tuned = updated.presets.single { it.id == updated.chats.first().presetId }
        assertFalse(tuned.generationBudgetEnabled)
        assertEquals(8192, tuned.maxOutput)
        assertEquals(16384, tuned.reasoningBudget)
    }

    @Test fun applyingCopiesPresetWithoutTouchingSharedStoriesOrWritingContent() {
        val applied = DeepSeekWriting.apply(data, chat.id, "low", 8192, false)
        val current = applied.chats.first()
        val tuned = applied.presets.single { it.id == current.presetId }
        assertNotEquals(preset.id, tuned.id)
        assertEquals(chat, current.copy(presetId = chat.presetId))
        assertEquals(data.chats[1], applied.chats[1])
        assertEquals(data.characters, applied.characters)
        assertEquals(preset, applied.presets.first())
        assertEquals(preset, tuned.copy(id = preset.id, name = preset.name, temperature = preset.temperature,
            maxOutput = preset.maxOutput, reasoningEffort = preset.reasoningEffort, includeVeniceSystemPrompt = preset.includeVeniceSystemPrompt))
        assertEquals("low", tuned.reasoningEffort)
        assertEquals(8192, tuned.maxOutput)
        assertFalse(tuned.generationBudgetEnabled)
        assertFalse(tuned.includeVeniceSystemPrompt)
        assertEquals(1.0, tuned.temperature, 0.0)
    }

    @Test fun reapplyingUpdatesOnlyAnUnsharedDedicatedCopy() {
        val applied = DeepSeekWriting.apply(data, chat.id, "low", 8192, false)
        val updated = DeepSeekWriting.apply(applied, chat.id, "none", 4096, true)
        assertEquals(2, updated.presets.size)
        assertEquals(applied.chats, updated.chats)
        assertEquals("none", updated.presets.last().reasoningEffort)
    }

    @Test fun aNewBranchSharingDedicatedPresetKeepsItsExistingSettings() {
        val applied = DeepSeekWriting.apply(data, chat.id, "low", 8192, false)
        val branch = applied.chats.first().copy(id = "branch")
        val shared = applied.copy(chats = applied.chats + branch)
        val updated = DeepSeekWriting.apply(shared, chat.id, "high", 16384, false)
        assertEquals(3, updated.presets.size)
        assertEquals(branch, updated.chats.last())
        assertEquals("low", updated.presets.single { it.id == branch.presetId }.reasoningEffort)
        assertEquals("high", updated.presets.single { it.id == updated.chats.first().presetId }.reasoningEffort)
    }

    @Test fun aCharacterDefaultAlsoPreventsModifyingDedicatedPreset() {
        val applied = DeepSeekWriting.apply(data, chat.id, "low", 8192, false)
        val shared = applied.copy(characters = listOf(Character(name = "周行", defaultPresetId = applied.chats.first().presetId)))
        val updated = DeepSeekWriting.apply(shared, chat.id, "high", 8192, false)
        assertEquals(shared.characters, updated.characters)
        assertEquals(3, updated.presets.size)
        assertEquals("low", updated.presets.single { it.id == shared.characters.first().defaultPresetId }.reasoningEffort)
    }

    @Test fun missingCatalogUnsupportedEffortAndInvalidBudgetsNeverApply() {
        listOf("default", "medium", "unexpected").forEach { effort ->
            assertTrue(runCatching { DeepSeekWriting.apply(data, chat.id, effort, 8192, false) }.isFailure)
        }
        listOf(0, -1, 131073).forEach { output ->
            assertTrue(runCatching { DeepSeekWriting.apply(data, chat.id, "low", output, false) }.isFailure)
        }
        val missing = data.copy(providers = listOf(provider.copy(models = emptyList())))
        assertTrue(runCatching { DeepSeekWriting.apply(missing, chat.id, "low", 8192, false) }.isFailure)
        val noLimit = model.copy(rawJson = model.rawJson.replace("\"maxCompletionTokens\":131072,", ""))
        val offline = model.copy(offline = true)
        listOf(noLimit, offline).forEach {
            assertTrue(runCatching { DeepSeekWriting.apply(data.copy(providers = listOf(provider.copy(models = listOf(it)))), chat.id, "low", 8192, false, generationBudgetEnabled = true) }.isFailure)
        }
        val smaller = model.copy(rawJson = model.rawJson.replace("131072", "4096"))
        assertTrue(runCatching { DeepSeekWriting.apply(data.copy(providers = listOf(provider.copy(models = listOf(smaller)))), chat.id, "low", 8192, false, generationBudgetEnabled = true) }.isFailure)
    }

    @Test fun changingModelCopiesCompatibleSettingsAndPreservesPreviousTunedPreset() {
        val tuned = DeepSeekWriting.apply(data, chat.id, "low", 8192, false, generationBudgetEnabled = true)
        val originalTuned = tuned.presets.last()
        val another = ModelInfo(id = "another-model", rawJson = """{"model_spec":{"maxCompletionTokens":4096}}""")
        val withModel = tuned.copy(providers = listOf(provider.copy(models = listOf(model, another))))
        val switched = DeepSeekWriting.selectModel(withModel, chat.id, provider.id, another.id)
        val active = switched.presets.single { it.id == switched.chats.first().presetId }
        assertEquals("default", active.reasoningEffort)
        assertEquals(4096, active.maxOutput)
        assertEquals(originalTuned, switched.presets.single { it.id == originalTuned.id })
        assertEquals(another.id, switched.chats.first().modelId)
        assertEquals(withModel.chats[1], switched.chats[1])
        assertEquals(chat.messages, switched.chats.first().messages)
        assertEquals(chat.summaries, switched.chats.first().summaries)
    }

    @Test fun changingModelWithDisabledBudgetOnlyAdaptsUnsupportedReasoningEffort() {
        val tuned = DeepSeekWriting.apply(data, chat.id, "low", 120000, false, 20000)
        val originalTuned = tuned.presets.last()
        val another = ModelInfo(id = "smaller-model", rawJson = """{"model_spec":{"maxCompletionTokens":4096}}""")
        val withModel = tuned.copy(providers = listOf(provider.copy(models = listOf(model, another))))
        val switched = DeepSeekWriting.selectModel(withModel, chat.id, provider.id, another.id)
        val active = switched.presets.single { it.id == switched.chats.first().presetId }
        assertEquals("default", active.reasoningEffort)
        assertFalse(active.generationBudgetEnabled)
        assertEquals(120000, active.maxOutput)
        assertEquals(20000, active.reasoningBudget)
        assertEquals(originalTuned, switched.presets.single { it.id == originalTuned.id })
    }

    @Test fun smallerCompatibleModelDoesNotRewriteADisabledBudget() {
        val tuned = DeepSeekWriting.apply(data, chat.id, "none", 120000, false, 20000)
        val smaller = model.copy(id = "smaller-model", rawJson = model.rawJson.replace("131072", "4096"))
        val withModel = tuned.copy(providers = listOf(provider.copy(models = listOf(model, smaller))))
        val switched = DeepSeekWriting.selectModel(withModel, chat.id, provider.id, smaller.id)
        assertEquals(tuned.presets, switched.presets)
        assertEquals(tuned.chats.first().presetId, switched.chats.first().presetId)
    }

    @Test fun changingToCompatibleOrOtherProviderDoesNotCreateUnneededPresetCopies() {
        val tuned = DeepSeekWriting.apply(data, chat.id, "low", 8192, false)
        val compatible = model.copy(id = "compatible")
        val other = provider.copy(id = "openai", kind = "openai")
        val withModels = tuned.copy(providers = listOf(provider.copy(models = listOf(model, compatible)), other))
        for ((providerId, id) in listOf(provider.id to compatible.id, other.id to model.id)) {
            val switched = DeepSeekWriting.selectModel(withModels, chat.id, providerId, id)
            assertEquals(tuned.presets, switched.presets)
            assertEquals(tuned.chats.first().presetId, switched.chats.first().presetId)
        }
    }

    @Test fun summaryDoesNotInheritExplicitChatEffortAcrossModelsOrProviders() {
        val active = preset.copy(reasoningEffort = "max", includeVeniceSystemPrompt = false)
        val another = ModelInfo(id = "another-model")
        val summary = DeepSeekWriting.summaryPreset(active, provider, another, 4096)
        assertEquals("default", summary.reasoningEffort)
        assertEquals(4096, summary.maxOutput)
        assertTrue(summary.generationBudgetEnabled)
        assertEquals(active.summaryPrompt, summary.summaryPrompt)
        assertFalse(summary.includeVeniceSystemPrompt)
        assertEquals("default", DeepSeekWriting.summaryPreset(active, provider.copy(kind = "openai"), model, 4096).reasoningEffort)
        assertEquals("default", DeepSeekWriting.summaryPreset(active, provider, model.copy(rawJson = "{}"), 4096).reasoningEffort)
    }

    @Test fun known1017SummariesDisableReasoningAndRespectCatalogOutputLimit() {
        val limited = model.copy(rawJson = model.rawJson.replace("131072", "2048"))
        val result = DeepSeekWriting.summaryPreset(preset.copy(reasoningEffort = "max"), provider, limited, 4096)
        assertEquals("none", result.reasoningEffort)
        assertEquals(0, result.reasoningBudget)
        assertEquals(2048, result.maxOutput)
        assertTrue(result.generationBudgetEnabled)
    }

    @Test fun summaryPresetDoesNotCarryChatReasoningIntoIncrementalCompression() {
        val active = preset.copy(reasoningEffort = "high", reasoningBudget = 32768, maxOutput = 8192)
        val result = DeepSeekWriting.summaryPreset(active, provider, model, 4096)
        assertEquals("none", result.reasoningEffort)
        assertEquals(0, result.reasoningBudget)
        assertEquals(4096, result.maxOutput)
        assertTrue(result.generationBudgetEnabled)
    }
}
