package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.ScenarioOption
import app.pockettavern.domain.ScenarioPerson
import app.pockettavern.domain.ScenarioQuest
import app.pockettavern.domain.ScenarioResource
import app.pockettavern.domain.ScenarioState
import app.pockettavern.domain.ScenarioUpgrade
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles the optional, single-pass state block returned with a story answer.
 * The block is intentionally a delta contract: absent fields stay untouched,
 * and every value is bounded and treated as untrusted model output.
 */
object ScenarioStateSync {
    data class ApplyResult(val chat: Chat, val visibleContent: String, val applied: Boolean)

    private val marker = Regex("(?is)<(?:pocket_tavern_state|pt_state)>\\s*(.*?)\\s*</(?:pocket_tavern_state|pt_state)>")

    fun prompt(chat: Chat): String {
        if (chat.characterId.isBlank()) return ""
        return buildString {
            append("【剧本状态同步】本会话有一个可持久化的状态面板。只有当本轮正文明确发生并确认了资源、位置、时间、系统等级、兑换、升级、任务或人物状态变化时，才在正文末尾追加一个应用内部状态块；没有变化时不要追加。状态块不是剧情内容，应用会在保存后从读者可见正文中移除。")
            append("\\n只提交本轮变化后的最终值，不要猜测、不要把计划写成已完成；未确认的字段保持缺省。格式如下：")
            append("\\n<pocket_tavern_state>{\"location\":\"...\",\"time\":\"...\",\"systemLevel\":\"...\",\"resources\":[{\"name\":\"文明点\",\"value\":\"970000\",\"unit\":\"点\",\"note\":\"本轮兑换后\"}],\"options\":[{\"name\":\"...\",\"cost\":\"...\",\"requirement\":\"...\",\"result\":\"...\",\"available\":true}],\"upgrades\":[{\"name\":\"...\",\"level\":\"...\",\"cost\":\"...\",\"requirement\":\"...\",\"benefit\":\"...\",\"status\":\"已完成/进行中/未解锁\"}],\"quests\":[{\"title\":\"...\",\"status\":\"进行中/已完成/未开始\",\"progress\":\"...\",\"description\":\"...\",\"reward\":\"...\"}],\"characters\":[{\"name\":\"...\",\"role\":\"...\",\"relationship\":\"...\",\"ability\":\"...\",\"activity\":\"...\",\"clothing\":\"...\",\"personality\":\"...\",\"description\":\"...\",\"status\":\"...\"}]}</pocket_tavern_state>")
            append("\\n数组中的对象按 name/title 合并到已有记录；只放确实改变的对象。不要输出 Markdown 代码围栏、解释文字或空数组来重置数据。")
            val current = compactCurrent(chat)
            if (current.isNotBlank()) append("\\n当前已登记的动态覆盖（只作为核对依据）：\\n").append(current)
        }
    }

    fun apply(chat: Chat, content: String): ApplyResult {
        if (content.isBlank()) return ApplyResult(chat, content, false)
        val matches = marker.findAll(content).toList()
        if (matches.isEmpty()) return ApplyResult(chat, content, false)
        val parsed = matches.asReversed().firstNotNullOfOrNull { match -> parse(match.groupValues[1]) }
            ?: return ApplyResult(chat, marker.replace(content, "").trimEnd(), false)
        val cleaned = marker.replace(content, "").trimEnd()
        return ApplyResult(chat.copy(scenarioState = mergeState(chat.scenarioState, parsed.state, parsed.optionAvailability), scenarioRoster = mergeRoster(chat.scenarioRoster, parsed.people)), cleaned, true)
    }

    private data class Parsed(
        val state: ScenarioState,
        val people: List<ScenarioPerson>,
        /** Only names explicitly carrying an `available` field are included. */
        val optionAvailability: Map<String, Boolean>
    )

