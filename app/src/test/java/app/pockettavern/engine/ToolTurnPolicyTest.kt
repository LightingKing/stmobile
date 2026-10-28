package app.pockettavern.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolTurnPolicyTest {
    private fun query(value: String): String = JSONObject().put("query", value).toString()
    private fun read(id: String = "message:source", offset: Int? = null): String = JSONObject().put("id", id)
        .apply { if (offset != null) put("offset", offset) }.toString()
    private fun hit(id: String = "message:source", offset: Int = 0): JSONObject = JSONObject().put("id", id)
        .put("offset", offset).put("text", "林岚把钥匙交还给周行。")
    private fun hits(vararg entries: JSONObject): String = JSONObject().put("matches", JSONArray().apply { entries.forEach { put(it) } }).toString()
    private fun page(offset: Int, nextOffset: Int?): String = JSONObject().put("id", "message:source").put("offset", offset)
        .put("text", "原文片段 $offset").put("nextOffset", nextOffset ?: JSONObject.NULL).toString()

    @Test fun searchThenReadAndNextPageAreAllowedBeforeARequiredFinalAnswer() {
        val policy = ToolTurnPolicy()
        assertTrue(policy.beforeCall("history_search", query("林岚")).execute)
        policy.recordResult("history_search", query("林岚"), hits(hit()))
        policy.completedRound(listOf("history_search"))
        assertFalse(policy.shouldFinalize)
        assertTrue(policy.beforeCall("history_read", read()).execute)
        policy.recordResult("history_read", read(), page(0, 40))
        policy.completedRound(listOf("history_read"))
        assertFalse(policy.shouldFinalize)
        assertTrue(policy.beforeCall("history_read", read(offset = 40)).execute)
        policy.recordResult("history_read", read(offset = 40), page(40, null))
        policy.completedRound(listOf("history_read"))
        assertTrue(policy.shouldFinalize)
        assertEquals("memory_limit", policy.reason)
        assertFalse(policy.beforeCall("history_search", query("更多")).execute)
        assertTrue(policy.finalInstruction.contains("直接回答用户最新要求"))
        assertTrue(policy.finalInstruction.contains("不得再调用工具"))
    }

    @Test fun duplicateNormalizedSearchDoesNotExecuteOrAppendAnotherCopyOfEvidence() {
        val policy = ToolTurnPolicy()
        assertTrue(policy.beforeCall("history_search", query(" Lin   KEY ")).execute)
        policy.recordResult("history_search", query(" Lin   KEY "), hits(hit()))
        val duplicate = policy.beforeCall("history_search", query("lin key"))
        assertFalse(duplicate.execute)
        assertEquals("duplicate_memory_call", JSONObject(duplicate.response!!).getString("error"))
        assertFalse(duplicate.response.orEmpty().contains("林岚把钥匙"))
        assertTrue(policy.shouldFinalize)
        assertEquals("duplicate_memory_call", policy.reason)
    }

    @Test fun readArgumentOrderAndDefaultOffsetDoNotBypassDuplicateDetection() {
        val policy = ToolTurnPolicy()
        assertTrue(policy.beforeCall("history_read", """{"id":"message:source"}""").execute)
        policy.recordResult("history_read", """{"id":"message:source"}""", page(0, 20))
        assertFalse(policy.beforeCall("history_read", """{"offset":0,"id":"message:source"}""").execute)
        assertEquals("duplicate_memory_call", policy.reason)
    }

    @Test fun variedQueriesReturningSameEvidenceStopInsteadOfSpendingAllModelSteps() {
        val policy = ToolTurnPolicy()
        listOf("林岚", "林岚的钥匙", "林岚此前的约定").forEach { term ->
            assertTrue(policy.beforeCall("history_search", query(term)).execute)
            policy.recordResult("history_search", query(term), hits(hit()))
        }
        assertTrue(policy.shouldFinalize)
        assertEquals("no_new_memory", policy.reason)
        assertFalse(policy.beforeCall("history_read", read()).execute)
    }

    @Test fun differentOffsetsInOneLargeSourceCountAsNewEvidence() {
        val policy = ToolTurnPolicy(memoryRoundLimit = 6)
        for (offset in listOf(0, 1000, 2000)) {
            val arguments = query("林岚 第 $offset 处")
            assertTrue(policy.beforeCall("history_search", arguments).execute)
            policy.recordResult("history_search", arguments, hits(hit(offset = offset)))
        }
        assertFalse(policy.shouldFinalize)
        assertTrue(policy.beforeCall("history_read", read(offset = 2000)).execute)
    }

    @Test fun emptyOrInvalidResultsStopAfterTwoNoProgressAttemptsButBudgetStopsImmediately() {
        val empty = ToolTurnPolicy()
        assertTrue(empty.beforeCall("history_search", query("第一次")).execute)
        empty.recordResult("history_search", query("第一次"), "[]")
        assertFalse(empty.shouldFinalize)
        assertTrue(empty.beforeCall("history_search", query("换个词")).execute)
        empty.recordResult("history_search", query("换个词"), "not valid json")
        assertEquals("no_new_memory", empty.reason)
        val budget = ToolTurnPolicy()
        assertTrue(budget.beforeCall("history_read", read()).execute)
        budget.recordResult("history_read", read(), """{"error":"budget_exhausted"}""")
        assertTrue(budget.shouldFinalize)
        assertEquals("memory_budget_exhausted", budget.reason)
    }

    @Test fun sixUsefulMemoryCallsInOneBatchAreAllowedThenRemainingToolsArePairedWithStopResults() {
        val policy = ToolTurnPolicy()
        repeat(6) { index ->
            val arguments = query("角色 $index")
            assertTrue(policy.beforeCall("history_search", arguments).execute)
            policy.recordResult("history_search", arguments, hits(hit(id = "message:$index")))
        }
        assertTrue(policy.shouldFinalize)
        assertEquals("memory_limit", policy.reason)
        val stopped = policy.beforeCall("history_search", query("第七个"))
        assertFalse(stopped.execute)
        assertEquals("tool_turn_finished", JSONObject(stopped.response!!).getString("error"))
        assertFalse(policy.beforeCall("workspace_write", "{}").execute)
    }

    @Test fun unrelatedToolsDoNotConsumeMemoryAllowanceAndIgnoredRecordsCannotCreateProgress() {
        val policy = ToolTurnPolicy()
        repeat(8) {
            assertTrue(policy.beforeCall("skill_read", "{}").execute)
            policy.recordResult("skill_read", "{}", "skill text")
            policy.completedRound(listOf("skill_read"))
        }
        assertFalse(policy.shouldFinalize)
        policy.recordResult("history_search", query("not executed"), "[]")
        assertTrue(policy.beforeCall("history_search", query("real lookup")).execute)
        policy.recordResult("history_search", query("real lookup"), "[]")
        policy.recordResult("history_search", query("real lookup"), "[]")
        assertFalse(policy.shouldFinalize)
    }

    @Test fun aNewGenerationCanQueryTheSameMemoryAgain() {
        val previous = ToolTurnPolicy()
        previous.beforeCall("history_read", read())
        previous.recordResult("history_read", read(), page(0, null))
        assertFalse(previous.beforeCall("history_read", read()).execute)
        val fresh = ToolTurnPolicy()
        assertTrue(fresh.beforeCall("history_read", read()).execute)
        assertFalse(fresh.shouldFinalize)
    }

    @Test fun finalModeAtStepCapAddsInstructionWithoutChangingToolEvidence() {
        val policy = ToolTurnPolicy()
        assertFalse(policy.finalAnswerRequired(0, 1))
        assertTrue(policy.finalAnswerRequired(1, 1))
        val input = """[{"role":"system","content":"existing"},{"role":"user","content":"question"},{"role":"assistant","tool_calls":[{"id":"a"}]},{"role":"tool","tool_call_id":"a","content":"evidence"}]"""
        val original = JSONArray(input)
        val output = JSONArray(policy.finalMessages(input))
        assertEquals(5, output.length())
        assertEquals(policy.finalInstruction, output.getJSONObject(1).getString("content"))
        assertEquals(original.getJSONObject(2).toString(), output.getJSONObject(3).toString())
        assertEquals(original.getJSONObject(3).toString(), output.getJSONObject(4).toString())
    }
}
