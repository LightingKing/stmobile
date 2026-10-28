package app.pockettavern.ui

import app.pockettavern.domain.SkillRecord
import java.util.Locale

/** Display-only purpose; the upstream files and persisted source metadata stay unchanged. */
enum class SkillUse(val label: String) {
    ALL("全部用途"),
    WORKFLOW("写作流程"),
    OUTLINE("大纲与结构"),
    PROSE("正文与对白"),
    REVISION("审稿与修订"),
    CHARACTER("角色塑造"),
    WORLDBUILDING("世界观设定"),
    ROLEPLAY("互动角色扮演"),
    TAVERN("酒馆制作"),
    SOCIAL("社会常识"),
    CUSTOM("自定义与其他")
}

/** Content scope is independent of purpose: an adult Skill can also be worldbuilding, etc. */
enum class SkillContentFilter(val label: String) {
    ALL("全部尺度"), GENERAL("通用"), ADULT("NSFW · 成人向")
}

enum class SkillStatusFilter(val label: String) {
    ALL("全部状态"), SELECTED("本会话已选"), ENABLED("已启用"), DISABLED("未启用")
}

/** The library view is intentionally small by default; the complete catalog remains recoverable. */
enum class SkillCatalogScope(val label: String) {
    ALL("全部目录"),
    CURATED("精选写作 + NSFW"),
    ADULT("仅 NSFW")
}

data class SkillFilters(
    val query: String = "",
    val use: SkillUse = SkillUse.ALL,
    val content: SkillContentFilter = SkillContentFilter.ALL,
    val status: SkillStatusFilter = SkillStatusFilter.ALL,
    val scope: SkillCatalogScope = SkillCatalogScope.ALL
)

/** Default for the library tab; chat pickers keep the full catalog for compatibility. */
internal fun defaultSkillLibraryFilters(): SkillFilters =
    SkillFilters(scope = SkillCatalogScope.CURATED)

/**
 * IDs are catalog metadata, not content inspection. Keep this list small and focused on skills that
 * improve prose, dialogue, continuity, or factual boundaries. Adult Skills are always included in
 * the curated view. User-installed Skills remain visible so an import is never hidden unexpectedly.
 */
private val CURATED_SKILL_IDS = setOf(
    // High-star, directly importable writing Skills added after the previous hand-picked set.
    "ss-stop-slop", "cn-chinese-novelist", "sh-shuorenhua",
    // Existing MIT story tools with direct value for prose, dialogue and continuity.
    "os-story-deslop", "os-story-long-write", "tm-continuity", "tm-dialogue",
    // Existing personal-use and first-party boundaries remain available in the focused view.
    "anti-ai-slop-writing", "ai-deslop", "pt-no-invention", "pt-canon-boundaries"
)

private fun isCuratedSkill(skill: SkillRecord): Boolean =
    skill.adult || !skill.id.startsWith("builtin-writing-") ||
        skill.id.removePrefix("builtin-writing-") in CURATED_SKILL_IDS

