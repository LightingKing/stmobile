package app.pockettavern.engine

import app.pockettavern.applyScenarioSkillUpdate
import app.pockettavern.data.DataCodec
import app.pockettavern.domain.AppData
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.ScenarioDraft
import app.pockettavern.domain.ScenarioDraftEntry
import app.pockettavern.domain.SkillRecord
import app.pockettavern.domain.UiSession
import app.pockettavern.domain.WorldEntry
import app.pockettavern.removeCharacter
import app.pockettavern.ui.ScenarioPanelData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.0: the user asked for a scenario builder that is its own chat, where worldview and several
 * characters are worked out with the assistant before the real chat starts.
 */
class ScenarioBuilderTest {
    private val direct = ProviderConfig(id = "v", kind = "openai", baseUrl = "https://api.example.com/v1",
        models = listOf(ModelInfo(id = "tools", type = "text", supportsTools = true), ModelInfo(id = "plain", type = "text")))

    private fun request(chat: Chat): Pair<List<String>, String> {
        val built = ContextEngine.build(AppData(providers = listOf(direct), chats = listOf(chat)), chat)
        val array = JSONArray(built.toolDefinitions)
        return (0 until array.length()).map { array.getJSONObject(it).getJSONObject("function").getString("name") } to built.messagesJson
    }

    private fun save(draft: ScenarioDraft, json: String) = ScenarioBuilder.applySave(draft, JSONObject(json), now = 10)

    private val ready = ScenarioDraft(title = "雾港疑案", summary = "民国上海的连环失踪案。", userRole = "刚到任的探长", setting = "1931 年的上海，租界与华界交错。",
        opening = "雨下了一整夜。", rules = "只写 NPC 和环境，不替用户行动。",
        world = listOf(ScenarioDraftEntry("百乐门", "租界最大的舞厅。", listOf("百乐门", "舞厅")), ScenarioDraftEntry("巡捕房", "法租界巡捕房。", always = true)),
        people = listOf(ScenarioDraftEntry("沈曼", "舞女，二十五岁，失踪者的室友。")), updatedAt = 5)

    @Test fun theBuilderChatGetsItsToolsAndSeesItsDraftEvenWithoutThePc() {
        val chat = Chat(id = "b", providerId = "v", modelId = "tools", task = ScenarioBuilder.TASK, messages = listOf(Message(role = "user", content = "想玩个悬疑剧本")))
        val (names, messages) = request(chat)
        assertTrue(names.containsAll(listOf(ScenarioBuilder.SAVE, ScenarioBuilder.READ, ScenarioBuilder.FINISH)))
        assertTrue(messages.contains("【剧本助手】") && messages.contains("【当前剧本草稿】还是空的。"))
        assertTrue("two lines stay", ScenarioBuilder.PROMPT.contains("必须明确是成年人") && ScenarioBuilder.PROMPT.contains("不写性内容"))
        assertTrue("building waits for the user", ScenarioBuilder.PROMPT.contains("用户明确说可以生成后"))
        val (_, withDraft) = request(chat.copy(scenarioDraft = ready))
        assertTrue(withDraft.contains("剧本名：雾港疑案") && withDraft.contains("- 巡捕房〔常驻〕") && withDraft.contains("还没有生成剧本"))
        val (noNames, noTools) = request(chat.copy(modelId = "plain"))
        assertTrue(noNames.none { it.startsWith("scenario_") })
        assertTrue(noTools.contains("当前模型不能调用工具"))
        val (ordinary, plain) = request(Chat(id = "o", providerId = "v", modelId = "tools", messages = listOf(Message(role = "user", content = "你好"))))
        assertTrue(ordinary.none { it.startsWith("scenario_") })
        assertFalse(plain.contains("剧本草稿"))
    }

