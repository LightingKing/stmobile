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
    TOOLS("联网工具"),
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
 * IDs are catalog metadata, not content inspection. Since 0.1.65 the bundled catalog only keeps
 * Skills that help chat writing, so the focused view lists every general one: de-AI and prose
 * standards, dialogue and character voice, continuity, and roleplay. Adult Skills are always
 * included in the curated view. User-installed Skills remain visible so an import is never hidden
 * unexpectedly.
 */
private val CURATED_SKILL_IDS = setOf(
    "se-sepia", "fp-fiction-prose-style", "ss-stop-slop", "anti-ai-slop-writing", "os-story-deslop", "tm-deslop",
    "tm-dialogue", "dp-character-style", "dp-chapter-direct", "tm-continuity",
    "tt-sp-roleplay", "fv-chat-doctor", "jw-game-facilitator", "an-roleplay-sandbox", "dv-roleplay-modes", "ds-roleplay-preset-original",
    // Existing personal-use and first-party boundaries remain available in the focused view.
    "ai-deslop", "pt-no-invention", "pt-canon-boundaries", "pt-character-voice", "pt-china-common-sense", "pt-web-reach"
)

private fun isCuratedSkill(skill: SkillRecord): Boolean =
    skill.adult || !skill.id.startsWith("builtin-writing-") ||
        skill.id.removePrefix("builtin-writing-") in CURATED_SKILL_IDS

// These assignments use the catalog's public names and descriptions, never the Skill body.
private val bundledSkillUses: Map<String, SkillUse> = buildMap {
    fun assign(use: SkillUse, vararg ids: String) = ids.forEach { put("builtin-writing-$it", use) }
    assign(SkillUse.WORKFLOW, "dn-deep-novel")
    assign(SkillUse.PROSE,
        "fp-fiction-prose-style", "tm-dialogue", "dp-chapter-direct",
        "hn-scene-writing", "dp-chapter-adult-original", "fe-fictional-erotica", "ec-erotica-craft")
    assign(SkillUse.REVISION,
        "se-sepia", "ss-stop-slop", "anti-ai-slop-writing", "os-story-deslop", "tm-deslop",
        "tm-continuity", "hn-continuity")
    assign(SkillUse.CHARACTER, "dp-character-style", "pt-character-voice")
    assign(SkillUse.WORLDBUILDING, "hn-worldbuilding")
    assign(SkillUse.ROLEPLAY,
        "va-velvet-ascent", "fv-chat-doctor", "tt-sp-roleplay", "jw-game-facilitator",
        "dv-roleplay-modes", "an-roleplay-sandbox", "at-adult-tension-original", "ds-roleplay-preset-original")
    assign(SkillUse.SOCIAL, "pt-china-common-sense")
    assign(SkillUse.TOOLS, "pt-web-reach")
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
