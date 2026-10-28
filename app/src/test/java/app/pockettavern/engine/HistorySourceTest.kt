package app.pockettavern.engine

import app.pockettavern.domain.Attachment
import app.pockettavern.domain.Message
import org.junit.Assert.*
import org.junit.Test

class HistorySourceTest {
    @Test fun decoderSupportsMimeParametersWithoutTreatingPlusAsASpace() {
        val attachment = Attachment(mime = "Application/Json; charset=UTF-8", dataUrl = "data:application/json,%7B%22value%22%3A%22A+B%22%7D")
        assertTrue(HistorySource.isTextAttachment(attachment))
        assertEquals("{\"value\":\"A+B\"}", HistorySource.attachmentText(attachment))
    }

    @Test fun unavailableAndMalformedDocumentsRemainReadableWithoutOpeningPaths() {
        assertEquals("[文本附件内容未加载: /private/missing.txt]", HistorySource.attachmentText(Attachment(mime = "text/plain", path = "/private/missing.txt")))
        assertEquals("[文本附件内容无法解码]", HistorySource.attachmentText(Attachment(mime = "text/plain", dataUrl = "data:text/plain;base64,???")))
        assertEquals("[文本附件内容无法解码]", HistorySource.attachmentText(Attachment(mime = "text/plain", dataUrl = "data:text/plain")))
    }

    @Test fun searchableSourceExcludesBinaryPayloadsReasoningAndToolArguments() {
        val attachment = Attachment(name = "image.jpg", mime = "image/jpeg", dataUrl = "data:image/jpeg;base64,PRIVATE_IMAGE")
        val source = Message(content = "已确认的原文", reasoning = "内部推理", toolCallsJson = "[{\"arguments\":\"工具参数\"}]", attachments = listOf(attachment))
        assertNull(HistorySource.attachmentText(attachment))
        assertEquals("已确认的原文", HistorySource.searchableText(source))
    }
}
