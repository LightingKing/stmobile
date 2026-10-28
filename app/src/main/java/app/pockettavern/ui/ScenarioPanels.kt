@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pockettavern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import app.pockettavern.domain.*
import app.pockettavern.engine.ScenarioHistoryOrganizer

/** A compact, bounded view of the world-book facts used by a scenario. */
internal data class ScenarioSourceSection(val title: String, val content: String, val kind: String)

internal object ScenarioPanelData {
    private val statusNameWords = Regex("状态|系统|文明|资源|进化|升级|任务|兑换|路线|账本|建筑|库存|庄园开局", RegexOption.IGNORE_CASE)
    private val statusHeadings = Regex("【本故事开局记录】|【演进账本】|【系统等级】|【文明点与建设】|【稳定模块报价与边界】|【空间进化树")
    private val rosterWords = Regex("人员与合作名册|开局在场|轮班|安保|角色：")
    private val personHeader = Regex("【角色：([^】]+)】")
    private val scenarioEntryWords = Regex("状态|系统|文明|资源|进化|升级|任务|兑换|路线|账本|建筑|库存|庄园|角色：|开局在场|轮班|名册|关系", RegexOption.IGNORE_CASE)

    /**
     * Resolve the scenario book defensively.  Imported chats often keep an old
     * character id after a card is replaced, while the world-book entries still
     * contain all the facts the user expects to see.  Prefer the normal scoped
     * set, then fall back to entries that clearly belong to a scenario rather
     * than showing an unrelated character's book.
     */
    fun entries(chat: Chat, state: UiSession): List<WorldEntry> {
        val all = state.data.worldEntries
        val enabled = all.filter { it.enabled }
        val scoped = enabled.filter { it.characterId.isBlank() || it.characterId == chat.characterId }
        val scenario = enabled.filter { entry ->
            scenarioEntryWords.containsMatchIn(entry.name) ||
                statusHeadings.containsMatchIn(entry.content) ||
                personHeader.containsMatchIn(entry.content)
        }
        val disabledScenario = all.filter { !it.enabled && (
            scenarioEntryWords.containsMatchIn(it.name) ||
                statusHeadings.containsMatchIn(it.content) ||
                personHeader.containsMatchIn(it.content) ||
                it.content.contains("【唯一关系名册】")
            ) }
        val selected = when {
            scoped.any { scenarioEntryWords.containsMatchIn(it.name) || statusHeadings.containsMatchIn(it.content) || personHeader.containsMatchIn(it.content) } -> scoped
            scenario.isNotEmpty() -> scenario
            disabledScenario.isNotEmpty() -> disabledScenario
            else -> scoped
        }
        return selected.sortedWith(compareByDescending<WorldEntry> { it.always }.thenByDescending { it.priority })
    }

    fun statusSections(entries: List<WorldEntry>): List<ScenarioSourceSection> = entries
        .filter { statusNameWords.containsMatchIn(it.name) || statusHeadings.containsMatchIn(it.content) || it.name.startsWith(ScenarioHistoryOrganizer.NOTE_TITLE_PREFIX) }
        .distinctBy { it.name }
        .take(24)
        .map { ScenarioSourceSection(it.name, it.content.trim(), "worldbook") }