    @Test fun theDraftIsWrittenAPartAtATimeAndEntriesAreReplacedByName() {
        val (first, said) = save(ScenarioDraft(), """{"title":"雾港疑案","summary":"民国悬疑"}""").getOrThrow()
        assertEquals("剧本名、简介", said)
        assertEquals(10L, first.updatedAt)
        val (second, _) = save(first, """{"world":[{"name":"百乐门","content":"舞厅","keywords":["百乐门"," ","百乐门"]},{"name":"巡捕房","content":"法租界","always":true}]}""").getOrThrow()
        assertEquals("雾港疑案", second.title)
        assertEquals(listOf("百乐门"), second.world[0].keywords)
        val (third, said3) = save(second, """{"world":[{"name":"百乐门","content":"租界最大的舞厅"}],"people":[{"name":"沈曼","content":"舞女"}]}""").getOrThrow()
        assertEquals(2, third.world.size)
        assertEquals("租界最大的舞厅", third.world.first { it.name == "百乐门" }.content)
        assertEquals("1 条世界观；1 个人物", said3)
        val (fourth, said4) = save(third, """{"remove":[{"section":"world","name":"巡捕房"},"沈曼",{"name":"不存在"}]}""").getOrThrow()
        assertEquals(listOf("百乐门"), fourth.world.map { it.name })
        assertTrue(fourth.people.isEmpty())
        assertEquals("删掉 2 条", said4)
        // All or nothing, with a reason the model can act on.
        assertEquals("条目“沈曼”没有内容", save(third, """{"title":"新名","people":[{"name":"沈曼","content":" "}]}""").exceptionOrNull()?.message)
        assertTrue(save(third, """{"opening":"${"雨".repeat(6001)}"}""").exceptionOrNull()?.message.orEmpty().contains("开场 超过 6000 字"))
        assertEquals("没有要保存的内容", save(third, "{}").exceptionOrNull()?.message)
        val many = JSONArray((1..31).map { JSONObject().put("name", "人$it").put("content", "x") }).toString()
        assertEquals("人物最多 30 个", save(ScenarioDraft(), """{"people":$many}""").exceptionOrNull()?.message)
    }

    @Test fun readingGivesFullTextAndViewingShowsFirstLines() {
        val long = ready.copy(world = ready.world + ScenarioDraftEntry("长条目", "长".repeat(300)))
        val read = JSONObject(ScenarioBuilder.read(long, JSONObject("""{"names":["长条目","沈曼","谁"]}""")))
        val entries = read.getJSONArray("entries")
        assertEquals(300, entries.getJSONObject(0).getString("content").length)
        assertEquals("people", entries.getJSONObject(1).getString("section"))
        assertEquals("谁", read.getJSONArray("missing").getString(0))
        assertEquals("雨下了一整夜。", JSONObject(ScenarioBuilder.read(long, JSONObject())).getString("opening"))
        val view = ScenarioBuilder.view(long)
        assertTrue(view.contains("- 长条目：" + "长".repeat(120) + "…（共 300 字）"))
        assertTrue(view.contains("- 百乐门（关键词：百乐门、舞厅）：租界最大的舞厅。"))
        assertTrue(ScenarioBuilder.view(long.copy(scenarioId = "user-scenario-1", builtAt = 4)).contains("之后的改动还没有更新进去"))
    }

    @Test fun finishingMakesAScenarioAndFinishingAgainUpdatesTheSameOne() {
        val chat = Chat(id = "b", task = ScenarioBuilder.TASK, scenarioDraft = ready)
        val data = AppData(chats = listOf(chat))
        val (built, card) = ScenarioBuilder.finish(data, "b", now = 100).getOrThrow()
        assertTrue(card.id.startsWith(ScenarioBuilder.USER_PREFIX) && ScenarioBuilder.isScenario(card))
        assertEquals(listOf("雾港疑案", "民国上海的连环失踪案。", "1931 年的上海，租界与华界交错。", "雨下了一整夜。", "只写 NPC 和环境，不替用户行动。", "刚到任的探长", "roleplay"),
            listOf(card.name, card.description, card.scenario, card.firstMessage, card.systemPrompt, card.userPersona, card.defaultPresetId))
        val entries = built.worldEntries.filter { it.characterId == card.id }
        assertEquals(3, entries.size)
        val person = entries.first { it.name == "沈曼" }
        assertTrue("the roster reads people by this heading", person.content.startsWith("【角色：沈曼】\n"))
        assertEquals(listOf("沈曼"), person.keywords)
        assertTrue(entries.first { it.name == "巡捕房" }.always)
        assertEquals(card.id, built.chats.single().scenarioDraft?.scenarioId)
        assertEquals(listOf("沈曼"), ScenarioPanelData.people(ScenarioPanelData.entries(Chat(characterId = card.id), UiSession(data = built))).map { it.name })

        // The user edits the card and adds an entry by hand, then changes the draft and builds again.
        val edited = built.copy(characters = built.characters.map { it.copy(avatar = "file:avatars/x.png") },
            worldEntries = built.worldEntries + WorldEntry(id = "manual", name = "手写", content = "用户加的", characterId = card.id))
        val changed = edited.copy(chats = edited.chats.map { c -> c.copy(scenarioDraft = c.scenarioDraft!!.copy(title = "雾港疑案（修订）",
            world = c.scenarioDraft.world.drop(1), people = c.scenarioDraft.people + ScenarioDraftEntry("顾言", "【角色：顾言】报社记者"), updatedAt = 200)) })
        assertTrue(changed.chats.single().scenarioDraft!!.changedSinceBuilt)
        val (again, card2) = ScenarioBuilder.finish(changed, "b", now = 300).getOrThrow()
        assertEquals(card.id, card2.id)
        assertEquals(1, again.characters.size)
        assertEquals("雾港疑案（修订）", card2.name)
        assertEquals("file:avatars/x.png", card2.avatar)
        assertEquals(setOf("巡捕房", "沈曼", "顾言", "手写"), again.worldEntries.filter { it.characterId == card.id }.map { it.name }.toSet())
        assertEquals("【角色：顾言】报社记者", again.worldEntries.first { it.name == "顾言" }.content)
        assertFalse(again.chats.single().scenarioDraft!!.changedSinceBuilt)

        assertEquals("还缺：剧本名、开场", ScenarioBuilder.finish(AppData(chats = listOf(chat.copy(scenarioDraft = ready.copy(title = "", opening = "")))), "b").exceptionOrNull()?.message)
        assertEquals("只有剧本助手会话可以生成剧本", ScenarioBuilder.finish(AppData(chats = listOf(chat.copy(task = ""))), "b").exceptionOrNull()?.message)
        assertEquals("剧本草稿还是空的", ScenarioBuilder.finish(AppData(chats = listOf(chat.copy(scenarioDraft = null))), "b").exceptionOrNull()?.message)
    }

