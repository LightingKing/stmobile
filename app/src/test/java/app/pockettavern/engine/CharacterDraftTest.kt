package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.2.4: the user found making characters too much work; a sentence or two now becomes a full card. */
class CharacterDraftTest {
    @Test fun theRequestCarriesTheIdeaTheFieldsAndTheRules() {
        val messages = JSONArray(CharacterDrafts.messages("毒舌的学姐，嘴上嫌弃其实很照顾人", Character()))
        val system = messages.getJSONObject(0).getString("content")
        val user = messages.getJSONObject(1).getString("content")
        assertTrue(user.contains("毒舌的学姐"))
        assertFalse("a new card has nothing to keep", user.contains("已有的角色卡"))
        for (key in listOf("name", "description", "personality", "scenario", "first_message", "examples")) assertTrue(key, system.contains("- $key："))
        assertTrue("no cards of real people", system.contains("不给现实中可识别的真人"))
        assertTrue("romance needs adults", system.contains("必须明确是成年人"))
        assertTrue(system.contains("只返回一个 JSON 对象"))
    }

    @Test fun editingSendsTheCardSoFar() {
        val user = JSONArray(CharacterDrafts.messages("再傲娇一点", Character(name = "黑猫", personality = "毒舌"))).getJSONObject(1).getString("content")
        assertTrue(user.contains("已有的角色卡") && user.contains("\"name\":\"黑猫\"") && user.contains("再傲娇一点"))
        assertFalse("empty fields are not sent", user.contains("\"examples\""))
        assertTrue(CharacterDrafts.hasContent(Character(name = "黑猫")))
        assertFalse(CharacterDrafts.hasContent(Character()))
    }

    @Test fun aReplyWithFencesOrChatterStillGivesTheCard() {
        val reply = "好的，下面是角色卡：\n```json\n{\"name\":\"白川凛\",\"description\":\"大三学姐\",\"personality\":\"毒舌\",\"scenario\":\"社团活动室\",\"first_message\":\"又迟到了？\",\"examples\":\"<START>\\n{{user}}: 早\\n{{char}}: 哼。\"}\n```\n希望你喜欢。"
        val card = CharacterDrafts.parse(reply).getOrThrow()
        assertEquals("白川凛", card.name)
        assertEquals("又迟到了？", card.firstMessage)
        assertTrue(card.examples.startsWith("<START>"))
        assertTrue(CharacterDrafts.parse("{\"name\":\"只有名字\"}").isFailure)
        assertTrue(CharacterDrafts.parse("我不能写这个").isFailure)
        assertEquals(60, CharacterDrafts.parse("{\"name\":\"${"长".repeat(100)}\",\"description\":\"x\"}").getOrThrow().name.length)
    }

    @Test fun aDraftFillsTheFormButKeepsWhatItDidNotWrite() {
        val form = Character(id = "keep", name = "旧名", avatar = "file:avatars/a.png", stickerPacks = listOf("黑猫"), systemPrompt = "保留", examples = "旧示例")
        val merged = CharacterDrafts.merge(form, Character(name = "新名", description = "新简介"))
        assertEquals("keep", merged.id)
        assertEquals("新名", merged.name)
        assertEquals("新简介", merged.description)
        assertEquals("旧示例", merged.examples)
        assertEquals("file:avatars/a.png", merged.avatar)
        assertEquals(listOf("黑猫"), merged.stickerPacks)
        assertEquals("保留", merged.systemPrompt)
    }

    @Test fun theOpenChatsModelIsUsedWhenItCanBe() {
        val venice = ProviderConfig(id = "v", models = listOf(ModelInfo(id = "big", type = "text"), ModelInfo(id = "img", type = "image")))
        val pc = ProviderConfig(id = "pc", models = listOf(ModelInfo(id = "pocket", type = "text")))
        val data = AppData(providers = listOf(venice, pc), chats = listOf(Chat(id = "c", providerId = "pc", modelId = "pocket")))
        assertEquals("pc" to "pocket", CharacterDrafts.defaultModel(data, "c", setOf("v", "pc")))
        assertEquals("without a key the open chat's model is skipped", "v" to "big", CharacterDrafts.defaultModel(data, "c", setOf("v")))
        assertNull(CharacterDrafts.defaultModel(data, "c", emptySet()))
    }
}