    /** Turns the well-formed opening ledger in a card into a readable table. */
    fun baselineState(entries: List<WorldEntry>): ScenarioState {
        val opening = entries.firstOrNull { it.name == "剧情状态与用户选择" }?.content.orEmpty()
            .ifBlank { entries.firstOrNull { section(it.content, "本故事开局记录") != null }?.content.orEmpty() }
        val fixed = entries.firstOrNull { it.name == "庄园人员与合作名册" }?.content.orEmpty()
            .ifBlank { entries.firstOrNull { it.name.contains("庄园固定口径") }?.content.orEmpty() }
            .ifBlank { entries.firstOrNull { it.content.contains("正式雇员") || it.content.contains("常住人口") }?.content.orEmpty() }
        val system = entries.firstOrNull { it.name.contains("零号文明系统") }?.content.orEmpty()
            .ifBlank { entries.firstOrNull { it.content.contains("系统等级") || it.content.contains("文明点") }?.content.orEmpty() }
        val evolution = entries.firstOrNull { it.name.contains("空间进化树") }?.content.orEmpty()
            .ifBlank { entries.firstOrNull { it.content.contains("进化树") || it.content.contains("升级路线") }?.content.orEmpty() }
        val openingRecord = section(opening, "本故事开局记录").orEmpty()
        val time = Regex("(\\d{4}年\\d+月\\d+日\\d{1,2}:\\d{2})").find(openingRecord)?.groupValues?.getOrNull(1).orEmpty()
        val location = openingRecord.substringAfter("，", "").substringBefore("。", "").trim()
        val resources = mutableListOf<ScenarioResource>()
        fun addResource(name: String, value: String, note: String) {
            if (value.isBlank() || resources.any { it.name == name }) return
            val unit = when (name) {
                "土地面积" -> "亩"
                "现有建筑面积" -> "万平方米"
                "正式雇员", "常住人口", "开局当班员工", "未当班员工", "开局在园", "直播合作方" -> "人"
                "遥收范围" -> "米"
                "安全跃迁范围" -> "公里"
                else -> ""
            }
            resources += ScenarioResource(name = name, value = value.trim(), unit = unit, note = note)
        }
        listOf("现金", "文明点", "晶核").forEach { label ->
            Regex("${Regex.escape(label)}\\s*([^，；。\\n]+)").find(openingRecord)?.groupValues?.getOrNull(1)?.let { value ->
                addResource(label, value, "开局记录 · 后续以实际剧情登记为准")
            }
        }
        // The roster and capability ledgers contain authoritative counts that
        // used to be left as prose, which made the panel appear incomplete.
        listOf(
            "土地面积" to Regex("庄园\\s*(\\d+(?:\\.\\d+)?)亩"),
            "现有建筑面积" to Regex("现有建筑\\s*(\\d+(?:\\.\\d+)?)万平方米"),
            "正式雇员" to Regex("正式雇员\\s*(\\d+)人"),
            "常住人口" to Regex("常住(?:人口)?\\s*(\\d+)人"),
            "开局当班员工" to Regex("当班员工\\s*(\\d+)人"),
            "未当班员工" to Regex("未当班员工\\s*(\\d+)人"),
            "开局在园" to Regex("开局在园\\s*(\\d+)人"),
            "直播合作方" to Regex("拍摄直播合作方\\s*(\\d+)人"),
            "个人仓储上限" to Regex("仓储上限\\s*([^，；。\\n]+)"),
            "遥收范围" to Regex("(\\d+)米遥收"),
            "安全跃迁范围" to Regex("(\\d+)公里安全跃迁")
        ).forEach { (label, pattern) ->
            pattern.find(fixed + "\n" + opening + "\n" + system)?.groupValues?.getOrNull(1)?.let { value ->
                addResource(label, value, "剧本固定口径 · 不是当前剧情变动")
            }
        }
        val options = buildList {
            Regex("T\\d+[^。；\\n]{0,100}?报价\\s*([^。；\\n]+)").findAll(system).take(8).forEach { match ->
                add(ScenarioOption(name = match.value.substringBefore("报价").trim(), cost = match.groupValues[1].trim(), requirement = "未来建造目录；开局未建成", result = "按剧本蓝图交付，需用户明确授权"))
            }
            Regex("([\\u4e00-\\u9fffA-Za-z0-9·]+模块)报价\\s*([^，；。]+)").findAll(system).take(12).forEach { match -> add(ScenarioOption(name = match.groupValues[1], cost = match.groupValues[2].trim(), requirement = "按世界书前置条件", result = "详见原始设定")) }
        }
        val upgrades = buildList {
            val levelText = section(system, "系统等级").orEmpty()
            Regex("L[1-6]·[^。]+") .findAll(levelText).take(6).forEach { match ->
                val text = match.value.trim()
                add(ScenarioUpgrade(name = text.substringAfter('·').substringBefore("要求").trim(), level = text.substringBefore('·'), requirement = text.substringAfter("要求", "").trim(), status = if (text.startsWith("L1")) "已绑定" else "未解锁"))
            }
            Regex("(?:I|II|III|IV|V|VI)·[^；。]+") .findAll(evolution).take(12).forEach { match ->
                val text = match.value.trim()
                add(ScenarioUpgrade(name = text.substringAfter('·').trim(), level = text.substringBefore('·'), status = "待按正文确认") )
            }
        }
        val quests = buildList {
            if (system.contains("T1十万平方米首期综合基地")) add(ScenarioQuest(title = "建造 T1 首期综合基地", status = "未开始", description = "需要用户明确授权；蓝图和报价不等于已建成", reward = "建立独立供能、供水、恒温、生态、制造和医疗闭环"))
            val levelText = section(system, "系统等级").orEmpty()
            Regex("L([2-6])·([^。\\n]+)").findAll(levelText).take(5).forEach { match ->
                add(ScenarioQuest(title = "系统升级 · L${match.groupValues[1]}", status = "未开始", progress = "前置条件待完成", description = match.groupValues[2].trim(), reward = "解锁对应系统能力"))
            }
            if (isEmpty()) add(ScenarioQuest(title = "待确认的剧情任务", status = "暂无已确认记录", description = "开局账本没有登记后续任务；需要在剧情中实际授权并完成后更新。"))
        }
        val notes = listOf(
            section(opening, "现实库存"), section(opening, "演进账本"), section(fixed, "唯一关系名册")
        ).filterNot { it.isNullOrBlank() }.joinToString("\n") { it!!.replace(Regex("\\s+"), " ").take(520) }
        return ScenarioState(location = location, time = time, systemLevel = if (system.contains("L1·随身顾问")) "L1·随身顾问（初始绑定）" else "", resources = resources, options = options, upgrades = upgrades, quests = quests, note = notes, source = "剧本世界书基线")
    }

