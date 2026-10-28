package app.pockettavern.domain

import java.util.Locale

/**
 * A user-facing explanation for an assistant message that is incomplete,
 * empty, or carries provider diagnostics. This is deliberately independent of
 * the provider layer so it can be tested with old and new persisted messages.
 */
enum class MessageNoticeTone { NEUTRAL, WARNING, ERROR }

data class MessageNotice(
    val text: String,
    val detail: String = "",
    val diagnosticText: String = "",
    val tone: MessageNoticeTone = MessageNoticeTone.NEUTRAL
)

/** Returns a notice only for assistant messages that need explanation. */
fun messageNotice(message: Message): MessageNotice? {
    if (message.role != "assistant") return null
    val hasText = message.content.isNotBlank()
    val hasReasoning = message.reasoning.isNotBlank()
    val hasTools = message.toolCallsJson.isNotBlank() && message.toolCallsJson != "[]"
    val status = message.status.lowercase(Locale.ROOT)
    val finish = message.finishReason.trim().lowercase(Locale.ROOT)

    // A filtered completion is a provider outcome, not a client timeout or a
    // generic empty response. Check it before status=error so old persisted
    // messages with a generic errorMessage but a retained finishReason get the
    // same explanation.
    if (finish == "content_filter" || finish == "content-filter") {
        return MessageNotice(
            text = if (hasText) "平台已过滤后续内容，已保留已收到的部分。" else "平台已过滤回复",
            detail = "平台或上游返回 content_filter，具体触发原因未提供。" +
                if (hasText) "本轮回复可能不完整。" else "本轮未收到回复正文。",
            diagnosticText = diagnosticText(message),
            tone = MessageNoticeTone.ERROR
        )
    }

    if (status == "error" || message.errorMessage.isNotBlank()) {
        val detail = sanitize(message.errorMessage.trim().ifBlank { "生成未完成，可重新生成或切换模型。" })
        return MessageNotice(
            text = "生成失败",
            detail = detail,
            diagnosticText = diagnosticText(message),
            tone = MessageNoticeTone.ERROR
        )
    }
    if (status == "stopped") {
        return MessageNotice(
            text = if (hasText) "已停止，已保留部分回复。" else "生成已停止，未收到回复正文。",
            tone = MessageNoticeTone.WARNING
        )
    }
    if (status == "interrupted") {
        return MessageNotice(
            text = if (hasText) "上次生成被中断，已保留部分回复。" else "上次生成被中断，未收到完整回复。",
            tone = MessageNoticeTone.WARNING
        )
    }
    if (status == "streaming") return MessageNotice("正在生成", tone = MessageNoticeTone.NEUTRAL)

    if (finish == "length" || finish == "max_tokens" || finish == "max_output_tokens" || finish == "token_limit") {
        return MessageNotice(
            text = if (hasText) "已达到输出上限，回复已保留。" else "模型达到输出上限，但未收到回复正文。",
            detail = "可以提高输出上限或重新生成。",
            diagnosticText = diagnosticText(message),
            tone = MessageNoticeTone.WARNING
        )
    }
    if (!hasText && hasReasoning && !hasTools) {
        return MessageNotice(
            text = "模型只返回了思考过程，未收到正文。",
            detail = "可以展开上方思考过程，或重新生成。",
            tone = MessageNoticeTone.WARNING
        )
    }
    if (!hasText && !hasReasoning && !hasTools) {
        return MessageNotice(
            text = "这轮没有收到回复正文，旧版本未记录原因。可重新生成或切换模型。",
            tone = MessageNoticeTone.WARNING
        )
    }
    return null
}

private fun diagnosticText(message: Message): String = buildString {
    message.errorMessage.trim().takeIf { it.isNotBlank() }?.let { append("错误：").append(sanitize(it)).append('\n') }
    message.finishReason.trim().takeIf { it.isNotBlank() }?.let { append("finish_reason：").append(sanitize(it)).append('\n') }
    message.requestId.trim().takeIf { it.isNotBlank() }?.let { append("request_id：").append(sanitize(it)) }
}.trim()

private fun sanitize(value: String): String = value
    .replace(Regex("(?i)(api[-_ ]?key|authorization|x-api-key)(\\s*[:=]\\s*)[^,\\s}]+"), "$1$2[REDACTED]")
    .replace(Regex("(?i)\\bsk-[A-Za-z0-9_-]{12,}\\b"), "[REDACTED]")
    .replace(Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [REDACTED]")
    .take(800)
