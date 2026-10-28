package app.pockettavern.engine

import app.pockettavern.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioHistoryOrganizerTest {
    @Test fun parsesStructuredHistoryPatchAndRejectsUnknownSource() {
        val json = """
            {"state":{"resources":[{"name":"文明点","value":"90","unit":"点"}]},"people":[{"name":"林岚","relationship":"已确认盟友","activity":"核对账本"}],"sourceMessageIds":["m2"]}
        """.trimIndent()
        val patch = ScenarioHistoryOrganizer.parse(json, setOf("m2"))
        assertEquals("90", patch.state.resources.single().value)
        assertEquals("已确认盟友", patch.people.single().relationship)
        assertEquals(listOf("m2"), patch.sourceMessageIds)
        try {
            ScenarioHistoryOrganizer.parse(json.replace("m2", "unknown"), setOf("m2"))
            throw AssertionError("unknown source should fail")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun chronologicalMergeKeepsOldFactsAndLetsLaterFactsReplaceFields() {
        val first = ScenarioHistoryPatch(
            state = ScenarioState(resources = listOf(ScenarioResource(name = "文明点", value = "100"))),
            people = listOf(ScenarioPerson(name = "林岚", relationship = "合作方", activity = "在门口")),
            sourceMessageIds = listOf("m1")
        )
        val second = ScenarioHistoryPatch(
            state = ScenarioState(resources = listOf(ScenarioResource(name = "文明点", value = "90"))),
            people = listOf(ScenarioPerson(name = "林岚", activity = "核对账本")),
            sourceMessageIds = listOf("m2")
        )
        val merged = ScenarioHistoryOrganizer.merge(first, second)
        assertEquals("90", merged.state.resources.single().value)
        assertEquals("合作方", merged.people.single().relationship)
        assertEquals("核对账本", merged.people.single().activity)
        assertEquals(listOf("m1", "m2"), merged.sourceMessageIds)
    }

    @Test fun mergeIntoDoesNotEraseExistingFieldsWhenHistoryOmitsThem() {
        val chat = Chat(
            scenarioState = ScenarioState(location = "主楼书房", resources = listOf(ScenarioResource(name = "晶核", value = "4"))),
            scenarioRoster = listOf(ScenarioPerson(name = "林岚", relationship = "已确认盟友", clothing = "灰色外套"))
        )
        val updated = ScenarioHistoryOrganizer.mergeInto(
            chat,
            ScenarioHistoryPatch(
                state = ScenarioState(resources = listOf(ScenarioResource(name = "晶核", value = "3"))),
                people = listOf(ScenarioPerson(name = "林岚", activity = "盘点物资")),
                sourceMessageIds = listOf("m1")
            )
        )
        assertEquals("主楼书房", updated.scenarioState.location)
        assertEquals("3", updated.scenarioState.resources.single().value)
        assertEquals("已确认盟友", updated.scenarioRoster.single().relationship)
        assertEquals("灰色外套", updated.scenarioRoster.single().clothing)
        assertEquals("盘点物资", updated.scenarioRoster.single().activity)
        assertTrue(updated.scenarioState.source.isNotBlank())
    }

    @Test fun mergeIntoPersistsSourceBoundScenarioNotesAndIsIdempotent() {
        val chat = Chat(messages = listOf(
            Message(id = "m1", role = "user", content = "文明点增加到 90，林岚在书房核对账本"),
            Message(id = "m2", role = "assistant", content = "林岚穿着灰色外套，与你是盟友。")
        ))
        val patch = ScenarioHistoryPatch(
            state = ScenarioState(resources = listOf(ScenarioResource(name = "文明点", value = "90", unit = "点"))),
            people = listOf(ScenarioPerson(name = "林岚", relationship = "盟友", clothing = "灰色外套")),
            sourceMessageIds = listOf("m1", "m2")
        )
        val first = ScenarioHistoryOrganizer.mergeInto(chat, patch)
        val notes = KnowledgeVault.validNotes(first)
        assertEquals(2, notes.size)
        assertTrue(notes.any { it.title == "剧本整理·剧本状态" && it.entity == "剧本状态" })
        assertTrue(notes.any { it.title == "剧本整理·人物·林岚" && it.entity == "林岚" })
        assertEquals(setOf("m1", "m2"), notes.flatMap { it.sourceMessageIds }.toSet())
        assertTrue(notes.all { it.sourceKind == "conversation" })
        assertTrue(notes.all { it.sourceHash == ContextEngine.sourceHash(chat.messages) })

        val second = ScenarioHistoryOrganizer.mergeInto(first, patch)
        assertEquals("重复整理同一来源不应新增知识条目", notes.map { it.id }.toSet(), KnowledgeVault.validNotes(second).map { it.id }.toSet())

        val later = ScenarioHistoryOrganizer.mergeInto(second, patch.copy(
            state = ScenarioState(resources = listOf(ScenarioResource(name = "文明点", value = "80", unit = "点"))),
            sourceMessageIds = listOf("m2")
        ))
        assertEquals("新的整理结果应替换旧的剧本快照", 2, KnowledgeVault.validNotes(later).size)
        assertTrue(KnowledgeVault.validNotes(later).all { it.sourceMessageIds == listOf("m2") })
    }

    @Test fun mergeIntoDoesNotCreateMemoryWithoutEvidenceIds() {
        val chat = Chat(messages = listOf(Message(id = "m1", content = "原文")))
        val updated = ScenarioHistoryOrganizer.mergeInto(
            chat,
            ScenarioHistoryPatch(state = ScenarioState(location = "未知地点"))
        )
        assertTrue(updated.knowledgeNotes.isEmpty())
    }

    @Test fun organizerRequestCarriesExistingValidatedMemoryAsEvidence() {
        val source = Message(id = "old-source", role = "assistant", content = "系统记录：骨材 26 吨，林岚负责仓库。")
        val note = KnowledgeNote(
            id = "old-note", title = "旧统计", entity = "scenario:state", kind = "fact",
            text = "骨材：26 吨；林岚负责仓库。", sourceMessageIds = listOf(source.id),
            sourceHash = ContextEngine.sourceHash(listOf(source))
        )
        val chat = Chat(messages = listOf(source), knowledgeNotes = listOf(note))
        val evidence = ScenarioHistoryOrganizer.memoryEvidence(chat, 500)
        assertEquals(listOf(note.id), evidence.map { it.id })
        val request = ScenarioHistoryOrganizer.requestText(ScenarioHistoryPatch(), emptyList(), evidence)
        assertTrue(request.contains("existingMemory"))
        assertTrue(request.contains("骨材：26 吨"))
        assertTrue(request.contains(source.id))
    }

    @Test fun organizerMemoryEvidenceExcludesRejectedDraftSources() {
        val draft = Message(id = "draft", role = "assistant", content = "废稿统计：文明点 999", contextExcluded = true)
        val note = KnowledgeNote(
            id = "draft-note", title = "废稿统计", kind = "fact", entity = "scenario:state",
            text = draft.content, sourceMessageIds = listOf(draft.id),
            sourceHash = ContextEngine.sourceHash(listOf(draft))
        )
        val chat = Chat(messages = listOf(draft), knowledgeNotes = listOf(note))
        assertTrue(ScenarioHistoryOrganizer.memoryEvidence(chat, 500).isEmpty())
    }

    @Test fun organizerUsesValidatedSummaryWhenItsRawPrefixWasCompacted() {
        val old = Message(id = "old", role = "assistant", content = "林岚与周行已经确认是姐弟，住在东楼。")
        val current = Message(id = "current", role = "user", content = "继续处理今天的安排。")
        val summary = Summary(
            id = "summary-old", text = "## 人物关系\n林岚与周行是姐弟。\n## 当前状态\n住在东楼。",
            throughMessageId = old.id, sourceHash = ContextEngine.sourceHash(listOf(old))
        )
        val chat = Chat(messages = listOf(old, current), summaries = listOf(summary))
        val evidence = ScenarioHistoryOrganizer.memoryEvidence(chat, 2000)
        assertTrue(evidence.any { it.id == summary.id && it.sourceKind == "summary" })
        val request = ScenarioHistoryOrganizer.requestText(ScenarioHistoryPatch(), listOf(current), evidence)
        assertTrue(request.contains("林岚与周行是姐弟"))
        val parsed = ScenarioHistoryOrganizer.parse(
            """{"state":{"note":"保留东楼居住事实"},"people":[],"sourceMessageIds":["current"],"sourceSummaryIds":["summary-old"]}""",
            setOf("current"), setOf("summary-old")
        )
        assertEquals(listOf("summary-old"), parsed.sourceSummaryIds)
    }

    @Test fun organizerKeepsSummaryEvidenceAfterItsRawPrefixIsExcluded() {
        val old = Message(id = "old", role = "assistant", content = "早期人物：顾砚舟是庄园管家，负责接待。", contextExcluded = true)
        val current = Message(id = "current", role = "user", content = "继续今天的安排。")
        val summary = Summary(
            id = "summary-old",
            text = "## 人物关系\n顾砚舟是庄园管家，负责接待。",
            throughMessageId = old.id,
            sourceHash = ContextEngine.sourceHash(listOf(old))
        )
        val chat = Chat(messages = listOf(old, current), summaries = listOf(summary))
        val evidence = ScenarioHistoryOrganizer.memoryEvidence(chat, 2000)
        assertTrue(evidence.any { it.id == summary.id && it.sourceKind == "summary" })
        assertTrue(ScenarioHistoryOrganizer.requestText(ScenarioHistoryPatch(), listOf(current), evidence).contains("顾砚舟"))
    }
}
