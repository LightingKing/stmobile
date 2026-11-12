package app.pockettavern.ui

import app.pockettavern.domain.ContextBudgetBreakdown
import app.pockettavern.domain.ContextInspection
import app.pockettavern.domain.SkillRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The memory page says what each message sends and what waits for the user. */
class MemoryOverviewTest {
    private val breakdown = ContextBudgetBreakdown(estimatedInputTokens = 30_000, skillTokens = 12_000, characterTokens = 1_000, presetTokens = 500,
        profileTokens = 1_500, chapterTokens = 2_500, summaryTokens = 800, recentTokens = 9_000, recentTurns = 16, toolTokens = 1_200, policyTokens = 1_500)

    @Test fun rowsShowWhereTheTokensGoLargestFirst() {
        val rows = MemoryOverviewModel.rows(breakdown)
        assertEquals("Skills", rows.first().label)
        assertTrue(rows.any { it.label == "最近原文（16 轮）" && it.tokens == 9_000 })
        assertTrue(rows.any { it.label == "角色卡与预设" && it.tokens == 1_500 })
        assertFalse("empty parts are left out", rows.any { it.label == "置顶事实与世界书" })
        assertEquals(rows.sortedByDescending { it.tokens }, rows)
    }

    @Test fun aLongBacklogWaitsForTheUserWithItsCost() {
        val waiting = ContextInspection("c", breakdown.copy(triggerReason = "HISTORY_BACKLOG", foldTurns = 79))
        assertEquals(79, MemoryOverviewModel.backlog(waiting))
        assertTrue(MemoryOverviewModel.backlogText(79).contains("约 10 次"))
        assertTrue(MemoryOverviewModel.backlogText(79).contains("原文一字不动"))
        assertNull("a single chapter folds on its own", MemoryOverviewModel.backlog(waiting.copy(breakdown = breakdown.copy(triggerReason = "HISTORY_LENGTH", foldTurns = 8))))
        assertTrue(MemoryOverviewModel.next(breakdown.copy(triggerReason = "HISTORY_LENGTH", foldTurns = 8)).contains("8 轮整理成章节"))
    }

    @Test fun layersLineCountsChaptersFilesAndPinnedFacts() {
        assertEquals("已整理 12 章 · 2 份人物档案 · 置顶事实 3 条", MemoryOverviewModel.layers(ContextInspection("c", breakdown, chapters = 12, profiles = listOf("林岚", "周行"), pinned = 3)))
        assertEquals("还没有章节 · 置顶事实 0 条", MemoryOverviewModel.layers(ContextInspection("c", breakdown)))
    }

    @Test fun twoFullRuleSetsTogetherGetAConflictHint() {
        val skills = listOf(SkillRecord(id = "a", name = "深度小说引擎"), SkillRecord(id = "b", name = "沙盒角色扮演"), SkillRecord(id = "c", name = "去 AI 腔"))
        val costs = mapOf("a" to 9_000, "b" to 6_000, "c" to 900)
        val both = skillCostSummary(skills, costs, limit = 1_000_000, adultConfirmed = true)
        assertTrue(both.conflicting)
        assertEquals(listOf("深度小说引擎", "沙盒角色扮演"), both.large)
        assertFalse(skillCostSummary(skills.drop(1), costs, 1_000_000, true).conflicting)
    }

    @Test fun manySkillsAreHeavyEvenWithNoInputLimit() {
        // A limit of 0 means the model window, so the share alone never warned.
        val skills = (1..11).map { SkillRecord(id = "s$it", name = "S$it") }
        val summary = skillCostSummary(skills, skills.associate { it.id to 5_000 }, limit = 950_000, adultConfirmed = true)
        assertTrue(summary.percent < 15)
        assertTrue(summary.heavy)
    }
}
