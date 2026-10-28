package app.pockettavern.ui

import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelLabelsTest {
    private val stable = ModelInfo(id = "deepseek-v4-1-flash", name = "DeepSeek V4.1 Flash", type = "text", contextTokens = 1_000_000)
    private val snapshot = stable.copy(id = "deepseek-v4-1-flash-1017")
    private val venice = ProviderConfig(id = "venice", name = "Venice", models = listOf(stable, snapshot))

    @Test fun sameNameModelsDifferByTheirIdLine() {
        val first = modelRowLabel(venice, stable)
        val second = modelRowLabel(venice, snapshot)
        assertEquals("DeepSeek V4.1 Flash", first.title)
        assertEquals(first.title, second.title)
        assertEquals("Venice · 976K 上下文", first.meta)
        assertEquals(first.meta, second.meta)
        assertEquals("deepseek-v4-1-flash", first.id)
        assertEquals("deepseek-v4-1-flash-1017", second.id)
        assertNotEquals(first, second)
    }

    @Test fun everyModelShowsItsIdUnlessTheIdIsAlreadyTheTitle() {
        val other = ModelInfo(id = "venice-uncensored", name = "Venice Uncensored", type = "text", contextTokens = 32_768)
        assertEquals("venice-uncensored", modelRowLabel(venice, other).id)
        val unnamed = ModelInfo(id = "qwen-3-8-max", type = "text", contextTokens = 131_072)
        assertEquals("qwen-3-8-max", modelRowLabel(venice, unnamed).title)
        assertNull(modelRowLabel(venice, unnamed).id)
    }

    @Test fun selectedModelShowsItsIdWhenAnotherModelSharesTheName() {
        assertEquals("deepseek-v4-1-flash-1017", selectedModelId(listOf(venice), snapshot))
        assertEquals("deepseek-v4-1-flash", selectedModelId(listOf(venice), stable))
        val unique = ModelInfo(id = "gemini-4-argon", name = "Gemini 4 Argon", type = "text")
        assertNull(selectedModelId(listOf(venice.copy(models = venice.models + unique)), unique))
        val sameNameElsewhere = ProviderConfig(id = "other", name = "其他平台", models = listOf(unique.copy(id = "google/gemini-4-argon")))
        assertEquals("gemini-4-argon", selectedModelId(listOf(venice.copy(models = listOf(unique)), sameNameElsewhere), unique))
        val offlineTwin = stable.copy(id = "deepseek-v4-1-flash-old", offline = true)
        assertNull(selectedModelId(listOf(venice.copy(models = listOf(stable, offlineTwin))), stable))
    }
}
