package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Preset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GenerationBudgetPersistenceTest {
    @Test fun newPresetsAndDefaultAppPresetsDoNotEnableGenerationBudgets() {
        assertFalse(Preset().generationBudgetEnabled)
        assertTrue(AppData().presets.all { !it.generationBudgetEnabled })
    }

    @Test fun backupAndStandalonePresetRoundTripsKeepExplicitSwitchAndSavedAmounts() {
        for (enabled in listOf(false, true)) {
            val preset = Preset(id = "story", name = "故事", maxOutput = 120000, reasoningBudget = 20000,
                reasoningEffort = "high", generationBudgetEnabled = enabled)
            val backup = DataCodec.encode(AppData(presets = listOf(preset)))
            val portable = Exchange.presetJson(preset)
            assertEquals(enabled, JSONObject(backup).getJSONArray("presets").getJSONObject(0).getBoolean("generationBudgetEnabled"))
            assertEquals(enabled, JSONObject(portable).getBoolean("generationBudgetEnabled"))
            assertEquals(preset, DataCodec.decode(backup).presets.single())
            assertEquals(preset, Exchange.preset(portable))
        }
    }

    @Test fun legacyBackupDisablesBudgetWithoutErasingSavedAmounts() {
        val preset = Preset(id = "legacy", maxOutput = 10000, reasoningBudget = 32768,
            reasoningEffort = "high", generationBudgetEnabled = true)
        val root = JSONObject(DataCodec.encode(AppData(presets = listOf(preset))))
        root.getJSONArray("presets").getJSONObject(0).remove("generationBudgetEnabled")
        val restored = DataCodec.decode(root.toString()).presets.single()
        assertEquals(preset.copy(generationBudgetEnabled = false), restored)
        assertFalse(DataCodec.decode(DataCodec.encode(AppData(presets = listOf(restored)))).presets.single().generationBudgetEnabled)
    }

    @Test fun legacyPortableImportsPreserveNumericAliasesWithoutEnablingBudget() {
        val legacy = JSONObject().put("name", "旧预设").put("max_tokens", 10000)
            .put("thinkingBudget", 32768).put("reasoningEffort", "high")
        for (json in listOf(legacy, JSONObject().put("data", legacy))) {
            val imported = Exchange.preset(json.toString())
            assertFalse(imported.generationBudgetEnabled)
            assertEquals(10000, imported.maxOutput)
            assertEquals(32768, imported.reasoningBudget)
        }
    }

    @Test fun invalidGenerationBudgetSwitchTypesAreRejectedByBothImportPaths() {
        val invalidValues = listOf("true", "false", 0, 1, JSONObject.NULL, JSONObject(), JSONArray())
        for (value in invalidValues) {
            val backup = JSONObject(DataCodec.encode(AppData()))
            backup.getJSONArray("presets").getJSONObject(0).put("generationBudgetEnabled", value)
            assertTrue("backup accepted $value", runCatching { DataCodec.decode(backup.toString()) }.isFailure)
            val portable = JSONObject().put("name", "错误开关").put("generationBudgetEnabled", value)
            assertTrue("preset accepted $value", runCatching { Exchange.preset(portable.toString()) }.isFailure)
        }
    }
}
