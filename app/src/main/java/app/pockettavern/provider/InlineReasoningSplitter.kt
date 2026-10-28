package app.pockettavern.provider

/**
 * Moves `<think>`-style blocks that open the reply into reasoning. Providers stream these
 * inline in `content` with tags split across chunks; tags later in the reply are story text.
 */
internal class InlineReasoningSplitter {
    private enum class Mode { START, THINK, BODY }

    private var mode = Mode.START
    private var close = ""
    private var pending = ""
    private var sawThink = false

    /** Returns the visible text and reasoning that can be released for [text]. */
    fun feed(text: String): Pair<String, String> {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var buffer = pending + text
        pending = ""
        while (buffer.isNotEmpty()) {
            when (mode) {
                Mode.BODY -> { content.append(buffer); buffer = "" }
                Mode.START -> {
                    val trimmed = buffer.trimStart()
                    val tag = TAGS.firstOrNull { trimmed.startsWith("<$it>", ignoreCase = true) }
                    when {
                        tag != null -> {
                            mode = Mode.THINK; close = "</$tag>"; sawThink = true
                            buffer = trimmed.substring(tag.length + 2)
                        }
                        trimmed.isEmpty() || TAGS.any { "<$it>".startsWith(trimmed, ignoreCase = true) } -> { pending = buffer; buffer = "" }
                        else -> { mode = Mode.BODY; content.append(if (sawThink) trimmed else buffer); buffer = "" }
                    }
                }
                Mode.THINK -> {
                    val end = buffer.indexOf(close, ignoreCase = true)
                    if (end >= 0) {
                        reasoning.append(buffer, 0, end)
                        buffer = buffer.substring(end + close.length)
                        mode = Mode.START
                    } else {
                        val keep = (close.length - 1 downTo 1).firstOrNull { buffer.endsWith(close.substring(0, it), ignoreCase = true) } ?: 0
                        reasoning.append(buffer, 0, buffer.length - keep)
                        pending = buffer.substring(buffer.length - keep)
                        buffer = ""
                    }
                }
            }
        }
        return content.toString() to reasoning.toString()
    }

    /** Releases held text at end of stream; an unclosed block stays reasoning. */
    fun finish(): Pair<String, String> {
        val rest = pending
        pending = ""
        return if (mode == Mode.THINK) "" to rest else (if (sawThink) rest.trimStart() else rest) to ""
    }

    companion object {
        private val TAGS = listOf("think", "thinking", "thought", "reasoning")

        fun split(text: String): Pair<String, String> {
            val splitter = InlineReasoningSplitter()
            val (content, reasoning) = splitter.feed(text)
            val (restContent, restReasoning) = splitter.finish()
            return content + restContent to reasoning + restReasoning
        }
    }
}