    private fun parse(raw: String): Parsed? = runCatching {
        val json = raw.trim().removePrefix("```").removeSuffix("```").trim().removePrefix("json").trim()
        val root = JSONObject(json)
        val parsedOptions = options(root.optJSONArray("options"))
        val state = ScenarioState(
            location = optionalText(root, "location", 512), time = optionalText(root, "time", 256),
            systemLevel = optionalText(root, "systemLevel", 256), note = optionalText(root, "note", 4000),
            resources = resources(root.optJSONArray("resources")), options = parsedOptions.values,
            upgrades = upgrades(root.optJSONArray("upgrades")), quests = quests(root.optJSONArray("quests")), source = "模型状态同步"
        )
        val people = root.optJSONArray("characters") ?: root.optJSONArray("roster")
        Parsed(state, people(people), parsedOptions.explicitAvailability)
    }.getOrNull()?.takeIf { it.state.hasData() || it.people.isNotEmpty() }

    private fun resources(array: JSONArray?): List<ScenarioResource> = arrayObjects(array).mapNotNull { item ->
        val name = text(item, "name", 256).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ScenarioResource(name = name, value = text(item, "value", 256), unit = text(item, "unit", 64), note = text(item, "note", 1000))
    }

    private data class ParsedOptions(
        val values: List<ScenarioOption>,
        val explicitAvailability: Map<String, Boolean>
    )

    private fun options(array: JSONArray?): ParsedOptions {
        val explicitAvailability = LinkedHashMap<String, Boolean>()
        val values = arrayObjects(array).mapNotNull { item ->
            val name = text(item, "name", 256).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (item.has("available")) explicitAvailability[name] = item.optBoolean("available")
            ScenarioOption(
                name = name,
                cost = text(item, "cost", 256),
                requirement = text(item, "requirement", 1000),
                result = text(item, "result", 2000),
                available = item.optBoolean("available", true)
            )
        }
        return ParsedOptions(values, explicitAvailability)
    }

    private fun upgrades(array: JSONArray?): List<ScenarioUpgrade> = arrayObjects(array).mapNotNull { item ->
        val name = text(item, "name", 256).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ScenarioUpgrade(name = name, level = text(item, "level", 128), cost = text(item, "cost", 256), requirement = text(item, "requirement", 1000), benefit = text(item, "benefit", 2000), status = text(item, "status", 128))
    }

    private fun quests(array: JSONArray?): List<ScenarioQuest> = arrayObjects(array).mapNotNull { item ->
        val title = text(item, "title", 256).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ScenarioQuest(title = title, status = text(item, "status", 128), progress = text(item, "progress", 256), description = text(item, "description", 2000), reward = text(item, "reward", 1000))
    }

    private fun people(array: JSONArray?): List<ScenarioPerson> = arrayObjects(array).mapNotNull { item ->
        val name = text(item, "name", 256).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ScenarioPerson(name = name, role = text(item, "role", 512), relationship = text(item, "relationship", 512), ability = text(item, "ability", 1000), activity = text(item, "activity", 1000), clothing = text(item, "clothing", 1000), personality = text(item, "personality", 1000), description = text(item, "description", 2000), status = text(item, "status", 128), source = "模型状态同步")
    }

