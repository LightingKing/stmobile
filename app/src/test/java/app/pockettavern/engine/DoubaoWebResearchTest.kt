package app.pockettavern.engine

import app.pockettavern.data.DataCodec
import app.pockettavern.data.Exchange
import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.domain.Message
import app.pockettavern.domain.Summary
import org.junit.Assert.*
import org.junit.Test

class DoubaoWebResearchTest {
    private val request = DoubaoWebRequest("request-1", "chat-1", "林岚的公开穿衣风格", listOf("appearance", "style"), false)
    private val answer = DoubaoWebAnswer("request-1", "【来源陈述】公开访谈中提到偏好简洁外套。\n第二段原文保留。",
        "https://www.doubao.com/chat/conversation-1?from_login=1", listOf("https://example.org/interview"))

    @Test fun rawAnswerStaysInspectableWithoutChangingChatOrSummaryState() {
        val messages = listOf(Message(id = "m-1", content = "林岚仍在车站。"))
        val summaries = listOf(Summary(id = "s-1", text = "两人等车。"))
        val history = KnowledgeNote(id = "history", text = "既有剧情记忆")
        val chat = Chat(id = request.chatId, providerId = "venice", modelId = "deepseek", messages = messages,
            summaries = summaries, activeSummaryId = "s-1", continuityNotes = "当前在车站", knowledgeNotes = listOf(history))
        val saved = DoubaoWebResearch.save(chat, request, answer)
        assertTrue(saved.knowledgeOnly)
        assertEquals(chat.copy(knowledgeNotes = listOf(history, saved.note)), saved.chat)
        assertEquals("doubao-web:request-1", saved.note.id)
        assertEquals("doubao-web", saved.note.modelId)
        assertEquals("research", saved.note.sourceKind)
        assertTrue(saved.note.sourceMessageIds.isEmpty())
        assertEquals("", saved.note.sourceHash)
        assertTrue(saved.note.text.contains(answer.text))
        assertTrue(saved.note.text.contains("网页原始回答，未逐项核验"))
        assertTrue(saved.note.text.contains("不代表剧情已发生"))
        assertTrue(saved.note.text.contains("https://example.org/interview"))
        assertFalse(saved.note.text.contains("from_login"))
        assertTrue(KnowledgeVault.validNotes(saved.chat).contains(saved.note))
    }