// These assignments use the catalog's public names and descriptions, never the Skill body.
private val bundledSkillUses: Map<String, SkillUse> = buildMap {
    fun assign(use: SkillUse, vararg ids: String) = ids.forEach { put("builtin-writing-$it", use) }
    assign(SkillUse.WORKFLOW,
        "dn-deep-novel", "ss-hard-sci-fi", "an-awesome-novel", "os-story",
        "os-story-long-write", "os-story-short-write", "os-story-import", "cw-webnovel",
        "ns-novel-studio", "ns-chief-editor", "ns-designer", "dp-tool-research", "dp-using-dreampowers")
    assign(SkillUse.OUTLINE,
        "sm-story-structure", "qm-apocalypse", "qm-outline-builder", "vn-booming",
        "vn-bookplan", "ns-architect", "dp-set-outline")
    assign(SkillUse.PROSE,
        "wf-mystery", "wf-dialogue", "wf-scene", "wf-romance", "sm-fiction-scene",
        "hn-scene-writing", "tm-dialogue", "vn-fuck-it", "vn-write", "vn-taste",
        "ns-writer", "dp-chapter-direct", "dp-chapter-draft", "dp-set-style", "dp-chapter-adult-original")
    assign(SkillUse.REVISION,
        "hn-continuity", "tm-deslop", "tm-continuity", "os-story-deslop", "anti-ai-slop-writing", "os-story-review",
        "os-story-long-analyze", "os-story-short-analyze", "os-story-long-scan", "os-story-short-scan",
        "vn-polish", "vn-revise", "vn-sync", "ns-editor", "dp-chapter-summary",
        "dp-review-consistency", "dp-review-final-report", "dp-review-reader", "ss-stop-slop", "sh-shuorenhua")
    assign(SkillUse.WORKFLOW, "cn-chinese-novelist")
    assign(SkillUse.CHARACTER, "qm-supporting-cast", "dp-character-style", "pt-character-voice")
    assign(SkillUse.WORLDBUILDING,
        "wf-fantasy", "hn-worldbuilding", "qm-world-rules", "vn-discuss", "vn-name",
        "vn-training-ground", "ns-lore-master", "iw-ip-worldbuilding", "dp-set-concept")
    assign(SkillUse.ROLEPLAY,
        "va-velvet-ascent", "fv-chat-doctor", "tt-sp-roleplay", "jw-game-facilitator",
        "dv-roleplay-modes", "an-roleplay-sandbox", "at-adult-tension-original", "ds-roleplay-preset-original")
    assign(SkillUse.TAVERN, "fv-card-author", "tc-tavern-design", "tc-tavern-cards", "tc-tavern-ui")
    assign(SkillUse.SOCIAL,
        "rs-renqing-shi-gu", "rq-renqing-shigu", "gq-workplace-eq", "tz-tizhi",
        "sp-socialization", "pt-china-common-sense")
}

fun skillUse(skill: SkillRecord): SkillUse = bundledSkillUses[skill.id] ?: SkillUse.CUSTOM

fun filterSkills(
    skills: List<SkillRecord>,
    filters: SkillFilters,
    selectedIds: Set<String> = emptySet()
): List<SkillRecord> {
    val words = filters.query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
    return skills.filter { skill ->
        when (filters.scope) {
            SkillCatalogScope.ALL -> true
            // A chat may already depend on an archived Skill. Keep it discoverable in that chat
            // while the default library stays focused.
            SkillCatalogScope.CURATED -> isCuratedSkill(skill) || skill.id in selectedIds
            SkillCatalogScope.ADULT -> skill.adult
        } &&
        (filters.use == SkillUse.ALL || skillUse(skill) == filters.use) &&
            when (filters.content) {
                SkillContentFilter.ALL -> true
                SkillContentFilter.GENERAL -> !skill.adult
                SkillContentFilter.ADULT -> skill.adult
            } && when (filters.status) {
                SkillStatusFilter.ALL -> true
                SkillStatusFilter.SELECTED -> skill.id in selectedIds
                SkillStatusFilter.ENABLED -> skill.enabled
                SkillStatusFilter.DISABLED -> !skill.enabled
            } && (words.isEmpty() || listOf(skill.name, skill.description, skill.source, skill.sourceUrl)
                .joinToString("\n").lowercase(Locale.ROOT).let { metadata -> words.all(metadata::contains) })
    }
}

/** Counts respect all other filters, so selecting a purpose never hides alternative purposes. */
fun skillUseCounts(
    skills: List<SkillRecord>,
    filters: SkillFilters,
    selectedIds: Set<String> = emptySet()
): Map<SkillUse, Int> {
    val matching = filterSkills(skills, filters.copy(use = SkillUse.ALL), selectedIds)
    val counts = matching.groupingBy(::skillUse).eachCount()
    return SkillUse.entries.associateWith { if (it == SkillUse.ALL) matching.size else counts[it] ?: 0 }
}

fun skillContentCounts(
    skills: List<SkillRecord>,
    filters: SkillFilters,
    selectedIds: Set<String> = emptySet()
): Map<SkillContentFilter, Int> {
    val matching = filterSkills(skills, filters.copy(content = SkillContentFilter.ALL), selectedIds)
    val adult = matching.count { it.adult }
    return linkedMapOf(
        SkillContentFilter.ALL to matching.size,
        SkillContentFilter.GENERAL to matching.size - adult,
        SkillContentFilter.ADULT to adult
    )
}