    private fun mergeState(old: ScenarioState, delta: ScenarioState, optionAvailability: Map<String, Boolean>): ScenarioState {
        fun <T> mergeRows(
            existing: List<T>,
            incoming: List<T>,
            key: (T) -> String,
            merge: (T?, T) -> T
        ): List<T> {
            val map = LinkedHashMap<String, T>()
            existing.forEach { item -> key(item).takeIf(String::isNotBlank)?.let { map[it] = item } }
            incoming.forEach { item ->
                key(item).takeIf(String::isNotBlank)?.let { rowKey -> map[rowKey] = merge(map[rowKey], item) }
            }
            return map.values.toList()
        }
        return old.copy(
            location = delta.location.ifBlank { old.location }, time = delta.time.ifBlank { old.time }, systemLevel = delta.systemLevel.ifBlank { old.systemLevel }, note = delta.note.ifBlank { old.note },
            resources = mergeRows(old.resources, delta.resources, { it.name }) { previous, next ->
                if (previous == null) next else previous.copy(
                    value = next.value.ifBlank { previous.value },
                    unit = next.unit.ifBlank { previous.unit },
                    note = next.note.ifBlank { previous.note }
                )
            },
            options = mergeRows(old.options, delta.options, { it.name }) { previous, next ->
                if (previous == null) next else previous.copy(
                    cost = next.cost.ifBlank { previous.cost },
                    requirement = next.requirement.ifBlank { previous.requirement },
                    result = next.result.ifBlank { previous.result },
                    available = optionAvailability[next.name] ?: previous.available
                )
            },
            upgrades = mergeRows(old.upgrades, delta.upgrades, { it.name }) { previous, next ->
                if (previous == null) next else previous.copy(
                    level = next.level.ifBlank { previous.level },
                    cost = next.cost.ifBlank { previous.cost },
                    requirement = next.requirement.ifBlank { previous.requirement },
                    benefit = next.benefit.ifBlank { previous.benefit },
                    status = next.status.ifBlank { previous.status }
                )
            },
            quests = mergeRows(old.quests, delta.quests, { it.title }) { previous, next ->
                if (previous == null) next else previous.copy(
                    status = next.status.ifBlank { previous.status },
                    progress = next.progress.ifBlank { previous.progress },
                    description = next.description.ifBlank { previous.description },
                    reward = next.reward.ifBlank { previous.reward }
                )
            },
            updatedAt = System.currentTimeMillis(), source = "模型状态同步"
        )
    }

    private fun mergeRoster(old: List<ScenarioPerson>, incoming: List<ScenarioPerson>): List<ScenarioPerson> {
        val map = LinkedHashMap<String, ScenarioPerson>()
        old.forEach { if (it.name.isNotBlank()) map[it.name] = it }
        incoming.forEach { item ->
            if (item.name.isBlank()) return@forEach
            val previous = map[item.name]
            map[item.name] = item.copy(
                id = previous?.id ?: item.id,
                role = item.role.ifBlank { previous?.role.orEmpty() }, relationship = item.relationship.ifBlank { previous?.relationship.orEmpty() }, ability = item.ability.ifBlank { previous?.ability.orEmpty() }, activity = item.activity.ifBlank { previous?.activity.orEmpty() }, clothing = item.clothing.ifBlank { previous?.clothing.orEmpty() }, personality = item.personality.ifBlank { previous?.personality.orEmpty() }, description = item.description.ifBlank { previous?.description.orEmpty() }, status = item.status.ifBlank { previous?.status.orEmpty() }
            )
        }
        return map.values.toList()
    }

    private fun ScenarioState.hasData(): Boolean = location.isNotBlank() || time.isNotBlank() || systemLevel.isNotBlank() || note.isNotBlank() || resources.isNotEmpty() || options.isNotEmpty() || upgrades.isNotEmpty() || quests.isNotEmpty()
    private fun compactCurrent(chat: Chat): String = buildString {
        val state = chat.scenarioState
        if (state.location.isNotBlank()) append("位置：").append(state.location).append('\n')
        if (state.time.isNotBlank()) append("时间：").append(state.time).append('\n')
        state.resources.take(30).forEach { append("资源：").append(it.name).append('=').append(it.value).append(it.unit).append('\n') }
        state.quests.take(20).forEach { append("任务：").append(it.title).append('=').append(it.status).append(' ').append(it.progress).append('\n') }
        chat.scenarioRoster.take(30).forEach { person -> append("人物：").append(person.name).append("；活动=").append(person.activity.ifBlank { "未记录" }).append("；状态=").append(person.status).append('\n') }
    }.take(5000)

    private fun arrayObjects(array: JSONArray?): List<JSONObject> = array?.let { (0 until it.length()).mapNotNull { index -> it.optJSONObject(index) }.take(200) }.orEmpty()
    private fun optionalText(root: JSONObject, key: String, limit: Int): String = if (root.has(key)) text(root, key, limit) else ""
    private fun text(root: JSONObject, key: String, limit: Int): String = root.optString(key).take(limit).trim()
}
