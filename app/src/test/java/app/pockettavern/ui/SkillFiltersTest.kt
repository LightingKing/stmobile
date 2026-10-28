package app.pockettavern.ui

import app.pockettavern.domain.SkillRecord
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillFiltersTest {
    private fun bundledMetadata(): List<SkillRecord> = listOf("writing-skills", "first-party-skills").flatMap { folder ->
        val entries = JSONObject(File("src/main/assets/$folder/catalog.json").readText()).getJSONArray("skills")
        (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            SkillRecord(
                id = "builtin-writing-${item.getString("id")}",
                name = item.getString("name"),
                description = item.getString("description"),
                source = "$folder · ${item.getString("category")}",
                sourceUrl = item.optString("sourceUrl"),
                enabled = false,
                adult = item.optBoolean("adult", false)
            )
        }
    }

    private fun builtin(id: String, adult: Boolean = false, enabled: Boolean = false): SkillRecord =
        SkillRecord(id = "builtin-writing-$id", adult = adult, enabled = enabled)

    @Test fun everyBundledMetadataRecordHasAnExplicitPurpose() {
        val bundled = bundledMetadata()
        assertTrue(bundled.size >= 79)
        bundled.forEach { skill ->
            assertFalse("Missing purpose for ${skill.id}", skillUse(skill) in setOf(SkillUse.ALL, SkillUse.CUSTOM))
        }
        val counts = skillUseCounts(bundled, SkillFilters())
        assertEquals(bundled.size, counts.getValue(SkillUse.ALL))
        assertEquals(bundled.size, counts.filterKeys { it != SkillUse.ALL }.values.sum())
        assertTrue(SkillUse.entries.all { it.label.isNotBlank() })
    }

    @Test fun adultScopeIsIndependentOfPurpose() {
        val world = builtin("hn-worldbuilding", adult = true)
        val prose = builtin("hn-scene-writing", adult = true)
        val general = builtin("wf-fantasy")
        val skills = listOf(world, prose, general)
        assertEquals(SkillUse.WORLDBUILDING, skillUse(world))
        assertEquals(SkillUse.PROSE, skillUse(prose))
        assertEquals(listOf(world, general), filterSkills(skills, SkillFilters(use = SkillUse.WORLDBUILDING)))
        assertEquals(listOf(world), filterSkills(skills, SkillFilters(use = SkillUse.WORLDBUILDING, content = SkillContentFilter.ADULT)))
        assertEquals(listOf(general), filterSkills(skills, SkillFilters(use = SkillUse.WORLDBUILDING, content = SkillContentFilter.GENERAL)))
        assertEquals(listOf(world, prose), filterSkills(skills, SkillFilters(content = SkillContentFilter.ADULT)))
        // Purpose is stable even if a user's content marker or display source changes.
        assertEquals(SkillUse.WORLDBUILDING, skillUse(world.copy(adult = false, source = "自定义显示")))
    }

    @Test fun countsIgnoreTheirOwnDimensionAndRespectAllOthers() {
        val world = builtin("hn-worldbuilding", adult = true, enabled = true)
        val prose = builtin("hn-scene-writing", adult = true, enabled = true)
        val general = builtin("wf-fantasy", enabled = true)
        val disabled = builtin("wf-dialogue")
        val skills = listOf(world, prose, general, disabled)
        val filters = SkillFilters(use = SkillUse.WORLDBUILDING, content = SkillContentFilter.ADULT, status = SkillStatusFilter.ENABLED)
        val purposes = skillUseCounts(skills, filters)
        assertEquals(2, purposes.getValue(SkillUse.ALL))
        assertEquals(1, purposes.getValue(SkillUse.WORLDBUILDING))
        assertEquals(1, purposes.getValue(SkillUse.PROSE))
        assertEquals(0, purposes.getValue(SkillUse.CUSTOM))
        val scope = skillContentCounts(skills, filters)
        assertEquals(mapOf(SkillContentFilter.ALL to 2, SkillContentFilter.GENERAL to 1, SkillContentFilter.ADULT to 1), scope)
    }

    @Test fun searchUsesOnlyDisplayAndRepositoryMetadataWithAndSemantics() {
        val match = builtin("hn-scene-writing", adult = true).copy(
            name = "情欲场景写作", description = "中文节奏和分级", source = "GitHub精选 · 成人向",
            sourceUrl = "https://github.com/C1audel3/Hnovel/blob/123/SKILL.md",
            content = "never-index-secret", files = mapOf("reference.md" to "never-index-file")
        )
        val other = builtin("wf-dialogue").copy(name = "节奏与对白", sourceUrl = "https://github.com/other/repo")
        val skills = listOf(match, other)
        assertEquals(listOf(match), filterSkills(skills, SkillFilters(query = "  HNOVEL\n中文\t场景 ")))
        assertEquals(listOf(match), filterSkills(skills, SkillFilters(query = "github精选 c1audel3")))
        assertTrue(filterSkills(skills, SkillFilters(query = "hnovel 不存在")).isEmpty())
        assertTrue(filterSkills(skills, SkillFilters(query = "never-index-secret")).isEmpty())
        assertTrue(filterSkills(skills, SkillFilters(query = "never-index-file")).isEmpty())
        assertEquals(skills, filterSkills(skills, SkillFilters(query = " \n\t ")))
        assertEquals(1, skillUseCounts(skills, SkillFilters(query = "hnovel")).getValue(SkillUse.ALL))
    }

    @Test fun statusAndSelectionAreDifferentAndDoNotMutateInputs() {
        val enabled = builtin("wf-dialogue", enabled = true)
        val disabled = builtin("hn-scene-writing", adult = true)
        val custom = SkillRecord(id = "imported", enabled = true)
        val skills = listOf(enabled, disabled, custom)
        val selected = setOf(disabled.id, "deleted")
        assertEquals(listOf(disabled), filterSkills(skills, SkillFilters(status = SkillStatusFilter.SELECTED), selected))
        assertEquals(listOf(enabled, custom), filterSkills(skills, SkillFilters(status = SkillStatusFilter.ENABLED), selected))
        assertEquals(listOf(disabled), filterSkills(skills, SkillFilters(status = SkillStatusFilter.DISABLED), selected))
        assertTrue(filterSkills(skills, SkillFilters(status = SkillStatusFilter.SELECTED)).isEmpty())
        assertEquals(1, skillContentCounts(skills, SkillFilters(status = SkillStatusFilter.SELECTED), selected).getValue(SkillContentFilter.ADULT))
        assertFalse(disabled.enabled)
        assertEquals(setOf(disabled.id, "deleted"), selected)
    }

    @Test fun restoredOriginalIdsAndLocalImportsHavePredictablePurposes() {
        assertEquals(SkillUse.PROSE, skillUse(builtin("dp-chapter-adult-original", adult = true)))
        assertEquals(SkillUse.ROLEPLAY, skillUse(builtin("at-adult-tension-original", adult = true)))
        assertEquals(SkillUse.ROLEPLAY, skillUse(builtin("ds-roleplay-preset-original")))
        val local = SkillRecord(id = "custom", source = "GitHub精选 · 成人向", adult = true)
        assertEquals(SkillUse.CUSTOM, skillUse(local))
        assertEquals(listOf(local), filterSkills(listOf(local), SkillFilters(use = SkillUse.CUSTOM, content = SkillContentFilter.ADULT)))
        assertEquals(SkillUse.CUSTOM, skillUse(SkillRecord(id = "hn-scene-writing")))
    }

    @Test fun curatedScopeKeepsWritingCoreAdultAndSelectedArchive() {
        val adult = builtin("hn-scene-writing", adult = true)
        val deslop = builtin("ss-stop-slop")
        val archived = builtin("wf-fantasy")
        val imported = SkillRecord(id = "imported")
        val skills = listOf(adult, deslop, archived, imported)
        assertEquals(
            listOf(adult, deslop, imported),
            filterSkills(skills, SkillFilters(scope = SkillCatalogScope.CURATED))
        )
        assertEquals(
            listOf(adult, deslop, archived, imported),
            filterSkills(skills, SkillFilters(scope = SkillCatalogScope.CURATED), selectedIds = setOf(archived.id))
        )
        assertEquals(listOf(adult), filterSkills(skills, SkillFilters(scope = SkillCatalogScope.ADULT)))
    }
}
