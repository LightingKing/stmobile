package app.pockettavern.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    private val providers = listOf(
        ProviderConfig(
            id = "venice-account", name = "Venice 主账户", kind = "venice",
            models = listOf(ModelInfo(id = "shared", name = "Gemini Chat"), ModelInfo(id = "picture", type = "image"))
        ),
        ProviderConfig(
            id = "custom-account", name = "我的备用平台", kind = "openai", baseUrl = "https://api.example.test/v1",
            models = listOf(ModelInfo(id = "shared", name = "Gemini Backup"), ModelInfo(id = "speech", type = "tts"))
        )
    )

    @Test fun providerSelectionKeepsSameNamedModelsSeparated() {
        val result = ModelCatalog.filter(providers, providerId = "custom-account")
        assertEquals(listOf("shared", "speech"), result.map { it.second.id })
        assertTrue(result.all { it.first.id == "custom-account" })
        assertEquals(4, ModelCatalog.filter(providers).size)
    }

    @Test fun queryFindsPlatformNameIdProtocolAndHost() {
        for (query in listOf("备用平台", "CUSTOM-ACCOUNT", "openai", "api.example.test")) {
            val result = ModelCatalog.filter(providers, query = query)
            assertEquals(2, result.size)
            assertTrue(result.all { it.first.id == "custom-account" })
        }
    }

    @Test fun multipleTermsCanMatchBothPlatformAndModel() {
        val result = ModelCatalog.filter(providers, query = "  VENICE   geMini ")
        assertEquals(listOf("venice-account" to "shared"), result.map { it.first.id to it.second.id })
    }

    @Test fun providerTypeAndSearchAllApplyTogether() {
        assertEquals(listOf("speech"), ModelCatalog.filter(providers, "备用", "TTS", "custom-account").map { it.second.id })
        assertTrue(ModelCatalog.filter(providers, "speech", "image", "custom-account").isEmpty())
        assertTrue(ModelCatalog.filter(providers, "Gemini", "text", "missing-account").isEmpty())
    }

    @Test fun blankSearchAndUnknownQueriesPreserveSourceData() {
        val original = providers.toList()
        assertEquals(4, ModelCatalog.filter(providers, " \t ").size)
        assertTrue(ModelCatalog.filter(providers, "does-not-exist").isEmpty())
        assertTrue(ModelCatalog.filter(emptyList()).isEmpty())
        assertEquals(original, providers)
    }
}
