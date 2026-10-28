package app.pockettavern.data

import app.pockettavern.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextBudgetPersistenceTest {
    private fun state(budget: ContextBudgetBreakdown? = null) = AppData(chats = listOf(Chat(summaries = listOf(
        Summary(id = "s", compression = CompressionRecord(budgetBreakdown = budget))
    ))))

    private fun restored(root: JSONObject): ContextBudgetBreakdown? = DataCodec.decode(root.toString())
        .chats.single().summaries.single().compression!!.budgetBreakdown

    private fun compression(root: JSONObject) = root.getJSONArray("chats").getJSONObject(0)
        .getJSONArray("summaries").getJSONObject(0).getJSONObject("compression")

    @Test fun allKnownBudgetFieldsSurviveTheBackupRoundTrip() {
        val budget = ContextBudgetBreakdown(modelWindow = 1_000_000, effectiveWindow = 1_000_000,
            configuredInputLimit = 65_536, modelInputBudget = 923_616, inputBudget = 65_536,
            triggerTokens = 52_428, threshold = .8, safetyReserve = 50_000, outputReserve = 10_000,
            reasoningReserve = 16_384, estimatedInputTokens = 60_000, presetTokens = 1000,
            characterTokens = 2000, memoryTokens = 3000, worldTokens = 4000, skillTokens = 25_000,
            policyTokens = 3000, toolTokens = 2000, summaryTokens = 8000, recentTokens = 12_000,
            projectedTokens = 48_000, triggerReason = "INPUT_LIMIT_THRESHOLD")
        assertEquals(budget, restored(JSONObject(DataCodec.encode(state(budget)))))
    }

    @Test fun legacyMissingAndExplicitNullBudgetStayOptional() {
        val root = JSONObject(DataCodec.encode(state()))
        assertNull(restored(root))
        compression(root).put("budgetBreakdown", JSONObject.NULL)
        assertNull(restored(root))
    }

    @Test fun partialOrNullOptionalFieldsCannotCrashOrPreserveArbitraryEnumText() {
        val root = JSONObject(DataCodec.encode(state()))
        compression(root).put("budgetBreakdown", JSONObject()
            .put("modelWindow", -100).put("skillTokens", -7).put("threshold", 4.0)
            .put("triggerReason", "PRIVATE_PROMPT_SENTINEL").put("blockedReason", JSONObject.NULL)
            .put("projectedTokens", JSONObject.NULL))
        val normalized = restored(root)!!
        assertEquals(0, normalized.modelWindow)
        assertEquals(0, normalized.skillTokens)
        assertEquals(1.0, normalized.threshold, 0.0)
        assertEquals("UNKNOWN", normalized.triggerReason)
        assertEquals("", normalized.blockedReason)
        assertNull(normalized.projectedTokens)
        assertEquals(0, normalized.estimatedInputTokens)
        assertFalse(DataCodec.encode(state(normalized)).contains("PRIVATE_PROMPT_SENTINEL"))
    }

    @Test fun normalizationHandlesGsonNullStringsAndNonFiniteThresholds() {
        val untrusted = DataCodec.gson.fromJson("""{"triggerReason":null,"blockedReason":null,"recentTokens":-1}""", ContextBudgetBreakdown::class.java)
        // Kotlin copy() itself rejects the Gson-created null strings; normalize first.
        val normalized = DataCodec.normalizeBudgetBreakdown(untrusted)
        assertEquals("UNKNOWN", normalized.triggerReason)
        assertEquals("", normalized.blockedReason)
        assertEquals(0, normalized.recentTokens)
        assertEquals(0.0, normalized.threshold, 0.0)
        assertEquals(0.0, DataCodec.normalizeBudgetBreakdown(ContextBudgetBreakdown(threshold = Double.NaN)).threshold, 0.0)
    }

    @Test fun optionalDiagnosticNullHandlingDoesNotPermitNullConversationBodies() {
        val root = JSONObject(DataCodec.encode(state()))
        root.getJSONArray("chats").getJSONObject(0).put("title", JSONObject.NULL)
        assertTrue(runCatching { DataCodec.decode(root.toString()) }.isFailure)
    }
}