    /** Overlay confirmed conversation values on top of the card's complete baseline. */
    fun mergeState(baseline: ScenarioState, current: ScenarioState): ScenarioState {
        fun <T> merge(existing: List<T>, incoming: List<T>, key: (T) -> String, combine: (T, T) -> T): List<T> {
            val map = LinkedHashMap<String, T>()
            existing.forEach { item -> key(item).takeIf(String::isNotBlank)?.let { map[it] = item } }
            incoming.forEach { item -> key(item).takeIf(String::isNotBlank)?.let { id -> map[id] = map[id]?.let { old -> combine(old, item) } ?: item } }
            return map.values.toList()
        }
        return baseline.copy(
            location = current.location.ifBlank { baseline.location }, time = current.time.ifBlank { baseline.time },
            systemLevel = current.systemLevel.ifBlank { baseline.systemLevel }, note = current.note.ifBlank { baseline.note },
            resources = merge(baseline.resources, current.resources, { it.name }) { old, next -> old.copy(value = next.value.ifBlank { old.value }, unit = next.unit.ifBlank { old.unit }, note = next.note.ifBlank { old.note }) },
            options = merge(baseline.options, current.options, { it.name }) { old, next -> old.copy(cost = next.cost.ifBlank { old.cost }, requirement = next.requirement.ifBlank { old.requirement }, result = next.result.ifBlank { old.result }, available = next.available) },
            upgrades = merge(baseline.upgrades, current.upgrades, { listOf(it.level, it.name).joinToString("|") }) { old, next -> old.copy(level = next.level.ifBlank { old.level }, cost = next.cost.ifBlank { old.cost }, requirement = next.requirement.ifBlank { old.requirement }, benefit = next.benefit.ifBlank { old.benefit }, status = next.status.ifBlank { old.status }) },
            quests = merge(baseline.quests, current.quests, { it.title }) { old, next -> old.copy(status = next.status.ifBlank { old.status }, progress = next.progress.ifBlank { old.progress }, description = next.description.ifBlank { old.description }, reward = next.reward.ifBlank { old.reward }) },
            updatedAt = current.updatedAt.takeIf { it > 0 } ?: baseline.updatedAt,
            source = if (current.source.isNotBlank()) "剧本基线 + 已确认动态" else baseline.source
        )
    }

    /** Recover structured panel facts written to the local knowledge vault. */
    fun historyState(notes: List<KnowledgeNote>): ScenarioState {
        var result = ScenarioState()
        notes.filter { it.title == ScenarioHistoryOrganizer.NOTE_TITLE_PREFIX + "剧本状态" && it.text.isNotBlank() }
            .sortedBy { it.createdAt }
            .forEach { note ->
                val text = note.text
                fun field(label: String): String = Regex("(?m)^[-•]\\s*${Regex.escape(label)}[：:]\\s*(.+)$").find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                fun rows(header: String): List<String> {
                    val start = text.indexOf(header)
                    if (start < 0) return emptyList()
                    val tail = text.substring(start + header.length)
                    return tail.lineSequence().dropWhile { it.isBlank() }.takeWhile { line -> line.trim().startsWith("-") }.toList()
                }
                val resources = rows("资源：").mapNotNull { line ->
                    val body = line.removePrefix("-").trim(); val split = body.split("：", limit = 2)
                    split.takeIf { it.size == 2 }?.let { ScenarioResource(name = it[0].trim(), value = it[1].trim().substringBefore("（"), note = it[1].substringAfter("（", "").substringBeforeLast("）", "")) }
                }
                val options = rows("兑换选项：").mapNotNull { line ->
                    val body = line.removePrefix("-").trim(); val name = body.substringBefore("；").trim()
                    name.takeIf { it.isNotBlank() }?.let { ScenarioOption(name = it, cost = body.substringAfter("消耗：", "").substringBefore("；"), requirement = body.substringAfter("要求：", "").substringBefore("；"), result = body.substringAfter("结果：", "").substringBefore("；"), available = !body.contains("不可用")) }
                }
                val upgrades = rows("升级路线：").mapNotNull { line ->
                    val body = line.removePrefix("-").trim(); val name = body.substringBefore("；").trim()
                    name.takeIf { it.isNotBlank() }?.let { ScenarioUpgrade(name = it, level = it.substringBefore(' ').takeIf { v -> v.matches(Regex("[A-Z]+\\d+")) }.orEmpty(), status = body.substringAfter("状态：", "").substringBefore("；"), cost = body.substringAfter("消耗：", "").substringBefore("；"), requirement = body.substringAfter("要求：", "").substringBefore("；"), benefit = body.substringAfter("收益：", "").substringBefore("；")) }
                }
                val quests = rows("任务：").mapNotNull { line ->
                    val body = line.removePrefix("-").trim(); val name = body.substringBefore("；").trim()
                    name.takeIf { it.isNotBlank() }?.let { ScenarioQuest(title = it, status = body.substringAfter("状态：", "").substringBefore("；"), progress = body.substringAfter("进度：", "").substringBefore("；"), description = body.substringAfter("说明：", "").substringBefore("；"), reward = body.substringAfter("奖励：", "").substringBefore("；")) }
                }
                result = mergeState(result, ScenarioState(location = field("位置"), time = field("时间"), systemLevel = field("系统/等级"), resources = resources, options = options, upgrades = upgrades, quests = quests, note = field("状态备注"), source = "历史知识库"))
            }
        return result
    }