    @Test fun builtInAndUserScenariosAreScenariosAndDeletingOneTakesItsWorldEntries() {
        assertTrue(ScenarioBuilder.isScenarioId("builtin-scenario-frost-manor"))
        assertTrue(ScenarioBuilder.isScenarioId("user-scenario-1"))
        assertFalse(ScenarioBuilder.isScenarioId("builtin-character-dafeiyu"))
        val data = AppData(characters = listOf(Character(id = "user-scenario-1"), Character(id = "c2")),
            worldEntries = listOf(WorldEntry(id = "a", characterId = "user-scenario-1"), WorldEntry(id = "b", characterId = "c2"), WorldEntry(id = "g")))
        val left = removeCharacter(data, "user-scenario-1")
        assertEquals(listOf("c2"), left.characters.map { it.id })
        assertEquals(listOf("b", "g"), left.worldEntries.map { it.id })
    }

    @Test fun theRosterOnlyLooksAtItsOwnScenarioOnceItHasEntries() {
        val people = listOf(
            WorldEntry(name = "沈曼", content = "【角色：沈曼】舞女", characterId = "user-scenario-a"),
            WorldEntry(name = "谷若尘", content = "【角色：谷若尘】主角", characterId = "builtin-scenario-frost"),
            WorldEntry(name = "百乐门", content = "舞厅", characterId = "user-scenario-b"))
        val session = UiSession(data = AppData(worldEntries = people))
        assertEquals(listOf("沈曼"), ScenarioPanelData.people(ScenarioPanelData.entries(Chat(characterId = "user-scenario-a"), session)).map { it.name })
        assertTrue("its own entries have no people yet: none borrowed", ScenarioPanelData.people(ScenarioPanelData.entries(Chat(characterId = "user-scenario-b"), session)).isEmpty())
    }

    @Test fun theBuilderChatItsDraftAndItsSkillsSurviveABackup() {
        val skills = listOf(SkillRecord(id = "w", name = "世界观", enabled = false), SkillRecord(id = "x", name = "成人", adult = true))
        val data = applyScenarioSkillUpdate(AppData(skills = skills, presets = listOf(Preset(id = "roleplay")),
            chats = listOf(Chat(id = "b", task = ScenarioBuilder.TASK, scenarioDraft = ready.copy(scenarioId = "user-scenario-1", builtAt = 9)), Chat(id = "o", task = "something_else"))),
            listOf("w", "x"))
        assertEquals(listOf("w"), data.settings.scenarioSkillIds)
        assertTrue(data.skills.first { it.id == "w" }.enabled)
        val back = DataCodec.decode(DataCodec.encode(data))
        assertEquals(ScenarioBuilder.TASK, back.chats.first { it.id == "b" }.task)
        assertEquals(ready.copy(scenarioId = "user-scenario-1", builtAt = 9), back.chats.first { it.id == "b" }.scenarioDraft)
        assertEquals("", back.chats.first { it.id == "o" }.task)
        assertEquals(null, back.chats.first { it.id == "o" }.scenarioDraft)
        assertEquals(listOf("w"), back.settings.scenarioSkillIds)
    }
}
