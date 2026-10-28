package app.pockettavern.engine

import app.pockettavern.data.DataCodec
import app.pockettavern.domain.*
import org.junit.Assert.*
import org.junit.Test

class ConversationTurnsTest {
    @Test fun rejectedAutomaticMemoriesCannotLeakThroughToolsAfterRestore() = kotlinx.coroutines.runBlocking {
        val skill=SkillRecord(id="memory",permissions=listOf("memory"))
        val chat=Chat(id="story",skillIds=listOf(skill.id),messages=listOf(Message(id="draft",role="assistant",content="旧事实",contextExcluded=true)))
        val data=AppData(skills=listOf(skill),chats=listOf(chat),memories=listOf(
            Memory(id="old",text="钥匙已经交付",targetId=chat.id,sourceMessageId="draft"),
            Memory(id="confirmed",text="钥匙还在柜台",targetId=chat.id)))
        val root=java.nio.file.Files.createTempDirectory("excluded-memory").toFile()
        try {
            val result=ToolRuntime(root).execute("memory_search","{\"query\":\"钥匙\"}",data.skills,data,chat).content
            assertFalse(result.contains("已经交付"))
            assertTrue(result.contains("还在柜台"))
        } finally { root.deleteRecursively() }
    }
    @Test fun explicitModesAndNarrowLegacyControls() {
        assertEquals("rewrite", ConversationTurns.resolveIntent("（重新写吧，要符合人设）", "auto"))
        assertEquals("correction", ConversationTurns.resolveIntent("（暂停，日期不对）", "auto"))
        assertEquals("dialogue", ConversationTurns.resolveIntent("他叫道：重写这封信。", "auto"))
        assertEquals("dialogue", ConversationTurns.resolveIntent("（重写这段）", "dialogue"))
    }
    @Test fun unbracketedAuthorPauseIsDetectedButQuotedSpeechAndActionsAreNot() {
        listOf("暂停续写，先检查日期", "请先停下续写，核对地点", "先别续写，设定还没确认", "设定修正：钥匙已经交给馆长", "OOC: 先核对时间").forEach {
            assertEquals(it, "correction", ConversationTurns.resolveIntent(it,"auto"))
        }
        listOf("“暂停续写，先检查日期。”", "\"OOC: 先核对时间\"", "他说：暂停续写。", "暂停脚步，听一听门外动静", "如果暂停续写，会怎么样", "设定修正员走进房间").forEach {
            assertEquals(it, "dialogue", ConversationTurns.resolveIntent(it,"auto"))
        }
        assertEquals("explicit dialogue always wins", "dialogue", ConversationTurns.resolveIntent("暂停续写，先检查日期","dialogue"))
    }
    @Test fun rewriteAfterUnbracketedAuthorPauseRejectsStoryDraftInsteadOfCheckReply() {
        val correctionIntent = ConversationTurns.resolveIntent("暂停续写，先检查日期", "auto")
        val chat = Chat(messages=listOf(
            Message(id="task",content="在码头见面"),
            Message(id="draft",role="assistant",generationId="story",content="需要修改的正文"),
            Message(id="check",content="暂停续写，先检查日期",intent=correctionIntent),
            Message(id="check-reply",role="assistant",generationId="check",content="日期应为周二",intent=correctionIntent)
        ))
        val rewritten = ConversationTurns.append(chat,Message(id="rewrite",content="重新写",intent="rewrite"))
        assertEquals("draft",rewritten.messages.last().revisionOf)
        assertTrue(rewritten.messages.first { it.id=="draft" }.contextExcluded)
        assertFalse(rewritten.messages.first { it.id=="check-reply" }.contextExcluded)
    }
    @Test fun rewriteKeepsOriginalAndDropsEntireGenerationButKeepsCorrection() {
        val chat = Chat(messages = listOf(Message(id="u", content="去码头"),
            Message(id="call",role="assistant",generationId="g",toolCallsJson="[{\"id\":\"c\"}]"),
            Message(id="t",role="tool",generationId="g",toolCallId="c"),
            Message(id="a",role="assistant",generationId="g",content="错误的场景"),
            Message(id="fix",intent="correction",content="地点应该是东码头"),
            Message(id="ack",role="assistant",intent="correction",content="已确认")))
        val next = ConversationTurns.append(chat, Message(id="rewrite",intent="rewrite",content="重新构思"))
        assertEquals(7, next.messages.size)
        assertEquals("错误的场景", next.messages[3].content)
        assertTrue(next.messages.subList(1,4).all { it.contextExcluded })
        assertFalse(next.messages[4].contextExcluded)
        assertEquals("a", next.messages.last().revisionOf)
        assertEquals(chat, ConversationTurns.setIncluded(next.copy(messages=next.messages.dropLast(1)), "a", true))
    }
    @Test fun regeneratePreservesEveryDraftAndCanRetryAnEmptyFailure() {
        val chat = Chat(messages=listOf(Message(id="u",content="继续"),Message(id="a",role="assistant",content="原稿"),Message(id="e",role="assistant",status="error")))
        val next = ConversationTurns.regenerate(chat)
        assertEquals(3,next.messages.size)
        assertTrue(next.messages.drop(1).all { it.contextExcluded })
        assertEquals("e",next.messages.first().revisionOf)
    }
    @Test fun exclusionDoesNotLeaveLegacyToolResultsActive() {
        val chat=Chat(messages=listOf(Message(id="u"), Message(id="a",role="assistant"), Message(id="t",role="tool"),Message(id="next")))
        val next=ConversationTurns.setIncluded(chat,"a",false)
        assertEquals(listOf(false,true,true,false),next.messages.map { it.contextExcluded })
    }
    @Test fun restoringLegacyDraftDoesNotRestoreNewerGeneration() {
        val chat=Chat(messages=listOf(
            Message(id="u",content="开始"),
            Message(id="old",role="assistant",content="旧稿",contextExcluded=true),
            Message(id="old-tool",role="tool",content="旧工具",contextExcluded=true),
            Message(id="new",role="assistant",generationId="new-generation",content="新稿"),
            Message(id="new-tool",role="tool",generationId="new-generation",content="新工具"),
            Message(id="next",content="后续要求"),
            Message(id="next-reply",role="assistant",generationId="next-generation",content="后续内容")
        ))
        val restored=ConversationTurns.setIncluded(chat,"old",true)
        assertEquals(listOf(false,false,false,true,true,false,false),restored.messages.map { it.contextExcluded })
        val excluded=ConversationTurns.setIncluded(chat,"old",false)
        assertFalse(excluded.messages[3].contextExcluded)
    }
    @Test fun restoringGenerationMakesOtherDraftsExclusiveAndRestoresItsUser() {
        val chat=Chat(messages=listOf(
            Message(id="u",content="开始",contextExcluded=true),
            Message(id="old",role="assistant",generationId="old",content="旧稿"),
            Message(id="new-call",role="assistant",generationId="new",contextExcluded=true),
            Message(id="new-tool",role="tool",generationId="new",contextExcluded=true),
            Message(id="new",role="assistant",generationId="new",content="新稿",contextExcluded=true)
        ))
        val restored=ConversationTurns.setIncluded(chat,"new",true)
        assertEquals(listOf(false,true,false,false,false),restored.messages.map { it.contextExcluded })
    }
    @Test fun restoringUserTurnSelectsOnlyLatestGeneration() {
        val chat=Chat(messages=listOf(
            Message(id="u",content="开始",contextExcluded=true),
            Message(id="legacy",role="assistant",content="升级前旧稿",contextExcluded=true),
            Message(id="first",role="assistant",generationId="first",content="第一稿",contextExcluded=true),
            Message(id="latest-call",role="assistant",generationId="latest",contextExcluded=true),
            Message(id="latest-tool",role="tool",generationId="latest",contextExcluded=true),
            Message(id="latest",role="assistant",generationId="latest",content="最新稿",contextExcluded=true)
        ))
        val restored=ConversationTurns.setIncluded(chat,"u",true)
        assertEquals(listOf(false,true,true,false,false,false),restored.messages.map { it.contextExcluded })
    }
    @Test fun rejectingDraftDisablesOnlyItsOwnChatMemoryAndPreservesBranchesAndSharedFacts() {
        val chat = Chat(id="original",messages=listOf(
            Message(id="shared-source",role="assistant",content="被否决稿",contextExcluded=true),
            Message(id="accepted-source",role="assistant",content="有效稿")
        ))
        val memories = listOf(
            Memory(id="original-memory",scope="chat",targetId="original",sourceMessageId="shared-source",text="原会话派生记忆"),
            Memory(id="branch-memory",scope="chat",targetId="branch",sourceMessageId="shared-source",text="分支保留的事实"),
            Memory(id="manual-memory",scope="chat",targetId="original",text="用户手动确认"),
            Memory(id="global-memory",scope="global",sourceMessageId="shared-source",text="用户提升的全局事实"),
            Memory(id="character-memory",scope="character",targetId="guide",sourceMessageId="shared-source",text="用户提升的角色事实"),
            Memory(id="accepted-memory",scope="chat",targetId="original",sourceMessageId="accepted-source",text="有效稿派生记忆")
        )
        val updated = ConversationTurns.disableExcludedMemories(chat,memories)
        assertFalse(updated.first().enabled)
        assertEquals(memories.drop(1),updated.drop(1))
        val restored = chat.copy(messages=chat.messages.map { it.copy(contextExcluded=false) })
        assertEquals("restoring a draft does not silently re-confirm its previously disabled memory",updated,ConversationTurns.disableExcludedMemories(restored,updated))
    }
    @Test fun rejectionAndIntentSurviveBackupWithoutDeletingHistory() {
        val chat=Chat(continuityNotes="第三日，东码头",messages=listOf(Message(content="纠正日期",intent="correction"),Message(role="assistant",content="旧稿",contextExcluded=true,generationId="g",skillSnapshotJson="{}")))
        val restored=DataCodec.decode(DataCodec.encode(AppData(chats=listOf(chat))))
        assertEquals(chat,restored.chats.single())
        val legacy=DataCodec.decode("{\"schemaVersion\":1,\"chats\":[{\"messages\":[{\"role\":\"user\",\"content\":\"旧消息\"}]}]}")
        assertEquals("dialogue",legacy.chats.single().messages.single().intent)
        assertFalse(legacy.chats.single().messages.single().contextExcluded)
    }
    @Test fun identicalOrCosmeticRewriteWarnsButNewSceneDoesNot() {
        val draft="渡船靠岸以后，林舟先清点货箱，再和守桥人核对名单。".repeat(10)
        assertTrue(ConversationTurns.rewriteNotice(draft,draft).isNotBlank())
        assertTrue(ConversationTurns.rewriteNotice(draft,draft.replace("，","。 ")).isNotBlank())
        assertEquals("",ConversationTurns.rewriteNotice(draft,"清晨的会议室中，沈宁发现地图缺了一角，她收起纸笔，决定先找失踪的联络员。".repeat(10)))
    }
}
