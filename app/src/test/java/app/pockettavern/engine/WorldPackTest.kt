package app.pockettavern.engine

import app.pockettavern.domain.Character
import app.pockettavern.domain.WorldEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.0: 做成小世界 turns a scenario into the world JSON the PC accepts. */
class WorldPackTest {
    private val scenario = Character(id = "user-scenario-1", name = "雾港旧事", description = "一座终年起雾的港口小城。", scenario = "民国港口，三大商会明争暗斗。",
        firstMessage = "汽笛声穿过浓雾。", systemPrompt = "人物说话符合身份。", userPersona = "用户扮演沈默，刚回港的报社记者。")
    private val entries = listOf(
        WorldEntry(id = "w1", name = "雾港", content = "港口城市，常年起雾。", keywords = listOf("雾港", "港口"), always = true, characterId = scenario.id, priority = 400),
        WorldEntry(id = "p1", name = "林曼", content = "【角色：林曼】\n商会千金，二十四岁，表面温婉。", keywords = listOf("林曼", "曼小姐"), characterId = scenario.id, priority = 350),
        WorldEntry(id = "p2", name = "顾长风", content = "【角色：顾长风】\n巡捕房探长，三十岁。", keywords = listOf("顾长风", "探长"), characterId = scenario.id, priority = 350),
        WorldEntry(id = "x", name = "别的剧本", content = "【角色：外人】不属于这个剧本", characterId = "other"),
        WorldEntry(id = "off", name = "停用", content = "【角色：停用】", characterId = scenario.id, enabled = false),
    )

    @Test fun peopleComeFirstAndAreSuggested() {
        val candidates = WorldPack.candidates(scenario, entries)
        assertEquals(listOf("p1", "p2", "w1"), candidates.map { it.id })
        assertEquals(setOf("p1", "p2"), WorldPack.suggested(candidates))
        assertEquals("林曼", WorldPack.personName(candidates.first()))
        assertEquals("沈默", WorldPack.guessUserName(scenario.userPersona))
        // More people than a world holds: nobody is ticked for the user.
        val crowd = (1..9).map { WorldEntry(id = "c$it", name = "人$it", content = "【角色：人$it】设定", characterId = scenario.id) }
        assertEquals(emptySet<String>(), WorldPack.suggested(WorldPack.candidates(scenario, crowd)))
        assertEquals("", WorldPack.guessUserName("一个普通人"))
    }

    @Test fun theChosenEntriesBecomeResidentsAndTheRestStaysWorldBook() {
        val pack = WorldPack.build(scenario, entries, listOf("p2", "p1"), "沈默", startPaused = true).getOrThrow()
        assertEquals("雾港旧事", pack.getString("name"))
        assertEquals("民国港口，三大商会明争暗斗。", pack.getString("setting"))
        assertEquals("人物说话符合身份。", pack.getString("rules"))
        assertEquals("用户扮演沈默，刚回港的报社记者。", pack.getString("userRole"))
        assertEquals("沈默", pack.getString("userName"))
        assertEquals("汽笛声穿过浓雾。", pack.getString("opening"))
        assertTrue(pack.getBoolean("startPaused"))
        val residents = pack.getJSONArray("residents")
        assertEquals(2, residents.length())
        val first = residents.getJSONObject(0)
        assertEquals("顾长风", first.getString("name"))
        assertEquals("巡捕房探长，三十岁。", first.getString("card"))
        assertEquals("探长", first.getJSONArray("aliases").getString(0))
        val book = pack.getJSONArray("worldBook")
        assertEquals(1, book.length())
        assertEquals("雾港", book.getJSONObject(0).getString("name"))
        assertTrue(book.getJSONObject(0).getBoolean("always"))
    }

    @Test fun whatThePcWouldRefuseIsCaughtOnThePhone() {
        assertTrue(WorldPack.build(scenario, entries, emptyList(), "", true).exceptionOrNull()!!.message!!.contains("至少选一位"))
        val many = (1..9).map { WorldEntry(id = "m$it", name = "人$it", content = "【角色：人$it】设定", characterId = scenario.id) }
        assertTrue(WorldPack.build(scenario, many, many.map { it.id }, "", true).exceptionOrNull()!!.message!!.contains("最多 8 位"))
        val twins = listOf(WorldEntry(id = "a", name = "甲", content = "【角色：阿青】一", characterId = scenario.id), WorldEntry(id = "b", name = "乙", content = "【角色：阿青】二", characterId = scenario.id))
        assertTrue(WorldPack.build(scenario, twins, listOf("a", "b"), "", true).exceptionOrNull()!!.message!!.contains("阿青"))
        // Another scenario's or a disabled entry cannot be picked.
        assertTrue(WorldPack.build(scenario, entries, listOf("x", "off"), "", true).isFailure)
    }

    @Test fun aKeywordAnotherResidentUsesIsNotAnAlias() {
        val shared = listOf(
            WorldEntry(id = "a", name = "阿青", content = "【角色：阿青】一", keywords = listOf("阿青", "姐妹"), characterId = scenario.id),
            WorldEntry(id = "b", name = "阿蓝", content = "【角色：阿蓝】二", keywords = listOf("阿蓝", "姐妹", "阿青"), characterId = scenario.id))
        val residents = WorldPack.build(scenario, shared, listOf("a", "b"), "", false).getOrThrow().getJSONArray("residents")
        assertEquals(listOf("姐妹"), (0 until residents.getJSONObject(0).getJSONArray("aliases").length()).map { residents.getJSONObject(0).getJSONArray("aliases").getString(it) })
        assertEquals(0, residents.getJSONObject(1).getJSONArray("aliases").length())
        assertFalse(WorldPack.build(scenario, shared, listOf("a", "b"), "", false).getOrThrow().getBoolean("startPaused"))
    }
}
