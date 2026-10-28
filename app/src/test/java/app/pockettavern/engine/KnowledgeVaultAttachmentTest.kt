package app.pockettavern.engine

import app.pockettavern.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class KnowledgeVaultAttachmentTest {
    @Test fun aCurrentTextAttachmentIsSearchableBeforeAnyCompression() {
        val source = Message(id = "new-document", content = "请核对附件", attachments = listOf(Attachment(
            name = "scene.md", mime = "text/markdown", dataUrl = "林澈已经把仓库钥匙交还给周宁。"
        )))
        val chat = Chat(messages = listOf(source))
        val hit = KnowledgeVault.search(chat, "仓库钥匙").single()
        assertEquals("message:new-document", hit.id)
        assertTrue(hit.text.contains("交还给周宁"))
    }

    @Test fun rawAttachmentRecoversANameOmittedByTheSummary() {
        val original = "秦岚是江舟的姐姐，已经将蓝色药盒交给江舟。A+B"
        for (attachment in attachments(original)) {
            val source = Message(id = "source", attachments = listOf(attachment))
            val chat = summarized(listOf(source)).let {
                it.copy(messages = it.messages + Message(id = "query", content = "秦岚是谁？"))
            }
            val found = KnowledgeVault.search(chat, "秦岚")
            assertEquals(attachment.mime, "message:source", found.first().id)
            assertTrue(attachment.mime, found.first().text.contains(original))
            assertTrue(KnowledgeVault.recall(chat, "秦岚", 1000).contains("姐姐"))
        }
    }

    @Test fun aRebuiltSingleUserAttachmentRemainsSearchableWithoutANewUserTurn() {
        val source = Message(id = "only-source", attachments = listOf(Attachment(
            name = "story.md", mime = "text/markdown", dataUrl = "秦岚是江舟的姐姐。"
        )))
        val chat = summarized(listOf(source))
        assertEquals("message:only-source", KnowledgeVault.search(chat, "秦岚").single().id)
        assertTrue(KnowledgeVault.search(chat, "继续").any { it.id == "message:only-source" && it.text.contains("秦岚") })
    }

    @Test fun anUncompressedCurrentQuestionIsNotReturnedAsItsOwnEvidence() {
        val source = Message(id = "source", role = "assistant", content = "今天到达港口。")
        val query = Message(id = "query", content = "秦岚是谁？")
        val chat = summarized(listOf(source)).let { it.copy(messages = it.messages + query) }
        assertTrue(KnowledgeVault.search(chat, "秦岚").isEmpty())
        assertTrue(KnowledgeVault.search(chat, "").none { it.id == "message:query" })

        val stale = summarized(listOf(query)).let {
            it.copy(messages = listOf(query.copy(content = "秦岚是谁？原文已修改")))
        }
        assertTrue(KnowledgeVault.search(stale, "秦岚").isEmpty())
    }

    @Test fun recentFallbackIncludesReadableAttachments() {
        val source = Message(id = "source", role = "assistant", attachments = listOf(Attachment(
            name = "history.json", mime = "application/json", dataUrl = "{\"fact\":\"秦岚已归还蓝色药盒\"}"
        )))
        val chat = Chat(messages = listOf(source, Message(content = "继续")))
        assertTrue(KnowledgeVault.search(chat, "继续").single().text.contains("秦岚已归还蓝色药盒"))
    }

    @Test fun readPagesKeepCompleteDecodedOriginalWithoutChangingTheProtocol() {
        val original = "雨夜的港口。".repeat(1600) + "秦岚已归还蓝色药盒，编号 A+B。"
        val source = Message(id = "source", attachments = listOf(attachments(original)[1]))
        val chat = summarized(listOf(source))
        val result = StringBuilder()
        var offset = 0
        do {
            val page = JSONObject(KnowledgeVault.read(chat, "message:source", offset))
            assertEquals("message:source", page.getString("id"))
            assertEquals(offset, page.getInt("offset"))
            result.append(page.getString("text"))
            offset = if (page.isNull("nextOffset")) -1 else page.getInt("nextOffset")
            assertTrue(page.getInt("totalChars") >= result.length)
        } while (offset >= 0)
        assertEquals(ContextEngine.summarySource(listOf(source)).single(), result.toString())
        assertTrue(result.toString().contains(original))
    }

    @Test fun repeatedQuestionOnlyMessagesDoNotCrowdOutTheSource() {
        val question = "秦岚与江舟的关系是什么？"
        val source = Message(id = "source", role = "assistant", content = "秦岚是江舟的姐姐。")
        val chat = summarized(listOf(source) + (1..8).map { Message(id = "question-$it", content = question) })
        assertEquals("message:source", KnowledgeVault.search(chat, question, limit = 1).single().id)
    }

    @Test fun scenarioOrganizerNotesRemainRecallableAfterTheirRawMessagesAreCompacted() {
        val source = Message(id = "early", role = "assistant", content = "骆歆仍是庄园管家，负责接待和起居安排。", contextExcluded = true)
        val note = KnowledgeNote(
            id = "scenario-note",
            title = "剧本整理·人物·骆歆",
            entity = "骆歆",
            kind = "person",
            sourceKind = "conversation",
            text = "- 角色/岗位：庄园管家\n- 当前活动：负责接待和起居安排",
            sourceMessageIds = listOf(source.id),
            sourceHash = ContextEngine.sourceHash(listOf(source))
        )
        val chat = Chat(messages = listOf(source, Message(id = "query", content = "骆歆现在负责什么？")), knowledgeNotes = listOf(note))
        val hit = KnowledgeVault.search(chat, "骆歆").firstOrNull { it.id == "note:scenario-note" }
        assertNotNull(hit)
        assertTrue(hit!!.text.contains("庄园管家"))
    }

    @Test fun searchOffsetsJumpDirectlyToDistantMessageAndNoteMatches() {
        val original = "普通正文。".repeat(30_000) + "秦岚已把蓝色药盒交给江舟。" + "后续剧情。".repeat(200)
        val source = Message(id = "source", role = "assistant", content = original)
        val note = KnowledgeNote(id = "note", text = original, sourceMessageIds = listOf(source.id), sourceHash = ContextEngine.sourceHash(listOf(source)))
        val chat = summarized(listOf(source)).copy(knowledgeNotes = listOf(note))
        val results = JSONArray(KnowledgeVault.searchJson(chat, "秦岚"))
        assertEquals(2, results.length())
        for (index in 0 until results.length()) {
            val hit = results.getJSONObject(index)
            val offset = hit.getInt("offset")
            assertTrue(offset > 100_000)
            val page = JSONObject(KnowledgeVault.read(chat, hit.getString("id"), offset))
            assertEquals(offset, page.getInt("offset"))
            assertTrue(page.getString("text").contains("秦岚已把蓝色药盒交给江舟"))
            assertTrue(KnowledgeVault.recall(chat, "秦岚", 1800).contains("offset=$offset"))
        }
    }

    @Test fun secondAttachmentOffsetSkipsLongReasoningToolDataAndEarlierAttachments() {
        val original = "普通剧情。".repeat(30_000) + "秦岚已归还药盒。"
        val source = Message(
            id = "source", role = "assistant", content = "附录见后文。",
            reasoning = "秦岚只是推理中的候选人。" + "待确认。".repeat(20_000),
            toolCallsJson = """[{"function":{"arguments":"秦岚只是工具参数"}}]""",
            toolCallId = "tool-result",
            attachments = listOf(
                Attachment(name = "first.txt", mime = "text/plain", dataUrl = "前期资料。".repeat(20_000)),
                Attachment(name = "second.txt", mime = "text/plain", dataUrl = "data:text/plain;base64," + Base64.getEncoder().encodeToString(original.toByteArray(Charsets.UTF_8)))
            )
        )
        val chat = summarized(listOf(source))
        val hit = KnowledgeVault.search(chat, "秦岚").single()
        val full = ContextEngine.summarySource(listOf(source)).single()
        assertTrue(hit.offset > full.indexOf("text(name=second.txt"))
        val page = JSONObject(KnowledgeVault.read(chat, hit.id, hit.offset))
        assertEquals(full.substring(hit.offset), page.getString("text"))
        assertTrue(page.getString("text").contains("秦岚已归还药盒"))
        assertFalse(page.getString("text").contains("推理中的候选人"))
    }

    @Test fun sharedFormatterPreservesTheExistingReadProtocol() {
        val source = Message(id = "source", role = "assistant", content = "正文", reasoning = "推理",
            toolCallsJson = "[{\"id\":\"call\"}]", toolCallId = "result", attachments = listOf(
                Attachment(name = "photo.png", mime = "image/png", dataUrl = "data:image/png;base64,PRIVATE_IMAGE"),
                Attachment(name = "voice.wav", mime = "audio/wav", dataUrl = "PRIVATE_AUDIO"),
                Attachment(name = "other.bin", mime = "application/octet-stream", dataUrl = "PRIVATE_BINARY"),
                Attachment(name = "story.txt", mime = "text/plain", dataUrl = "文本附件原文")
            ))
        val expected = "id=source role=assistant content=正文 reasoning_content=推理 tool_calls=[{\"id\":\"call\"}] tool_call_id=result attachments=" +
            "image(name=photo.png,mime=image/png); audio(name=voice.wav,mime=audio/wav); attachment(name=other.bin,mime=application/octet-stream); text(name=story.txt,mime=text/plain): 文本附件原文"
        assertEquals(expected, ContextEngine.summarySource(listOf(source)).single())
        assertEquals("正文\n\n【文本附件：story.txt】\n文本附件原文", HistorySource.searchableText(source))
        val hit = KnowledgeVault.search(summarized(listOf(source)), "文本附件原文").single()
        assertEquals(expected.indexOf("文本附件原文"), hit.offset)
    }

    @Test fun offsetsAndReadPageBoundariesNeverSplitSurrogatePairs() {
        val source = Message(id = "unicode", role = "assistant", content = "x".repeat(12_000) + "🌙" + "a".repeat(179) + "秦岚已归还药盒。" + "z".repeat(8_000))
        val chat = summarized(listOf(source))
        val hit = KnowledgeVault.search(chat, "秦岚").single()
        val full = ContextEngine.summarySource(listOf(source)).single()
        assertTrue(hit.text.startsWith("…🌙"))
        val page = JSONObject(KnowledgeVault.read(chat, hit.id, hit.offset))
        assertTrue(page.getString("text").startsWith("🌙"))
        assertEquals(full.indexOf("🌙"), hit.offset)

        val prefixLength = ContextEngine.summarySource(listOf(Message(id = "paged", role = "assistant"))).single().length
        val paged = Message(id = "paged", role = "assistant", content = "a".repeat(5_999 - prefixLength) + "🌙尾部")
        val pagedChat = summarized(listOf(paged))
        val first = JSONObject(KnowledgeVault.read(pagedChat, "message:paged"))
        assertEquals(5_999, first.getInt("nextOffset"))
        val second = JSONObject(KnowledgeVault.read(pagedChat, "message:paged", first.getInt("nextOffset")))
        assertTrue(second.getString("text").startsWith("🌙"))
        assertEquals(ContextEngine.summarySource(listOf(paged)).single(), first.getString("text") + second.getString("text"))
        val insidePair = JSONObject(KnowledgeVault.read(pagedChat, "message:paged", 6_000))
        assertEquals(5_999, insidePair.getInt("offset"))
        assertTrue(insidePair.getString("text").startsWith("🌙"))
    }

    private fun attachments(text: String) = listOf(
        Attachment(name = "story.md", mime = "text/markdown", dataUrl = text),
        Attachment(name = "story.txt", mime = "text/plain", dataUrl = "data:text/plain;charset=utf-8," + URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("%2B", "+")),
        Attachment(name = "story.json", mime = "application/json", dataUrl = "data:application/json;base64," + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))),
        Attachment(name = "story.xml", mime = "application/xml", dataUrl = text),
        Attachment(name = "story.csv", mime = "application/csv", dataUrl = text)
    )

    private fun summarized(messages: List<Message>): Chat {
        val summary = Summary(id = "summary", text = "众人来到港口。", throughMessageId = messages.last().id, sourceHash = ContextEngine.sourceHash(messages))
        return Chat(messages = messages, summaries = listOf(summary), activeSummaryId = summary.id)
    }
}
