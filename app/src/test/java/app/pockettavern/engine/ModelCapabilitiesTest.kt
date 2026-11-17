package app.pockettavern.engine

import app.pockettavern.data.DataCodec
import app.pockettavern.domain.AppData
import app.pockettavern.domain.ModelEdit
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.3.4: models synced from a relay came in without vision, and nothing could change that. */
class ModelCapabilitiesTest {
    private fun bare(id: String) = ModelInfo(id = id, type = "text", contextTokens = 32_768, rawJson = """{"id":"$id","object":"model"}""")
    private val pcModel = ModelInfo(id = "kimi-k3-1-pro", type = "text", contextTokens = 1_000_000, supportsTools = true, supportsVision = true, supportsReasoning = true,
        rawJson = """{"id":"kimi-k3-1-pro","model_spec":{"availableContextTokens":1000000,"capabilities":{"supportsVision":true,"supportsFunctionCalling":true}}}""")
    private val pc = ProviderConfig(id = "pc", kind = "writing-server", models = listOf(pcModel))
    private val relay = ProviderConfig(id = "relay", kind = "openai")

    @Test fun aRefreshedBareModelBorrowsWhatTheSameModelSaysElsewhere() {
        val models = ModelCapabilities.refreshed("relay", listOf(bare("k3.1-pro"), bare("mystery-7b")), listOf(relay, pc), emptyMap())
        val k3 = models.first()
        assertTrue(k3.supportsVision)
        assertTrue(k3.supportsTools)
        assertTrue(k3.supportsReasoning)
        assertEquals(1_000_000, k3.contextTokens)
        val unknown = models[1]
        assertFalse(unknown.supportsVision)
        assertEquals(32_768, unknown.contextTokens)
    }

    @Test fun aModelThatDescribesItselfIsTakenAtItsWord() {
        val said = ModelInfo(id = "k3.1-pro", type = "text", contextTokens = 128_000, supportsVision = false,
            rawJson = """{"id":"k3.1-pro","context_length":128000,"supports_vision":false}""")
        val model = ModelCapabilities.refreshed("relay", listOf(said), listOf(relay, pc), emptyMap()).single()
        assertFalse(model.supportsVision)
        assertEquals(128_000, model.contextTokens)
    }

    @Test fun theUsersCorrectionsSurviveEveryRefresh() {
        val edits = mapOf("mystery-7b" to ModelEdit(vision = true, contextTokens = 65_536))
        val model = ModelCapabilities.refreshed("relay", listOf(bare("mystery-7b")), listOf(relay, pc), edits).single()
        assertTrue(model.supportsVision)
        assertEquals(65_536, model.contextTokens)
        val provider = relay.copy(models = listOf(model), modelEdits = edits)
        assertEquals(65_536, ModelWindows.of(provider.models.single(), listOf(provider, pc)))
    }

    @Test fun aTypedWindowWinsOverTheOneFoundElsewhere() {
        val provider = ModelCapabilities.withEdit(relay.copy(models = listOf(bare("k3.1-pro"))), "k3.1-pro", ModelEdit(contextTokens = 200_000))
        assertEquals(200_000, ModelWindows.of(provider.models.single(), listOf(provider, pc)))
        val cleared = ModelCapabilities.withEdit(provider, "k3.1-pro", null)
        assertTrue(cleared.modelEdits.isEmpty())
        assertEquals(1_000_000, ModelWindows.of(cleared.models.single(), listOf(cleared, pc)))
    }

    @Test fun anEditTurningVisionOffIsKeptToo() {
        val provider = ModelCapabilities.withEdit(pc, "kimi-k3-1-pro", ModelEdit(vision = false))
        assertFalse(provider.models.single().supportsVision)
        assertTrue(provider.models.single().supportsTools)
        assertTrue(ModelCapabilities.withEdit(pc, "kimi-k3-1-pro", ModelEdit()).modelEdits.isEmpty())
    }

    @Test fun savesFromBeforeCorrectionsExistedStillLoad() {
        val saved = JSONObject(DataCodec.encode(AppData(providers = listOf(pc))))
        val provider = saved.getJSONArray("providers").getJSONObject(0)
        provider.remove("modelEdits")
        val restored = DataCodec.decode(saved.toString()).providers.single()
        assertEquals(emptyMap<String, ModelEdit>(), restored.modelEdits)
        val withEdit = AppData(providers = listOf(ModelCapabilities.withEdit(pc, "kimi-k3-1-pro", ModelEdit(vision = false, contextTokens = 99_999_999))))
        val roundTrip = DataCodec.decode(DataCodec.encode(withEdit)).providers.single().modelEdits.getValue("kimi-k3-1-pro")
        assertEquals(false, roundTrip.vision)
        assertEquals(0, roundTrip.contextTokens)
    }
}
