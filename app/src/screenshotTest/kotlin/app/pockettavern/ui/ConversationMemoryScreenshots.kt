package app.pockettavern.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.pockettavern.domain.*
import app.pockettavern.engine.ContextEngine
import com.android.tools.screenshot.PreviewTest

private fun memoryPreviewChat(): Chat {
    val messages = listOf(
        Message(id = "first", role = "user", content = "我们约定明天去旧书店找那本航海日志。", createdAt = 1_792_700_000_000),
        Message(id = "second", role = "assistant", content = "林舟把钥匙交给你。你们已经确认彼此是旧友，约好在清晨的钟楼下见面。", createdAt = 1_792_700_060_000),
        Message(id = "third", role = "user", content = "我把钥匙收好，继续向港口走去。", createdAt = 1_792_700_120_000)
    )
    val summary = Summary(id = "current", text = "人物关系：你与林舟是多年旧友。\n已发生：林舟交出旧书店钥匙。\n当前进度：你正走向港口。\n待办：明天清晨在钟楼下汇合，一起寻找航海日志。", throughMessageId = "third", sourceHash = ContextEngine.sourceHash(messages), createdAt = 1_792_700_180_000, modelId = "deepseek-v4-flash")
    val note = KnowledgeNote(id = "note", text = "林舟是多年旧友，已经交给你旧书店钥匙。你们约定明天清晨在钟楼下汇合。", sourceMessageIds = listOf("first", "second"), sourceHash = ContextEngine.sourceHash(messages.take(2)), createdAt = 1_792_700_180_000)
    return Chat(id = "memory-preview", title = "港口往事 · 原会话继续", messages = messages, summaries = listOf(summary), activeSummaryId = summary.id, knowledgeNotes = listOf(note))
}

private fun organizedMemoryPreviewChat(): Chat {
    val chat = memoryPreviewChat()
    val summary = chat.summaries.single().copy(format = "compact-v1")
    val source = chat.messages.take(2)
    val notes = listOf(
        KnowledgeNote(id = "person", title = "林舟 · 旧友与钥匙", kind = "person", entity = "person:林舟", aliases = listOf("阿舟"),
            text = "林舟与你是多年旧友，已经交给你旧书店的钥匙。后续是否归还，需要核对较新的原文。", sourceMessageIds = source.map { it.id }, sourceHash = ContextEngine.sourceHash(source)),
        KnowledgeNote(id = "agreement", title = "清晨在钟楼汇合", kind = "agreement", entity = "agreement:钟楼汇合",
            text = "约定明天清晨在钟楼下汇合，寻找航海日志；目前尚未兑现。", sourceMessageIds = source.map { it.id }, sourceHash = ContextEngine.sourceHash(source)),
        KnowledgeNote(id = "archive", title = "已归档第 1–2 条原文", kind = "archive", text = "归档范围索引；需要细节时读取对应原文。",
            sourceMessageIds = source.map { it.id }, sourceHash = ContextEngine.sourceHash(source))
    )
    return chat.copy(summaries = listOf(summary), knowledgeNotes = notes.map { it.copy(createdAt = summary.createdAt) })
}

@PreviewTest
@Preview(name = "知识库按人物关系事件分类", showBackground = true, widthDp = 360, heightDp = 940, fontScale = 1.2f)
@Composable
fun conversationMemoryOrganizedPreview() {
    val chat = organizedMemoryPreviewChat()
    PocketTheme("light") {
        ConversationMemoryContent(chat, UiSession(loading = false), onRebuild = {}, onStop = {}, onDismiss = {}, initialSection = 1, initialCategory = "person")
    }
}

@PreviewTest
@Preview(name = "知识条目别名与原文入口", showBackground = true, widthDp = 320, heightDp = 900, fontScale = 1.3f)
@Composable
fun conversationMemoryKnowledgeDetailPreview() {
    val chat = organizedMemoryPreviewChat()
    PocketTheme("dark") {
        ConversationMemoryContent(chat, UiSession(loading = false), onRebuild = {}, onStop = {}, onDismiss = {}, initialSection = 1,
            initialSelectionId = "person", initialSource = MemoryBrowser.source(chat, chat.knowledgeNotes.first()))
    }
}

