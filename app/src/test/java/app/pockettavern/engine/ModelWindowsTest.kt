package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.SkillRecord
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.4: a relay's "k3.1-pro" listed without a window was planned as 32,768 tokens, less than the
 * 剧本助手 chat's Skills alone, so nothing could be sent.
 */
class ModelWindowsTest {
    // How the catalog parser stores a model: the parsed number plus the entry as it came.
    private fun relayModel(id: String) = ModelInfo(id = id, type = "text", contextTokens = 32_768, supportsTools = true, rawJson = """{"id":"$id","object":"model"}""")
    private fun veniceModel(id: String, window: Int) = ModelInfo(id = id, type = "text", contextTokens = window, supportsTools = true,
        rawJson = """{"id":"$id","model_spec":{"availableContextTokens":$window}}""")

    private val relay = ProviderConfig(id = "relay", name = "中转", models = listOf(relayModel("k3.1-pro"), relayModel("mystery-7b")))
    private val pc = ProviderConfig(id = "pc", name = "电脑", kind = "writing-server", models = listOf(
        veniceModel("kimi-k3-1-pro", 1_000_000), veniceModel("kimi-k3-1-pro-highspeed", 1_000_000), veniceModel("e2ee-kimi-k2-6", 262_144)))

    @Test fun aWindowTheEntryReportsIsTaken() {
        val model = veniceModel("kimi-k3-1-pro", 1_000_000)
        assertTrue(ModelWindows.reported(model))
        assertFalse(ModelWindows.reported(relayModel("k3.1-pro")))
        assertEquals(1_000_000, ModelWindows.of(model, emptyList()))
    }

    @Test fun anUnreportedModelTakesTheWindowOfTheSameModelOnAnotherPlatform() {
        val model = relay.models.first()
        assertEquals("k31pro", ModelWindows.normalized("k3.1-pro"))
        assertEquals("kimik26", ModelWindows.normalized("e2ee-kimi-k2-6"))
        assertEquals(1_000_000, ModelWindows.of(model, listOf(relay, pc)))
        // Nothing alike elsewhere: the old fallback stays.
        assertNull(ModelWindows.known(relay.models[1], listOf(relay, pc)))
        assertEquals(32_768, ModelWindows.of(relay.models[1], listOf(relay, pc)))
    }

    @Test fun shortOrVersionlessNamesDoNotMatchByAccident() {
        val pro = ProviderConfig(id = "a", models = listOf(relayModel("pro")))
        val known = ProviderConfig(id = "b", models = listOf(veniceModel("gemini-pro", 2_000_000), veniceModel("qwen-max", 128_000)))
        assertNull(ModelWindows.known(pro.models.single(), listOf(pro, known)))
        val max = ProviderConfig(id = "c", models = listOf(relayModel("max")))
        assertNull(ModelWindows.known(max.models.single(), listOf(max, known)))
    }

    @Test fun theSettingCapsAKnownWindowAndStandsInForAMissingOne() {
        val providers = listOf(relay, pc)
        assertEquals(1_000_000, ModelWindows.effective(relay.models[0], providers, 0))
        assertEquals(200_000, ModelWindows.effective(relay.models[0], providers, 200_000))
        // Before 0.3.4 the setting could only lower a window, never raise the 32,768 guess.
        assertEquals(131_072, ModelWindows.effective(relay.models[1], providers, 131_072))
        assertEquals(32_768, ModelWindows.effective(relay.models[1], providers, 0))
    }

    private fun skill(id: String, chars: Int) = SkillRecord(id = id, name = id, content = "写".repeat(chars))

    // The phone in the report had the daily input budget set to unlimited (0).
    private fun data(model: ModelInfo, providers: List<ProviderConfig>, skills: List<SkillRecord>, override: Int = 0, spending: Int = 0): Pair<AppData, Chat> {
        val chat = Chat(id = "c", providerId = providers.first().id, modelId = model.id, presetId = "preset", skillIds = skills.map { it.id },
            messages = listOf(Message(role = "user", content = "开始写剧本")))
        return AppData(providers = providers, chats = listOf(chat), skills = skills,
            presets = listOf(Preset(id = "preset", systemPrompt = "【预设】")), settings = AppSettings(contextOverride = override, maxContextInputTokens = spending)) to chat
    }

    @Test fun aWindowFoundOnAnotherPlatformKeepsEverySkill() {
        val skills = listOf(skill("a", 20_000), skill("b", 12_000), skill("c", 9_000))
        val (data, chat) = data(relay.models[0], listOf(relay, pc), skills)
        val built = ContextEngine.build(data, chat)
        assertEquals(emptyList<String>(), built.skippedSkills)
        assertTrue(built.estimatedTokens <= built.budget)
        assertEquals(1_000_000, built.budgetBreakdown?.modelWindow)
    }

    @Test fun aSmallWindowTakesTheSkillsThatFitInOrderAndSaysWhichWereLeftOut() {
        val small = relayModel("mystery-7b")
        val provider = ProviderConfig(id = "relay", models = listOf(small))
        val skills = listOf(skill("first", 6_000), skill("large", 14_000), skill("small", 2_000))
        val (data, chat) = data(small, listOf(provider), skills)
        val built = ContextEngine.build(data, chat)
        assertEquals(listOf("large"), built.skippedSkills)
        val sent = JSONArray(built.messagesJson).let { list -> (0 until list.length()).map { list.getJSONObject(it).optString("content") } }
        assertTrue(sent.any { it.startsWith("【Skill: first】") })
        assertTrue(sent.any { it.startsWith("【Skill: small】") })
        assertFalse(sent.any { it.startsWith("【Skill: large】") })
        assertTrue(built.estimatedTokens <= built.budget)
        assertTrue(built.preview.contains("模型容量约 32,768 tokens，放不下全部 Skills：本轮没带上 large"))
    }

    @Test fun aDailyInputBudgetBelowTheModelIsNamedAsTheLimit() {
        // About 30k + 24k + 9k tokens against 65,536, a quarter of which stays for the conversation.
        val skills = listOf(skill("a", 20_000), skill("b", 16_000), skill("c", 6_000))
        val (data, chat) = data(relay.models[0], listOf(relay, pc), skills, spending = 65_536)
        val built = ContextEngine.build(data, chat)
        assertEquals(listOf("b"), built.skippedSkills)
        assertTrue(built.estimatedTokens <= built.budget)
        assertTrue(built.preview.contains("日常输入预算约 65,536 tokens，放不下全部 Skills"))
    }

    @Test fun skillsThatLeaveRoomForTheConversationAreAllSentEvenUnderADailyBudget() {
        // About 36k of Skills against 65,536: compressing history can still make room, so nothing is dropped.
        val skills = listOf(skill("a", 16_000), skill("b", 8_000))
        val (data, chat) = data(relay.models[0], listOf(relay, pc), skills, spending = 65_536)
        assertEquals(emptyList<String>(), ContextEngine.build(data, chat).skippedSkills)
    }

    @Test fun fittingKeepsEverythingWhenTheSkillsFitTogether() {
        val skills = listOf(skill("a", 100), skill("b", 100))
        val (kept, left) = ContextEngine.fitSkills(skills, toolsSupported = false, cap = 10_000)
        assertEquals(listOf("a", "b"), kept.map { it.id })
        assertTrue(left.isEmpty())
        val (some, rest) = ContextEngine.fitSkills(skills, toolsSupported = false, cap = ContextEngine.skillPromptTokens(skills[0], false))
        assertEquals(listOf("a"), some.map { it.id })
        assertEquals(listOf("b"), rest.map { it.id })
    }
}
