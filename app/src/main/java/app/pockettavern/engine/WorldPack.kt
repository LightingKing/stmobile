package app.pockettavern.engine

import app.pockettavern.domain.Character
import app.pockettavern.domain.ScenarioDraft
import app.pockettavern.domain.WorldDetail
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

    /**
     * A character card copied into a world as a resident: the card's own text in labelled parts,
     * within the resident limit. The card itself is not linked; editing one leaves the other alone.
     */
    fun residentFromCharacter(character: Character): JSONObject {
        val parts = listOf("简介" to character.description, "性格" to character.personality, "背景" to character.scenario,
            "说话示例" to character.examples, "补充规则" to character.systemPrompt)
            .filter { it.second.isNotBlank() }
            .joinToString("\n\n") { (label, value) -> "【$label】\n${value.trim()}" }
        val name = character.name.trim().ifBlank { "新居民" }.take(30)
        return JSONObject().put("name", name).put("card", parts.ifBlank { name }.take(6000))
    }

    /**
     * A 世界助手 draft as the world JSON: its people are the residents, its world entries the world
     * book. A draft without a core setting uses its always-on entries (or their names) instead.
     */
    fun fromDraft(draft: ScenarioDraft, startPaused: Boolean): Result<JSONObject> = runCatching {
        val missing = ScenarioBuilder.missing(draft.copy(worldMode = true))
        require(missing.isEmpty()) { "还缺：${missing.joinToString("、")}" }
        require(draft.people.size <= WorldLimits.RESIDENTS) { "小世界最多 ${WorldLimits.RESIDENTS} 位居民" }
        val residents = residentsJson(draft.people.map { Triple(it.name.trim().take(30), it.content, it.keywords) })
        val book = JSONArray()
        draft.world.take(BOOK_ENTRIES).forEach { book.put(bookEntry(it.name, it.content, it.keywords, it.always)) }
        val setting = draft.setting.trim()
            .ifBlank { draft.world.filter { it.always }.joinToString("\n") { "${it.name}：${it.content}" } }
            .ifBlank { "世界观见世界书：" + draft.world.joinToString("、") { it.name } }
        JSONObject()
            .put("name", draft.title.trim().take(40))
            .put("summary", draft.summary.trim().take(500))
            .put("setting", setting.take(8000))
            .put("rules", draft.rules.trim().take(4000))
            .put("userRole", draft.userRole.trim().take(2000))
            .put("userName", draft.userName.trim().take(20))
            .put("groupName", draft.groupName.trim().take(30))
            .put("opening", draft.opening.trim().take(4000))
            .put("worldBook", book)
            .put("residents", residents)
            .put("startPaused", startPaused)
    }

    /** What building a draft again does to the world it already made. */
    class WorldSync(
        /** World fields to PATCH (blank names left out: the PC requires them). */
        val patch: JSONObject,
        /** Resident id to the card and aliases to set, for residents whose name is still in the draft. */
        val updates: List<Pair<String, JSONObject>>,
        val adds: List<JSONObject>,
        /** Residents no longer in the draft, by id; they move out (what they said stays). */
        val removes: List<String>,
    ) {
        val isEmpty: Boolean get() = updates.isEmpty() && adds.isEmpty() && removes.isEmpty()
    }

    /** Residents are matched by name: a renamed one moves out and comes back as new. */
    fun syncPlan(pack: JSONObject, current: WorldDetail): WorldSync {
        val patch = JSONObject()
        listOf("name", "summary", "setting", "rules", "userRole").forEach { key -> patch.put(key, pack.optString(key)) }
        listOf("userName", "groupName").forEach { key -> pack.optString(key).takeIf { it.isNotBlank() }?.let { patch.put(key, it) } }
        patch.put("worldBook", pack.optJSONArray("worldBook") ?: JSONArray())
        val wanted = pack.optJSONArray("residents") ?: JSONArray()
        val existing = current.summary.residents
        val updates = mutableListOf<Pair<String, JSONObject>>()
        val adds = mutableListOf<JSONObject>()
        val kept = mutableSetOf<String>()
        for (index in 0 until wanted.length()) {
            val resident = wanted.getJSONObject(index)
            val name = resident.optString("name")
            val match = existing.firstOrNull { it.name == name }
            if (match == null) { adds += resident; continue }
            kept += match.id
            val aliases = resident.optJSONArray("aliases")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty()
            if (match.card != resident.optString("card") || match.aliases != aliases) {
                updates += match.id to JSONObject().put("card", resident.optString("card")).put("aliases", JSONArray(aliases))
            }
        }
        return WorldSync(patch, updates, adds, existing.filter { it.id !in kept }.map { it.id })
    }

    /** Residents from (name, text, keywords): the 【角色：】 heading dropped, other residents' names never an alias. */
    private fun residentsJson(people: List<Triple<String, String, List<String>>>): JSONArray {
        val names = people.map { it.first }
        require(names.none { it.isBlank() }) { "有一位居民没有名字" }
        names.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { throw IllegalArgumentException("有两位居民都叫“$it”，只留一位") }
        val used = names.toMutableSet()
        val residents = JSONArray()
        people.forEach { (name, content, keywords) ->
            val aliases = keywords.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 30 && it !in used }.distinct().take(6)
            used += aliases
            residents.put(JSONObject().put("name", name).put("aliases", JSONArray(aliases)).put("card", content.replace(PERSON, "").trim().ifBlank { name }.take(6000)))
        }
        return residents
    }

    private fun bookEntry(name: String, content: String, keywords: List<String>, always: Boolean): JSONObject =
        JSONObject().put("name", name.ifBlank { content.take(20) }.trim().take(60)).put("content", content.trim().take(6000))
            .put("keywords", JSONArray(keywords.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 30 }.distinct().take(12)))
            .put("always", always)

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
        val residents = residentsJson(chosen.map { Triple(personName(it), it.content, it.keywords) })
        val book = JSONArray()
        pool.filter { it.id !in chosen.map { c -> c.id } }
            .sortedWith(compareByDescending<WorldEntry> { it.always }.thenByDescending { it.priority })
            .take(BOOK_ENTRIES)
            .forEach { entry -> book.put(bookEntry(entry.name, entry.content, entry.keywords, entry.always)) }
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
