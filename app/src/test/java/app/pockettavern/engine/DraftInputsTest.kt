package app.pockettavern.engine

import app.pockettavern.applyDraftSkillUpdate
import app.pockettavern.data.DataCodec
import app.pockettavern.data.DraftMedia
import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.CardMaterial
import app.pockettavern.domain.CardPicture
import app.pockettavern.domain.CardSource
import app.pockettavern.domain.Character
import app.pockettavern.domain.SkillRecord
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.9: the user asked to build characters from their own photos, videos and text, and to use
 * card-related Skills while doing it.
 */
class DraftInputsTest {
    @Test fun filesAreToldApartAndAVideoGivesOneFramePerFifteenSeconds() {
        assertEquals(DraftMedia.Kind.PHOTO, DraftMedia.kindOf("image/jpeg", "a.bin"))
        assertEquals(DraftMedia.Kind.PHOTO, DraftMedia.kindOf("application/octet-stream", "IMG_1.HEIC"))
        assertEquals(DraftMedia.Kind.VIDEO, DraftMedia.kindOf("video/mp4", ""))
        assertEquals(DraftMedia.Kind.TEXT, DraftMedia.kindOf("application/octet-stream", "设定.md"))
        assertEquals(DraftMedia.Kind.OTHER, DraftMedia.kindOf("application/pdf", "a.pdf"))
        assertEquals(listOf(5_000L), DraftMedia.frameTimes(10_000))
        assertEquals(listOf(0L, 29_999L), DraftMedia.frameTimes(30_000))
        assertEquals(DraftMedia.MAX_FRAMES, DraftMedia.frameTimes(DraftMedia.MAX_VIDEO_MILLIS).size)
        assertEquals(0L, DraftMedia.frameTimes(DraftMedia.MAX_VIDEO_MILLIS).first())
        assertEquals(DraftMedia.MAX_VIDEO_MILLIS - 1, DraftMedia.frameTimes(DraftMedia.MAX_VIDEO_MILLIS).last())
    }

    @Test fun textFilesAreReadAsUtf8AndBinaryIsNot() {
        val bom = 0xFEFF.toChar()
        assertEquals("黑猫的设定\n第二行", DraftMedia.textOf("${bom}黑猫的设定\r\n第二行\n".toByteArray()))
        assertNull(DraftMedia.textOf(ByteArray(64) { (it % 7).toByte() }))
        assertNull(DraftMedia.textOf(ByteArray(0)))
    }

    @Test fun theUsersPicturesComeFirstAndFoundOnesFillTheRest() {
        val mine = (1..7).map { CardPicture(id = "m$it", source = if (it == 7) CardPicture.FRAME else CardPicture.PHOTO) }
        val found = (1..4).map { CardPicture(id = "f$it") }
        assertEquals((1..6).map { "m$it" }, CharacterResearch.picturesToShow(mine + found).map { it.id })
        assertEquals(listOf("m1", "m2", "f1", "f2", "f3"), CharacterResearch.picturesToShow(mine.take(2) + found).map { it.id })
        assertEquals("without the user's pictures it is as before", listOf("f1", "f2", "f3"), CharacterResearch.picturesToShow(found).map { it.id })
        assertEquals(listOf("m2"), CharacterResearch.picturesToShow(listOf(mine[0].copy(used = false), mine[1])).map { it.id })
        assertTrue(mine[6].mine)
        assertFalse(found[0].mine)
    }

