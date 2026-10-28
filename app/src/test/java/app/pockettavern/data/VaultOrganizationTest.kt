package app.pockettavern.data

import app.pockettavern.domain.*
import app.pockettavern.engine.ContextEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class VaultOrganizationTest {
    @Test fun organizedVaultPreservesHistoryAndAllGeneratedLinksResolve() {
        val before = Message(id = "one", content = "林宁答应明天归还钥匙。")
        val after = Message(id = "two", content = "林宁在钟楼归还了钥匙。")
        val notes = listOf(
            KnowledgeNote(id = "promise", title = "归还钥匙的约定", kind = "agreement", entity = "person:林宁", aliases = listOf("小林"), text = before.content,
                sourceMessageIds = listOf(before.id), sourceHash = ContextEngine.sourceHash(listOf(before))),
            KnowledgeNote(id = "returned", title = "钥匙已归还", kind = "event", entity = "person:林宁", text = after.content,
                sourceMessageIds = listOf(after.id), sourceHash = ContextEngine.sourceHash(listOf(after))),
            KnowledgeNote(id = "range", title = "第 1–2 条原文", kind = "archive", text = "本次归档包含两条来源消息。",
                sourceMessageIds = listOf(before.id, after.id), sourceHash = ContextEngine.sourceHash(listOf(before, after)))
        )
        val summary = Summary(id = "compact", text = "钥匙已交还；林宁还在钟楼。", throughMessageId = after.id,
            sourceHash = ContextEngine.sourceHash(listOf(before, after)), format = "compact-v1")
        val chat = Chat(messages = listOf(before, after), knowledgeNotes = notes, summaries = listOf(summary), activeSummaryId = summary.id)
        withVault(chat) { files ->
            val person = files.getValue("entities/${VaultExport.token("person:林宁")}.md")
            assertTrue(person.indexOf(before.content) < person.indexOf(after.content))
            assertTrue(person.contains("aliases: [\"小林\"]"))
            assertTrue(files.getValue("categories/agreement.md").contains("归还钥匙的约定"))
            assertTrue(files.getValue("sources/${VaultExport.token(before.id)}.md").contains("notes/${VaultExport.token("promise")}"))
            assertTrue(files.getValue("notes/${VaultExport.token("range")}.md").contains("不能据此断言所有事实都已被提取"))
            assertTrue(files.getValue("summaries/${VaultExport.token(summary.id)}.md").contains("当前生效"))
            assertTrue(files.getValue("summary.md").contains(summary.text))
            files.filterKeys { it.endsWith(".md") }.forEach { (path, body) ->
                Regex("\\[\\[([^]|]+)(?:\\|[^]]*)?]]").findAll(body).forEach { link ->
                    assertTrue("$path contains broken link ${link.value}", files.containsKey("${link.groupValues[1]}.md"))
                }
            }
        }
    }

    @Test fun pathsAreGeneratedAndTitlesCannotCreateWikiTargets() {
        val message = Message(id = "../../outside", content = "原文事实")
        val note = KnowledgeNote(id = "../../note", title = "林宁|别页]]\n#标题", kind = "person", entity = "person:../../林宁",
            aliases = listOf("别名\"\n新行"), text = "原文事实", sourceMessageIds = listOf(message.id), sourceHash = ContextEngine.sourceHash(listOf(message)))
        withVault(Chat(messages = listOf(message), knowledgeNotes = listOf(note))) { files ->
            assertTrue(files.keys.none { it.contains("..") || it.startsWith("/") || it.contains('\\') })
            assertTrue(files.getValue("index.md").contains("林宁｜别页］］ #标题"))
            assertTrue(files.getValue("notes/${VaultExport.token(note.id)}.md").contains("aliases: [\"别名\\\"\\n新行\"]"))
        }
    }

    @Test fun invalidOrExcludedNotesStayOutOfKnowledgePagesButOriginalRemains() {
        val original = Message(id = "m", content = "已经归还钥匙")
        val note = KnowledgeNote(id = "n", title = "归还", kind = "event", entity = "event:归还", text = original.content,
            sourceMessageIds = listOf(original.id), sourceHash = ContextEngine.sourceHash(listOf(original)))
        withVault(Chat(messages = listOf(original.copy(contextExcluded = true)), knowledgeNotes = listOf(note))) { files ->
            assertFalse(files.containsKey("notes/${VaultExport.token(note.id)}.md"))
            assertTrue(files.containsKey("sources/${VaultExport.token(original.id)}.md"))
        }
    }

    @Test fun scenarioHistoryNotesAreVisibleInConversationExportAndVault() {
        val source = Message(id = "scenario-source", content = "系统面板显示文明点 90；林宁与你是盟友。")
        val organized = app.pockettavern.engine.ScenarioHistoryOrganizer.mergeInto(
            Chat(messages = listOf(source)),
            app.pockettavern.engine.ScenarioHistoryPatch(
                state = ScenarioState(resources = listOf(ScenarioResource(name = "文明点", value = "90", unit = "点"))),
                people = listOf(ScenarioPerson(name = "林宁", relationship = "盟友")),
                sourceMessageIds = listOf(source.id)
            )
        )
        val conversation = VaultExport.conversation(organized)
        assertTrue(conversation.contains("## 历史整理记忆"))
        assertTrue(conversation.contains("文明点"))
        assertTrue(conversation.contains("人物·林宁"))
        withVault(organized) { files ->
            assertTrue(files.keys.any { it.startsWith("notes/") && files[it]!!.contains("剧本整理·") })
            assertTrue(files.getValue("categories/fact.md").contains("剧本整理·剧本状态"))
            assertTrue(files.getValue("categories/person.md").contains("剧本整理·人物·林宁"))
        }
    }

    private fun withVault(chat: Chat, inspect: (Map<String, String>) -> Unit) {
        val directory = kotlin.io.path.createTempDirectory("organized-vault").toFile()
        try {
            val file = File(directory, "vault.zip")
            VaultExport.write(chat, file)
            ZipFile(file).use { zip -> inspect(zip.entries().asSequence().associate { entry ->
                entry.name to zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }) }
        } finally { directory.deleteRecursively() }
    }
}
