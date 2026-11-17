package app.pockettavern.engine

import app.pockettavern.data.DataCodec
import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.ScenarioDraft
import app.pockettavern.domain.ScenarioDraftEntry
import app.pockettavern.domain.WorldDetail
import app.pockettavern.domain.WorldResident
import app.pockettavern.domain.WorldSummary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.2 (W4): 世界助手, the 剧本助手 in world mode, and building its draft into a 小世界. */
class WorldBuilderTest {
    private val world = ScenarioDraft(worldMode = true)
    private fun person(name: String, content: String = "【身份】民宿老板娘\n【作息】六点起床", keywords: List<String> = emptyList()) = ScenarioDraftEntry(name, content, keywords)

    @Test fun worldModeAsksForSchedulesGoalsAndRelationsAndHasItsOwnFields() {
        assertTrue(ScenarioBuilder.prompt(world).startsWith("【世界助手】"))
        assertTrue(ScenarioBuilder.prompt(world).contains("【作息】"))
        assertTrue(ScenarioBuilder.prompt(world).contains("【目标】"))
        assertTrue(ScenarioBuilder.prompt(world).contains("【关系】"))
        assertTrue(ScenarioBuilder.prompt(ScenarioDraft()).startsWith("【剧本助手】"))
        val save = ScenarioBuilder.definitions(worldMode = true).first().getJSONObject("function")
        val properties = save.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(properties.has("user_name"))
        assertTrue(properties.has("group_name"))
        assertTrue(properties.getJSONObject("people").getString("description").contains("最多 8 位"))
        assertFalse(ScenarioBuilder.definitions().first().getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").has("user_name"))
        assertTrue(ScenarioBuilder.definitions(worldMode = true)[2].getJSONObject("function").getString("description").contains("建成小世界"))
    }

    @Test fun savingKeepsTheWorldsLimits() {
        val (saved, said) = ScenarioBuilder.applySave(world, JSONObject().put("title", "海边民宿").put("user_name", "阿遥").put("group_name", "民宿大家庭")
            .put("people", JSONArray().put(JSONObject().put("name", "林姐").put("content", "【身份】老板娘")))).getOrThrow()
        assertEquals("阿遥", saved.userName)
        assertEquals("民宿大家庭", saved.groupName)
        assertTrue(said.contains("居民怎么称呼用户"))
        assertTrue(said.contains("1 个人物"))
        // A ninth resident, an over-long name, or a world name past 40 characters is refused whole.
        val eight = world.copy(people = (1..8).map { person("人$it") })
        assertTrue(ScenarioBuilder.applySave(eight, JSONObject().put("people", JSONArray().put(JSONObject().put("name", "第九位").put("content", "x")))).exceptionOrNull()!!.message!!.contains("最多 8 位居民"))
        assertTrue(ScenarioBuilder.applySave(world, JSONObject().put("people", JSONArray().put(JSONObject().put("name", "名".repeat(31)).put("content", "x")))).exceptionOrNull()!!.message!!.contains("30 字"))
        assertTrue(ScenarioBuilder.applySave(world, JSONObject().put("title", "名".repeat(41))).exceptionOrNull()!!.message!!.contains("40 字"))
        // Ordinary scenarios keep their own limits and ignore the world-only fields.
        val scenario = ScenarioBuilder.applySave(ScenarioDraft(), JSONObject().put("title", "名".repeat(60)).put("user_name", "阿遥")).getOrThrow().first
        assertEquals("", scenario.userName)
    }

    @Test fun theDraftViewAndWhatIsMissingFollowTheMode() {
        assertTrue(ScenarioBuilder.view(world).startsWith("【当前世界草稿】还是空的"))
        val draft = world.copy(title = "海边民宿", userName = "阿遥", setting = "海边小镇", people = listOf(person("林姐")))
        val view = ScenarioBuilder.view(draft)
        assertTrue(view.contains("居民怎么称呼用户：阿遥"))
        assertTrue(view.contains("居民（1/8 位）"))
        assertTrue(view.contains("还没有建成世界"))
        assertEquals(listOf("世界名", "世界观（核心设定或世界观条目）", "至少一位居民"), ScenarioBuilder.missing(world))
        assertEquals(emptyList<String>(), ScenarioBuilder.missing(draft))
        // No opening needed for a world.
        assertTrue(ScenarioBuilder.missing(ScenarioDraft(title = "x", setting = "y")).contains("开场"))
        assertTrue(ScenarioBuilder.view(draft.copy(worldId = "wcfad81922177", builtAt = 5, updatedAt = 9)).contains("之后的改动还没有更新进去"))
    }

    @Test fun aWorldDraftIsNeverTurnedIntoAScenarioCard() {
        val chat = Chat(id = "c", task = ScenarioBuilder.TASK, scenarioDraft = world.copy(title = "海边民宿", setting = "海边小镇", people = listOf(person("林姐"))))
        assertTrue(ScenarioBuilder.finish(AppData(chats = listOf(chat)), "c").exceptionOrNull()!!.message!!.contains("小世界"))
    }

    @Test fun theDraftsWorldFieldsSurviveSavingAndLoading() {
        val chat = Chat(id = "c", task = ScenarioBuilder.TASK, scenarioDraft = world.copy(title = "海边民宿", userName = "阿遥", groupName = "民宿大家庭", worldId = "wcfad81922177"))
        val restored = DataCodec.decode(DataCodec.encode(AppData(chats = listOf(chat)))).chats.single().scenarioDraft!!
        assertTrue(restored.worldMode)
        assertEquals("阿遥", restored.userName)
        assertEquals("民宿大家庭", restored.groupName)
        assertEquals("wcfad81922177", restored.worldId)
        assertEquals("wcfad81922177", restored.builtId)
    }

    @Test fun aDraftBecomesTheWorldJson() {
        val draft = world.copy(title = "海边民宿", summary = "海边小镇的民宿", userRole = "新来的帮工", userName = "阿遥", groupName = "民宿大家庭",
            setting = "南方海边小镇，民宿三层楼。", rules = "节奏慢。", opening = "清晨，第一班渡轮靠岸。",
            world = listOf(ScenarioDraftEntry("渡口", "每天三班渡轮", listOf("渡口", "渡轮"), always = true)),
            people = listOf(person("林姐", "【角色：林姐】\n【身份】老板娘", listOf("老板娘", "林姐")), person("小满", "【身份】林姐的女儿", listOf("老板娘"))))
        val pack = WorldPack.fromDraft(draft, startPaused = true).getOrThrow()
        assertEquals("海边民宿", pack.getString("name"))
        assertEquals("阿遥", pack.getString("userName"))
        assertEquals("民宿大家庭", pack.getString("groupName"))
        assertEquals("清晨，第一班渡轮靠岸。", pack.getString("opening"))
        assertTrue(pack.getBoolean("startPaused"))
        val residents = pack.getJSONArray("residents")
        assertEquals("【身份】老板娘", residents.getJSONObject(0).getString("card"))
        assertEquals("老板娘", residents.getJSONObject(0).getJSONArray("aliases").getString(0))
        assertEquals(0, residents.getJSONObject(1).getJSONArray("aliases").length(), "an alias another resident already has is dropped")
        assertTrue(pack.getJSONArray("worldBook").getJSONObject(0).getBoolean("always"))
        // Without a core setting the always-on entries stand in.
        assertEquals("渡口：每天三班渡轮", WorldPack.fromDraft(draft.copy(setting = ""), true).getOrThrow().getString("setting"))
        assertTrue(WorldPack.fromDraft(draft.copy(people = emptyList()), true).exceptionOrNull()!!.message!!.contains("至少一位居民"))
        assertTrue(WorldPack.fromDraft(draft.copy(people = listOf(person("林姐"), person("林姐"))), true).exceptionOrNull()!!.message!!.contains("林姐"))
    }

    private fun assertEquals(expected: Int, actual: Int, message: String) = org.junit.Assert.assertEquals(message, expected.toLong(), actual.toLong())

    @Test fun buildingAgainMatchesResidentsByName() {
        val current = WorldDetail(summary = WorldSummary(id = "wcfad81922177", name = "海边民宿", residents = listOf(
            WorldResident(id = "r1", name = "林姐", card = "【身份】老板娘", aliases = listOf("老板娘")),
            WorldResident(id = "r2", name = "小满", card = "【身份】女儿"),
            WorldResident(id = "r3", name = "老周", card = "【身份】长住客"))), userName = "阿遥")
        val draft = world.copy(title = "海边民宿（改）", setting = "南方海边小镇", userName = "", people = listOf(
            person("林姐", "【身份】老板娘", listOf("老板娘")), person("小满", "【身份】林姐的女儿，十九岁"), person("阿青", "【身份】新来的厨师")))
        val plan = WorldPack.syncPlan(WorldPack.fromDraft(draft, true).getOrThrow(), current)
        assertEquals("海边民宿（改）", plan.patch.getString("name"))
        assertFalse("a blank name for the user is not sent; the PC needs one", plan.patch.has("userName"))
        assertEquals(listOf("r2"), plan.updates.map { it.first })
        assertEquals("【身份】林姐的女儿，十九岁", plan.updates.single().second.getString("card"))
        assertEquals(listOf("阿青"), plan.adds.map { it.getString("name") })
        assertEquals(listOf("r3"), plan.removes)
        assertFalse(plan.isEmpty)
        // Nothing changed: nothing to do for the residents.
        val same = world.copy(title = "海边民宿", setting = "x", people = current.summary.residents.map { person(it.name, it.card, it.aliases) })
        assertTrue(WorldPack.syncPlan(WorldPack.fromDraft(same, true).getOrThrow(), current).isEmpty)
    }
}
