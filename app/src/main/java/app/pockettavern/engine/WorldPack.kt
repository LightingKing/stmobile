package app.pockettavern.engine

import app.pockettavern.domain.Character
import app.pockettavern.domain.WorldEntry
import app.pockettavern.domain.WorldLimits
import org.json.JSONArray
import org.json.JSONObject

/**
 * 做成小世界: a scenario becomes the world JSON the PC accepts (POST /v1/pocket/worlds). The world
 * book entries the user picks become residents, the world's own copies; the rest stays world book.
 * The card's setting, narrative rules, the user's role and the opening come along.
 */
object WorldPack {
    private val PERSON = Regex("^\\s*【角色：([^】]+)】\\s*")
    private val ROLE = Regex("(?:用户|你)(?:扮演|是)([^，。,.;；\\s（(]{1,10})")
    private const val BOOK_ENTRIES = 80

    /** The scenario's enabled world book entries, people first (those written with the 【角色：名字】 heading). */
    fun candidates(scenario: Character, entries: List<WorldEntry>): List<WorldEntry> =
        entries.filter { it.characterId == scenario.id && it.enabled && it.content.isNotBlank() }
            .sortedWith(compareByDescending<WorldEntry> { isPerson(it) }.thenByDescending { it.priority })

    fun isPerson(entry: WorldEntry) = PERSON.containsMatchIn(entry.content)

    /**
     * The starting choice in the dialog: everyone 剧本助手 marked as a person, when they all fit. A
     * scenario with more people than a world holds starts with nobody ticked; the user picks, the
     * app does not pick the first eight for them.
     */
    fun suggested(candidates: List<WorldEntry>): Set<String> {
        val people = candidates.filter(::isPerson)
        return if (people.size <= WorldLimits.RESIDENTS) people.mapTo(LinkedHashSet()) { it.id } else emptySet()
    }

    fun personName(entry: WorldEntry): String = (PERSON.find(entry.content)?.groupValues?.get(1) ?: entry.name).trim().take(30)

    /** "用户扮演谷若尘，……" → 谷若尘; blank when the role does not say. */
    fun guessUserName(userPersona: String): String = ROLE.find(userPersona)?.groupValues?.get(1)?.trim().orEmpty().take(20)

    /**
     * The world JSON, or why it cannot be made. [residentIds] are world book entry ids; their order
     * is kept. Names must differ; keywords another resident already uses are dropped as aliases.
     */
    fun build(scenario: Character, entries: List<WorldEntry>, residentIds: List<String>, userName: String, startPaused: Boolean): Result<JSONObject> = runCatching {
        val pool = candidates(scenario, entries)
        val chosen = residentIds.distinct().mapNotNull { id -> pool.firstOrNull { it.id == id } }
        require(chosen.isNotEmpty()) { "至少选一位居民" }
        require(chosen.size <= WorldLimits.RESIDENTS) { "一个世界最多 ${WorldLimits.RESIDENTS} 位居民" }
        val names = chosen.map(::personName)
        names.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { throw IllegalArgumentException("有两位居民都叫“$it”，只留一位") }
        require(names.none { it.isBlank() }) { "有一位居民没有名字" }
        val used = names.toMutableSet()
        val residents = JSONArray()
        chosen.forEachIndexed { index, entry ->
            val name = names[index]
            val aliases = entry.keywords.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 30 && it !in used }.distinct().take(6)
            used += aliases
            residents.put(JSONObject().put("name", name).put("aliases", JSONArray(aliases))
                .put("card", entry.content.replace(PERSON, "").trim().ifBlank { name }.take(6000)))
        }
        val book = JSONArray()
        pool.filter { it.id !in chosen.map { c -> c.id } }
            .sortedWith(compareByDescending<WorldEntry> { it.always }.thenByDescending { it.priority })
            .take(BOOK_ENTRIES)
            .forEach { entry ->
                book.put(JSONObject().put("name", entry.name.ifBlank { entry.content.take(20) }.trim().take(60)).put("content", entry.content.trim().take(6000))
                    .put("keywords", JSONArray(entry.keywords.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 30 }.distinct().take(12)))
                    .put("always", entry.always))
            }
        val name = scenario.name.trim().ifBlank { "小世界" }
        JSONObject()
            .put("name", name.take(40))
            .put("summary", scenario.description.trim().take(500))
            .put("setting", scenario.scenario.trim().ifBlank { scenario.description.trim() }.ifBlank { name }.take(8000))
            .put("rules", scenario.systemPrompt.trim().take(4000))
            .put("userRole", scenario.userPersona.trim().take(2000))
            .put("userName", userName.trim().take(20))
            .put("opening", scenario.firstMessage.trim().take(4000))
            .put("worldBook", book)
            .put("residents", residents)
            .put("startPaused", startPaused)
    }
}
