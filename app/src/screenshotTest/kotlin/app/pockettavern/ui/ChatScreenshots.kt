package app.pockettavern.ui

import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Attachment
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
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
        busy = busy,
        loading = false,
        pendingAttachments = pendingAttachments
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
@Preview(name = "待发送附件", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun chatPendingAttachmentsPreview() {
    val attachments = listOf(
        Attachment("preview-attachment-1", "request-log.txt", "text/plain", "/preview/request-log.txt"),
        Attachment("preview-attachment-2", "layout-reference.png", "image/png", "/preview/layout-reference.png")
    )
    PocketApp(previewSession(pendingAttachments = attachments), PreviewActions)
}
