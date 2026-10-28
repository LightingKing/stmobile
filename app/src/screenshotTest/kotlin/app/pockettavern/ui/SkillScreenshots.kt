package app.pockettavern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.GitHubStars
import app.pockettavern.domain.Memory
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.SkillRecord
import app.pockettavern.domain.UiSession
import app.pockettavern.domain.WorldEntry
import com.android.tools.screenshot.PreviewTest

private val SkillActions = object : AppActions {
    override fun saveProvider(provider: ProviderConfig, key: String) = Unit
    override fun deleteProvider(id: String) = Unit
    override fun refreshModels(id: String) = Unit
    override fun createChat(characterId: String) = Unit
    override fun selectChat(id: String) = Unit
    override fun updateChat(chat: Chat) = Unit
    override fun deleteChat(id: String) = Unit
    override fun chooseModel(providerId: String, modelId: String) = Unit
    override fun send(text: String): Boolean = true
    override fun stop() = Unit
    override fun regenerate() = Unit
    override fun editMessage(id: String, text: String) = Unit
    override fun branchAt(messageId: String) = Unit
    override fun savePreset(preset: Preset) = Unit
    override fun deletePreset(id: String) = Unit
    override fun saveCharacter(character: Character) = Unit
    override fun deleteCharacter(id: String) = Unit
    override fun saveMemory(memory: Memory) = Unit
    override fun deleteMemory(id: String) = Unit
    override fun saveWorldEntry(entry: WorldEntry) = Unit
    override fun deleteWorldEntry(id: String) = Unit
    override fun saveSkill(skill: SkillRecord) = Unit
    override fun setSkillForCurrentChat(id: String, selected: Boolean) = Unit
    override fun installSkillUrl(url: String) = Unit
    override fun deleteSkill(id: String) = Unit
    override fun saveSettings(settings: AppSettings) = Unit
    override fun compress() = Unit
    override fun restoreSummary(id: String) = Unit
    override fun previewContext() = Unit
    override fun dismissPreview() = Unit
    override fun importFile(kind: String) = Unit
    override fun exportFile(kind: String, id: String) = Unit
    override fun pickAttachment() = Unit
    override fun removeAttachment(id: String) = Unit
    override fun runMedia(providerId: String, modelId: String, requestJson: String) = Unit
    override fun pollMedia(id: String) = Unit
    override fun openMedia(id: String) = Unit
    override fun saveMedia(id: String) = Unit
    override fun clearStatus() = Unit
}

private val SkillChat = Chat(id = "skill-chat", title = "末世长篇", skillIds = listOf("builtin-writing-qm-world-rules"))

private val RoleplaySkill = SkillRecord(
    id = "builtin-writing-qm-world-rules", name = "世界规则设定", enabled = true,
    description = "为长篇小说建立世界规则、力量体系与设定档案，附带设定档案标准供按需读取。",
    source = "GitHub精选 · 小说写作", sourceLicense = "GPL-3.0",
    files = mapOf("SKILL.md" to "", "SETTING_PROFILE_STANDARD.md" to "", "LICENSE" to "")
)

private val AdultSkill = SkillRecord(
    id = "builtin-writing-hn-scene-writing", name = "情欲场景写作", enabled = false, adult = true,
    description = "成年角色之间的亲密场景写作指导，含节奏、感官描写与情感推进。",
    source = "GitHub精选 · 成人向", sourceLicense = "MIT",
    files = mapOf("SKILL.md" to "", "references/nsfw-writing-guidelines.md" to "", "LICENSE" to "")
)

@androidx.compose.runtime.Composable
private fun SkillPreview() {
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkillCard(RoleplaySkill, SkillChat, busy = false, adultConfirmed = false, actions = SkillActions, stars = GitHubStars("example/world-rules", 832, "2026-10-22"), onDetails = {})
                SkillCard(AdultSkill, SkillChat, busy = false, adultConfirmed = false, actions = SkillActions, stars = GitHubStars("example/fiction", null, "2026-10-22"), onDetails = {})
                Card(Modifier.padding(horizontal = 12.dp)) { Column(Modifier.padding(14.dp)) { AdultSkillNotice() } }
            }
        }
    }
}

@PreviewTest
@Preview(name = "成人向 Skill 标识与确认说明", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun skillAdultGatePreview() {
    SkillPreview()
}

@PreviewTest
@Preview(name = "窄屏大字成人向 Skill", showBackground = true, widthDp = 360, heightDp = 860, fontScale = 1.3f)
@androidx.compose.runtime.Composable
fun skillAdultGateNarrowLargeTextPreview() {
    SkillPreview()
}

@androidx.compose.runtime.Composable
private fun SkillGuidePreview() {
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("tm-continuity", "qm-apocalypse", "va-velvet-ascent").forEach { SkillGuideCard(skillGuide("builtin-writing-$it")!!) }
            }
        }
    }
}

