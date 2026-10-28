package app.pockettavern.engine

import app.pockettavern.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/** A structured, evidence-bound patch produced while reading old scenario dialogue. */
data class ScenarioHistoryPatch(
    val state: ScenarioState = ScenarioState(),
    val people: List<ScenarioPerson> = emptyList(),
    val sourceMessageIds: List<String> = emptyList(),
    /** Valid compact summaries used as evidence when their raw prefix was compressed away. */
    val sourceSummaryIds: List<String> = emptyList()
)

/**
 * Converts historical dialogue into the two scenario panels.  This is kept
 * separate from the normal reply synchronizer: a button press may scan old
 * messages, while normal replies only report changes from the current turn.
 */
object ScenarioHistoryOrganizer {
    const val FORMAT = "scenario-history-v1"
    /** Prefix used for notes produced by the scenario history action. */
    const val NOTE_TITLE_PREFIX = "剧本整理·"
    val GUIDE = """
        你是剧本状态整理器。只根据本批历史对话中明确发生、明确确认或作者明确更正的事实，更新当前草稿。
        不续写，不推测，不把角色卡计划当成已经发生，不把模型分析、工具往返、废稿或用户要求本身当成剧情事实。
        后文明确改变前文时以后文为准；没有提到的字段保持草稿原值。输出必须是单个合法 JSON，不要 Markdown 代码块或解释。
        JSON 结构：{"state":{"location":"","time":"","systemLevel":"","resources":[{"name":"","value":"","unit":"","note":""}],"options":[{"name":"","cost":"","requirement":"","result":"","available":true}],"upgrades":[{"name":"","level":"","cost":"","requirement":"","benefit":"","status":""}],"quests":[{"title":"","status":"","progress":"","description":"","reward":""}],"note":""},"people":[{"name":"","role":"","relationship":"","ability":"","activity":"","clothing":"","personality":"","description":"","status":""}],"sourceMessageIds":["本批输入中的真实消息 id"],"sourceSummaryIds":["已有有效摘要的真实 id"]}
        existingMemory 是以前整理出的知识条目或有效压缩摘要，只能作为补全线索；知识条目必须结合其来源消息 ID，摘要必须引用 existingMemory 中的摘要 id。不能把没有来源的旧摘要当成新事实。只输出本批输入或已有记忆证据中能找到证据的变化；没有变化时 state 使用空字符串和空数组、people 为空、sourceMessageIds 和 sourceSummaryIds 为空。人物按稳定姓名合并，同一人物后文覆盖前文的非空字段。关系必须写清与谁的关系，不能因同场就推断亲密关系。
    """.trimIndent()

    fun systemPrompt(): String = GUIDE

    fun messages(chat: Chat): List<Message> = chat.messages.filter { message ->
        !message.contextExcluded && message.status !in setOf("error", "streaming", "stopped") && message.role in setOf("user", "assistant") &&
            (message.content.isNotBlank() || message.attachments.any(HistorySource::isTextAttachment))
    }