    @Test fun theUsersTextGoesFirstAndThePromptSaysItOutranksTheWeb() {
        val material = CardMaterial(sources = listOf(
            CardSource(title = "百科", url = "https://baike.example/", text = "网上的设定", full = true),
            CardSource(kind = CardSource.USER, title = "我的设定.md", text = "她怕水，左眼下有泪痣", full = true)))
        val request = JSONArray(CharacterResearch.draftMessages("做黑猫", Character(), material, listOf("image/jpeg" to "QQ==", "image/jpeg" to "Qg==", "image/jpeg" to "Qw=="),
            12_000, minePictures = 2, skillTexts = listOf("【Skill: 写卡】按结构写")))
        val system = request.getJSONObject(0).getString("content")
        assertTrue(system.contains(CharacterResearch.USER_RULES))
        assertEquals("the Skill follows the instructions", "【Skill: 写卡】按结构写", request.getJSONObject(1).getString("content"))
        val parts = request.getJSONObject(2).getJSONArray("content")
        val text = parts.getJSONObject(0).getString("text")
        assertTrue(text.contains("〔资料 1 · 用户提供〕我的设定.md\n她怕水") && text.indexOf("我的设定.md") < text.indexOf("百科"))
        assertTrue(text.contains("以下是用户提供和从网上找到的资料"))
        assertTrue(text.contains("前 2 张是用户提供的照片或视频截图，其余是网上找到的"))
        assertEquals(4, parts.length())
        // Only the user's pictures, and no web material at all.
        val ownOnly = JSONArray(CharacterResearch.draftMessages("", Character(), CardMaterial(pictures = listOf(CardPicture(source = CardPicture.PHOTO))),
            listOf("image/jpeg" to "QQ=="), 12_000, minePictures = 1)).getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(ownOnly.contains("另附 1 张用户提供的照片或视频截图"))
        // Without anything from the user the prompt is as before.
        assertFalse(JSONArray(CharacterResearch.draftMessages("x", Character(), CardMaterial(sources = listOf(material.sources[0])), emptyList(), 12_000))
            .getJSONObject(0).getString("content").contains(CharacterResearch.USER_RULES))
    }

    @Test fun skillsReachAOneShotDraftWithTheirFilesAndTheAdultBoundary() {
        val plain = SkillRecord(id = "a", name = "角色卡结构", content = "先写外貌", enabled = true, files = mapOf("SKILL.md" to "x", "references/form.md" to "y"))
        val adult = SkillRecord(id = "b", name = "成人向", content = "规则", enabled = true, adult = true)
        val texts = ContextEngine.draftSkillTexts(listOf(plain, adult)) { skill -> if (skill.id == "a") listOf("references/form.md" to "表格模板") else emptyList() }
        assertTrue(texts[0].contains("成年人"))
        assertTrue(texts[1].contains("只返回规定的 JSON"))
        assertEquals("【Skill: 角色卡结构】\n先写外貌\n\n【Skill 引用文件：references/form.md】\n表格模板", texts[2])
        assertEquals("【Skill: 成人向】\n规则", texts[3])
        assertTrue(ContextEngine.draftSkillTexts(emptyList()).isEmpty())
        val withoutAdult = ContextEngine.draftSkillTexts(listOf(plain))
        assertFalse(withoutAdult.any { it.contains("成人向内容边界") })
        val request = JSONArray(CharacterDrafts.messages("学姐", Character(), withoutAdult))
        assertEquals(listOf("system", "system", "system", "user"), (0 until request.length()).map { request.getJSONObject(it).getString("role") })
    }

    @Test fun theDraftersSkillsAreKeptCheckedForAdultConfirmationAndSwitchedOn() {
        val skills = listOf(SkillRecord(id = "a", name = "A", enabled = false), SkillRecord(id = "b", name = "B", adult = true, enabled = false), SkillRecord(id = "c", name = "C", enabled = true))
        val data = AppData(skills = skills)
        val updated = applyDraftSkillUpdate(data, listOf("c", "a", "b", "missing", "a"))
        assertEquals(listOf("c", "a"), updated.settings.draftSkillIds)
        assertTrue("a newly picked Skill is switched on", updated.skills.first { it.id == "a" }.enabled)
        assertFalse(updated.skills.first { it.id == "b" }.enabled)
        val confirmed = applyDraftSkillUpdate(data.copy(settings = AppSettings(adultSkillsConfirmed = true)), listOf("b"))
        assertEquals(listOf("b"), confirmed.settings.draftSkillIds)
        assertEquals("kept across a backup", listOf("c", "a"), DataCodec.decode(DataCodec.encode(updated)).settings.draftSkillIds)
    }
}
