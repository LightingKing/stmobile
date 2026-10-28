package app.pockettavern.ui

import app.pockettavern.domain.SkillRecord
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SkillChoiceGuideTest {
    // Catalog metadata only; upstream prompt and reference bodies are never loaded.
    private fun bundledMetadata(): List<SkillRecord> = listOf("writing-skills", "first-party-skills").flatMap { folder ->
        val entries = JSONObject(File("src/main/assets/$folder/catalog.json").readText()).getJSONArray("skills")
        (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            SkillRecord(
                id = "builtin-writing-${item.getString("id")}",
                name = item.getString("name"), description = item.getString("description"),
                source = "$folder · ${item.getString("category")}",
                sourceUrl = item.optString("sourceUrl"), enabled = false,
                adult = item.optBoolean("adult", false)
            )
        }
    }

    @Test fun eachScenarioHasThreeInstalledCandidatesInStableRoleOrder() {
        val skills = bundledMetadata()
        assertEquals(86, skills.size)
        SkillScenario.entries.forEach { scenario ->
            val result = suggestSkills(skills.reversed(), scenario)
            assertEquals("Candidates for $scenario", 3, result.size)
            assertEquals(SkillSuggestionRole.entries, result.map { it.role })
            assertEquals(3, result.map { it.skill.id }.toSet().size)
            assertTrue(result.all { it.reason.isNotBlank() })
            result.forEach { assertSame(skills.first { skill -> skill.id == it.skill.id }, it.skill) }
        }
    }

    @Test fun adultScenariosAndGeneralScenariosNeverMixScopes() {
        val skills = bundledMetadata()
        SkillScenario.entries.forEach { scenario ->
            val adult = scenario in setOf(SkillScenario.ADULT_STORY, SkillScenario.ADULT_ROLEPLAY)
            assertTrue(suggestSkills(skills, scenario).all { it.skill.adult == adult })
            val changedMarkers = skills.map { it.copy(adult = !adult) }
            assertTrue(suggestSkills(changedMarkers, scenario).isEmpty())
        }
    }

    @Test fun missingCandidateIsOmittedWithoutPromotingOrReplacingAnotherSkill() {
        val skills = bundledMetadata()
        val original = suggestSkills(skills, SkillScenario.ROLEPLAY)
        val missingPrimary = skills.filterNot { it.id == original.first().skill.id }
        assertEquals(original.drop(1), suggestSkills(missingPrimary, SkillScenario.ROLEPLAY))
        assertTrue(suggestSkills(emptyList(), SkillScenario.ROLEPLAY).isEmpty())
    }

    @Test fun importedLookalikeIdsAreNotTreatedAsBundledRecommendations() {
        val lookalike = SkillRecord(id = "imported-tt-sp-roleplay", name = "角色扮演连贯性", description = "角色互动")
        assertTrue(suggestSkills(listOf(lookalike), SkillScenario.ROLEPLAY).isEmpty())
        assertTrue(skillChoiceHint(lookalike).whenToUse.contains("自定义"))
    }

    @Test fun recommendationPreservesUserEditsAndDoesNotToggleOrSelectSkills() {
        val original = bundledMetadata().first { it.id == "builtin-writing-tt-sp-roleplay" }.copy(
            name = "我的自定义名字", content = "user edited content", files = mapOf("reference.md" to "user file"),
            permissions = listOf("files.read"), enabled = false
        )
        val skills = listOf(original)
        val selectedIds = setOf("previous-selection")
        SkillScenario.entries.forEach { suggestSkills(skills, it) }
        val candidate = suggestSkills(skills, SkillScenario.ROLEPLAY).single().skill
        assertSame(original, candidate)
        assertFalse(candidate.enabled)
        assertEquals(setOf("previous-selection"), selectedIds)
        assertEquals("user edited content", candidate.content)
        assertEquals(mapOf("reference.md" to "user file"), candidate.files)
    }

    @Test fun everyBundledRecordHasChineseUseAndPairingGuidance() {
        val chinese = Regex("[\\u4e00-\\u9fff]")
        bundledMetadata().forEach { skill ->
            val hint = skillChoiceHint(skill)
            assertTrue(skill.id, chinese.containsMatchIn(hint.whenToUse))
            assertTrue(skill.id, chinese.containsMatchIn(hint.pairing))
        }
        SkillScenario.entries.forEach {
            assertTrue(chinese.containsMatchIn(it.label))
            assertTrue(chinese.containsMatchIn(it.description))
        }
    }
}
