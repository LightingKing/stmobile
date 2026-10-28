package app.pockettavern.engine

import app.pockettavern.data.DataCodec
import app.pockettavern.domain.AppData
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.Message
import app.pockettavern.domain.SkillRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionPolicyTest {
    private val adult = SkillRecord(id = "adult", name = "成人正文", adult = true, content = "ADULT_SKILL_BODY")
    private val general = SkillRecord(id = "voice", name = "角色声线", content = "VOICE_SKILL_BODY")

    @Test fun writingGuidanceIsUnifiedAndHasNoToneSelector() {
        val data = AppData(skills = listOf(general), characters = listOf(Character(id = "c", name = "角色")))
        val build = ContextEngine.build(data, Chat(characterId = "c", skillIds = listOf(general.id), messages = listOf(Message(content = "继续"))))
        assertTrue(build.messagesJson.contains("表达强度跟随用户本轮的明确要求"))
        assertTrue(build.messagesJson.contains("文字是读者唯一的画面来源"))
        assertFalse(build.messagesJson.contains("表达档位："))
    }

    @Test fun adultSkillGateDependsOnGlobalConfirmationOnly() {
        val unconfirmed = AppData(skills = listOf(adult), characters = listOf(Character(id = "c", name = "角色")))
        val blocked = ContextEngine.build(unconfirmed, Chat(characterId = "c", skillIds = listOf(adult.id), messages = listOf(Message(content = "继续"))))
        assertFalse(blocked.messagesJson.contains("ADULT_SKILL_BODY"))
        val confirmed = unconfirmed.copy(settings = app.pockettavern.domain.AppSettings(adultSkillsConfirmed = true))
        val allowed = ContextEngine.build(confirmed, Chat(characterId = "c", skillIds = listOf(adult.id), messages = listOf(Message(content = "继续"))))
        assertTrue(allowed.messagesJson.contains("ADULT_SKILL_BODY"))
        assertFalse(allowed.messagesJson.contains("仅在成年确认"))
    }

    @Test fun characterCardSupplementAndPhysiologyRulesAreSent() {
        val card = """{"data":{"creator_notes":"只在熟人面前说方言","tags":["嘴硬","慢热"],"alternate_greetings":["你又来了？"]}}"""
        val data = AppData(characters = listOf(Character(id = "c", name = "角色", originalJson = card)))
        val prompt = ContextEngine.build(data, Chat(characterId = "c", messages = listOf(Message(content = "继续")))).messagesJson
        assertTrue(prompt.contains("角色卡补充字段"))
        assertTrue(prompt.contains("只在熟人面前说方言"))
        assertTrue(prompt.contains("生理反应只在角色当前状态"))
        assertTrue(prompt.contains("角色卡是人物身份"))
    }
    @Test fun legacyExpressionSelectionIsIgnoredAndNotWrittenToNewBackups() {
        val legacy = AppData(chats = listOf(Chat(expressionMode = "explicit")))
        val encoded = DataCodec.encode(legacy)
        assertFalse(encoded.contains("expressionMode"))
        assertTrue(DataCodec.decode(encoded).chats.single().expressionMode == "request")
    }
}