    fun historyPeople(notes: List<KnowledgeNote>): List<ScenarioPerson> = notes
        .filter { it.title.startsWith(ScenarioHistoryOrganizer.NOTE_TITLE_PREFIX + "人物·") && it.text.isNotBlank() }
        .sortedBy { it.createdAt }
        .mapNotNull { note ->
            val name = note.title.removePrefix(ScenarioHistoryOrganizer.NOTE_TITLE_PREFIX + "人物·").trim()
            if (name.isBlank()) return@mapNotNull null
            fun field(label: String) = Regex("(?m)^[-•]\\s*${Regex.escape(label)}[：:]\\s*(.+)$").find(note.text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            ScenarioPerson(name = name, role = field("角色/岗位"), relationship = field("与主角关系"), ability = field("能力"), personality = field("性格"), activity = field("当前活动"), clothing = field("当前穿着"), status = field("状态").ifBlank { "历史记录" }, description = field("补充描述"), source = "历史知识库")
        }.distinctBy { it.name }

    fun rosterSections(entries: List<WorldEntry>): List<ScenarioSourceSection> = entries
        .filter { rosterWords.containsMatchIn(it.name) || personHeader.containsMatchIn(it.content) }
        .distinctBy { it.name }
        .take(80)
        .map { ScenarioSourceSection(it.name, it.content.trim(), "worldbook") }

    /** Extracts named person cards without treating their prose as a verified live status. */
    fun people(entries: List<WorldEntry>): List<ScenarioPerson> = entries.mapNotNull { entry ->
        val organizerPerson = entry.name.removePrefix(ScenarioHistoryOrganizer.NOTE_TITLE_PREFIX).removePrefix("人物·").takeIf {
            entry.name.startsWith(ScenarioHistoryOrganizer.NOTE_TITLE_PREFIX + "人物·") && it.isNotBlank()
        }
        val name = personHeader.find(entry.content)?.groupValues?.getOrNull(1)?.trim()
            ?: organizerPerson
            ?: entry.name.takeIf { personHeader.containsMatchIn(entry.content) }
            ?: return@mapNotNull null
        val role = section(entry.content, "庄园岗位与初始状态") ?: section(entry.content, "本作定位")
        val visual = section(entry.content, "视觉") ?: section(entry.content, "视觉外形") ?: inlineField(entry.content, "视觉") ?: inlineField(entry.content, "视觉外形") ?: section(entry.content, "开局衣着方案")
        val ability = section(entry.content, "身份") ?: section(entry.content, "能力") ?: bullet(entry.content, "能力")
        val personality = section(entry.content, "小说性格与价值观") ?: section(entry.content, "性格") ?: bullet(entry.content, "性格")
        val rosterText = entries.firstOrNull { it.name == "庄园开局在场与轮班" }?.content.orEmpty()
        val liveSentence = rosterText.split(Regex("[。；]")).firstOrNull { it.contains(name) && it.contains(Regex("在|负责|当班|未当班|处理")) }.orEmpty().trim()
        val liveStatus = when {
            liveSentence.contains("未当班") -> "未当班"
            liveSentence.contains("当班") || liveSentence.contains("在园") || liveSentence.contains("在主宅") -> "当前在场"
            else -> "角色卡基线"
        }
        ScenarioPerson(
            id = "scenario-person-${name.hashCode()}",
            name = name, role = oneLine(role ?: bullet(entry.content, "角色/岗位") ?: bullet(entry.content, "岗位")), ability = oneLine(ability), personality = oneLine(personality),
            clothing = oneLine(section(entry.content, "开局衣着方案") ?: visual ?: bullet(entry.content, "当前穿着")), description = entry.content.take(1800).trim(),
            relationship = oneLine(bullet(entry.content, "与主角关系").orEmpty()).ifBlank { "以角色卡为准，当前具体关系未单独登记" }, activity = oneLine(liveSentence).ifBlank { oneLine(bullet(entry.content, "当前活动")).ifBlank { "未记录" } }, status = liveStatus, source = entry.name
        )
    }.distinctBy { it.name }

    fun groupSummary(entries: List<WorldEntry>): String = entries.firstOrNull { it.name == "庄园人员与合作名册" }
        ?.content?.lineSequence()?.firstOrNull { it.contains("正式雇员") || it.contains("合作方") }?.trim()
        ?: entries.asSequence().flatMap { it.content.lineSequence() }.firstOrNull { it.contains("正式雇员") || it.contains("合作方") || it.contains("常住人口") }?.trim().orEmpty()

    private fun section(content: String, title: String): String? {
        val start = content.indexOf("【$title】")
        if (start < 0) return null
        val bodyStart = start + title.length + 2
        val next = content.indexOf("【", bodyStart).takeIf { it >= 0 } ?: content.length
        return content.substring(bodyStart, next).trim().trimStart(':', '：', ' ')
    }

    private fun oneLine(value: String?): String = value.orEmpty().replace(Regex("\\s+"), " ").take(420)

    private fun bullet(content: String, label: String): String? = Regex("(?:^|\\n)\\s*[-•]\\s*${Regex.escape(label)}\\s*[：:]\\s*(.+)$", setOf(RegexOption.MULTILINE)).find(content)?.groupValues?.getOrNull(1)?.trim()

    private fun inlineField(content: String, title: String): String? = Regex("(?:${Regex.escape(title)})\\s*[：:]\\s*(.*?)(?=\\s*[一二三四五六七八九十]+、|【|$)", setOf(RegexOption.DOT_MATCHES_ALL)).find(content)?.groupValues?.getOrNull(1)?.trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScenarioStatusScreen(chat: Chat, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    val entries = remember(chat, state.data.worldEntries) { ScenarioPanelData.entries(chat, state) }
    val sections = remember(entries) { ScenarioPanelData.statusSections(entries) }
    val baseline = remember(entries) { ScenarioPanelData.baselineState(entries) }
    val historyState = remember(chat.knowledgeNotes) { ScenarioPanelData.historyState(chat.knowledgeNotes) }
    val historySections = remember(chat.knowledgeNotes) {
        chat.knowledgeNotes.filter { it.title.startsWith(ScenarioHistoryOrganizer.NOTE_TITLE_PREFIX) }
            .sortedByDescending { it.createdAt }
            .map { ScenarioSourceSection(it.title, it.text, "knowledge") }
    }
    var editOpen by remember { mutableStateOf(false) }
    val panelMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxWidth().wrapContentHeight().heightIn(max = panelMaxHeight)) {
            PanelHeader("剧本状态", "资源、兑换、升级和任务；数据来自当前会话与剧本世界书", Icons.Outlined.Inventory2, onDismiss) {
                IconButton(onClick = { actions.organizeScenarioFromHistory(chat.id) }, enabled = !state.busy) { Icon(Icons.Outlined.AutoAwesome, "从历史对话整理") }
                IconButton(onClick = { editOpen = true }, enabled = !state.busy) { Icon(Icons.Outlined.Edit, "编辑动态状态") }
            }
            LazyColumn(Modifier.fillMaxWidth().wrapContentHeight().heightIn(max = panelMaxHeight - 96.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)) {
                item {
                    val current = chat.scenarioState
                    val merged = remember(current, baseline, historyState) {
                        ScenarioPanelData.mergeState(ScenarioPanelData.mergeState(baseline, historyState), current)
                    }
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f))) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("当前状态总览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("剧本基线 + 历史整理 + 本轮已确认变化", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (merged.location.isNotBlank()) AssistChip(onClick = {}, label = { Text(merged.location) }, leadingIcon = { Icon(Icons.Outlined.LocationOn, null, Modifier.size(16.dp)) })
                                if (merged.time.isNotBlank()) AssistChip(onClick = {}, label = { Text(merged.time) }, leadingIcon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp)) })
                                if (merged.systemLevel.isNotBlank()) AssistChip(onClick = {}, label = { Text(merged.systemLevel) }, leadingIcon = { Icon(Icons.Outlined.Route, null, Modifier.size(16.dp)) })
                            }
                            Text(
                                "完整统计：资源 ${merged.resources.size} 项 · 兑换 ${merged.options.size} 项 · 升级 ${merged.upgrades.size} 项 · 任务 ${merged.quests.size} 项",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ScenarioMetricCard("资源", merged.resources.size, Modifier.weight(1f))
                                ScenarioMetricCard("兑换", merged.options.size, Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ScenarioMetricCard("升级", merged.upgrades.size, Modifier.weight(1f))
                                ScenarioMetricCard("任务", merged.quests.size, Modifier.weight(1f))
                            }
                            val historyCount = chat.knowledgeNotes.count { it.title.startsWith(ScenarioHistoryOrganizer.NOTE_TITLE_PREFIX) }
                            Text(
                                if (historyCount > 0) "已接入知识库：历史整理 ${historyCount} 条 · 已合并 ${historyState.resources.size + historyState.options.size + historyState.upgrades.size + historyState.quests.size} 项状态 · 会话原文 ${chat.messages.count { !it.contextExcluded }} 条"
                                else "知识库：尚未生成剧本整理条目 · 会话原文 ${chat.messages.count { !it.contextExcluded }} 条",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (historyCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            merged.note.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
                            }
                            ScenarioStateTables(merged)
                            if (current == ScenarioState()) Text("当前还没有会话动态覆盖；上面显示的是剧本卡已登记的初始口径。剧情发生变化后，状态会在回复中自动更新，或可点右上角整理历史。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (baseline.source.isNotBlank()) item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("数据来源", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("完整状态由角色卡的固定口径、历史整理得到的知识条目、当前会话状态三部分合并；计划、报价和未解锁路线不会被当成已完成。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (sections.isEmpty()) item { EmptyPanel("当前剧本没有可识别的状态条目", "可以在编辑动态状态中登记资源、任务和升级路线。", Icons.Outlined.WarningAmber) }
                else {
                    item { PanelSectionTitle("剧本设定基线", "这些内容来自角色卡世界书，未等同于已发生的剧情") }
                    items(sections, key = { it.title }) { SourceSectionCard(it) }
                }
                if (historySections.isNotEmpty()) {
                    item { PanelSectionTitle("历史整理证据", "这些条目来自会话原文并已写入知识库；展开可查看对应整理文本") }
                    items(historySections, key = { "history-${it.title}-${it.content.hashCode()}" }) { SourceSectionCard(it) }
                }
            }
        }
    }
    if (editOpen) ScenarioStateEditor(chat.scenarioState, state.busy, onDismiss = { editOpen = false }) { actions.saveScenarioState(chat.id, it); editOpen = false }
}