    /** Builds one bounded request. The caller supplies a chronological source batch. */
    fun requestText(draft: ScenarioHistoryPatch, sources: List<Message>, existingMemory: List<KnowledgeNote> = emptyList()): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("currentDraft", toJson(draft))
            .put("sources", JSONArray(sources.map { HistorySource.memoryText(it) }))
        if (existingMemory.isNotEmpty()) {
            root.put("existingMemory", JSONArray(existingMemory.map { note ->
                JSONObject()
                    .put("title", note.title)
                    .put("entity", note.entity)
                    .put("text", note.text)
                    .put("sourceMessageIds", JSONArray(note.sourceMessageIds))
                    .put("evidenceId", note.id)
                    .put("evidenceKind", note.sourceKind)
            }))
        }
        return root.toString()
    }

    /**
     * Returns source-validated old notes for the organizer.  Notes are sent as
     * bounded evidence, while their complete text remains local and readable in
     * the vault.  Older entries come first so later entries can override them.
     */
    fun memoryEvidence(chat: Chat, tokenBudget: Int): List<KnowledgeNote> {
        if (tokenBudget < 256) return emptyList()
        val positions = chat.messages.mapIndexed { index, message -> message.id to index }.toMap()
        val messages = chat.messages.associateBy { it.id }
        val candidates = KnowledgeVault.validNotes(chat).filter { note ->
            !note.isResearch && note.sourceMessageIds.isNotEmpty() && note.text.isNotBlank() &&
                // Organizer notes are durable evidence in their own right. A
                // later compact marks the original messages contextExcluded,
                // but the source hash still proves which text produced this
                // note. Keep those notes available so early relationships and
                // scene facts do not disappear after the next compression.
                (note.title.startsWith(NOTE_TITLE_PREFIX) || note.sourceMessageIds.all { id -> messages[id]?.contextExcluded == false })
        }
        // A valid summary is already hash-bound to the complete source prefix.
        // Its raw messages are expected to become contextExcluded after
        // compression, so do not discard the summary merely because that
        // prefix is no longer sent verbatim on every request.
        val summaryCandidates = KnowledgeVault.validSummaries(chat).filter { it.text.isNotBlank() }.map { summary ->
            KnowledgeNote(
                id = summary.id,
                text = summary.text,
                sourceMessageIds = emptyList(),
                sourceHash = summary.sourceHash,
                createdAt = summary.createdAt,
                modelId = summary.modelId,
                sourceKind = "summary",
                title = "历史续聊摘要",
                kind = "summary"
            )
        }
        val allCandidates = (candidates + summaryCandidates).sortedWith(compareBy<KnowledgeNote> {
            val end = if (it.sourceKind == "summary") chat.messages.indexOfFirst { message -> message.id == chat.summaries.firstOrNull { summary -> summary.id == it.id }?.throughMessageId } else
                it.sourceMessageIds.mapNotNull(positions::get).maxOrNull() ?: -1
            end
        }.thenBy { it.createdAt }.thenBy { it.id })
        // Keep the beginning and the newest entries when the vault is larger
        // than the evidence budget.  A chronological prefix alone silently
        // drops the latest relationship/state corrections.
        val targetCount = minOf(80, allCandidates.size, maxOf(1, tokenBudget / 180))
        val selected = if (allCandidates.size <= targetCount) allCandidates else {
            val indexes = linkedSetOf<Int>()
            listOf(0, 1, allCandidates.lastIndex - 1, allCandidates.lastIndex).forEach { index ->
                if (index in allCandidates.indices) indexes += index
            }
            var cursor = 0
            while (indexes.size < targetCount) {
                indexes += ((cursor.toDouble() / targetCount) * allCandidates.lastIndex).toInt().coerceIn(0, allCandidates.lastIndex)
                cursor++
            }
            indexes.sorted().map { allCandidates[it] }
        }
        val result = mutableListOf<KnowledgeNote>()
        var used = 0
        var remaining = selected.size
        selected.forEach { note ->
            val overhead = ContextEngine.estimateTokens(JSONObject()
                .put("title", note.title).put("entity", note.entity)
                .put("evidenceId", note.id).put("evidenceKind", note.sourceKind)
                .put("sourceMessageIds", JSONArray(note.sourceMessageIds)).toString()) + 8
            val textBudget = ((tokenBudget - used - overhead) / remaining.coerceAtLeast(1)).coerceAtMost(1400)
            remaining--
            if (textBudget < 96) return@forEach
            val boundedText = KnowledgeVault.bounded(note.text, textBudget)
            if (boundedText.isBlank()) return@forEach
            val copy = note.copy(text = boundedText)
            result += copy
            used += ContextEngine.estimateTokens(JSONObject()
                .put("title", copy.title).put("entity", copy.entity).put("text", boundedText)
                .put("evidenceId", copy.id).put("evidenceKind", copy.sourceKind)
                .put("sourceMessageIds", JSONArray(copy.sourceMessageIds)).toString()) + 8
        }
        return result
    }

    /** Parse only the fields in the protocol and reject source ids outside this request. */
    fun parse(content: String, allowedIds: Set<String>, allowedSummaryIds: Set<String> = emptySet()): ScenarioHistoryPatch {
        val problem = "历史整理结果格式无效或引用了未提供的原文；旧剧本状态未修改"
        try {
            var text = content.trim()
            if (text.startsWith("```json") && text.endsWith("```")) text = text.removePrefix("```json").removeSuffix("```").trim()
            else if (text.startsWith("```") && text.endsWith("```")) text = text.removePrefix("```").removeSuffix("```").trim()
            val root = JSONTokener(text).nextValue() as? JSONObject ?: error(problem)
            val ids = readStrings(root.optJSONArray("sourceMessageIds"), 200)
            val summaryIds = readStrings(root.optJSONArray("sourceSummaryIds"), 80)
            require(ids.distinct().size == ids.size && ids.all { it in allowedIds } &&
                summaryIds.distinct().size == summaryIds.size && summaryIds.all { it in allowedSummaryIds }) { problem }
            val stateObject = root.optJSONObject("state") ?: JSONObject()
            val state = ScenarioState(
                location = field(stateObject, "location"),
                time = field(stateObject, "time"),
                systemLevel = field(stateObject, "systemLevel"),
                resources = readResources(stateObject.optJSONArray("resources")),
                options = readOptions(stateObject.optJSONArray("options")),
                upgrades = readUpgrades(stateObject.optJSONArray("upgrades")),
                quests = readQuests(stateObject.optJSONArray("quests")),
                note = field(stateObject, "note", 1000),
                source = "历史对话整理"
            )
            val people = readPeople(root.optJSONArray("people"))
            require(ids.isNotEmpty() || summaryIds.isNotEmpty() || (state.isEmptyPatch() && people.isEmpty())) { problem }
            return ScenarioHistoryPatch(state, people, ids, summaryIds)
        } catch (error: Exception) {
            if (error is IllegalArgumentException && error.message == problem) throw error
            throw IllegalArgumentException(problem, error)
        }
    }

    /** Merge chronological patches; blank fields and empty lists never erase prior facts. */
    fun merge(base: ScenarioHistoryPatch, delta: ScenarioHistoryPatch): ScenarioHistoryPatch = ScenarioHistoryPatch(
        state = mergeState(base.state, delta.state),
        people = mergePeople(base.people, delta.people),
        sourceMessageIds = (base.sourceMessageIds + delta.sourceMessageIds).distinct(),
        sourceSummaryIds = (base.sourceSummaryIds + delta.sourceSummaryIds).distinct()
    )

    fun mergeInto(base: Chat, patch: ScenarioHistoryPatch): Chat {
        val oldState = base.scenarioState
        val state = mergeState(oldState, patch.state).copy(
            updatedAt = System.currentTimeMillis(),
            source = "历史对话整理"
        )
        val roster = mergePeople(base.scenarioRoster, patch.people)
        val notes = scenarioNotes(base, patch)
        if (notes.isEmpty()) return base.copy(scenarioState = state, scenarioRoster = roster)

        // These entries represent the current panel snapshot. Replace the old
        // organizer snapshot as one set when the source changes, while keeping
        // each entry's ID stable for links already opened in Obsidian.
        val oldOrganizerNotes = base.knowledgeNotes.filter { it.title.startsWith(NOTE_TITLE_PREFIX) }
        val refreshed = notes.map { generated ->
            val previous = oldOrganizerNotes.firstOrNull { current ->
                current.sourceKind == "conversation" && current.title == generated.title &&
                    current.kind == generated.kind && current.entity == generated.entity
            }
            if (previous == null) generated else generated.copy(id = previous.id, createdAt = previous.createdAt)
        }
        val existing = base.knowledgeNotes.filterNot { it.title.startsWith(NOTE_TITLE_PREFIX) } + refreshed
        return base.copy(scenarioState = state, scenarioRoster = roster, knowledgeNotes = existing)
    }

    /**
     * Turns an extracted panel delta into source-bound knowledge notes. The
     * notes intentionally use the same conversation source contract as compact
     * memory, so KnowledgeVault.validNotes and Obsidian export can audit them.
     */
    fun scenarioNotes(chat: Chat, patch: ScenarioHistoryPatch): List<KnowledgeNote> {
        val ids = patch.sourceMessageIds.distinct().toMutableList()
        val byId = chat.messages.associateBy { it.id }
        if (ids.any { it !in byId }) return emptyList()
        val validSummaries = KnowledgeVault.validSummaries(chat).filter { it.id in patch.sourceSummaryIds }
        validSummaries.forEach { summary ->
            val end = chat.messages.indexOfFirst { it.id == summary.throughMessageId }
            if (end >= 0 && chat.messages.take(end + 1).none { it.contextExcluded }) {
                ids += chat.messages.take(end + 1).map { it.id }
            }
        }
        val allIds = ids.distinct()
        if (allIds.isEmpty()) return emptyList()
        // Always hash and store IDs in the chat's chronological order. This is
        // the exact order expected by KnowledgeVault.validNotes when rechecking
        // the source span after an edit or import.
        val sourceMessages = chat.messages.filter { it.id in allIds }
        if (sourceMessages.isEmpty()) return emptyList()
        val sourceIds = sourceMessages.map { it.id }
        val sourceHash = ContextEngine.sourceHash(sourceMessages)
        val notes = mutableListOf<KnowledgeNote>()

        if (!patch.state.isEmptyPatch()) {
            val state = patch.state
            val text = buildString {
                append("本条记忆来自历史对话整理，只记录原文中明确确认的剧本状态。\n\n")
                if (state.location.isNotBlank()) append("- 位置：").append(state.location).append('\n')
                if (state.time.isNotBlank()) append("- 时间：").append(state.time).append('\n')
                if (state.systemLevel.isNotBlank()) append("- 系统/等级：").append(state.systemLevel).append('\n')
                if (state.note.isNotBlank()) append("- 状态备注：").append(state.note).append('\n')
                if (state.resources.isNotEmpty()) {
                    append("\n资源：\n")
                    state.resources.forEach { append("- ").append(it.name).append("：").append(it.value)
                        .append(if (it.unit.isBlank()) "" else " ${it.unit}")
                        .append(if (it.note.isBlank()) "" else "（${it.note}）").append('\n') }
                }
                if (state.options.isNotEmpty()) {
                    append("\n兑换选项：\n")
                    state.options.forEach { append("- ").append(it.name).append("；消耗：").append(it.cost)
                        .append("；要求：").append(it.requirement).append("；结果：").append(it.result)
                        .append(if (it.available) "；可用" else "；不可用").append('\n') }
                }
                if (state.upgrades.isNotEmpty()) {
                    append("\n升级路线：\n")
                    state.upgrades.forEach { append("- ").append(it.level).append(' ').append(it.name)
                        .append("；状态：").append(it.status).append("；消耗：").append(it.cost)
                        .append("；要求：").append(it.requirement).append("；收益：").append(it.benefit).append('\n') }
                }
                if (state.quests.isNotEmpty()) {
                    append("\n任务：\n")
                    state.quests.forEach { append("- ").append(it.title).append("；状态：").append(it.status)
                        .append("；进度：").append(it.progress).append("；说明：").append(it.description)
                        .append("；奖励：").append(it.reward).append('\n') }
                }
            }.trim()
            if (text.isNotBlank()) notes += KnowledgeNote(
                text = text.take(MAX_SCENARIO_NOTE_CHARS), sourceMessageIds = sourceIds,
                sourceHash = sourceHash, modelId = "scenario-history-organizer",
                sourceKind = "conversation", title = NOTE_TITLE_PREFIX + "剧本状态",
                kind = "fact", entity = "剧本状态"
            )
        }

        patch.people.filter { it.name.isNotBlank() }.forEach { person ->
            val text = buildString {
                append("本条记忆来自历史对话整理，只记录原文中明确确认的“").append(person.name).append("”状态。\n\n")
                fun line(label: String, value: String) { if (value.isNotBlank()) append("- ").append(label).append("：").append(value).append('\n') }
                line("角色/岗位", person.role); line("与主角关系", person.relationship)
                line("能力", person.ability); line("性格", person.personality)
                line("当前活动", person.activity); line("当前穿着", person.clothing)
                line("状态", person.status); line("补充描述", person.description)
            }.trim()
            if (text.isNotBlank()) notes += KnowledgeNote(
                text = text.take(MAX_SCENARIO_NOTE_CHARS), sourceMessageIds = sourceIds,
                sourceHash = sourceHash, modelId = "scenario-history-organizer",
                sourceKind = "conversation", title = NOTE_TITLE_PREFIX + "人物·" + person.name,
                kind = "person", entity = person.name
            )
        }
        return notes
    }

    private const val MAX_SCENARIO_NOTE_CHARS = 24_000

    private fun mergeState(old: ScenarioState, delta: ScenarioState): ScenarioState = ScenarioState(
        location = delta.location.ifBlank { old.location },
        time = delta.time.ifBlank { old.time },
        systemLevel = delta.systemLevel.ifBlank { old.systemLevel },
        resources = mergeByName(old.resources, delta.resources) { it.name },
        options = mergeByName(old.options, delta.options) { it.name },
        upgrades = mergeByName(old.upgrades, delta.upgrades) { listOf(it.level, it.name).joinToString("|") },
        quests = mergeByName(old.quests, delta.quests) { it.title },
        note = delta.note.ifBlank { old.note },
        updatedAt = delta.updatedAt.takeIf { it > 0 } ?: old.updatedAt,
        source = delta.source.ifBlank { old.source }
    )

    private fun mergePeople(old: List<ScenarioPerson>, delta: List<ScenarioPerson>): List<ScenarioPerson> {
        val result = old.toMutableList()
        delta.filter { it.name.isNotBlank() }.forEach { next ->
            val index = result.indexOfFirst { normalize(it.name) == normalize(next.name) }
            if (index < 0) result += next.copy(source = next.source.ifBlank { "历史对话整理" })
            else result[index] = mergePerson(result[index], next)
        }
        return result.take(100)
    }

    private fun mergePerson(old: ScenarioPerson, next: ScenarioPerson): ScenarioPerson = old.copy(
        role = next.role.ifBlank { old.role }, relationship = next.relationship.ifBlank { old.relationship },
        ability = next.ability.ifBlank { old.ability }, activity = next.activity.ifBlank { old.activity },
        clothing = next.clothing.ifBlank { old.clothing }, personality = next.personality.ifBlank { old.personality },
        description = next.description.ifBlank { old.description }, status = next.status.ifBlank { old.status },
        source = "历史对话整理"
    )

    private fun <T> mergeByName(old: List<T>, delta: List<T>, key: (T) -> String): List<T> {
        val result = old.toMutableList()
        delta.filter { key(it).isNotBlank() }.forEach { next ->
            val index = result.indexOfFirst { normalize(key(it)) == normalize(key(next)) }
            if (index < 0) result += next
            else result[index] = mergeValue(result[index], next)
        }
        return result.take(100)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> mergeValue(old: T, next: T): T = when {
        old is ScenarioResource && next is ScenarioResource -> old.copy(
            value = next.value.ifBlank { old.value }, unit = next.unit.ifBlank { old.unit }, note = next.note.ifBlank { old.note }
        ) as T
        old is ScenarioOption && next is ScenarioOption -> old.copy(
            cost = next.cost.ifBlank { old.cost }, requirement = next.requirement.ifBlank { old.requirement },
            result = next.result.ifBlank { old.result }, available = next.available
        ) as T
        old is ScenarioUpgrade && next is ScenarioUpgrade -> old.copy(
            level = next.level.ifBlank { old.level }, cost = next.cost.ifBlank { old.cost },
            requirement = next.requirement.ifBlank { old.requirement }, benefit = next.benefit.ifBlank { old.benefit },
            status = next.status.ifBlank { old.status }
        ) as T
        old is ScenarioQuest && next is ScenarioQuest -> old.copy(
            status = next.status.ifBlank { old.status }, progress = next.progress.ifBlank { old.progress },
            description = next.description.ifBlank { old.description }, reward = next.reward.ifBlank { old.reward }
        ) as T
        else -> next
    }

    private fun ScenarioState.isEmptyPatch(): Boolean = location.isBlank() && time.isBlank() && systemLevel.isBlank() &&
        resources.isEmpty() && options.isEmpty() && upgrades.isEmpty() && quests.isEmpty() && note.isBlank()

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun readResources(array: JSONArray?): List<ScenarioResource> = (0 until (array?.length() ?: 0)).mapNotNull { i ->
        array?.optJSONObject(i)?.let { o -> ScenarioResource(name = field(o, "name", 160), value = field(o, "value"), unit = field(o, "unit", 80), note = field(o, "note")) }
    }.filter { it.name.isNotBlank() }.take(100)

    private fun readOptions(array: JSONArray?): List<ScenarioOption> = (0 until (array?.length() ?: 0)).mapNotNull { i ->
        array?.optJSONObject(i)?.let { o -> ScenarioOption(name = field(o, "name", 160), cost = field(o, "cost"), requirement = field(o, "requirement"), result = field(o, "result"), available = if (o.has("available")) o.optBoolean("available") else true) }
    }.filter { it.name.isNotBlank() }.take(100)

    private fun readUpgrades(array: JSONArray?): List<ScenarioUpgrade> = (0 until (array?.length() ?: 0)).mapNotNull { i ->
        array?.optJSONObject(i)?.let { o -> ScenarioUpgrade(name = field(o, "name", 160), level = field(o, "level", 80), cost = field(o, "cost"), requirement = field(o, "requirement"), benefit = field(o, "benefit"), status = field(o, "status", 120)) }
    }.filter { it.name.isNotBlank() }.take(100)

    private fun readQuests(array: JSONArray?): List<ScenarioQuest> = (0 until (array?.length() ?: 0)).mapNotNull { i ->
        array?.optJSONObject(i)?.let { o -> ScenarioQuest(title = field(o, "title", 180), status = field(o, "status", 120), progress = field(o, "progress"), description = field(o, "description", 600), reward = field(o, "reward")) }
    }.filter { it.title.isNotBlank() }.take(100)

    private fun readPeople(array: JSONArray?): List<ScenarioPerson> = (0 until (array?.length() ?: 0)).mapNotNull { i ->
        array?.optJSONObject(i)?.let { o -> ScenarioPerson(name = field(o, "name", 160), role = field(o, "role"), relationship = field(o, "relationship"), ability = field(o, "ability"), activity = field(o, "activity"), clothing = field(o, "clothing"), personality = field(o, "personality"), description = field(o, "description", 600), status = field(o, "status", 120), source = "历史对话整理") }
    }.filter { it.name.isNotBlank() }.distinctBy { normalize(it.name) }.take(100)

    private fun readStrings(array: JSONArray?, max: Int): List<String> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it)?.trim()?.takeIf(String::isNotBlank) }.take(max)

    private fun field(objectValue: JSONObject, key: String, max: Int = 420): String = objectValue.optString(key).trim().take(max)

    private fun toJson(patch: ScenarioHistoryPatch): JSONObject = JSONObject().put("state", stateJson(patch.state)).put("people", JSONArray(patch.people.take(40).map { personJson(it) })).put("sourceMessageIds", JSONArray(patch.sourceMessageIds)).put("sourceSummaryIds", JSONArray(patch.sourceSummaryIds))
    private fun stateJson(state: ScenarioState): JSONObject = JSONObject().put("location", state.location.take(420)).put("time", state.time.take(160)).put("systemLevel", state.systemLevel.take(240)).put("resources", JSONArray(state.resources.take(60).map { JSONObject().put("name", it.name.take(160)).put("value", it.value.take(420)).put("unit", it.unit.take(80)).put("note", it.note.take(420)) })).put("options", JSONArray(state.options.take(60).map { JSONObject().put("name", it.name.take(160)).put("cost", it.cost.take(420)).put("requirement", it.requirement.take(420)).put("result", it.result.take(420)).put("available", it.available) })).put("upgrades", JSONArray(state.upgrades.take(60).map { JSONObject().put("name", it.name.take(160)).put("level", it.level.take(80)).put("cost", it.cost.take(420)).put("requirement", it.requirement.take(420)).put("benefit", it.benefit.take(420)).put("status", it.status.take(120)) })).put("quests", JSONArray(state.quests.take(60).map { JSONObject().put("title", it.title.take(180)).put("status", it.status.take(120)).put("progress", it.progress.take(420)).put("description", it.description.take(600)).put("reward", it.reward.take(420)) })).put("note", state.note.take(1000))
    private fun personJson(person: ScenarioPerson): JSONObject = JSONObject().put("name", person.name.take(160)).put("role", person.role.take(420)).put("relationship", person.relationship.take(420)).put("ability", person.ability.take(420)).put("activity", person.activity.take(420)).put("clothing", person.clothing.take(420)).put("personality", person.personality.take(420)).put("description", person.description.take(600)).put("status", person.status.take(120))
}
