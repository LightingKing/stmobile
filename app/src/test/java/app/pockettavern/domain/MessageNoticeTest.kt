package app.pockettavern.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNoticeTest {
    @Test fun legacyCompleteEmptyMessageExplainsMissingReason() {
        val notice = messageNotice(Message(role = "assistant")) ?: error("notice expected")
        assertEquals("这轮没有收到回复正文，旧版本未记录原因。可重新生成或切换模型。", notice.text)
        assertEquals(MessageNoticeTone.WARNING, notice.tone)
    }

    @Test fun errorKeepsPartialTextAndOffersRedactedDiagnostics() {
        val notice = messageNotice(Message(
            role = "assistant",
            content = "已经写到这里",
            status = "error",
            errorMessage = "HTTP 401: api_key=secret-value",
            requestId = "req-1"
        )) ?: error("notice expected")
        assertEquals("生成失败", notice.text)
        assertTrue(notice.detail.contains("HTTP 401"))
        assertFalse(notice.detail.contains("secret-value"))
        assertTrue(notice.diagnosticText.contains("req-1"))
        assertFalse(notice.diagnosticText.contains("secret-value"))
        assertTrue(notice.diagnosticText.contains("[REDACTED]"))
    }

    @Test fun contentFilterExplainsEmptyCompletionWithoutSuggestingRetry() {
        val notice = messageNotice(Message(
            role = "assistant",
            status = "error",
            errorMessage = "模型返回空回复",
            finishReason = "content_filter",
            requestId = "response-1"
        )) ?: error("notice expected")
        assertEquals("平台已过滤回复", notice.text)
        assertTrue(notice.detail.contains("content_filter"))
        assertTrue(notice.detail.contains("具体触发原因未提供"))
        assertFalse(notice.detail.contains("重新生成"))
        assertTrue(notice.diagnosticText.contains("finish_reason：content_filter"))
    }

    @Test fun contentFilterKeepsPartialCompletionAndMarksFilteredTail() {
        val notice = messageNotice(Message(
            role = "assistant",
            content = "已收到的部分",
            status = "complete",
            finishReason = "content-filter"
        )) ?: error("notice expected")
        assertEquals("平台已过滤后续内容，已保留已收到的部分。", notice.text)
        assertTrue(notice.detail.contains("平台或上游"))
        assertTrue(notice.detail.contains("可能不完整"))
        assertFalse(notice.detail.contains("未收到回复正文"))
    }

    @Test fun mentioningFilterInAnUnrelatedErrorDoesNotInventAFilterOutcome() {
        val notice = messageNotice(Message(role = "assistant", status = "error",
            errorMessage = "unsupported parameter: content_filter")) ?: error("notice expected")
        assertEquals("生成失败", notice.text)
    }

    @Test fun diagnosticsRedactStandaloneKeyAndBearerTokens() {
        val notice = messageNotice(Message(
            role = "assistant",
            status = "error",
            errorMessage = "upstream rejected sk-1234567890abcdef and Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"
        )) ?: error("notice expected")
        assertFalse(notice.diagnosticText.contains("sk-1234567890abcdef"))
        assertFalse(notice.diagnosticText.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(notice.diagnosticText.contains("[REDACTED]"))
    }

    @Test fun reasoningOnlyDoesNotPretendToContainReply() {
        val notice = messageNotice(Message(role = "assistant", reasoning = "思考内容")) ?: error("notice expected")
        assertEquals("模型只返回了思考过程，未收到正文。", notice.text)
    }

    @Test fun lengthFinishReasonIsShownAfterText() {
        val notice = messageNotice(Message(role = "assistant", content = "部分回复", finishReason = "length")) ?: error("notice expected")
        assertEquals("已达到输出上限，回复已保留。", notice.text)
        assertTrue(notice.detail.contains("输出上限"))
    }

    @Test fun normalMessageHasNoNotice() {
        assertEquals(null, messageNotice(Message(role = "assistant", content = "完整回复")))
        assertEquals(null, messageNotice(Message(role = "user", content = "用户消息")))
    }
}