@PreviewTest
@Preview(name = "独立会话记忆与原文入口", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
fun conversationMemoryPreview() {
    val chat = memoryPreviewChat()
    PocketTheme("light") {
        ConversationMemoryContent(chat, UiSession(loading = false), onRebuild = {}, onStop = {}, onDismiss = {})
    }
}

@PreviewTest
@Preview(name = "会话记忆重建进度窄屏大字", showBackground = true, widthDp = 320, heightDp = 900, fontScale = 1.3f)
@Composable
fun conversationMemoryNarrowLargeTextPreview() {
    val chat = memoryPreviewChat()
    PocketTheme("light") {
        ConversationMemoryContent(chat, UiSession(loading = false, chatTasks = mapOf(chat.id to app.pockettavern.domain.ChatTask(app.pockettavern.domain.ChatTask.REBUILD)), memoryRebuild = MemoryRebuildState(chatId = chat.id, phase = "running", completed = 2, total = 5, message = "正在发送第 3 次请求（预计共 5 次）\n冷咖啡 · deepseek-v4-flash · 窗口 32768 tokens · 128 条原文", noteCount = 2)), onRebuild = {}, onStop = {}, onDismiss = {}, initialSection = 1)
    }
}

@PreviewTest
@Preview(name = "长错误仍可阅读记忆窄屏大字", showBackground = true, widthDp = 320, heightDp = 900, fontScale = 1.3f)
@Composable
fun conversationMemoryLongErrorPreview() {
    val chat = memoryPreviewChat()
    PocketTheme("light") {
        ConversationMemoryContent(chat, UiSession(loading = false, memoryRebuild = MemoryRebuildState(chatId = chat.id, phase = "error", completed = 2, total = 5, message = "平台请求失败：模型返回 HTTP 502。旧摘要和原文未修改。\n" + "平台暂时不可用，请稍后重试。完整错误信息会保留在此处，可上下滚动查看和选择复制。".repeat(12), noteCount = 2)), onRebuild = {}, onStop = {}, onDismiss = {})
    }
}

@PreviewTest
@Preview(name = "分段记忆对应原文完整阅读", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
fun conversationMemorySourcePreview() {
    val chat = memoryPreviewChat()
    PocketTheme("light") {
        ConversationMemoryContent(chat, UiSession(loading = false), onRebuild = {}, onStop = {}, onDismiss = {}, initialSection = 1, initialSelectionId = "note", initialShowSources = true, initialSource = MemoryBrowser.source(chat, chat.knowledgeNotes.first()))
    }
}

@PreviewTest
@Preview(name = "摘要增量范围与累计原文", showBackground = true, widthDp = 360, heightDp = 900, fontScale = 1.3f)
@Composable
fun conversationMemoryIncrementalRangePreview() {
    val original = memoryPreviewChat()
    val summary = original.summaries.single().copy(compression = CompressionRecord(
        baseSummaryId = "previous", reusedThroughMessageId = "first",
        firstMessageId = "second", lastMessageId = "third", messageCount = 2,
        estimatedSourceTokens = 450, segmentCount = 1, reason = "ACTIVE"
    ))
    val chat = original.copy(summaries = listOf(summary))
    PocketTheme("light") {
        ConversationMemoryContent(chat, UiSession(loading = false), onRebuild = {}, onStop = {}, onDismiss = {},
            initialSection = 0, initialSelectionId = summary.id, initialSource = MemoryBrowser.source(chat, summary))
    }
}

private fun omittedNamePreviewChat(): Chat {
    val messages = listOf(
        Message(id = "attachment", role = "user", content = "这是前面的会话记录。", attachments = listOf(
            Attachment(name = "早期会话.md", mime = "text/markdown", dataUrl = "乔宁曾把旧书店的钥匙交给你。你们已经确认彼此是多年的旧友。")
        ), createdAt = 1_792_700_000_000),
        Message(id = "recent", role = "assistant", content = "你继续向港口走去。", createdAt = 1_792_700_060_000)
    )
    val summary = Summary(id = "current", text = "当前进度：你正在去港口的路上。", throughMessageId = "recent", sourceHash = ContextEngine.sourceHash(messages), createdAt = 1_792_700_180_000, modelId = "deepseek-v4-flash")
    val note = KnowledgeNote(id = "early-note", text = "乔宁是多年旧友，已经交给你旧书店钥匙。", sourceMessageIds = listOf("attachment"), sourceHash = ContextEngine.sourceHash(messages.take(1)), createdAt = 1_792_700_120_000)
    return Chat(id = "omitted-name", title = "早期人物关系 · 查完整记录", messages = messages, summaries = listOf(summary), activeSummaryId = summary.id, knowledgeNotes = listOf(note))
}

@PreviewTest
@Preview(name = "摘要未命中但全库有原文入口", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
fun conversationMemoryOmittedNamePreview() {
    val chat = omittedNamePreviewChat()
    PocketTheme("dark") {
        ConversationMemoryContent(chat, UiSession(loading = false), onRebuild = {}, onStop = {}, onDismiss = {}, initialSection = 0, initialSelectionId = "current", initialDetailQuery = "乔宁", initialSource = MemoryBrowser.source(chat, chat.summaries.first()))
    }
}

@PreviewTest
@Preview(name = "全库搜索同时命中分段与附件原文", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
fun conversationMemoryGlobalSearchPreview() {
    val chat = omittedNamePreviewChat()
    PocketTheme("dark") {
        ConversationMemoryContent(chat, UiSession(loading = false), onRebuild = {}, onStop = {}, onDismiss = {}, initialQuery = "乔宁")
    }
}

private fun completedRebuildPreviewChat(): Chat {
    val messages = (1..128).map { number ->
        Message(id = "original-$number", role = if (number % 2 == 0) "assistant" else "user", content = "原文第 $number 条：继续记录港口往事中的人物与约定。", createdAt = 1_792_700_000_000 + number * 60_000L)
    }
    val old = Summary(id = "before-rebuild", text = "早期记录：林舟把旧书店钥匙交给你，约定明天清晨汇合。", throughMessageId = messages[18].id, sourceHash = ContextEngine.sourceHash(messages.take(19)), createdAt = 1_792_701_140_000, modelId = "deepseek-v4-flash")
    val rebuilt = Summary(id = "rebuilt", text = "完整重建导览：从旧书店的钥匙与清晨之约，到港口调查及最新进展。详细人物关系与事件保存在各段记忆中。", throughMessageId = messages.last().id, sourceHash = ContextEngine.sourceHash(messages), createdAt = 1_792_707_740_000, modelId = "deepseek-v4-flash")
    val notes = messages.chunked(26).mapIndexed { index, sources ->
        KnowledgeNote(id = "rebuilt-note-$index", text = "第 ${index + 1} 段：人物关系、已发生事件与未完成约定。", sourceMessageIds = sources.map { it.id }, sourceHash = ContextEngine.sourceHash(sources), createdAt = rebuilt.createdAt)
    }
    return Chat(id = "rebuild-result-preview", title = "港口往事 · 128 条原文", messages = messages, summaries = listOf(old, rebuilt), activeSummaryId = rebuilt.id, knowledgeNotes = notes)
}

private fun completedRebuildPreviewState(chat: Chat) = UiSession(
    loading = false,
    memoryRebuild = MemoryRebuildState(chatId = chat.id, phase = "success", completed = 5, total = 5, noteCount = 5, summaryId = "rebuilt", sourceMessageCount = 128, finishedAt = 1_792_707_760_000)
)

@PreviewTest
@Preview(name = "重建结果当前生效窄屏大字", showBackground = true, widthDp = 320, heightDp = 1000, fontScale = 1.3f)
@Composable
fun conversationMemoryRebuildSuccessNarrowPreview() {
    val chat = completedRebuildPreviewChat()
    PocketTheme("light") {
        ConversationMemoryContent(chat, completedRebuildPreviewState(chat), onRebuild = {}, onStop = {}, onDismiss = {})
    }
}

@PreviewTest
@Preview(name = "重建成功但当前为旧摘要窄屏大字", showBackground = true, widthDp = 320, heightDp = 1000, fontScale = 1.3f)
@Composable
fun conversationMemoryRebuildMismatchNarrowPreview() {
    val chat = completedRebuildPreviewChat().copy(activeSummaryId = "before-rebuild")
    val old = chat.summaries.first()
    PocketTheme("light") {
        ConversationMemoryContent(chat, completedRebuildPreviewState(chat), onRebuild = {}, onStop = {}, onDismiss = {}, initialSection = 0, initialSelectionId = old.id, initialSource = MemoryBrowser.source(chat, old))
    }
}

@PreviewTest
@Preview(name = "重建后原文修改结果失效窄屏大字", showBackground = true, widthDp = 320, heightDp = 1000, fontScale = 1.3f)
@Composable
fun conversationMemoryRebuildInvalidNarrowPreview() {
    val original = completedRebuildPreviewChat()
    val chat = original.copy(messages = original.messages.mapIndexed { index, message ->
        if (index == 0) message.copy(content = "这条原文已由用户修改，旧摘要的来源需要重新核对。") else message
    })
    PocketTheme("light") {
        ConversationMemoryContent(chat, completedRebuildPreviewState(chat), onRebuild = {}, onStop = {}, onDismiss = {}, initialSection = 0, initialSelectionId = "rebuilt", initialSource = MemoryBrowser.source(chat, chat.summaries.last()))
    }
}
