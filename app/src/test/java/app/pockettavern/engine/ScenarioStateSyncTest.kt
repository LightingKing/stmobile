package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.ScenarioOption
import app.pockettavern.domain.ScenarioPerson
import app.pockettavern.domain.ScenarioQuest
import app.pockettavern.domain.ScenarioResource
import app.pockettavern.domain.ScenarioState
import app.pockettavern.domain.ScenarioUpgrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioStateSyncTest {
    @Test fun validStateBlockIsHiddenAndMergesOnlyChangedRows() {
        val chat = Chat(
            characterId = "story",
            scenarioState = ScenarioState(resources = listOf(ScenarioResource(name = "文明点", value = "100万", unit = "点")))
        )
        val answer = "顾砚舟把账本推到你面前。\n<pocket_tavern_state>{\"resources\":[{\"name\":\"文明点\",\"value\":\"970000\",\"unit\":\"点\",\"note\":\"完成兑换\"}],\"location\":\"主楼书房\",\"characters\":[{\"name\":\"顾砚舟\",\"activity\":\"核对账本\",\"status\":\"当前在场\"}]}</pocket_tavern_state>"

        val result = ScenarioStateSync.apply(chat, answer)

        assertTrue(result.applied)
        assertFalse(result.visibleContent.contains("pocket_tavern_state"))
        assertEquals("970000", result.chat.scenarioState.resources.single().value)
        assertEquals("主楼书房", result.chat.scenarioState.location)
        assertEquals("核对账本", result.chat.scenarioRoster.single().activity)
        assertEquals("当前在场", result.chat.scenarioRoster.single().status)
    }

    @Test fun incrementalRowsPreserveUnsubmittedFieldsAndReadAllPersonAndOptionFields() {
        val chat = Chat(
            characterId = "story",
            scenarioState = ScenarioState(
                resources = listOf(ScenarioResource(name = "文明点", value = "100", unit = "点", note = "账本")),
                options = listOf(ScenarioOption(name = "医疗模块", cost = "20", requirement = "L2", result = "恢复", available = false)),
                upgrades = listOf(ScenarioUpgrade(name = "防护", level = "L1", cost = "10", requirement = "材料", benefit = "保温", status = "进行中")),
                quests = listOf(ScenarioQuest(title = "采购", status = "进行中", progress = "1/3", description = "采购材料", reward = "库存"))
            ),
            scenarioRoster = listOf(
                ScenarioPerson(name = "林岚", role = "管家", relationship = "盟友", ability = "盘点", activity = "休息", clothing = "外套", personality = "谨慎", description = "旧描述", status = "当前在场")
            )
        )
        val answer = """
            <pt_state>{
              "resources":[{"name":"文明点","value":"90"}],
              "options":[{"name":"医疗模块","result":"治疗"},{"name":"储存模块","available":false}],
              "upgrades":[{"name":"防护","status":"已完成"}],
              "quests":[{"title":"采购","progress":"2/3"}],
              "characters":[{"name":"林岚","role":"副管家","activity":"核对账本","description":"新描述"}]
            }</pt_state>
        """.trimIndent()

        val result = ScenarioStateSync.apply(chat, answer)
        val state = result.chat.scenarioState
        assertEquals("90", state.resources.single().value)
        assertEquals("点", state.resources.single().unit)
        assertEquals("账本", state.resources.single().note)
        val medical = state.options.first { it.name == "医疗模块" }
        assertEquals("20", medical.cost)
        assertEquals("L2", medical.requirement)
        assertEquals("治疗", medical.result)
        assertFalse(medical.available)
        assertEquals(false, state.options.first { it.name == "储存模块" }.available)
        assertEquals("L1", state.upgrades.single().level)
        assertEquals("10", state.upgrades.single().cost)
        assertEquals("材料", state.upgrades.single().requirement)
        assertEquals("保温", state.upgrades.single().benefit)
        assertEquals("已完成", state.upgrades.single().status)
        assertEquals("进行中", state.quests.single().status)
        assertEquals("2/3", state.quests.single().progress)
        assertEquals("采购材料", state.quests.single().description)
        assertEquals("库存", state.quests.single().reward)
        val person = result.chat.scenarioRoster.single()
        assertEquals("副管家", person.role)
        assertEquals("新描述", person.description)
        assertEquals("盟友", person.relationship)
        assertEquals("当前在场", person.status)
    }

    @Test fun missingOrInvalidStateBlockDoesNotChangeChat() {
        val chat = Chat(characterId = "story", scenarioState = ScenarioState(location = "原地点"))
        val noBlock = ScenarioStateSync.apply(chat, "普通回复")
        assertFalse(noBlock.applied)
        assertEquals(chat, noBlock.chat)
        val invalid = ScenarioStateSync.apply(chat, "正文 <pt_state>这不是 JSON</pt_state>")
        assertFalse(invalid.applied)
        assertEquals(chat, invalid.chat)
    }

    @Test fun statePromptIsOnlyEnabledForScenarioChats() {
        assertEquals("", ScenarioStateSync.prompt(Chat()))
        val prompt = ScenarioStateSync.prompt(Chat(characterId = "story"))
        assertTrue(prompt.contains("pocket_tavern_state"))
        assertTrue(prompt.contains("没有变化时不要追加"))
    }
}
