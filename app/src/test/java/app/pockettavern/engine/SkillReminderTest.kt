package app.pockettavern.engine

import app.pockettavern.domain.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/**
 * 0.2.4: the user found that many models write without the selected Skills unless told to. Skill
 * bodies sit at the top of the request, far from the latest message in a long chat, so every turn
 * the latest message now carries a short note naming them.
 */
class SkillReminderTest {
    private val plain = SkillRecord(id = "s1", name = "去 AI 味", content = "规则", enabled = true)
    private val withFiles = SkillRecord(id = "s2", name = "场景导演", content = "先读 references/pace.md", enabled = true, files = mapOf("references/pace.md" to "节奏"))

    private fun build(skills: List<SkillRecord>, tools: Boolean = true, messages: List<Message> = listOf(Message(content = "第一问"), Message(role = "assistant", content = "第一答"), Message(id = "now", content = "继续写"))): List<org.json.JSONObject> {
        val provider = ProviderConfig(id = "p", models = listOf(ModelInfo(id = "m", contextTokens = 200_000, supportsTools = tools)))
        val chat = Chat(id = "c", providerId = "p", modelId = "m", skillIds = skills.map { it.id }, messages = messages)
        val json = JSONArray(ContextEngine.build(AppData(providers = listOf(provider), chats = listOf(chat), skills = skills), chat).messagesJson)
        return (0 until json.length()).map(json::getJSONObject)
    }

    @Test fun theLatestMessageNamesTheSkillsAndAsksForTheirFiles() {
        val sent = build(listOf(plain, withFiles))
        val latest = sent.last { it.getString("role") == "user" }.getString("content")
        assertTrue(latest.startsWith("继续写\n\n" + SkillReminder.HEADING))
        assertTrue(latest.contains("《去 AI 味》、《场景导演》"))
        assertTrue("files are read first", latest.contains("《场景导演》带参考文件") && latest.contains("skill_read"))
        assertEquals("earlier turns stay as written", "第一问", sent.first { it.getString("role") == "user" }.getString("content"))
        assertEquals("one note per request", 1, sent.count { it.optString("content").contains(SkillReminder.HEADING) })
    }

    @Test fun noSkillsNoNoteAndCorrectionsStayPlain() {
        assertFalse(build(emptyList()).any { it.optString("content").contains(SkillReminder.HEADING) })
        val correction = listOf(Message(content = "写一段"), Message(role = "assistant", content = "好"), Message(content = "她是左撇子", intent = "correction"))
        assertFalse(build(listOf(plain), messages = correction).any { it.optString("content").contains(SkillReminder.HEADING) })
    }

    @Test fun withoutToolsTheNoteDoesNotAskForFiles() {
        val latest = build(listOf(withFiles), tools = false).last { it.getString("role") == "user" }.getString("content")
        assertTrue(latest.contains("《场景导演》"))
        assertFalse(latest.contains("skill_read"))
    }

    @Test fun theStoredMessageIsUnchanged() {
        val messages = listOf(Message(id = "now", content = "继续写"))
        val hash = ContextEngine.sourceHash(messages)
        build(listOf(plain), messages = messages)
        assertEquals(hash, ContextEngine.sourceHash(messages))
        assertEquals("继续写", messages.single().content)
    }
}
