package app.pockettavern.provider

import app.pockettavern.domain.WorldDetail
import app.pockettavern.domain.WorldDiaryEntry
import app.pockettavern.domain.WorldItem
import app.pockettavern.domain.WorldResident
import app.pockettavern.domain.WorldSettings
import app.pockettavern.domain.WorldSummary
import app.pockettavern.domain.WorldUsage
import org.json.JSONArray
import org.json.JSONObject

/** What the PC's /v1/pocket/worlds endpoints return, read into the phone's world models. */
object WorldJson {
    class Page(val items: List<WorldItem>, val next: Long, val more: Boolean, val world: WorldSummary?)
    class Diary(val entries: List<WorldDiaryEntry>, val memories: Map<String, String>)

    fun summary(json: JSONObject): WorldSummary {
        val settings = json.optJSONObject("settings") ?: JSONObject()
        val defaults = WorldSettings()
        return WorldSummary(
            id = json.optString("id"), name = json.optString("name"), summary = json.optString("summary"),
            worldTimeLabel = json.optString("world_time_label"), paused = json.optBoolean("paused"),
            hold = json.optString("hold").takeUnless { json.isNull("hold") }.orEmpty(), busy = json.optBoolean("busy"),
            unread = json.optInt("unread"), lastMessage = json.optJSONObject("last_message")?.let(::item),
            usage = usage(json.optJSONObject("usage")), totals = usage(json.optJSONObject("totals")),
            settings = WorldSettings(
                tickMinutes = settings.optInt("tickMinutes", defaults.tickMinutes), quietStart = settings.optString("quietStart", defaults.quietStart),
                quietEnd = settings.optString("quietEnd", defaults.quietEnd), dailyTicks = settings.optInt("dailyTicks", defaults.dailyTicks),
                dailyCalls = settings.optInt("dailyCalls", defaults.dailyCalls), slowWhenAway = settings.optBoolean("slowWhenAway", defaults.slowWhenAway),
                dailyDms = settings.optInt("dailyDms", defaults.dailyDms)
            ),
            model = json.optString("model", "pocket-direct"), residents = residents(json.optJSONArray("residents")),
            nextTickAt = if (json.has("next_tick_at") && !json.isNull("next_tick_at")) json.optLong("next_tick_at") else null,
            intervalMinutes = json.optInt("interval_minutes", defaults.tickMinutes),
            lastError = json.optJSONObject("last_error")?.optString("message").orEmpty(), seq = json.optLong("seq")
        )
    }

    /** One world in full: its summary plus the parts of its definition the phone shows. */
    fun detail(json: JSONObject): WorldDetail {
        val definition = json.optJSONObject("definition") ?: JSONObject()
        val defined = definition.optJSONArray("residents").objects().associateBy { it.optString("id") }
        val summary = summary(json)
        return WorldDetail(
            summary = summary.copy(residents = summary.residents.map { resident ->
                val source = defined[resident.id]
                resident.copy(card = source?.optString("card").orEmpty(), aliases = source?.optJSONArray("aliases").strings())
            }),
            groupName = definition.optString("groupName").ifBlank { "${summary.name}群" }, userName = definition.optString("userName"),
            userRole = definition.optString("userRole"), setting = definition.optString("setting"),
            facts = definition.optJSONArray("facts").strings().filter { it.isNotBlank() }
        )
    }

    fun item(json: JSONObject) = WorldItem(
        seq = json.optLong("seq"), kind = json.optString("kind"), channel = json.optString("channel"), from = json.optString("from"),
        name = json.optString("name"), text = json.optString("text"), time = json.optString("time"), worldAt = json.optLong("worldAt"),
        who = json.optJSONArray("who").strings()
    )

    fun page(json: JSONObject) = Page(
        items = json.optJSONArray("items").objects().map(::item).filter { it.seq > 0 },
        next = json.optLong("next"), more = json.optBoolean("more"), world = json.optJSONObject("world")?.let(::summary)
    )

    fun list(json: JSONObject): List<WorldSummary> = json.optJSONArray("data").objects().map(::summary).filter { it.id.isNotBlank() }

    fun diary(json: JSONObject) = Diary(
        entries = json.optJSONArray("entries").objects().map { WorldDiaryEntry(it.optString("day"), it.optString("text")) },
        memories = json.optJSONArray("residents").objects().filter { it.optString("notes").isNotBlank() }.associate { it.optString("name") to it.optString("notes") }
    )

    /** Only the fields changed in the PATCH body, so the PC keeps anything this phone does not know about. */
    fun settingsPatch(settings: WorldSettings): JSONObject = JSONObject()
        .put("tickMinutes", settings.tickMinutes).put("quietStart", settings.quietStart).put("quietEnd", settings.quietEnd)
        .put("dailyTicks", settings.dailyTicks).put("dailyCalls", settings.dailyCalls).put("slowWhenAway", settings.slowWhenAway)
        .put("dailyDms", settings.dailyDms)

    private fun usage(json: JSONObject?) = WorldUsage(
        ticks = json?.optInt("ticks") ?: 0, calls = json?.optInt("calls") ?: 0,
        promptTokens = json?.optLong("prompt_tokens") ?: 0, completionTokens = json?.optLong("completion_tokens") ?: 0
    )

    private fun residents(list: JSONArray?): List<WorldResident> = list.objects().map {
        WorldResident(
            id = it.optString("id"), name = it.optString("name"), location = it.optString("location"), activity = it.optString("activity"),
            mood = it.optString("mood"), plan = it.optString("plan"), notes = it.optString("notes")
        )
    }.filter { it.id.isNotBlank() }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
}
