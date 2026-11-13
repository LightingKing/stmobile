package app.pockettavern.domain

/**
 * A long task tied to one chat. Replies run side by side in different chats; upkeep (compress,
 * rebuild, organize, research) runs one at a time across the app. [queued] means it is waiting
 * for a free reply slot or for another chat's memory work to finish.
 */
data class ChatTask(val kind: String = REPLY, val queued: Boolean = false) {
    val isReply: Boolean get() = kind == REPLY

    companion object {
        const val REPLY = "reply"
        const val COMPRESS = "compress"
        const val REBUILD = "rebuild"
        const val ORGANIZE = "organize"
        const val RESEARCH = "research"
        const val REPAIR = "repair"

        /** Upstream rate limits and cost: more replies than this wait their turn. */
        const val MAX_REPLIES = 3
    }
}

object ChatTasks {
    /** What the chat list and the chat itself show while [task] runs. */
    fun label(task: ChatTask): String = when {
        task.queued -> "排队中…"
        task.kind == ChatTask.COMPRESS -> "正在压缩记忆…"
        task.kind == ChatTask.REBUILD -> "正在重建记忆…"
        task.kind == ChatTask.ORGANIZE -> "正在整理剧本…"
        task.kind == ChatTask.RESEARCH -> "正在研究资料…"
        task.kind == ChatTask.REPAIR -> "正在修复乱码…"
        else -> "正在回复…"
    }

    /** Shown when the user tries to change a chat that is busy. */
    fun busyNotice(task: ChatTask): String = "这个会话${label(task).removeSuffix("…")}，请等它完成或先停止"

    /** Why a [kind] task cannot start on [chatId] now, or null when it can. */
    fun refusal(tasks: Map<String, ChatTask>, chatId: String, kind: String): String? {
        tasks[chatId]?.let { return busyNotice(it) }
        if (kind != ChatTask.REPLY) tasks.values.firstOrNull { !it.isReply }?.let {
            return "另一个会话${label(it.copy(queued = false)).removeSuffix("…")}，请等它完成后再开始"
        }
        return null
    }

    /** A new reply waits when [ChatTask.MAX_REPLIES] are already being written. */
    fun mustQueue(tasks: Map<String, ChatTask>): Boolean = tasks.values.count { it.isReply && !it.queued } >= ChatTask.MAX_REPLIES

    /** One line for the list header and the notification; empty when nothing runs. */
    fun summary(tasks: Map<String, ChatTask>): String {
        val replies = tasks.values.count { it.isReply }
        val upkeep = tasks.size - replies
        return when {
            tasks.isEmpty() -> ""
            upkeep == 0 -> "$replies 个会话正在回复"
            replies == 0 -> "$upkeep 个会话正在整理"
            else -> "$replies 个会话正在回复，$upkeep 个正在整理"
        }
    }
}
