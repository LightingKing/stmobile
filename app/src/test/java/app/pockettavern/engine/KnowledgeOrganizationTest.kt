package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.domain.Message
import org.junit.Assert.*
import org.junit.Test

class KnowledgeOrganizationTest {
    private fun note(id: String, message: Message, entity: String, alias: String, time: Long) = KnowledgeNote(
        id = id, title = "林宁的记录", kind = "person", entity = entity, aliases = listOf(alias),
        text = message.content, sourceMessageIds = listOf(message.id),
        sourceHash = ContextEngine.sourceHash(listOf(message)), createdAt = time
    )

    @Test fun entityTimelineKeepsBothOldAndNewFactsInSourceOrder() {
        val first = Message(id = "first", content = "林宁保管钥匙。")
        val next = Message(id = "next", content = "林宁已经把钥匙交还周远。")
        val before = note("old", first, "person:林宁", "小林", 200)
        val after = note("new", next, "person:林宁", "林老师", 100)
        val chat = Chat(messages = listOf(first, next), knowledgeNotes = listOf(after, before))

        val page = KnowledgeOrganization.pages(chat).single()
        assertEquals("林宁", page.title)
        assertEquals(listOf("old", "new"), page.notes.map { it.id })
        assertEquals(listOf("小林", "林老师"), page.aliases)
        assertTrue(page.notes.first().text.contains("保管"))
        assertTrue(page.notes.last().text.contains("交还"))
        assertEquals(listOf(after, before), chat.knowledgeNotes)
    }

    @Test fun sharedAliasDoesNotMergeDifferentExplicitEntities() {
        val a = Message(id = "a", content = "林宁是医生。")
        val b = Message(id = "b", content = "林远是教师。")
        val chat = Chat(messages = listOf(a, b), knowledgeNotes = listOf(
            note("a", a, "person:林宁", "小林", 1), note("b", b, "person:林远", "小林", 2)
        ))
        assertEquals(2, KnowledgeOrganization.pages(chat).size)
        assertEquals("person:alice", KnowledgeOrganization.canonicalEntity("  ＰＥＲＳＯＮ:Ａｌｉｃｅ  "))
    }

    @Test fun invalidatedSourcesAndArchiveIndexesAreNotPromotedToEntityFacts() {
        val original = Message(id = "m", content = "林宁保管钥匙。")
        val stale = note("stale", original, "person:林宁", "小林", 1)
        val edited = original.copy(content = "作者更正：钥匙没有交给林宁。")
        val range = note("range", edited, "person:林宁", "", 2).copy(kind = "archive", text = "已归档来源范围")
        assertTrue(KnowledgeOrganization.pages(Chat(messages = listOf(edited), knowledgeNotes = listOf(stale, range))).isEmpty())
        assertEquals("归档范围", KnowledgeOrganization.label(KnowledgeOrganization.category(range)))
        assertEquals("旧版记忆", KnowledgeOrganization.label(KnowledgeOrganization.category(stale.copy(kind = "unknown"))))
    }
}
