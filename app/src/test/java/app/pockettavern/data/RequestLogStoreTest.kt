package app.pockettavern.data

import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLogStoreTest {
    @Test fun omittedGenerationBudgetIsDisplayedAsPlatformDefaultAndSurvivesExport() {
        val directory = java.nio.file.Files.createTempDirectory("omitted-budget").toFile()
        try {
            val store = RequestLogStore(directory, "test", 34)
            store.record("""{"event":"finished","purpose":"chat","outcome":"complete","generation_budget_enabled":false,"output_budget_field":"omitted","max_output_tokens":0}""")
            assertTrue(store.latestSummary().contains("输出预算：未发送（跟随平台）"))
            assertFalse(store.latestSummary().contains("omitted=0"))
            assertTrue(store.latestRequestJson().orEmpty().contains("\"generation_budget_enabled\":false"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun balanceAndBudgetDiagnosticsStaySeparateFromBillableChat() {
        withStore { store, _ ->
            store.record("""{"event":"finished","purpose":"chat","trace_id":"chat-1","outcome":"complete"}""")
            store.record("""{"event":"context_budget","purpose":"context_budget","context_input_tokens":60000,"context_trigger_tokens":52428,"context_input_budget":65536,"context_skill_tokens":27000,"context_summary_tokens":8000,"context_recent_tokens":21000,"outcome":"INPUT_LIMIT_THRESHOLD","prompt":"private source"}""")
            store.record("""{"event":"balance_finished","purpose":"venice_balance","trace_id":"balance-1","endpoint_path":"/billing/balance","phase":"account_response_headers","outcome":"timeout","call_timeout_ms":45000,"balance":123.45,"key":"private key"}""")
            val summary = store.latestSummary()
            assertTrue(summary.contains("查询接口：/billing/balance"))
            assertTrue(summary.contains("最近上下文估算：60000"))
            assertTrue(summary.contains("Skills：27000"))
            assertTrue(store.latestRequestJson().orEmpty().contains("chat-1"))
            assertFalse(store.latestRequestJson().orEmpty().contains("balance-1"))
            assertFalse(store.exportJsonl().contains("private"))
            assertFalse(store.exportJsonl().contains("123.45"))
        }
    }

    @Test
    fun filtersPrivateContentAndKeepsAllowlistedUsageNumbers() {
        withStore { store, _ ->
            store.record(
                """
                {
                  "trace_id":"trace-1",
                  "event":"completion",
                  "phase":"stream",
                  "provider_name":"Venice sk-super-secret-token",
                  "provider_id":"venice-provider",
                  "model_id":"venice-model",
                  "purpose":"chat",
                  "api_host":"https://api.example.test/v1",
                  "request_id":"req-1",
                  "response_id":"resp-1",
                  "http_status":200,
                  "done_signal":true,
                  "content_chars":42,
                  "input_chars":900,
                  "prompt":"do not save this prompt",
                  "response_body":"do not save this response",
                  "headers":{"Authorization":"Bearer top-secret"},
                  "error":{"message":"private failure"},
                  "usage":{
                    "prompt_tokens":12,
                    "completion_tokens":8,
                    "total_tokens":20,
                    "reasoning_tokens":3,
                    "cached_tokens":2,
                    "cost":0.123,
                    "secret":"drop me",
                    "completion_tokens_details":{"reasoning_tokens":3,"text":"drop me"},
                    "prompt_tokens_details":{"cached_tokens":2,"raw":"drop me"}
                  }
                }
                """.trimIndent(),
            )

            val exported = store.exportJsonl()
            assertFalse(exported.contains("do not save"))
            assertFalse(exported.contains("Authorization"))
            assertFalse(exported.contains("private failure"))
            assertFalse(exported.contains("sk-super-secret-token"))
            assertFalse(exported.contains("top-secret"))
            assertTrue(exported.contains("[REDACTED]"))
            assertTrue(exported.contains("\"prompt_tokens\":12"))
            assertTrue(exported.contains("\"completion_tokens\":8"))
            assertTrue(exported.contains("\"total_tokens\":20"))
            assertTrue(exported.contains("\"cost\":0.123"))
            assertTrue(exported.contains("\"reasoning_tokens\":3"))
            assertTrue(exported.contains("\"cached_tokens\":2"))

            val line = exported.trim().lineSequence().single()
            val objectValue = JsonParser.parseString(line).asJsonObject
            assertEquals("0.1.6", objectValue.get("app_version").asString)
            assertEquals(35, objectValue.get("android_sdk").asInt)
            assertEquals("venice-provider", objectValue.get("provider_id").asString)
            assertEquals("resp-1", objectValue.get("response_id").asString)
            assertEquals("chat", objectValue.get("purpose").asString)
            assertTrue(objectValue.get("done_signal").asBoolean)
            assertFalse(objectValue.has("prompt"))
            assertFalse(objectValue.has("response_body"))
            assertFalse(objectValue.has("headers"))
            assertFalse(objectValue.has("error"))
            assertFalse(objectValue.getAsJsonObject("usage").has("secret"))
            assertFalse(objectValue.getAsJsonObject("usage").getAsJsonObject("completion_tokens_details").has("text"))
        }
    }

    @Test
    fun keepsAppStateAndErrorTypeForBackgroundNetworkDiagnosis() {
        withStore { store, _ ->
            store.record("""{"trace_id":"t","event":"finished","outcome":"error","app_state":"background","error_type":"UnknownHostException","error_message":"private host detail"}""")
            val line = JsonParser.parseString(store.exportJsonl().trim()).asJsonObject
            assertEquals("background", line.get("app_state").asString)
            assertEquals("UnknownHostException", line.get("error_type").asString)
            assertFalse(line.has("error_message"))
            assertTrue(store.latestSummary().contains("应用状态：后台 · 异常类型：UnknownHostException"))
            assertFalse(store.latestSummary().contains("private host detail"))
        }
    }

    @Test
    fun bearerAndShortKeysAreRedactedInAllowedStrings() {
        withStore { store, _ ->
            store.record("""{"phase":"Bearer abc.def-123", "request_id":"sk-test", "event":"ok"}""")
            val output = store.exportJsonl()
            assertFalse(output.contains("Bearer abc.def-123"))
            assertFalse(output.contains("sk-test"))
            assertTrue(output.contains("Bearer [REDACTED]"))
            assertTrue(output.contains("[REDACTED]"))
        }
    }

    @Test
    fun retainsAtMostTwoHundredEventsAndOneMiB() {
        withStore { store, _ ->
            repeat(260) { index ->
                store.record("""{"event":"event-$index", "started_at_ms":$index}""")
            }
            val lines = store.exportJsonl().trim().lines()
            assertTrue(lines.size <= 200)
            assertTrue(store.exportJsonl().toByteArray(StandardCharsets.UTF_8).size <= 1 * 1024 * 1024)
            assertTrue(lines.last().contains("event-259"))
        }
    }

    @Test
    fun reopensAndPreservesOriginalRecordingVersion() {
        withStore { store, dir ->
            store.record("""{"event":"first", "started_at_ms":1700000000000}""")
            val reopened = RequestLogStore(dir, appVersion = "0.2.0", androidSdk = 36)
            val output = reopened.exportJsonl()
            assertTrue(output.contains("\"app_version\":\"0.1.6\""))
            assertTrue(output.contains("\"android_sdk\":35"))

            reopened.record("""{"event":"second"}""")
            val lines = reopened.exportJsonl().trim().lines()
            assertEquals(2, lines.size)
            assertTrue(lines[1].contains("\"app_version\":\"0.2.0\""))
            assertTrue(lines[1].contains("\"android_sdk\":36"))
        }
    }

    @Test
    fun ignoresUnparseableEventsAndSummaryDistinguishesUnknowns() {
        withStore { store, _ ->
            store.record("not-json")
            store.record("[]")
            assertEquals("", store.exportJsonl())

            store.record("""{"event":"finished", "purpose":"chat", "outcome":"timeout", "elapsed_ms":120000, "http_status":504, "request_id":"req-timeout", "response_id":"resp-timeout", "first_byte_ms":120000, "first_delta_ms":120000}""")
            val summary = store.latestSummary()
            assertTrue(summary.contains("版本 0.1.6"))
            assertTrue(summary.contains("请求数：1"))
            assertTrue(summary.contains("耗时：120.00s"))
            assertTrue(summary.contains("HTTP：504"))
            assertTrue(summary.contains("结果：timeout"))
            assertTrue(summary.contains("首字节：120.00s"))
            assertTrue(summary.contains("首个增量：120.00s"))
            assertTrue(summary.contains("request_id：req-timeout"))
            assertTrue(summary.contains("response_id：resp-timeout"))
            assertTrue(summary.contains("finish_reason：未知"))
            assertTrue(summary.contains("token：未知"))
            assertTrue(summary.contains("计费状态：未知"))
            assertTrue(store.latestRequestJson().orEmpty().contains("\"outcome\":\"timeout\""))
        }
    }

    @Test
    fun explainsZeroTimeoutsAsUnsetAndKeepsLegacyReadTimeoutField() {
        withStore { store, _ ->
            store.record(
                """{"event":"finished","purpose":"chat","outcome":"complete","timeout_ms":0,"read_timeout_ms":0,"connect_timeout_ms":0,"write_timeout_ms":0,"call_timeout_ms":0}""",
            )

            val summary = store.latestSummary()
            val unset = "未设置（等待完成或手动停止）"
            assertTrue(summary.contains("读取空闲超时：$unset"))
            assertTrue(summary.contains("连接超时：$unset"))
            assertTrue(summary.contains("写入超时：$unset"))
            assertTrue(summary.contains("整轮上限：$unset"))
            assertFalse(summary.contains("0ms"))
            assertFalse(summary.contains("0.00s"))

            store.clear()
            store.record("""{"event":"finished","purpose":"chat","outcome":"complete","timeout_ms":0,"call_timeout_ms":1500}""")
            val legacySummary = store.latestSummary()
            assertTrue(legacySummary.contains("读取空闲超时：$unset"))
            assertTrue(legacySummary.contains("整轮上限：1.50s"))
        }
    }

    @Test
    fun requestCountUsesDistinctTraceIdsAndLatestRequestReturnsTerminalEvent() {
        withStore { store, _ ->
            store.record("""{"trace_id":"trace-a", "event":"started", "purpose":"chat"}""")
            store.record("""{"trace_id":"trace-a", "event":"headers", "purpose":"chat", "http_status":200}""")
            store.record("""{"trace_id":"trace-a", "event":"finished", "purpose":"chat", "outcome":"complete", "request_id":"req-a"}""")
            store.record("""{"trace_id":"trace-b", "event":"finished", "purpose":"compression", "outcome":"error", "request_id":"req-b", "usage":{"completion_tokens_details":{"reasoning_tokens":7}, "prompt_tokens_details":{"cached_tokens":5}}}""")

            val summary = store.latestSummary()
            assertTrue(summary.contains("日志事件数：4"))
            assertTrue(summary.contains("请求数：2"))
            assertTrue(summary.contains("purpose：compression"))
            assertTrue(summary.contains("reasoning=7"))
            assertTrue(summary.contains("cached=5"))
            val latest = store.latestRequestJson().orEmpty()
            assertTrue(latest.contains("\"trace_id\":\"trace-b\""))
            assertTrue(latest.contains("\"outcome\":\"error\""))
            assertFalse(latest.contains("trace-a"))
        }
    }

    @Test
    fun clearRemovesEventsAndTemporaryReplacement() {
        withStore { store, dir ->
            store.record("""{"event":"one"}""")
            File(dir, "request-diagnostics.jsonl.tmp").writeText("partial")
            store.clear()
            assertEquals("", store.exportJsonl())
            assertFalse(File(dir, "request-diagnostics.jsonl").exists())
            assertFalse(File(dir, "request-diagnostics.jsonl.tmp").exists())
            assertTrue(store.latestSummary().contains("暂无诊断日志"))
        }
    }

    private fun withStore(block: (RequestLogStore, File) -> Unit) {
        val directory = File(System.getProperty("java.io.tmpdir"), "pocket-tavern-log-${UUID.randomUUID()}")
        directory.mkdirs()
        try {
            block(RequestLogStore(directory, appVersion = "0.1.6", androidSdk = 35), directory)
        } finally {
            directory.walkBottomUp().forEach { it.delete() }
        }
    }
}
