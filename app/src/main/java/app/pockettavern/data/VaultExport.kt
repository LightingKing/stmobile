package app.pockettavern.data

import app.pockettavern.domain.Chat
import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.domain.Summary
import app.pockettavern.domain.ScenarioState
import app.pockettavern.engine.ContextEngine
import app.pockettavern.engine.HistorySource
import app.pockettavern.engine.KnowledgeVault
import app.pockettavern.engine.KnowledgeOrganization
import app.pockettavern.engine.ScenarioHistoryOrganizer
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Portable UTF-8 Markdown, with only generated path components and resolvable wiki links. */
object VaultExport {
    fun token(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

    fun conversation(chat: Chat): String = buildString {
        append("# ").append(chat.title.replace('\n', ' ').replace('\r', ' ')).append("\n\n")
        if (chat.continuityNotes.isNotBlank()) append(Exchange.markdownContinuityMetadata(chat.continuityNotes)).append("\n\n")
        if (chat.scenarioState != ScenarioState() || chat.scenarioRoster.isNotEmpty()) {
            append("## 剧本面板快照\n\n")
            append("面板数据属于会话快照；没有登记的动态变化仍显示为未记录。\n\n")
            val state = chat.scenarioState
            if (state.location.isNotBlank() || state.time.isNotBlank() || state.systemLevel.isNotBlank()) {
                append("- 位置：").append(state.location.ifBlank { "未记录" }).append("\n")
                append("- 时间：").append(state.time.ifBlank { "未记录" }).append("\n")
                append("- 等级：").append(state.systemLevel.ifBlank { "未记录" }).append("\n")
            }
            if (state.resources.isNotEmpty()) append("\n### 资源\n\n").append(state.resources.joinToString("\n") { "- ${it.name}：${it.value} ${it.unit} ${it.note}" }).append("\n")
            if (state.options.isNotEmpty()) append("\n### 兑换选项\n\n").append(state.options.joinToString("\n") { "- ${it.name} · ${it.cost} · ${it.requirement} · ${it.result}" }).append("\n")
            if (state.upgrades.isNotEmpty()) append("\n### 升级路线\n\n").append(state.upgrades.joinToString("\n") { "- ${it.level} ${it.name} · ${it.status} · ${it.cost} · ${it.requirement} · ${it.benefit}" }).append("\n")
            if (state.quests.isNotEmpty()) append("\n### 任务\n\n").append(state.quests.joinToString("\n") { "- ${it.title} · ${it.status} · ${it.progress} · ${it.description} · ${it.reward}" }).append("\n")
            if (chat.scenarioRoster.isNotEmpty()) append("\n### 人物名册\n\n").append(chat.scenarioRoster.joinToString("\n") { "- ${it.name} · ${it.role} · 关系：${it.relationship} · 活动：${it.activity} · 穿着：${it.clothing}" }).append("\n")
            append("\n")
        }
        // The scenario organizer also writes a source-bound knowledge snapshot.
        // Keep it visible in the human-readable conversation export; the same
        // notes are exported to notes/, categories/ and entities/ below.
        val organizedScenarioNotes = KnowledgeVault.validNotes(chat).filter {
            it.title.startsWith(ScenarioHistoryOrganizer.NOTE_TITLE_PREFIX)
        }
        if (organizedScenarioNotes.isNotEmpty()) {
            append("## 历史整理记忆\n\n")
            append("这些条目由“从历史对话整理”生成，并保留到对应原文的来源校验。\n\n")
            organizedScenarioNotes.forEach { note ->
                append("### ").append(note.title.ifBlank { "剧本整理记忆" }).append("\n\n")
                append(note.text).append("\n\n")
                append("来源消息数：").append(note.sourceMessageIds.size).append("\n\n")
            }
        }
        val research = chat.knowledgeNotes.filter { it.isResearch }
        if (research.isNotEmpty()) {
            append(Exchange.markdownResearchMetadata(chat)).append("\n\n")
            append("## 外部研究资料\n\n来源陈述、推测和虚构设定见各记录标注；这些资料不代表剧情已发生。\n\n")
            // Quote every line so headings within a research document cannot become chat turns on import.
            research.forEach { append(it.text.lineSequence().joinToString("\n") { line -> "> $line" }).append("\n\n") }
        }
        append("会话 ID：").append(chat.id).append("\n\n")
        validActiveSummary(chat)?.let {
            val summary = Exchange.cleanMarkdownMessageContent(it.text, "assistant")
            if (summary.isNotBlank()) append("## 当前摘要\n\n").append(summary).append("\n\n")
        } ?: run {
            if (chat.activeSummaryId.isNotBlank()) append("## 历史摘要状态\n\n旧摘要已失效，待根据当前有效原文重建。\n\n")
        }
        val visibleMessages = chat.messages.mapNotNull { message ->
            if (message.role !in listOf("user", "assistant")) return@mapNotNull null
            val content = Exchange.cleanMarkdownMessageContent(message.content, message.role)
            if (content.isBlank() && message.attachments.isEmpty()) null else message to content
        }
        visibleMessages.forEachIndexed { index, (message, content) ->
            append("## ").append(index + 1).append(" · ").append(message.role).append("\n\n")
            append(Exchange.markdownMessageMetadata(message)).append("\n\n")
            if (content.isNotBlank()) append(content).append("\n\n")
            message.attachments.forEach { append("附件：").append(it.name).append("（").append(it.mime).append("）\n\n") }
        }
    }

    fun write(chat: Chat, target: File) {
        target.parentFile?.mkdirs()
        val stage = File(target.parentFile, "${target.name}.tmp")
        try {
            ZipOutputStream(stage.outputStream()).use { zip ->
                fun entry(path: String, text: String) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                val included = chat.messages.filterNot { it.contextExcluded }.mapTo(hashSetOf()) { it.id }
                val notes = KnowledgeVault.validNotes(chat).filter { it.isResearch || it.sourceMessageIds.any { id -> id in included } }
                val pages = KnowledgeOrganization.pages(chat, notes)
                val pagesByEntity = pages.associateBy { it.entity }
                val positions = chat.messages.mapIndexed { index, message -> message.id to index }.toMap()
                val noteNumbers = notes.mapIndexed { index, note -> note.id to index + 1 }.toMap()
                fun noteTitle(note: KnowledgeNote) = KnowledgeOrganization.title(note, noteNumbers.getValue(note.id))
                fun noteLink(note: KnowledgeNote) = "[[notes/${token(note.id)}|${wikiLabel(noteTitle(note))}]]"
                fun sourceLinks(ids: List<String>) = ids.mapNotNull { id -> positions[id]?.let { index ->
                    "- [[sources/${token(id)}|核对原文 ${index + 1} · ${wikiLabel(chat.messages[index].role)}]]"
                } }.joinToString("\n")
                val notesBySource = mutableMapOf<String, MutableList<KnowledgeNote>>()
                notes.forEach { note -> note.sourceMessageIds.forEach { id -> notesBySource.getOrPut(id) { mutableListOf() }.add(note) } }
                val byCategory = notes.groupBy(KnowledgeOrganization::category)
                val categories = KnowledgeOrganization.categories.filter { byCategory.containsKey(it.id) }
                val index = buildString {
                    append("# 会话知识库\n\n").append(chat.title).append("\n\n")
                    append("离线 Markdown 知识库：解压后在 Obsidian 中打开文件夹；无需插件。\n\n")
                    append("[[conversation|完整会话]] · [[summary|当前摘要]]\n\n")
                    append("当前摘要用于续聊，知识条目和原文按需查询，不会每轮整库发送。导出是此刻的快照；在 Obsidian 中修改不会自动同步回 App。\n\n")
                    append("笔记由模型生成，可能遗漏或有误；原文为核对依据。修改原文后失效的条目不参与召回。\n\n")
                    append("## 分类\n\n")
                    categories.forEach { category -> append("- [[categories/${category.id}|${category.label}]] · ${byCategory.getValue(category.id).size} 条\n") }
                    append("\n## 人物与主题\n\n")
                    if (pages.isEmpty()) append("尚无带对象标识的条目，旧版笔记仍可从分类和原文中查看。\n")
                    pages.forEach { page -> append("- [[entities/${token(page.entity)}|${wikiLabel(page.title)}]] · ${page.notes.size} 条记录\n") }
                    append("\n## 历史分段与知识条目\n\n")
                    notes.filterNot { it.isResearch }.forEachIndexed { n, note ->
                        // Preserve existing links to legacy source ranges while new records have meaningful titles.
                        val label = if (KnowledgeOrganization.category(note) == "legacy" && note.title.isBlank()) "分段 ${n + 1}" else wikiLabel(noteTitle(note))
                        append("- [[notes/${token(note.id)}|$label]]\n")
                    }
                    append("\n## 外部研究资料\n\n保留资料中的来源陈述、推测和虚构标注；不代表剧情已发生，也不对应聊天原文。\n\n")
                    notes.filter { it.isResearch }.forEachIndexed { n, note -> append("- [[notes/").append(token(note.id)).append("|研究 ").append(n + 1).append("]]\n") }
                    append("\n## 摘要版本\n\n")
                    chat.summaries.forEachIndexed { n, summary -> append("- [[summaries/${token(summary.id)}|摘要版本 ${n + 1}]]\n") }
                    append("\n## 原文索引\n\n")
                    chat.messages.forEachIndexed { n, m -> append("- [[sources/").append(token(m.id)).append("|第 ").append(n + 1).append(" 条 · ").append(m.role).append("]]\n") }
                }
                entry("index.md", index)
                entry("conversation.md", conversation(chat))
                entry("conversation.jsonl", Exchange.chatJsonl(chat))
                entry("summary.md", "# 当前摘要\n\n" + (validActiveSummary(chat)?.text
                    ?: if (chat.activeSummaryId.isNotBlank()) "旧摘要已失效，待根据当前有效原文重建。" else "尚未生成摘要") + "\n\n[[index|返回索引]]\n")
                categories.forEach { category ->
                    val body = byCategory.getValue(category.id).joinToString("\n") { "- ${noteLink(it)}" }
                    entry("categories/${category.id}.md", "# ${category.label}\n\n$body\n\n[[index|返回索引]]\n")
                }
                pages.forEach { page ->
                    val body = buildString {
                        append("---\naliases: [").append(page.aliases.joinToString(", ", transform = JSONObject::quote)).append("]\n---\n\n")
                        append("# ").append(singleLine(page.title)).append("\n\n")
                        append("以下记录按来源原文先后排列。较后明确更新才改变旧状态；未再次提及不等于取消。\n\n")
                        page.notes.forEach { note ->
                            append("## ").append(singleLine(noteTitle(note))).append("\n\n")
                            append(note.text).append("\n\n")
                            append(noteLink(note)).append("\n\n").append(sourceLinks(note.sourceMessageIds)).append("\n\n")
                        }
                        append("[[index|返回索引]]\n")
                    }
                    entry("entities/${token(page.entity)}.md", body)
                }
                notes.forEach { note ->
                    val provenance = if (note.isResearch) "外部研究资料，不对应聊天原文；来源链接及事实/推测/虚构标注保留在正文中，不代表剧情已发生。" else sourceLinks(note.sourceMessageIds)
                    val category = KnowledgeOrganization.category(note)
                    val entity = KnowledgeOrganization.canonicalEntity(note.entity)
                    val entityLink = pagesByEntity[entity]?.let { " · [[entities/${token(entity)}|${wikiLabel(it.title)}]]" }.orEmpty()
                    val metadata = "---\ntags: [pocket-tavern, ${if (note.isResearch) "public-research" else "story-memory"}]\nkind: $category\nentity: ${JSONObject.quote(entity)}\naliases: [${note.aliases.joinToString(", ", transform = JSONObject::quote)}]\n---"
                    val archiveNotice = if (category == "archive") "\n\n这是归档范围索引，不能据此断言所有事实都已被提取；细节请读取来源原文。" else ""
                    entry("notes/${token(note.id)}.md", "$metadata\n\n# ${singleLine(noteTitle(note))}\n\n[[categories/$category|${KnowledgeOrganization.label(category)}]]$entityLink\n\n${note.text}$archiveNotice\n\n## 来源\n\n$provenance\n\n[[index|返回索引]]\n")
                }
                val activeId = validActiveSummary(chat)?.id
                chat.summaries.forEachIndexed { index, summary ->
                    val end = positions[summary.throughMessageId]
                    val status = if (summary.id == activeId) "当前生效" else "历史版本，仅供核对，不代表当前续聊状态"
                    val source = if (end == null) "来源结束消息已缺失" else "累计覆盖第 1–${end + 1} 条；[[sources/${token(summary.throughMessageId)}|查看结束位置]]"
                    entry("summaries/${token(summary.id)}.md", "# 摘要版本 ${index + 1}\n\n$status\n\n${summary.text}\n\n## 来源范围\n\n$source\n\n[[summary|当前摘要]] · [[index|返回索引]]\n")
                }
                chat.messages.forEachIndexed { index, message ->
                    val backlinks = notesBySource[message.id].orEmpty().joinToString("\n") { "- ${noteLink(it)}" }
                    entry("sources/${token(message.id)}.md", "# 第 ${index + 1} 条 · ${message.role}\n\n${HistorySource.summaryText(message)}\n\n## 对应知识条目\n\n$backlinks\n\n[[index|返回索引]]\n")
                }
            }
            java.nio.file.Files.move(stage.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { stage.delete() }
    }

    private fun singleLine(value: String): String = value.replace(Regex("[\\r\\n]+"), " ")
    private fun wikiLabel(value: String): String = singleLine(value).replace('|', '｜').replace('[', '［').replace(']', '］')
    private fun validActiveSummary(chat: Chat): Summary? = ContextEngine.resolveSummary(chat).effective
}
