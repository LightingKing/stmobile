package app.pockettavern.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTypesTest {
    @Test fun explicitVeniceTypesRemainDistinct() {
        val types = listOf("text", "image", "inpaint", "upscale", "tts", "asr", "video", "music", "embedding", "decision", "model", "vendor-special")
        types.forEach { type ->
            val model = ModelInfo(id = type, type = type, offline = true, rawJson = """{"pricing":{"usd":0.01}}""")
            assertEquals(model, ModelTypes.normalize(model, "venice"))
        }
    }

    @Test fun caseAndWhitespaceAreCanonicalizedWithoutLosingMetadata() {
        val model = ModelInfo(id = "cached", type = " TEXT ", supportsVision = true, rawJson = "not-json")
        assertEquals(model.copy(type = "text"), ModelTypes.normalize(model, "openai"))
    }

    @Test fun absentTypeUsesNestedCapabilityBeforeTextFallback() {
        val image = ModelInfo(type = "", rawJson = """{"model_spec":{"type":" IMAGE "}}""")
        assertEquals("image", ModelTypes.normalize(image, "openai").type)
        val cachedImage = ModelInfo(type = "", rawJson = """{"type":"image"}""")
        assertEquals("image", ModelTypes.normalize(cachedImage, "openai").type)
        assertEquals("text", ModelTypes.normalize(ModelInfo(type = "", rawJson = "{}"), "openai").type)
        assertEquals("text", ModelTypes.normalize(ModelInfo(type = "model", rawJson = "not-json"), "anthropic").type)
        assertEquals("text", ModelTypes.normalize(ModelInfo(type = "model", rawJson = "not-json"), "writing-server").type)
    }
}