@Composable
private fun ScenarioStateTables(value: ScenarioState) {
    if (value.resources.isNotEmpty()) {
        PanelSectionTitle("资源与账本 · ${value.resources.size}", "当前已登记的余额、库存或来源")
        value.resources.forEach { TableLine(it.name, listOf(it.value, it.unit).filter(String::isNotBlank).joinToString(" "), it.note) }
    }
    if (value.options.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
        PanelSectionTitle("兑换选项 · ${value.options.size}", "报价、前置和兑换结果")
        value.options.forEach { TableLine(it.name, it.cost.ifBlank { "消耗未记录" }, listOf(it.requirement, it.result).filter(String::isNotBlank).joinToString(" · ")) }
    }
    if (value.upgrades.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
        PanelSectionTitle("升级路线 · ${value.upgrades.size}", "只有正文确认完成的升级才会标记为已解锁")
        value.upgrades.forEach { TableLine(listOf(it.level, it.name).filter(String::isNotBlank).joinToString(" · "), it.status, listOf(it.cost, it.requirement, it.benefit).filter(String::isNotBlank).joinToString(" · ")) }
    }
    if (value.quests.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
        PanelSectionTitle("任务 · ${value.quests.size}", "已确认、进行中和待核对事项")
        value.quests.forEach { TableLine(it.title, listOf(it.status, it.progress).filter(String::isNotBlank).joinToString(" · "), listOf(it.description, it.reward).filter(String::isNotBlank).joinToString(" · ")) }
    }
}