@PreviewTest
@Preview(name = "Skill 详情效果说明", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun skillGuidePreview() {
    SkillGuidePreview()
}

@PreviewTest
@Preview(name = "窄屏大字 Skill 效果说明", showBackground = true, widthDp = 360, heightDp = 960, fontScale = 1.3f)
@androidx.compose.runtime.Composable
fun skillGuideNarrowLargeTextPreview() {
    SkillGuidePreview()
}

// Metadata-only fixtures: no upstream prompt or reference content is rendered by these previews.
private val LibraryAdultSkills = listOf(
    AdultSkill,
    AdultSkill.copy(id = "builtin-writing-hn-worldbuilding", name = "成人向世界观", description = "成年角色题材的世界规则和设定维护。"),
    AdultSkill.copy(id = "builtin-writing-hn-continuity", name = "成人向连续性", description = "追踪角色状态、人物关系和跨章连续性。"),
    AdultSkill.copy(id = "builtin-writing-va-velvet-ascent", name = "成人向文字 GalGame", description = "关系阶段、状态栏与选项驱动的互动叙事。"),
    AdultSkill.copy(id = "builtin-writing-dn-deep-novel", name = "成人向长篇小说系统", description = "长篇创作流程、项目记忆与角色状态维护。"),
    AdultSkill.copy(id = "builtin-writing-dp-chapter-adult-original", name = "成人章节写作（原版）", description = "Dreampowers 的可选成人章节模块，保留上游原版。"),
    AdultSkill.copy(id = "builtin-writing-at-adult-tension-original", name = "成人互动叙事（原版）", description = "DeepSeek 角色互动、关系变化与存档叙事。")
)

private val LibrarySkills = listOf(
    RoleplaySkill,
    RoleplaySkill.copy(id = "builtin-writing-wf-fantasy", name = "幻想写作", description = "奇幻设定、叙事与正文创作。"),
    RoleplaySkill.copy(id = "builtin-writing-rq-renqing-shigu", name = "人情世故", description = "人际沟通与社会场景判断。", source = "GitHub精选 · 社会常识")
) + LibraryAdultSkills

@androidx.compose.runtime.Composable
private fun SkillLibraryPreview(missingOnly: Boolean = false) {
    val installed = if (missingOnly) listOf(RoleplaySkill) else LibrarySkills
    val missing = if (missingOnly) LibraryAdultSkills.takeLast(2) else emptyList()
    val state = UiSession(
        data = AppData(chats = listOf(SkillChat), skills = installed),
        selectedChatId = SkillChat.id,
        loading = false,
        missingBuiltinSkills = missing
    )
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SkillsTab(state, SkillActions, initialFilters = SkillFilters(content = SkillContentFilter.ADULT))
        }
    }
}

@PreviewTest
@Preview(name = "Skills 库 NSFW 筛选（示例元数据）", showBackground = true, widthDp = 420, heightDp = 1200)
@androidx.compose.runtime.Composable
fun skillLibraryNsfwPreview() {
    SkillLibraryPreview()
}

@PreviewTest
@Preview(name = "窄屏大字内置原版恢复（示例元数据）", showBackground = true, widthDp = 360, heightDp = 1400, fontScale = 1.3f)
@androidx.compose.runtime.Composable
fun skillLibraryRestoreNarrowLargeTextPreview() {
    SkillLibraryPreview(missingOnly = true)
}

@PreviewTest
@Preview(name = "会话 Skills 窄屏筛选与勾选", showBackground = true, widthDp = 360, heightDp = 1100, fontScale = 1.3f)
@androidx.compose.runtime.Composable
fun chatSkillPickerNarrowLargeTextPreview() {
    var selected by remember { mutableStateOf(setOf(RoleplaySkill.id, AdultSkill.id)) }
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                ChatSkillPicker(
                    skills = LibrarySkills, selectedIds = selected, locked = false,
                    onSelect = { skill, checked -> selected = if (checked) selected + skill.id else selected - skill.id },
                    initialFilters = SkillFilters(content = SkillContentFilter.ADULT)
                )
            }
        }
    }
}

