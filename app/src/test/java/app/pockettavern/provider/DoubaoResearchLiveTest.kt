package app.pockettavern.provider

import app.pockettavern.engine.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in only: a single paid research request, no history or user documents. */
class DoubaoResearchLiveTest {
    @Test fun officialSearchReturnsCitedDossier() = runBlocking {
        val secret = System.getenv("POCKET_ARK_LIVE_KEY").orEmpty()
        assumeTrue("No explicitly supplied Ark live-test key", secret.isNotBlank())
        val model = System.getenv("POCKET_ARK_LIVE_MODEL")?.takeIf(String::isNotBlank) ?: DoubaoResearchClient.DEFAULT_MODEL
        val request = PublicResearchRequest("周迅（中国演员）", ResearchCategory.values().toList(), allowFiction = false)
        val started = System.nanoTime()
        val result = DoubaoResearchClient().research(DoubaoResearchClient.DEFAULT_BASE_URL, secret, model, request)
        assertEquals("ok", result.networkStatus)
        assertTrue(result.items.isNotEmpty())
        assertTrue(result.items.all { it.evidence != ResearchEvidence.FICTIONAL && it.source != null })
        val metadata = JSONObject().put("model", model).put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            .put("items", result.items.size).put("categories", JSONArray(result.items.map { it.category.wireName }.distinct()))
            .put("sources", JSONArray(result.items.mapNotNull { it.source?.url }.distinct())).put("requestedAt", result.requestedAt)
        System.getenv("POCKET_ARK_LIVE_REPORT")?.takeIf(String::isNotBlank)?.let {
            File(it).writeText(metadata.toString(2))
        }
        println("Ark live research verified: ${result.items.size} items, ${result.items.mapNotNull { it.source?.url }.distinct().size} cited sources")
    }
}