    @Test fun duplicateDeliveryIsIdempotentButChangedAnswerCannotOverwrite() {
        val first = DoubaoWebResearch.save(Chat(id = request.chatId), request, answer)
        val second = DoubaoWebResearch.save(first.chat, request, answer)
        assertSame(first.chat, second.chat)
        assertEquals(first.note, second.note)
        assertEquals(1, second.chat.knowledgeNotes.size)
        assertThrows(IllegalArgumentException::class.java) {
            DoubaoWebResearch.save(first.chat, request, answer.copy(text = "不同的回答"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DoubaoWebResearch.save(first.chat, request, answer.copy(sourceLinks = listOf("https://example.org/other")))
        }
    }

    @Test fun wrongConversationOrRequestCannotReceiveTheAnswer() {
        assertThrows(IllegalArgumentException::class.java) { DoubaoWebResearch.save(Chat(id = "another-chat"), request, answer) }
        assertThrows(IllegalArgumentException::class.java) {
            DoubaoWebResearch.save(Chat(id = request.chatId), request, answer.copy(requestId = "another-request"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DoubaoWebResearch.save(Chat(id = request.chatId), request, answer.copy(text = "  \n "))
        }
    }

    @Test fun onlyOfficialConversationPagesAndPublicHttpsSourcesAreAccepted() {
        listOf("https://www.doubao.com/chat/", "https://www.doubao.com:443/chat/abc-123/", "https://www.doubao.com/chat/a/b?from_login=1#tail")
            .forEach { assertTrue(it, DoubaoWebResearch.isConversationUrl(it)) }
        listOf("http://www.doubao.com/chat/a", "https://doubao.com/chat/a", "https://www.doubao.com.evil.com/chat/a",
            "https://user:secret@www.doubao.com/chat/a", "https://www.doubao.com:444/chat/a", "https://www.doubao.com/login",
            "https://www.doubao.com/chat/../login", "https://www.doubao.com/chat/a%2Fb", "https://www.doubao.com/chat")
            .forEach { url ->
                assertFalse(url, DoubaoWebResearch.isConversationUrl(url))
                assertThrows(IllegalStateException::class.java) { DoubaoWebResearch.save(Chat(id = request.chatId), request, answer.copy(pageUrl = url)) }
            }
        listOf("http://example.org/", "https://127.0.0.1/private", "https://private.local/", "https://user:secret@example.org/", "javascript:alert(1)")
            .forEach { url ->
                assertThrows(IllegalArgumentException::class.java) {
                    DoubaoWebResearch.save(Chat(id = request.chatId), request, answer.copy(sourceLinks = listOf(url)))
                }
            }
    }

    @Test fun emptySubjectRequiresMediaAndThePromptSeparatesEvidence() {
        val media = "视频抽取 8 帧，时间点 0–40 秒；另附一段音轨，尚未由模型确认读取。"
        val visual = request.copy(subject = "", categories = listOf("appearance", "relationships", "social_sentiment"), allowFiction = true)
        assertThrows(IllegalArgumentException::class.java) { DoubaoWebResearch.prompt(visual) }
        val prompt = DoubaoWebResearch.prompt(visual, media)
        assertTrue(prompt.contains(media))
        assertTrue(prompt.contains("【画面可见】"))
        assertTrue(prompt.contains("不声称读取了完整视频"))
        assertTrue(prompt.contains("只有实际收到并读取了音频"))
        assertTrue(prompt.contains("不把少数网友评论当作社会共识"))
        assertTrue(prompt.contains("【虚构设定】"))
        assertTrue(DoubaoWebResearch.prompt(request).contains("本次不允许虚构补全"))
        val saved = DoubaoWebResearch.save(Chat(id = request.chatId), visual, answer.copy(mediaDescription = media))
        assertTrue(saved.note.text.contains("媒体处理说明：$media"))
        assertTrue(saved.note.text.contains("截图 / 视频素材"))
    }

    @Test fun requestValidationCountsUnicodeAndRejectsInvalidCategories() {
        DoubaoWebResearch.requireValid(request.copy(subject = "😀".repeat(200)))
        assertThrows(IllegalArgumentException::class.java) { DoubaoWebResearch.requireValid(request.copy(subject = "😀".repeat(201))) }
        listOf(request.copy(id = ""), request.copy(chatId = " "), request.copy(categories = emptyList()),
            request.copy(categories = listOf("style", "style")), request.copy(categories = listOf("unknown")))
            .forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { DoubaoWebResearch.requireValid(invalid) } }
    }

    @Test fun capacityRejectsWholeRecordWithoutTruncationOrEviction() {
        val chat = Chat(id = request.chatId, messages = listOf(Message(content = "保留原文")))
        assertThrows(IllegalArgumentException::class.java) {
            DoubaoWebResearch.save(chat, request, answer.copy(text = "文".repeat(KnowledgeVault.MAX_RESEARCH_NOTE_CHARS)))
        }
        val full = chat.copy(knowledgeNotes = (1..KnowledgeVault.MAX_RESEARCH_NOTES).map {
            KnowledgeNote(id = "research-$it", text = "旧研究 $it", sourceKind = "research")
        })
        assertThrows(IllegalArgumentException::class.java) { DoubaoWebResearch.save(full, request, answer) }
        assertEquals(KnowledgeVault.MAX_RESEARCH_NOTES, full.knowledgeNotes.size)
        assertEquals(chat.messages, full.messages)
    }

    @Test fun existingResearchStorageAndPortableExportsPreserveTheWebRecord() {
        val saved = DoubaoWebResearch.save(Chat(id = request.chatId), request, answer)
        val restored = DataCodec.decode(DataCodec.encode(AppData(chats = listOf(saved.chat)))).chats.single()
        assertEquals(saved.note, restored.knowledgeNotes.single())
        val jsonl = Exchange.chatJsonl(saved.chat)
        val imported = Exchange.chat(jsonl, "venice", "deepseek")
        assertEquals(saved.note, imported.knowledgeNotes.single())
        assertTrue(KnowledgeVault.search(saved.chat, "公开访谈").any { it.text.contains("公开访谈") })
    }
}