private val ChoiceSkills = LibrarySkills + listOf(
    RoleplaySkill.copy(id = "builtin-writing-dp-chapter-draft", name = "章节初稿", description = "依据设定和大纲写本章初稿。", enabled = false),
    RoleplaySkill.copy(id = "builtin-writing-tm-dialogue", name = "人物对白", description = "让台词符合角色身份和当下情境。", enabled = false),
    RoleplaySkill.copy(id = "builtin-writing-ds-roleplay-preset-original", name = "DeepSeek 沉浸角色预设", description = "DeepSeek 向角色扮演预设，偏沉浸表演与文件记忆。", enabled = false),
    RoleplaySkill.copy(id = "builtin-writing-tt-sp-roleplay", name = "角色扮演连贯性", description = "让角色扮演保持状态和对话连贯。", enabled = false),
    RoleplaySkill.copy(id = "builtin-writing-an-roleplay-sandbox", name = "角色扮演沙盒", description = "互动角色与场景推进。", enabled = false),
    RoleplaySkill.copy(id = "builtin-writing-tm-continuity", name = "长篇连贯性检查", description = "核查人物关系与跨章连续性。", enabled = false),
    RoleplaySkill.copy(id = "builtin-writing-vn-write", name = "网文正文写作", description = "根据大纲写章节正文。", enabled = false),
    RoleplaySkill.copy(id = "builtin-writing-ns-writer", name = "小说写手", description = "章节起草与小说正文。", enabled = false),
    RoleplaySkill.copy(id = "builtin-writing-wf-dialogue", name = "对白写作", description = "对白、潜台词与动作。", enabled = false)
)

@androidx.compose.runtime.Composable
private fun SkillChoiceLibraryPreview(scenario: SkillScenario = SkillScenario.ROLEPLAY, missing: Boolean = false, noChat: Boolean = false) {
    val absent = ChoiceSkills.filter { it.id in setOf("builtin-writing-ds-roleplay-preset-original", "builtin-writing-tt-sp-roleplay", "builtin-writing-tm-dialogue") }
    val state = UiSession(
        data = AppData(chats = if (noChat) emptyList() else listOf(SkillChat), skills = if (missing) ChoiceSkills - absent.toSet() else ChoiceSkills),
        selectedChatId = if (noChat) "" else SkillChat.id,
        loading = false,
        missingBuiltinSkills = if (missing) absent else emptyList()
    )
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SkillsTab(state, SkillActions, initialScenario = scenario)
        }
    }
}

@PreviewTest
@Preview(name = "帮我选默认入口", showBackground = true, widthDp = 420, heightDp = 1100)
@androidx.compose.runtime.Composable
fun skillChoiceDefaultPreview() = SkillChoiceLibraryPreview()

@PreviewTest
@Preview(name = "成人互动场景窄屏大字", showBackground = true, widthDp = 360, heightDp = 1400, fontScale = 1.3f)
@androidx.compose.runtime.Composable
fun skillChoiceAdultNarrowPreview() = SkillChoiceLibraryPreview(scenario = SkillScenario.ADULT_ROLEPLAY)

@PreviewTest
@Preview(name = "推荐缺失可安装原版", showBackground = true, widthDp = 420, heightDp = 1200)
@androidx.compose.runtime.Composable
fun skillChoiceMissingPreview() = SkillChoiceLibraryPreview(missing = true, noChat = true)

@PreviewTest
@Preview(name = "会话选择小说候选", showBackground = true, widthDp = 360, heightDp = 1400, fontScale = 1.3f)
@androidx.compose.runtime.Composable
fun chatSkillChoiceNovelPreview() {
    var selected by remember { mutableStateOf(setOf("builtin-writing-vn-write", "builtin-writing-tm-continuity")) }
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                ChatSkillPicker(ChoiceSkills, selected, false, { skill, checked -> selected = if (checked) selected + skill.id else selected - skill.id }, initialScenario = SkillScenario.NOVEL)
            }
        }
    }
}

@PreviewTest
@Preview(name = "本会话已选与关闭总开关", showBackground = true, widthDp = 360, heightDp = 1100, fontScale = 1.3f)
@androidx.compose.runtime.Composable
fun skillChoiceSelectedPreview() {
    val selectedIds = listOf("builtin-writing-vn-write", "builtin-writing-tm-continuity")
    val chat = SkillChat.copy(skillIds = selectedIds)
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SkillsTab(UiSession(data = AppData(chats = listOf(chat), skills = ChoiceSkills), selectedChatId = chat.id, loading = false), SkillActions, initialFilters = SkillFilters(status = SkillStatusFilter.SELECTED))
        }
    }
}

@PreviewTest
@Preview(name = "会话 Skill 注入顺序窄屏大字", showBackground = true, widthDp = 360, heightDp = 1500, fontScale = 1.3f)
@androidx.compose.runtime.Composable
fun chatSkillInjectionOrderNarrowLargeTextPreview() {
    val injectionOrder = listOf(
        "builtin-writing-dn-deep-novel",
        "builtin-writing-qm-world-rules",
        "builtin-writing-hn-scene-writing"
    )
    val chat = SkillChat.copy(skillIds = injectionOrder)
    val state = UiSession(
        data = AppData(
            chats = listOf(chat),
            skills = LibrarySkills,
            settings = AppSettings(adultSkillsConfirmed = true)
        ),
        selectedChatId = chat.id,
        loading = false
    )
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ChatOptionsDialog(chat, state, SkillActions, onDismiss = {}, onPreview = {}, onMemory = {})
        }
    }
}
