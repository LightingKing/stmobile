package app.pockettavern.ui

import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Attachment
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.ChatTask
import app.pockettavern.domain.CompressionProgress
import app.pockettavern.domain.Memory
import app.pockettavern.domain.MediaJob
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.Preset
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.SkillRecord
import app.pockettavern.domain.UiSession
import app.pockettavern.domain.WorldEntry

private const val PREVIEW_PROVIDER = "preview-provider"
private const val PREVIEW_MODEL = "preview-model"
private const val PREVIEW_CHAT = "preview-chat"
private const val PREVIEW_TIME = 1_735_689_600_000L
/** A fixed "now" for the chat list, 13 hours after [PREVIEW_TIME]. */
private const val PREVIEW_NOW = PREVIEW_TIME + 13 * 3_600_000L

private val PreviewActions = object : AppActions {
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

private val PreviewProvider = ProviderConfig(
    id = PREVIEW_PROVIDER,
    name = "本地预览平台",
    kind = "venice",
    baseUrl = "https://preview.invalid/api/v1",
    models = listOf(ModelInfo(id = PREVIEW_MODEL, name = "预览文本模型", type = "text", contextTokens = 128_000))
)

private val PreviewPreset = Preset(
    id = "preview-preset",
    name = "技术说明",
    systemPrompt = "请准确区分事实与推测。"
)

private fun previewSession(
    chat: Chat? = previewChat(),
    settings: AppSettings = AppSettings(darkMode = "light"),
    busy: Boolean = false,
    pendingAttachments: List<Attachment> = emptyList()
): UiSession {
    val chats = if (chat == null) emptyList() else listOf(chat)
    return UiSession(
        data = AppData(
            providers = listOf(PreviewProvider),
            chats = chats,
            presets = listOf(PreviewPreset),
            settings = settings
        ),
        selectedChatId = chat?.id.orEmpty(),
        loading = false,
        pendingAttachments = pendingAttachments,
        chatTasks = if (busy && chat != null) mapOf(chat.id to ChatTask()) else emptyMap(),
        chatOpen = chat != null
    )
}

/** The home list: two chats working at once, one waiting its turn, one with an unread share. */
private fun chatListSession(dark: Boolean = false): UiSession {
    val heimao = Character(id = "preview-heimao", name = "黑猫")
    val dafeiyu = Character(id = "preview-dafeiyu", name = "大肥鱼")
    fun chat(id: String, title: String, minutesAgo: Long, last: Message, characterId: String = "", unread: Int = 0, browse: Int = 0) = Chat(
        id = id, title = title, providerId = PREVIEW_PROVIDER, modelId = PREVIEW_MODEL, presetId = PreviewPreset.id, characterId = characterId,
        messages = listOf(Message(id = "$id-u", role = "user", content = "在吗", createdAt = PREVIEW_TIME), last),
        updatedAt = PREVIEW_NOW - minutesAgo * 60_000L, unread = unread, browsePerDay = browse)
    val chats = listOf(
        chat("list-1", "黑猫", 0, Message(id = "l1", role = "user", content = "今天看了什么番？"), heimao.id),
        chat("list-2", "极寒末世：从继承庄园开始", 0, Message(id = "l2", role = "user", content = "继续，写到暴风雪来的那一晚。")),
        chat("list-3", "大肥鱼", 34, Message(id = "l3", role = "assistant", content = "主人主人！刚刷到一个猫猫开会的视频，笑死我了 [sticker:laugh]"), dafeiyu.id, unread = 1, browse = 2),
        chat("list-4", "角色卡助手", 3, Message(id = "l4", role = "user", content = "帮我做一张图书管理员的角色卡")),
        chat("list-5", "周报整理", 60 * 24 * 3, Message(id = "l5", role = "assistant", content = "好的，周报我帮你捋成了三段。")),
        chat("list-6", "接口排查", 60 * 24 * 12, Message(id = "l6", role = "assistant", content = "", status = "error", errorMessage = "上游返回 502，请稍后重试"))
    )
    return UiSession(
        data = AppData(providers = listOf(PreviewProvider), chats = chats, characters = listOf(heimao, dafeiyu), presets = listOf(PreviewPreset),
            settings = AppSettings(darkMode = if (dark) "dark" else "light")),
        selectedChatId = "list-1", loading = false,
        chatTasks = mapOf("list-1" to ChatTask(), "list-2" to ChatTask(), "list-4" to ChatTask(queued = true))
    )
}

private fun previewChat(messages: List<Message> = normalMessages(), title: String = "接口响应排版") = Chat(
    id = PREVIEW_CHAT,
    title = title,
    providerId = PREVIEW_PROVIDER,
    modelId = PREVIEW_MODEL,
    presetId = PreviewPreset.id,
    messages = messages,
    updatedAt = PREVIEW_TIME
)

private fun normalMessages() = listOf(
    Message(
        id = "preview-user-1",
        role = "user",
        content = "请把这段日志整理成三点：连接建立、首个响应、重试条件。",
        createdAt = PREVIEW_TIME
    ),
    Message(
        id = "preview-assistant-1",
        role = "assistant",
        content = "整理结果\n\n1. **连接建立**：客户端完成 HTTPS 握手，并发送模型与消息上下文。\n2. **首个响应**：服务端返回首个流式片段后，界面立即开始显示。\n3. **重试条件**：只有请求尚未产生有效输出且错误可恢复时才建议重试。",
        createdAt = PREVIEW_TIME + 60_000L
    )
)

private fun longMessages() = listOf(
    Message(
        id = "preview-long-user",
        role = "user",
        content = "请给出一份移动端流式聊天排版检查清单。",
        createdAt = PREVIEW_TIME
    ),
    Message(
        id = "preview-long-assistant",
        role = "assistant",
        content = """# 移动端排版检查清单

先确认阅读路径，再检查交互反馈。长文应保持稳定的段落宽度、清晰的标题层级和足够的行间距。

## 内容层级

- 标题用于定位，不承担整段说明。
- 列表项使用短句，细节放在后续段落。
- 代码、日志和普通正文保持明显的视觉区分。

## 流式状态

生成中的消息需要明确显示当前状态；用户滚动查看历史时，新增片段不应强行把阅读位置拉回底部。停止、错误和中断状态都应该保留已有内容，方便继续处理。

```text
首个片段 -> 累积正文 -> 完成或中断
```

## 验收记录

在窄屏上检查标题换行、按钮触达区域和输入框高度；在大字体下检查底部输入区是否遮挡最后一条消息。截图只验证排版状态，不代表真实服务请求已成功。""".trimIndent(),
        createdAt = PREVIEW_TIME + 60_000L
    )
)

@PreviewTest
@Preview(name = "普通中文对话", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun chatConversationPreview() {
    PocketApp(previewSession(), PreviewActions)
}

@PreviewTest
@Preview(name = "长文阅读", showBackground = true, widthDp = 420, heightDp = 980)
@androidx.compose.runtime.Composable
fun chatLongChinesePreview() {
    PocketApp(previewSession(chat = previewChat(longMessages(), "移动端排版检查")), PreviewActions)
}

@PreviewTest
@Preview(name = "窄屏 320dp", showBackground = true, widthDp = 320, heightDp = 720)
@androidx.compose.runtime.Composable
fun chatNarrowPreview() {
    PocketApp(previewSession(), PreviewActions)
}

@PreviewTest
@Preview(name = "深色主题", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun chatDarkPreview() {
    PocketApp(previewSession(settings = AppSettings(darkMode = "dark")), PreviewActions)
}

@PreviewTest
@Preview(name = "200% 字体", showBackground = true, widthDp = 420, heightDp = 980, fontScale = 2f)
@androidx.compose.runtime.Composable
fun chatLargeTextPreview() {
    PocketApp(previewSession(), PreviewActions)
}

@PreviewTest
@Preview(name = "空态", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun chatEmptyPreview() {
    PocketApp(previewSession(chat = null), PreviewActions)
}

@PreviewTest
@Preview(name = "流式生成中", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun chatStreamingPreview() {
    val messages = normalMessages() + Message(
        id = "preview-assistant-streaming",
        role = "assistant",
        content = "正在整理第二条响应，当前片段会继续追加到正文中。",
        reasoning = "先确认已收到的字段，再按固定顺序输出可读结果。",
        status = "streaming",
        createdAt = PREVIEW_TIME + 120_000L
    )
    PocketApp(previewSession(chat = previewChat(messages, "流式响应"), busy = true), PreviewActions)
}

@PreviewTest
@Preview(name = "增量压缩范围窄屏大字", showBackground = true, widthDp = 320, heightDp = 820, fontScale = 1.3f)
@androidx.compose.runtime.Composable
fun chatIncrementalCompressionNarrowPreview() {
    val chat = previewChat(normalMessages() + Message(
        id = "preview-compression-user", role = "user", content = "接着刚才的剧情继续。", createdAt = PREVIEW_TIME + 120_000L
    ), "第三次压缩 · 保留旧记忆")
    PocketApp(previewSession(chat = chat, busy = true).copy(compressionProgress = CompressionProgress(
        chatId = chat.id,
        scope = "整轮输入约 60,000 tokens · 触发线 52,428（80%） · 日常输入上限 65,536\n其中 Skills 27,000 · 当前摘要 8,000 · 近期对话 21,000 · 其他固定内容 3,000 · 工具定义 1,000\n复用上次摘要，已覆盖原文第 1–160 条。\n本次仅整理新增原文第 161–186 条，共 26 条，约 12,422 tokens。",
        completed = 0, total = 1,
        message = "正在更新续聊摘要与知识：第 1 次请求，预计共 1 次（已等待 20s）"
    )), PreviewActions)
}

@PreviewTest
@Preview(name = "旧工具链未完成仍可重试", showBackground = true, widthDp = 320, heightDp = 820, fontScale = 1.3f)
@androidx.compose.runtime.Composable
fun chatUnfinishedToolTurnPreview() {
    val calls = """[{"id":"lookup-1","type":"function","function":{"name":"history_search","arguments":"{\"query\":\"旧约定\"}"}}]"""
    val messages = listOf(
        Message(id = "tool-user", content = "上次约好的路线是什么？", createdAt = PREVIEW_TIME),
        Message(id = "tool-assistant", role = "assistant", toolCallsJson = calls, finishReason = "tool_calls", createdAt = PREVIEW_TIME),
        Message(id = "tool-result", role = "tool", toolCallId = "lookup-1", content = "已找到对应原文片段。", createdAt = PREVIEW_TIME)
    )
    PocketApp(previewSession(chat = previewChat(messages, "原会话继续")), PreviewActions)
}

@PreviewTest
@Preview(name = "长回复流式跟随到底部", showBackground = true, widthDp = 420, heightDp = 720)
@androidx.compose.runtime.Composable
fun chatStreamingFollowsEndPreview() {
    val paragraphs = (1..12).joinToString("\n\n") { "第 $it 段：雪线沿着庄园围墙慢慢上涨，巡夜的人把新的读数写进本子。" }
    val messages = normalMessages() + Message(
        id = "preview-assistant-long-streaming",
        role = "assistant",
        content = "$paragraphs\n\n最新一行：输出仍在继续。",
        status = "streaming",
        createdAt = PREVIEW_TIME + 120_000L
    )
    PocketApp(previewSession(chat = previewChat(messages, "流式跟随"), busy = true), PreviewActions)
}

@PreviewTest
@Preview(name = "空回复与诊断", showBackground = true, widthDp = 420, heightDp = 900)
@androidx.compose.runtime.Composable
fun chatEmptyReplyDiagnosticsPreview() {
    val messages = normalMessages() + Message(
        id = "preview-assistant-error",
        role = "assistant",
        content = "已经收到请求，但正文没有完成。",
        status = "error",
        errorMessage = "模型返回空回复",
        finishReason = "length",
        requestId = "preview-request-123",
        createdAt = PREVIEW_TIME + 120_000L
    )
    PocketApp(previewSession(chat = previewChat(messages, "空回复诊断")), PreviewActions)
}

@PreviewTest
@Preview(name = "旧版本空回复", showBackground = true, widthDp = 420, heightDp = 760)
@androidx.compose.runtime.Composable
fun chatLegacyEmptyReplyPreview() {
    val messages = listOf(
        Message(id = "preview-user-empty", role = "user", content = "继续写下一段。", createdAt = PREVIEW_TIME),
        Message(id = "preview-assistant-empty", role = "assistant", createdAt = PREVIEW_TIME + 60_000L)
    )
    PocketApp(previewSession(chat = previewChat(messages, "旧版空回复")), PreviewActions)
}

@PreviewTest
@Preview(name = "内容过滤空回复", showBackground = true, widthDp = 420, heightDp = 820)
@androidx.compose.runtime.Composable
fun chatContentFilterPreview() {
    val messages = listOf(
        Message(id = "preview-user-filter", role = "user", content = "继续生成下一段。", createdAt = PREVIEW_TIME),
        Message(
            id = "preview-assistant-filter",
            role = "assistant",
            status = "error",
            errorMessage = "模型返回空回复",
            finishReason = "content_filter",
            requestId = "preview-response-filter",
            createdAt = PREVIEW_TIME + 60_000L
        )
    )
    PocketApp(previewSession(chat = previewChat(messages, "内容过滤诊断")), PreviewActions)
}

@PreviewTest
@Preview(name = "请求日志", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun requestDiagnosticsPreview() {
    PocketApp(previewSession().copy(appVersion = "0.1.7 (8)", diagnostics = "应用版本：0.1.7 (8)\n平台：Venice\n模型：Gemini 4 Argon\n结果：正文为空\nHTTP：200\n耗时：125 秒\n结束原因：length\n正文：0 字符\n思考：0 字符\n输入 token：8200\n输出 token：2048\n其中推理 token：2048\nrequest_id：preview-request-123\n\n计费状态：未知。请核对 Venice 账本。"), PreviewActions)
}

@PreviewTest
@Preview(name = "重开后废稿仍可重试", showBackground = true, widthDp = 380, heightDp = 820)
@androidx.compose.runtime.Composable
fun chatReopenedDiscardedReplyPreview() {
    val saved = previewChat(listOf(
        Message(id = "retry-user", content = "（重新生成）", intent = "rewrite", createdAt = PREVIEW_TIME),
        Message(id = "retry-error", role = "assistant", status = "error", errorMessage = "网络连接失败",
            contextExcluded = true, createdAt = PREVIEW_TIME + 60_000L)
    ), "退出后恢复当前会话")
    val reopened = app.pockettavern.data.DataCodec.decode(app.pockettavern.data.DataCodec.encode(AppData(chats = listOf(saved)))).chats.single()
    PocketApp(previewSession(chat = reopened), PreviewActions)
}

@PreviewTest
@Preview(name = "尚无回复也可重试", showBackground = true, widthDp = 320, heightDp = 760, fontScale = 1.3f)
@androidx.compose.runtime.Composable
fun chatReopenedUnansweredPreview() {
    PocketApp(previewSession(chat = previewChat(listOf(
        Message(id = "unanswered-user", content = "接着刚才的剧情继续。", createdAt = PREVIEW_TIME)
    ), "尚未收到回复")), PreviewActions)
}

@PreviewTest
@Preview(name = "待发送附件", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun chatPendingAttachmentsPreview() {
    val attachments = listOf(
        Attachment("preview-attachment-1", "request-log.txt", "text/plain", "/preview/request-log.txt"),
        Attachment("preview-attachment-2", "layout-reference.png", "image/png", "/preview/layout-reference.png")
    )
    PocketApp(previewSession(pendingAttachments = attachments), PreviewActions)
}

@PreviewTest
@Preview(name = "会话列表", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun chatListPreview() {
    androidx.compose.runtime.CompositionLocalProvider(LocalClock provides { PREVIEW_NOW }) { PocketApp(chatListSession(), PreviewActions) }
}

@PreviewTest
@Preview(name = "会话列表深色", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun chatListDarkPreview() {
    androidx.compose.runtime.CompositionLocalProvider(LocalClock provides { PREVIEW_NOW }) { PocketApp(chatListSession(dark = true), PreviewActions) }
}

@PreviewTest
@Preview(name = "会话列表窄屏大字", showBackground = true, widthDp = 320, heightDp = 760, fontScale = 1.5f)
@androidx.compose.runtime.Composable
fun chatListNarrowLargeTextPreview() {
    androidx.compose.runtime.CompositionLocalProvider(LocalClock provides { PREVIEW_NOW }) { PocketApp(chatListSession(), PreviewActions) }
}

private fun backgroundSession(scene: String, dark: Boolean): UiSession {
    val heimao = Character(id = "preview-heimao", name = "黑猫", look = app.pockettavern.domain.ChatLook("builtin:$scene"))
    val chat = previewChat(listOf(
        Message(id = "b1", role = "assistant", content = "……这么晚还来找我，前辈也是夜之眷属么。\n（打了个哈欠）别误会，我才不是在等你。", createdAt = PREVIEW_TIME),
        Message(id = "b2", role = "user", content = "新番看了吗，第一集我觉得还不错", createdAt = PREVIEW_TIME + 60_000L),
        Message(id = "b3", role = "assistant", content = "哼，作画勉强及格，剧本嘛……三流。\n不过片尾那首歌，吾姑且认可了。", createdAt = PREVIEW_TIME + 120_000L),
        Message(id = "b4", role = "user", content = "那周末一起补完？", createdAt = PREVIEW_TIME + 180_000L)
    ), "黑猫").copy(characterId = heimao.id, look = app.pockettavern.domain.ChatLook("builtin:$scene", dim = 20, bubbleClear = 15))
    return previewSession(chat = chat, settings = AppSettings(darkMode = if (dark) "dark" else "light")).let { it.copy(data = it.data.copy(characters = listOf(heimao))) }
}

@PreviewTest
@Preview(name = "聊天背景夜空", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun chatBackgroundNightPreview() {
    PocketApp(backgroundSession("night", dark = false), PreviewActions)
}

@PreviewTest
@Preview(name = "聊天背景海底深色", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun chatBackgroundSeaDarkPreview() {
    PocketApp(backgroundSession("sea", dark = true), PreviewActions)
}

@PreviewTest
@Preview(name = "聊天背景暖灯窄屏大字", showBackground = true, widthDp = 320, heightDp = 760, fontScale = 1.5f)
@androidx.compose.runtime.Composable
fun chatBackgroundLampLargeTextPreview() {
    PocketApp(backgroundSession("lamp", dark = false), PreviewActions)
}

@PreviewTest
@Preview(name = "聊天外观", showBackground = true, widthDp = 420, heightDp = 760)
@androidx.compose.runtime.Composable
fun chatAppearancePreview() {
    val session = backgroundSession("night", dark = false)
    val chat = session.data.chats.single()
    PocketTheme("light") {
        androidx.compose.material3.Surface {
            ChatAppearanceContent(chat, session.data.characters.single(), AppSettings(userAvatar = ""), PreviewActions)
        }
    }
}
