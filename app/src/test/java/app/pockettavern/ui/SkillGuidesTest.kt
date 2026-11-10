package app.pockettavern.ui

import app.pockettavern.data.BuiltinSkills
import app.pockettavern.domain.GitHubStars
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillGuidesTest {
    private val shipped = BuiltinSkills.loadAll { File("src/main/assets", it).readText() }

    @Test fun everyBundledSkillHasGuideAndNoStrayGuides() {
        assertEquals(27, shipped.size)
        shipped.forEach { assertNotNull("${it.id} 缺少效果说明", skillGuide(it.id)) }
        assertEquals(shipped.map { it.id }.toSet(), SkillGuides.ids)
    }

    @Test fun guidesFitPhoneDetailScreen() {
        shipped.forEach { skill ->
            val guide = skillGuide(skill.id)!!
            assertTrue(skill.id, guide.effect.isNotBlank() && guide.effect.length <= 90)
            assertTrue(skill.id, guide.note.length <= 110)
            guide.example?.let { (before, after) -> assertTrue(skill.id, before.length <= 60 && after.length <= 60) }
        }
    }

    @Test fun adultGuidesRepeatExistingConfirmationAndReportFormatsSayOnRequest() {
        shipped.filter { it.adult }.forEach { assertTrue(it.id, skillGuide(it.id)!!.note.contains("年满 18 岁")) }
        listOf("tm-deslop", "tm-dialogue", "tm-continuity").forEach {
            val guide = skillGuide("builtin-writing-$it")!!
            assertNotNull(it, guide.example)
            assertTrue(it, guide.note.contains("要求"))
        }
    }

    @Test fun userInstalledSkillsHaveNoGuide() {
        assertNull(skillGuide("custom-skill"))
        assertNull(skillGuide("tm-deslop"))
    }

    @Test fun everyGithubSkillHasChineseSummaryDetailsAndRepositoryStars() {
        val github = BuiltinSkills.load { File("src/main/assets", it).readText() }
        val stars = BuiltinSkills.loadStars { File("src/main/assets", it).readText() }
        val chinese = Regex("[\\u4e00-\\u9fff]")
        github.forEach { skill ->
            assertTrue("${skill.id} 缺少中文简介", chinese.containsMatchIn(skill.description))
            assertTrue("${skill.id} 缺少中文详情", chinese.containsMatchIn(skillGuide(skill.id)!!.effect))
            val repository = stars[skill.id]
            assertNotNull("${skill.id} 缺少仓库星数记录", repository)
            assertTrue("${skill.id} 星数未记录来源", repository!!.repo.isNotBlank())
            assertTrue("${skill.id} 星数未记录日期", repository.checkedAt.isNotBlank())
        }
    }

    @Test fun starLabelsDistinguishZeroUnknownAndSnapshotDate() {
        val zero = GitHubStars("owner/repo", 0, "2026-10-22")
        assertEquals("GitHub 仓库 ★ 0", starsCardLine(zero))
        val details = starsDetailLine(zero)
        assertTrue(details, details.contains("仓库星数：0"))
        assertTrue(details, details.contains("owner/repo"))
        assertTrue(details, details.contains("2026-10-22 记录"))
        assertTrue(details, details.contains("非实时"))
        val unknown = zero.copy(stars = null)
        assertEquals("GitHub 仓库星数未知", starsCardLine(unknown))
        assertTrue(starsDetailLine(unknown).contains("2026-10-22 记录"))
        assertTrue(starsDetailLine(unknown).contains("仓库星数：未知"))
        assertTrue(starsDetailLine(unknown.copy(checkedAt = "")).contains("记录日期未知"))
    }
}
