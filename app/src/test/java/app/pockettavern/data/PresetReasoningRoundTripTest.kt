package app.pockettavern.data

import app.pockettavern.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PresetReasoningRoundTripTest {
    @Test fun backupAndPresetRoundTripsKeepExplicitReasoningAndPlatformPromptChoice() {
        val preset = Preset(name = "故事", reasoningEffort = "low", reasoningBudget = 8192, includeVeniceSystemPrompt = false)
        assertEquals(preset, Exchange.preset(Exchange.presetJson(preset)))
        assertEquals(preset, DataCodec.decode(DataCodec.encode(AppData(presets = listOf(preset)))).presets.single())
    }

    @Test fun legacyBackupsAndImportsKeepPlatformDefaultsWithoutSilentTuning() {
        val data = JSONObject(DataCodec.encode(AppData()))
        val presets = data.getJSONArray("presets")
        for (i in 0 until presets.length()) {
            presets.getJSONObject(i).remove("reasoningEffort")
            presets.getJSONObject(i).remove("includeVeniceSystemPrompt")
            presets.getJSONObject(i).remove("reasoningBudget")
        }
        DataCodec.decode(data.toString()).presets.forEach {
            assertEquals("default", it.reasoningEffort)
            assertTrue(it.includeVeniceSystemPrompt)
        }
        val imported = Exchange.preset("""{"name":"旧预设","max_tokens":4096}""")
        assertEquals("default", imported.reasoningEffort)
        assertTrue(imported.includeVeniceSystemPrompt)
        assertEquals(4096, imported.maxOutput)
        assertEquals(0, imported.reasoningBudget)
    }

    @Test fun importedReasoningReserveIsAcceptedAndExported() {
        val imported = Exchange.preset("""{"name":"新预算","maxOutput":4096,"reasoningEffort":"high","reasoningBudget":12288}""")
        assertEquals(12288, imported.reasoningBudget)
        assertTrue(Exchange.presetJson(imported).contains("\"reasoningBudget\": 12288"))
    }

    @Test fun invalidImportedReasoningAndPromptSwitchAreRejected() {
        listOf("""{"reasoningEffort":"extreme"}""", """{"reasoningEffort":null}""",
            """{"includeVeniceSystemPrompt":"false"}""").forEach {
            assertTrue(runCatching { Exchange.preset(it) }.isFailure)
        }
        val data = AppData(presets = listOf(Preset(reasoningEffort = "extreme")))
        assertTrue(runCatching { DataCodec.decode(DataCodec.encode(data)) }.isFailure)
    }
}
