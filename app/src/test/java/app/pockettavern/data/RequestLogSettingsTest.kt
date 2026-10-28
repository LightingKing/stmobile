package app.pockettavern.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RequestLogSettingsTest {
    @Test fun effectiveGenerationSettingsSurviveFilteringWithoutSavingBodiesOrCredentials() {
        val directory = kotlin.io.path.createTempDirectory("request-settings").toFile()
        try {
            val store = RequestLogStore(directory, "test", 36)
            store.record("""{"event":"finished","outcome":"complete","output_budget_field":"max_completion_tokens",
                "max_output_tokens":10240,"visible_max_output_tokens":8192,"reasoning_budget_tokens":2048,"requested_reasoning_effort":"high","default_reasoning_effort":"low",
                "reasoning_effort":"high","temperature_sent":false,"request_tool_count":3,
                "venice_system_prompt_included":false,"disable_thinking":false,"strip_thinking_response":false,
                "messages":[{"content":"private story"}],"venice_parameters":{"private":"drop me"},
                "Authorization":"Bearer private-secret"}""")
            val event = JSONObject(store.exportJsonl().trim())
            assertEquals("max_completion_tokens", event.getString("output_budget_field"))
            assertEquals("high", event.getString("requested_reasoning_effort"))
            assertEquals("low", event.getString("default_reasoning_effort"))
            assertEquals(3, event.getInt("request_tool_count"))
            assertFalse(event.getBoolean("temperature_sent"))
            assertFalse(event.getBoolean("venice_system_prompt_included"))
            assertFalse(event.has("messages"))
            assertFalse(event.has("venice_parameters"))
            assertFalse(store.exportJsonl().contains("private"))
            val summary = store.latestSummary()
            assertTrue(summary.contains("max_completion_tokens=10240（总计；正文上限=8192，思考预留=2048）"))
            assertTrue(summary.contains("请求=high · 目录默认=low · 实际参数=high"))
            assertTrue(summary.contains("温度：未发送"))
            assertTrue(summary.contains("Venice 平台提示：关闭"))

            store.record("""{"event":"finished","outcome":"complete","temperature_sent":true,"temperature":0.85}""")
            assertTrue(store.latestSummary().contains("温度：0.85"))
            store.record("""{"temperature_sent":"false","temperature":"private text","request_tool_count":"bad"}""")
            val invalid = JSONObject(store.exportJsonl().trim().lineSequence().last())
            assertFalse(invalid.has("temperature_sent"))
            assertFalse(invalid.has("temperature"))
            assertFalse(invalid.has("request_tool_count"))
        } finally { directory.deleteRecursively() }
    }
}
