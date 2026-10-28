package app.pockettavern.engine

import app.pockettavern.domain.Attachment
import app.pockettavern.domain.Message
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

internal data class HistorySearchSpan(val start: Int, val end: Int, val readStart: Int)

/** Search positions refer only to prose; read positions include the original metadata fields. */
internal data class HistorySearchSource(val text: String, val spans: List<HistorySearchSpan>) {
    fun matchIndex(term: String): Int = spans.firstNotNullOfOrNull { span ->
        text.indexOf(term, span.start, ignoreCase = true).takeIf { it >= span.start && it + term.length <= span.end }
    } ?: -1

    fun readOffset(match: Int, context: Int = 180): Int {
        val span = spans.firstOrNull { match in it.start until it.end } ?: return 0
        var start = (match - context).coerceAtLeast(span.start)
        if (start > span.start && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
        return span.readStart + start - span.start
    }
}

/** Shared original prose for compression, retrieval and the memory reader. */
object HistorySource {
    fun isTextAttachment(attachment: Attachment): Boolean {
        val mime = attachment.mime.substringBefore(';').trim().lowercase(Locale.ROOT)
        return mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/csv")
    }

    /** Null means non-text; unavailable data is reported without reading arbitrary device paths. */
    fun attachmentText(attachment: Attachment): String? {
        if (!isTextAttachment(attachment)) return null
        val data = attachment.dataUrl
        if (data.isBlank()) return "[文本附件内容未加载${attachment.path.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}]"
        if (!data.startsWith("data:", true)) return data
        return runCatching {
            val comma = data.indexOf(',')
            require(comma >= 0)
            val metadata = data.substring(0, comma)
            val payload = data.substring(comma + 1)
            if (metadata.split(';').any { it.equals("base64", true) }) {
                String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
            } else {
                // A data URL is not form data: a literal '+' remains a plus sign.
                URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8")
            }
        }.getOrElse { "[文本附件内容无法解码]" }
    }

    /** Search only the conversation prose and its text documents, not model reasoning or tool data. */
    fun searchableText(message: Message): String = searchableSource(message).text

    internal fun searchableSource(message: Message, includeContent: Boolean = true): HistorySearchSource =
        render(message, search = true, includeContent = includeContent)

    fun summaryText(message: Message): String = render(message, search = false).text

    /** Memory uses evidence, not private reasoning drafts or tool argument guesses. */
    fun memoryText(message: Message): String {
        if (message.contextExcluded) return "id=${message.id} [已排除的草稿，不作为事实]"
        if (message.role == "tool" || (message.content.isBlank() && message.toolCallsJson != "[]"))
            return "id=${message.id} [工具往返，不作为剧情事件]"
        return render(message.copy(reasoning = "", toolCallsJson = "[]", toolCallId = ""), search = false).text
    }

    /** One formatter owns both the read protocol and offsets, without building a second full copy. */
    private fun render(message: Message, search: Boolean, includeContent: Boolean = true): HistorySearchSource {
        val result = StringBuilder()
        val spans = mutableListOf<HistorySearchSpan>()
        var readPosition = 0
        fun appendSource(value: String, searchable: Boolean = false) {
            if (!search) result.append(value)
            else if (searchable && value.isNotEmpty()) {
                val start = result.length
                result.append(value)
                spans += HistorySearchSpan(start, result.length, readPosition)
            }
            readPosition += value.length
        }
        appendSource("id=${message.id} role=${message.role}")
        if (message.intent != "dialogue") appendSource(" intent=${message.intent}" + if (message.intent == "correction" || message.role == "user") " [剧情外指令/回应，不是人物台词或剧情事件]" else " [本次重写的正文]")
        if (message.contextExcluded) appendSource(" [已排除草稿]")
        appendSource(" content=")
        // Even an unsearchable tool-plan still occupies its original read span;
        // attachment hit offsets must continue to address the full saved source.
        appendSource(message.content, searchable = includeContent)
        if (message.reasoning.isNotBlank()) { appendSource(" reasoning_content="); appendSource(message.reasoning) }
        if (message.toolCallsJson.isNotBlank() && message.toolCallsJson != "[]") { appendSource(" tool_calls="); appendSource(message.toolCallsJson) }
        if (message.toolCallId.isNotBlank()) { appendSource(" tool_call_id="); appendSource(message.toolCallId) }
        if (message.attachments.isNotEmpty()) appendSource(" attachments=")
        message.attachments.forEachIndexed { index, attachment ->
            if (index > 0) appendSource("; ")
            val text = attachmentText(attachment)
            if (text != null) {
                if (search) {
                    if (result.isNotEmpty()) result.append("\n\n")
                    result.append("【文本附件：")
                }
                appendSource("text(name=")
                appendSource(attachment.name, searchable = attachment.name.isNotBlank())
                if (search) result.append(if (attachment.name.isBlank()) "未命名附件】\n" else "】\n")
                appendSource(",mime=${attachment.mime}): ")
                appendSource(text, searchable = true)
            } else {
                val kind = when {
                    attachment.mime.lowercase().startsWith("image/") -> "image"
                    attachment.mime.lowercase().startsWith("audio/") -> "audio"
                    else -> "attachment"
                }
                appendSource("$kind(name=${attachment.name},mime=${attachment.mime})")
            }
        }
        return HistorySearchSource(result.toString(), spans)
    }
}
