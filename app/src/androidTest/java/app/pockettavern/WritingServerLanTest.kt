package app.pockettavern

import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.provider.ProviderClient
import kotlinx.coroutines.runBlocking

/** Opt-in read-only check; no chat generation and no account credential in APK. */
@Suppress("DEPRECATION")
class WritingServerLanTest : InstrumentationTestCase() {
    fun testConfiguredLanCatalogUsesProductionUrlPolicy() = runBlocking {
        val arguments = (instrumentation as InstrumentationTestRunner).arguments
        val base = arguments.getString("lanBase") ?: return@runBlocking
        val token = arguments.getString("lanToken") ?: return@runBlocking
        val models = ProviderClient().models(
            ProviderConfig(kind = "writing-server", baseUrl = base), token
        )
        assertEquals(setOf("pocket-direct", "pocket-writer"), models.map { it.id }.toSet())
        assertTrue(models.all { it.type == "text" && it.contextTokens == 1_000_000 })
    }
}
