package app.pockettavern.ui

import app.pockettavern.data.BuiltinScenarios
import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.ScenarioResource
import app.pockettavern.domain.ScenarioState
import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.domain.WorldEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScenarioPanelTest {
    @Test fun frostManorBaselineBecomesReadableStatusTables() {
        val entry = BuiltinScenarios.load { File("src/main/assets", it).readText(Charsets.UTF_8) }.single()
        val chat = Chat(characterId = entry.character.id)
        val data = BuiltinScenarios.merge(AppData(), listOf(entry))
        val session = app.pockettavern.domain.UiSession(data = data)
        val world = ScenarioPanelData.entries(chat, session)
        val baseline = ScenarioPanelData.baselineState(world)
        assertEquals("上海青浦栖云庄园卧室", baseline.location)
        assertEquals("2026年11月1日09:00", baseline.time)
        assertTrue(baseline.resources.any { it.name == "文明点" && it.value == "100万" })
        assertTrue(baseline.resources.any { it.name == "正式雇员" && it.value == "33" })
        assertTrue(baseline.resources.any { it.name == "开局当班员工" && it.value == "13" })
        assertTrue(baseline.options.any { it.name.contains("T1") })
        assertTrue(baseline.upgrades.any { it.level == "L1" })
        assertTrue(baseline.quests.isNotEmpty())
    }

    @Test fun frostManorRosterReadsCharacterCardsAndCurrentRosterSections() {
        val entry = BuiltinScenarios.load { File("src/main/assets", it).readText(Charsets.UTF_8) }.single()
        val data = BuiltinScenarios.merge(AppData(), listOf(entry))
        val world = ScenarioPanelData.entries(Chat(characterId = entry.character.id), app.pockettavern.domain.UiSession(data = data))
        val people = ScenarioPanelData.people(world)
        val manager = people.first { it.name == "骆歆" }
        assertTrue(manager.role.contains("起居") || manager.role.contains("接待"))
        assertTrue(manager.clothing.isNotBlank())
        assertTrue(manager.activity.isNotBlank())
        assertTrue(people.any { it.name == "王六堡" && it.ability.isNotBlank() })
        assertTrue(ScenarioPanelData.rosterSections(world).any { it.title == "庄园人员与合作名册" })
    }

    @Test fun currentStateOverlaysBaselineWithoutDroppingUnchangedStatistics() {
        val baseline = ScenarioState(
            location = "庄园", resources = listOf(
                ScenarioResource(name = "文明点", value = "100万"),
                ScenarioResource(name = "正式雇员", value = "33")
            )
        )
        val current = ScenarioState(resources = listOf(ScenarioResource(name = "文明点", value = "97万")))
        val merged = ScenarioPanelData.mergeState(baseline, current)
        assertEquals("97万", merged.resources.first { it.name == "文明点" }.value)
        assertEquals("33", merged.resources.first { it.name == "正式雇员" }.value)
        assertEquals("庄园", merged.location)
    }

    @Test fun legacyWorldbookIdStillFeedsScenarioPanel() {
        val entry = WorldEntry(
            name = "旧剧本状态",
            content = "【本故事开局记录】2026年11月1日09:00，栖云庄园卧室。现金8000万元，文明点100万。",
            characterId = "character-from-previous-install"
        )
        val world = ScenarioPanelData.entries(Chat(characterId = "new-character-id"), app.pockettavern.domain.UiSession(data = AppData(worldEntries = listOf(entry))))
        assertEquals(1, world.size)
        assertEquals("栖云庄园卧室", ScenarioPanelData.baselineState(world).location)
    }

    @Test fun historyVaultFactsAreMergedIntoScenarioPanel() {
        val note = KnowledgeNote(
            title = "剧本整理·剧本状态",
            text = "本条记忆来自历史对话整理，只记录原文中明确确认的剧本状态。\n\n- 位置：主宅客厅\n- 时间：2026年11月2日\n\n资源：\n- 文明点：97万（已支付基地蓝图）\n\n任务：\n- 建造 T1 基地；状态：进行中；进度：20%；说明：施工已授权；奖励：独立闭环"
        )
        val state = ScenarioPanelData.historyState(listOf(note))
        assertEquals("主宅客厅", state.location)
        assertEquals("97万", state.resources.single().value)
        assertEquals("进行中", state.quests.single().status)
    }

    @Test fun historyVaultPeopleAreVisibleInRoster() {
        val note = KnowledgeNote(
            title = "剧本整理·人物·顾砚舟",
            text = "- 角色/岗位：庄园管家\n- 与主角关系：长期管家\n- 当前活动：在主宅核对预算\n- 状态：当前在场"
        )
        val person = ScenarioPanelData.historyPeople(listOf(note)).single()
        assertEquals("顾砚舟", person.name)
        assertEquals("庄园管家", person.role)
        assertEquals("当前在场", person.status)
    }

    @Test fun peerRelationshipDoesNotCreateMisleadingProtagonistEdge() {
        val protagonist = app.pockettavern.domain.ScenarioPerson(name = "谷若尘", role = "主角")
        val linq = app.pockettavern.domain.ScenarioPerson(name = "林岚", relationship = "与顾砚舟是朋友")
        val manager = app.pockettavern.domain.ScenarioPerson(name = "顾砚舟", relationship = "与主角是管家")
        val edges = relationshipEdges("谷若尘", protagonist, listOf(protagonist, linq, manager))
        assertTrue(edges.any { it.from == "林岚" && it.to == "顾砚舟" && it.label == "朋友" })
        assertTrue(edges.any { it.from == "谷若尘" && it.to == "顾砚舟" && it.label == "管家" })
        assertTrue(edges.none { it.from == "谷若尘" && it.to == "林岚" })
    }

    @Test fun unknownRelationshipOnlyAppearsAsUnclassifiedGroup() {
        val person = app.pockettavern.domain.ScenarioPerson(name = "路人甲", role = "临时访客", relationship = "未记录")
        val edges = relationshipEdges("主角 / 当前视角", null, listOf(person))
        assertTrue(edges.isEmpty())
    }}
