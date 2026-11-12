package app.pockettavern.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replies run side by side in different chats. Memory upkeep runs one at a time and never blocks
 * another chat's reply. A chat that is busy cannot start a second task.
 */
class ChatTasksTest {
    private val reply = ChatTask()

    @Test fun anotherChatCanReplyWhileOneIsReplying() {
        val tasks = mapOf("a" to reply)
        assertNull(ChatTasks.refusal(tasks, "b", ChatTask.REPLY))
        assertNull("three at once still start; a fourth queues instead of being refused",
            ChatTasks.refusal(mapOf("a" to reply, "b" to reply, "c" to reply), "d", ChatTask.REPLY))
    }

    @Test fun aBusyChatCannotStartASecondTask() {
        for (kind in listOf(ChatTask.REPLY, ChatTask.COMPRESS, ChatTask.REBUILD, ChatTask.ORGANIZE, ChatTask.RESEARCH)) {
            val refusal = ChatTasks.refusal(mapOf("a" to reply), "a", kind)
            assertNotNull(kind, refusal)
            assertTrue(refusal!!.contains("这个会话正在回复"))
        }
        assertTrue(ChatTasks.refusal(mapOf("a" to ChatTask(ChatTask.REBUILD)), "a", ChatTask.REPLY)!!.contains("正在重建记忆"))
        assertTrue(ChatTasks.refusal(mapOf("a" to ChatTask(queued = true)), "a", ChatTask.REPLY)!!.contains("排队中"))
    }

    @Test fun memoryUpkeepRunsOneAtATimeButDoesNotBlockReplies() {
        val tasks = mapOf("novel" to ChatTask(ChatTask.REBUILD))
        assertNull("another chat keeps chatting during a rebuild", ChatTasks.refusal(tasks, "b", ChatTask.REPLY))
        for (kind in listOf(ChatTask.COMPRESS, ChatTask.REBUILD, ChatTask.ORGANIZE, ChatTask.RESEARCH)) {
            val refusal = ChatTasks.refusal(tasks, "b", kind)
            assertNotNull(kind, refusal)
            assertTrue(refusal!!.contains("另一个会话正在重建记忆"))
        }
        assertNull("replies elsewhere do not block upkeep", ChatTasks.refusal(mapOf("a" to reply, "b" to reply), "c", ChatTask.COMPRESS))
        // A reply waiting for the summary gate is still a reply: it must not count as upkeep.
        assertNull(ChatTasks.refusal(mapOf("a" to ChatTask(queued = true)), "c", ChatTask.REBUILD))
    }

    @Test fun aFourthReplyWaitsItsTurn() {
        assertEquals(3, ChatTask.MAX_REPLIES)
        assertFalse(ChatTasks.mustQueue(mapOf("a" to reply, "b" to reply)))
        assertTrue(ChatTasks.mustQueue(mapOf("a" to reply, "b" to reply, "c" to reply)))
        assertFalse("queued replies and upkeep do not hold a reply slot",
            ChatTasks.mustQueue(mapOf("a" to reply, "b" to reply, "c" to ChatTask(queued = true), "d" to ChatTask(ChatTask.REBUILD))))
    }

    @Test fun labelsSayWhatEachChatIsDoing() {
        assertEquals("正在回复…", ChatTasks.label(reply))
        assertEquals("排队中…", ChatTasks.label(ChatTask(queued = true)))
        assertEquals("排队中…", ChatTasks.label(ChatTask(ChatTask.COMPRESS, queued = true)))
        assertEquals("正在压缩记忆…", ChatTasks.label(ChatTask(ChatTask.COMPRESS)))
        assertEquals("正在重建记忆…", ChatTasks.label(ChatTask(ChatTask.REBUILD)))
        assertEquals("正在整理剧本…", ChatTasks.label(ChatTask(ChatTask.ORGANIZE)))
        assertEquals("正在研究资料…", ChatTasks.label(ChatTask(ChatTask.RESEARCH)))
        assertEquals("这个会话正在回复，请等它完成或先停止", ChatTasks.busyNotice(reply))
    }

    @Test fun summaryCountsRepliesAndUpkeepSeparately() {
        assertEquals("", ChatTasks.summary(emptyMap()))
        assertEquals("2 个会话正在回复", ChatTasks.summary(mapOf("a" to reply, "b" to ChatTask(queued = true))))
        assertEquals("1 个会话正在整理", ChatTasks.summary(mapOf("a" to ChatTask(ChatTask.REBUILD))))
        assertEquals("2 个会话正在回复，1 个正在整理", ChatTasks.summary(mapOf("a" to reply, "b" to reply, "c" to ChatTask(ChatTask.COMPRESS))))
    }

    @Test fun uiStateKnowsWhichChatIsBusy() {
        val state = UiSession(chatTasks = mapOf("a" to reply))
        assertTrue(state.chatBusy("a"))
        assertFalse(state.chatBusy("b"))
        assertFalse(state.chatBusy(null))
        assertFalse("a busy chat is not an app-wide lock", state.busy)
        assertFalse("the list is home until a chat is opened", UiSession().chatOpen)
        assertEquals(0, Chat().unread)
    }
}
