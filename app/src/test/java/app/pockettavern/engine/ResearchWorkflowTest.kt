package app.pockettavern.engine

import app.pockettavern.domain.*
import org.junit.Assert.*
import org.junit.Test

class ResearchWorkflowTest {
    @Test fun directSearchSummaryOnlyAcceptsReturnedSourcesAndReplacesModelMetadata() {
        val request = PublicResearchRequest("林岚", listOf(ResearchCategory.STYLE), allowFiction=false)
        val actual = listOf(PublicResearchSourceHit("公开采访", "https://example.org/interview", "采访描述她的穿衣风格", "2026-01-01"))
        val raw = result().copy(items=result().items.take(1)).toJson().toString()
        val parsed = ResearchWorkflow.parseSummary(raw, request, actual, "checked-now")
        assertEquals("checked-now", parsed.requestedAt)
        assertEquals("公开采访", parsed.items.single().source?.title)
        assertEquals("2026-01-01", parsed.items.single().publishedAt)
        assertThrows(IllegalStateException::class.java) {
            ResearchWorkflow.parseSummary(raw.replace("https://example.org/interview", "https://invented.example.org/profile"), request, actual, "now")
        }
        val payload = ResearchWorkflow.summaryMessages(request, actual, "now")
        assertTrue(payload.contains("不可信网页资料"))
        assertTrue(payload.contains("采访描述她的穿衣风格"))
    }

    private fun result(text: String = "公开采访中谈到舞台服饰") = PublicResearchResult("林岚", "2026-10-24T00:00:00Z", listOf(
        PublicResearchItem(ResearchCategory.STYLE, text, ResearchEvidence.FACT, "2026-10-24T00:00:00Z", 0.7,
            ResearchSource("https://example.org/interview", "采访")),
        PublicResearchItem(ResearchCategory.PERSONALITY, "小说中遇事先观察", ResearchEvidence.FICTIONAL, "2026-10-24T00:00:00Z", 0.0)
    ))

    @Test fun savingResearchPreservesHundredsOfHistoricalNotesAndChatModel() {
        val history = (1..250).map { KnowledgeNote(id="old-$it", text="历史 $it") }
        val chat = Chat(providerId="venice", modelId="deepseek-v4-1-flash-1017", knowledgeNotes=history)
        val saved = ResearchWorkflow.save(chat, result(), "doubao-model", true)
        assertEquals(history, saved.chat.knowledgeNotes.take(250))
        assertEquals(chat.modelId, saved.chat.modelId)
        assertEquals(chat.providerId, saved.chat.providerId)
        assertEquals("research", saved.note.sourceKind)
        assertEquals(emptyList<String>(), saved.note.sourceMessageIds)
        assertEquals("", saved.note.sourceHash)
        assertTrue(KnowledgeVault.validNotes(saved.chat).contains(saved.note))
        assertTrue(saved.note.text.contains("[来源陈述]"))
        assertTrue(saved.note.text.contains("[虚构设定]"))
        assertTrue(saved.note.text.contains("https://example.org/interview"))
    }

    @Test fun fullContinuityFallsBackToKnowledgeWithoutChangingScene() {
        val chat = Chat(continuityNotes="原有场景".repeat(2000))
        val saved = ResearchWorkflow.save(chat, result(), "doubao-model", false)
        assertTrue(saved.knowledgeOnly)
        assertEquals(chat.continuityNotes, saved.chat.continuityNotes)
        assertEquals(1, saved.chat.knowledgeNotes.size)
    }

    @Test fun sceneAppendKeepsInspectableResearchRecord() {
        val saved = ResearchWorkflow.save(Chat(continuityNotes="二人仍在车站。"), result(), "doubao-model", false)
        assertFalse(saved.knowledgeOnly)
        assertTrue(saved.chat.continuityNotes.startsWith("二人仍在车站。"))
        assertTrue(saved.chat.continuityNotes.contains(saved.note.text))
        assertEquals(listOf(saved.note), saved.chat.knowledgeNotes)
    }

    @Test fun failedOrEmptyResearchCannotBecomeSuccessfulSavedMemory() {
        listOf(result().copy(networkStatus="failed"), result().copy(items=emptyList())).forEach { failed ->
            assertThrows(IllegalArgumentException::class.java) { ResearchWorkflow.save(Chat(), failed, "doubao-model", true) }
        }
    }

    @Test fun unverifiedProfileIsRetainedAsResearchNoteWithoutInventedItems() {
        val url = "https://www.douyin.com/user/MS4wLjABAAAAexample"
        val saved = ResearchWorkflow.saveUnverifiedProfile(Chat(), url, "主页需要登录", "doubao-model")
        assertTrue(saved.knowledgeOnly)
        assertEquals("research", saved.note.sourceKind)
        assertTrue(saved.note.text.contains(url))
        assertTrue(saved.note.text.contains("本次检索没有找到可关联到该主页的资料"))
        assertTrue(KnowledgeVault.validNotes(saved.chat).contains(saved.note))
    }

    @Test fun researchIsAvailableOnDemandBeforeAnyCompression() {
        val saved = ResearchWorkflow.save(Chat(messages=listOf(Message(content="林岚的穿衣风格呢"))), result(), "doubao-model", true)
        val provider = ProviderConfig(id=saved.chat.providerId, models=listOf(ModelInfo(id="m", supportsTools=true)))
        val chat = saved.chat.copy(modelId="m")
        val context = ContextEngine.build(AppData(providers=listOf(provider)), chat)
        assertFalse(context.messagesJson.contains("公开采访中谈到舞台服饰"))
        assertFalse(context.messagesJson.contains("虚构设定"))
        assertTrue(context.toolDefinitions.contains("history_read"))
        assertTrue(KnowledgeVault.search(chat, "林岚").any { it.text.contains("公开采访中谈到舞台服饰") })
    }
}