@Composable
private fun ScenarioMetricCard(label: String, count: Int, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(if (count == 0) "待整理" else "已载入", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScenarioRosterScreen(chat: Chat, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    val entries = remember(chat, state.data.worldEntries) { ScenarioPanelData.entries(chat, state) }
    val derived = remember(entries, chat.knowledgeNotes) {
        val fromCard = ScenarioPanelData.people(entries)
        val fromHistory = ScenarioPanelData.historyPeople(chat.knowledgeNotes)
        (fromCard + fromHistory).fold(LinkedHashMap<String, ScenarioPerson>()) { map, person ->
            val previous = map[person.name]
            map[person.name] = if (previous == null) person else previous.copy(
                role = person.role.ifBlank { previous.role }, relationship = person.relationship.ifBlank { previous.relationship },
                ability = person.ability.ifBlank { previous.ability }, activity = person.activity.ifBlank { previous.activity },
                clothing = person.clothing.ifBlank { previous.clothing }, personality = person.personality.ifBlank { previous.personality },
                description = person.description.ifBlank { previous.description }, status = person.status.ifBlank { previous.status },
                source = if (person.source == "历史知识库") "历史知识库 · ${previous.source}" else person.source
            )
            map
        }.values.toList()
    }
    val manualByName = chat.scenarioRoster.filter { it.name.isNotBlank() }.associateBy { it.name }
    val people = (derived.map { manualByName[it.name]?.let { override -> it.copy(
        relationship = override.relationship.ifBlank { it.relationship }, ability = override.ability.ifBlank { it.ability },
        activity = override.activity.ifBlank { it.activity }, clothing = override.clothing.ifBlank { it.clothing },
        personality = override.personality.ifBlank { it.personality }, status = override.status.ifBlank { it.status },
        source = "会话动态覆盖 · ${it.source}"
    ) } ?: it } + chat.scenarioRoster.filter { it.name.isNotBlank() && it.name !in derived.map { person -> person.name } })
        .distinctBy { it.name }
    val sections = remember(entries) { ScenarioPanelData.rosterSections(entries) }
    var editPerson by remember { mutableStateOf<ScenarioPerson?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val panelMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().wrapContentHeight().heightIn(max = panelMaxHeight)) {
            PanelHeader("角色关系图", "人物、岗位、关系和当前状态；未记录的字段会明确标出", Icons.Outlined.AccountTree, onDismiss) {
                IconButton(onClick = { actions.organizeScenarioFromHistory(chat.id) }, enabled = !state.busy) { Icon(Icons.Outlined.AutoAwesome, "从历史对话整理") }
                IconButton(onClick = { editPerson = ScenarioPerson() }, enabled = !state.busy) { Icon(Icons.Outlined.Add, "新增人物") }
            }
            LazyColumn(Modifier.fillMaxWidth().wrapContentHeight().heightIn(max = panelMaxHeight - 96.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)) {
                item {
                    val summary = ScenarioPanelData.groupSummary(entries)
                    if (summary.isNotBlank()) Text(summary, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val historyCount = chat.knowledgeNotes.count { it.title.startsWith(ScenarioHistoryOrganizer.NOTE_TITLE_PREFIX + "人物·") }
                    Text(
                        if (historyCount > 0) "知识库已接入 ${historyCount} 条历史人物记录；关系边只显示原文明确登记的关系。"
                        else "当前显示角色卡中的基线人物。点右上角整理历史后，人物关系、活动和穿着会写入知识库并在这里显示。",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = if (historyCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    RelationshipOverview(people)
                }
                if (people.isEmpty()) item { EmptyPanel("还没有可显示的人物", "角色卡中没有可识别的【角色：】条目，可点击右上角新增。", Icons.Outlined.Group) }
                items(people, key = { it.id }) { person ->
                    ScenarioPersonCard(person, expanded = expanded == person.id, editable = chat.scenarioRoster.isNotEmpty(), onExpand = { expanded = if (expanded == person.id) null else person.id }, onEdit = { editPerson = person }, onDelete = {
                        actions.saveScenarioRoster(chat.id, chat.scenarioRoster.filterNot { it.name == person.name }); expanded = null
                    })
                }
                if (sections.isNotEmpty()) {
                    item { PanelSectionTitle("名册与关系原文", "基线资料") }
                    items(sections, key = { "source-${it.title}" }) { SourceSectionCard(it) }
                }
            }
        }
    }
    editPerson?.let { person -> ScenarioPersonEditor(person, state.busy, onDismiss = { editPerson = null }) { next ->
        val updated = chat.scenarioRoster.filterNot { it.name == next.name } + next
        actions.saveScenarioRoster(chat.id, updated); editPerson = null
    } }
}

@Composable
private fun RelationshipOverview(people: List<ScenarioPerson>) {
    if (people.isEmpty()) return
    val protagonist = people.firstOrNull { person ->
        person.role.contains("主角") || person.relationship.contains("主角") || person.name == "谷若尘"
    }
    val rootName = protagonist?.name ?: "主角 / 当前视角"
    val members = people.filter { it.name != protagonist?.name }
    val groups = members.groupBy(::organizationGroup).toList()
    val edges = relationshipEdges(rootName, protagonist, people)
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .42f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("组织架构", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("${people.size} 人 · ${groups.size} 个组织分组 · 分组按岗位/活动归类，关系网只显示原文明确登记的关系", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
                Text("$rootName · 核心", Modifier.padding(horizontal = 14.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
            }
            if (groups.isEmpty()) {
                Text("暂无可分组的关系记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            groups.forEach { (group, groupedPeople) ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("│", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                            Text("$group · ${groupedPeople.size} 人", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            groupedPeople.forEach { person ->
                                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        Text(person.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                        Text(person.role.ifBlank { person.relationship.ifBlank { "关系未记录" } }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 3.dp))
            Text("关系网络 · ${edges.size} 条明确关系", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            edges.forEach { edge ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) { Text(edge.from, Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) { Text(edge.to, Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
                        Text(edge.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

internal data class RelationshipEdge(val from: String, val to: String, val label: String)

private fun organizationGroup(person: ScenarioPerson): String {
    val text = "${person.role} ${person.relationship} ${person.activity}"
    return when {
        Regex("管家|行政|预算|合同").containsMatchIn(text) -> "庄园管理"
        Regex("安保|队长|副队长|门岗|巡逻").containsMatchIn(text) -> "安保体系"
        Regex("生活服务|起居|接待|餐饮|茶点|清洁|衣物|园艺").containsMatchIn(text) -> "生活服务"
        Regex("合作方|拍摄|直播|合作|盟友|顾问|伙伴|同伴").containsMatchIn(text) -> "合作与盟友"
        Regex("亲密|恋|伴侣|爱人|后宫|暧昧|情感").containsMatchIn(text) -> "亲密关系"
        Regex("敌|对立|陌生|访客|外部").containsMatchIn(text) -> "外部与对立"
        else -> "未分类 / 待明确"
    }
}

internal fun relationshipEdges(rootName: String, protagonist: ScenarioPerson?, people: List<ScenarioPerson>): List<RelationshipEdge> {
    val result = mutableListOf<RelationshipEdge>()
    people.filter { it.name != protagonist?.name }.forEach { person ->
        // A job title or a co-location is not a relationship. Keep those
        // people in the organization tree, but only draw a network edge when
        // the card/history explicitly states who they are to someone.
        val raw = person.relationship.trim()
        val label = raw
            .takeUnless { it.isBlank() || Regex("关系未记录|未记录|以角色卡为准|待明确|未知").containsMatchIn(it) }
            ?: return@forEach
        // A field such as “与林岚是朋友” names a peer target. It must not
        // also be shown as a relationship from the protagonist.
        val referenced = people.firstOrNull { other -> other.name != person.name && raw.contains(other.name) }
        if (referenced != null && !raw.contains("主角")) {
            result += RelationshipEdge(person.name, referenced.name, normalizeRelationshipLabel(raw, referenced.name))
        } else {
            result += RelationshipEdge(rootName, person.name, normalizeRelationshipLabel(raw, rootName))
        }
    }
    return result.distinctBy { listOf(it.from, it.to, it.label) }.take(80)
}

private fun relationshipFieldLabel(value: String): String =
    if (Regex("与[^：:，,；; ]+|对[^：:，,；; ]+").containsMatchIn(value) && !value.contains("主角")) "人物关系" else "与主角的关系"

private fun normalizeRelationshipLabel(value: String, target: String): String = value
    .replace(Regex("^(与|对)\\s*${Regex.escape(target)}\\s*(是|为|属于)?\\s*"), "")
    .replace(Regex("^(与|对)?\\s*主角\\s*(是|为|与)?\\s*"), "")
    .trim(' ', '：', ':', '，', ',', '；', ';')
    .ifBlank { value.take(120) }
    .take(120)

@Composable
private fun ScenarioPersonCard(person: ScenarioPerson, expanded: Boolean, editable: Boolean, onExpand: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Group, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp)); Text(person.name.ifBlank { "未命名人物" }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onExpand) { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起" else "展开") }
                if (editable) { IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "编辑") }; IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "删除") } }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (person.role.isNotBlank()) AssistChip(onClick = {}, label = { Text(person.role.take(30)) })
                AssistChip(onClick = {}, label = { Text(person.status.ifBlank { "未知" }) }, leadingIcon = { Icon(if (person.status == "当前在场") Icons.Outlined.CheckCircle else Icons.Outlined.Info, null, Modifier.size(15.dp)) })
            }
            if (person.relationship.isNotBlank()) LabelValue(relationshipFieldLabel(person.relationship), person.relationship)
            if (person.activity.isNotBlank()) LabelValue("正在做什么", person.activity)
            if (person.clothing.isNotBlank()) LabelValue("当前穿着/外观", person.clothing)
            if (expanded) {
                if (person.ability.isNotBlank()) LabelValue("能力/职责", person.ability)
                if (person.personality.isNotBlank()) LabelValue("性格", person.personality)
                if (person.description.isNotBlank()) { HorizontalDivider(); Text("角色卡原文摘要", style = MaterialTheme.typography.labelLarge); Text(person.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (person.source.isNotBlank()) Text("来源：${person.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SourceSectionCard(section: ScenarioSourceSection) {
    var expanded by remember(section.title) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起原文" else "展开原文") }
            }
            if (expanded) Text(section.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text(section.content.replace(Regex("\\s+"), " ").take(180), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PanelHeader(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        actions(); TextButton(onClick = onDismiss) { Text("关闭") }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

@Composable private fun PanelSectionTitle(title: String, subtitle: String) { Column(Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun TableLine(title: String, value: String, note: String) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.Top) { Column(Modifier.weight(1f)) { Text(title.ifBlank { "未命名" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold); if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(value.ifBlank { "未记录" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) } }
@Composable private fun LabelValue(label: String, value: String) { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(value, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun EmptyPanel(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector) { Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScenarioStateEditor(value: ScenarioState, busy: Boolean, onDismiss: () -> Unit, onSave: (ScenarioState) -> Unit) {
    var location by remember { mutableStateOf(value.location) }; var time by remember { mutableStateOf(value.time) }; var level by remember { mutableStateOf(value.systemLevel) }; var note by remember { mutableStateOf(value.note) }
    var resources by remember { mutableStateOf(value.resources.joinToString("\n") { listOf(it.name, it.value, it.unit, it.note).joinToString(" | ") }) }
    var options by remember { mutableStateOf(value.options.joinToString("\n") { listOf(it.name, it.cost, it.requirement, it.result).joinToString(" | ") }) }
    var upgrades by remember { mutableStateOf(value.upgrades.joinToString("\n") { listOf(it.name, it.level, it.cost, it.requirement, it.benefit, it.status).joinToString(" | ") }) }
    var quests by remember { mutableStateOf(value.quests.joinToString("\n") { listOf(it.title, it.status, it.progress, it.description, it.reward).joinToString(" | ") }) }
    fun cells(text: String) = text.lineSequence().map { it.split('|').map(String::trim) }.filter { it.firstOrNull().orEmpty().isNotBlank() }.toList()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑动态状态") }, text = { Column(Modifier.fillMaxWidth().height(560.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("每行一项，字段之间用 | 分隔。只登记你确认已经发生的变化。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(location, { location = it }, label = { Text("当前位置") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(time, { time = it }, label = { Text("时间") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(level, { level = it }, label = { Text("系统/基地等级") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(resources, { resources = it }, label = { Text("资源：名称 | 数值 | 单位 | 备注") }, minLines = 3, modifier = Modifier.fillMaxWidth()); OutlinedTextField(options, { options = it }, label = { Text("兑换：名称 | 消耗 | 前置 | 结果") }, minLines = 3, modifier = Modifier.fillMaxWidth()); OutlinedTextField(upgrades, { upgrades = it }, label = { Text("升级：名称 | 等级 | 消耗 | 前置 | 收益 | 状态") }, minLines = 3, modifier = Modifier.fillMaxWidth()); OutlinedTextField(quests, { quests = it }, label = { Text("任务：标题 | 状态 | 进度 | 描述 | 奖励") }, minLines = 3, modifier = Modifier.fillMaxWidth()); OutlinedTextField(note, { note = it }, label = { Text("补充说明") }, minLines = 2, modifier = Modifier.fillMaxWidth())
    } }, confirmButton = { Button(onClick = { onSave(ScenarioState(location=location.trim(), time=time.trim(), systemLevel=level.trim(), note=note.trim(), resources=cells(resources).map { ScenarioResource(name=it.getOrElse(0){""}, value=it.getOrElse(1){""}, unit=it.getOrElse(2){""}, note=it.getOrElse(3){""}) }, options=cells(options).map { ScenarioOption(name=it.getOrElse(0){""}, cost=it.getOrElse(1){""}, requirement=it.getOrElse(2){""}, result=it.getOrElse(3){""}) }, upgrades=cells(upgrades).map { ScenarioUpgrade(name=it.getOrElse(0){""}, level=it.getOrElse(1){""}, cost=it.getOrElse(2){""}, requirement=it.getOrElse(3){""}, benefit=it.getOrElse(4){""}, status=it.getOrElse(5){"未解锁"}) }, quests=cells(quests).map { ScenarioQuest(title=it.getOrElse(0){""}, status=it.getOrElse(1){"未开始"}, progress=it.getOrElse(2){""}, description=it.getOrElse(3){""}, reward=it.getOrElse(4){""}) }, source="用户确认")); }, enabled = !busy) { Icon(Icons.Outlined.Save, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ScenarioPersonEditor(value: ScenarioPerson, busy: Boolean, onDismiss: () -> Unit, onSave: (ScenarioPerson) -> Unit) {
    var name by remember { mutableStateOf(value.name) }; var role by remember { mutableStateOf(value.role) }; var relationship by remember { mutableStateOf(value.relationship) }; var ability by remember { mutableStateOf(value.ability) }; var activity by remember { mutableStateOf(value.activity) }; var clothing by remember { mutableStateOf(value.clothing) }; var personality by remember { mutableStateOf(value.personality) }; var status by remember { mutableStateOf(value.status) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (value.name.isBlank()) "新增人物" else "编辑人物") }, text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) { listOf("姓名" to name, "岗位/身份" to role, "与主角关系" to relationship, "能力/职责" to ability, "当前活动" to activity, "当前穿着/外观" to clothing, "性格" to personality, "状态" to status).forEach { (label, current) -> OutlinedTextField(current, { next -> when (label) { "姓名" -> name=next; "岗位/身份" -> role=next; "与主角关系" -> relationship=next; "能力/职责" -> ability=next; "当前活动" -> activity=next; "当前穿着/外观" -> clothing=next; "性格" -> personality=next; "状态" -> status=next } }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = label == "姓名" || label == "状态") } } }, confirmButton = { Button(onClick = { onSave(value.copy(name=name.trim(),role=role.trim(),relationship=relationship.trim(),ability=ability.trim(),activity=activity.trim(),clothing=clothing.trim(),personality=personality.trim(),status=status.trim().ifBlank { "未知" },source="用户确认")) }, enabled = !busy && name.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
