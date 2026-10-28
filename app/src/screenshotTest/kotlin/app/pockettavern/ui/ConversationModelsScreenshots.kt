package app.pockettavern.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.Modifier
import app.pockettavern.data.DataCodec
import app.pockettavern.domain.*
import com.android.tools.screenshot.PreviewTest

private val ModelPickerActions = object : AppActions {
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

private val PickerChat = Chat(
    id = "picker-chat",
    title = "冷咖啡模型验证",
    providerId = "cool-coffee",
    modelId = "cool-text"
)

private val PickerProviders = listOf(
    ProviderConfig(
        id = "venice",
        name = "Venice",
        kind = "venice",
        models = listOf(ModelInfo(id = "venice-text", name = "Venice 中文模型", type = "text"))
    ),
    ProviderConfig(
        id = "cool-coffee",
        name = "冷咖啡",
        kind = "openai",
        baseUrl = "https://coffee.example/v1",
        models = listOf(
            // Simulate an old cached catalog; the production decoder upgrades type=model to 文本.
            ModelInfo(id = "cool-text", name = "冷咖啡·长篇写作", type = "model", contextTokens = 131_072),
            ModelInfo(id = "cool-image", name = "冷咖啡·插画", type = "image"),
            ModelInfo(id = "cool-offline", name = "冷咖啡·离线缓存", type = "text", offline = true)
        )
    )
)

private val SameNameChat = Chat(id = "same-name-chat", title = "同名模型", providerId = "venice", modelId = "deepseek-v4-1-flash-1017")

private val SameNameProviders = listOf(
    ProviderConfig(
        id = "venice",
        name = "Venice",
        kind = "venice",
        models = listOf(
            ModelInfo(id = "deepseek-v4-1-flash", name = "DeepSeek V4.1 Flash", type = "text", contextTokens = 1_000_000),
            ModelInfo(id = "deepseek-v4-1-flash-1017", name = "DeepSeek V4.1 Flash", type = "text", contextTokens = 1_000_000),
            ModelInfo(id = "venice-uncensored", name = "Venice Uncensored", type = "text", contextTokens = 32_768)
        )
    )
)

private fun pickerSession(status: String = "", providers: List<ProviderConfig> = PickerProviders, chat: Chat = PickerChat): UiSession {
    val restored = DataCodec.decode(DataCodec.encode(AppData(
        providers = providers,
        chats = listOf(chat),
        settings = AppSettings(darkMode = "light")
    )))
    return UiSession(
        data = restored,
        selectedChatId = chat.id,
        loading = false,
        status = status
    )
}

@androidx.compose.runtime.Composable
private fun ModelPickerPreview(
    state: UiSession,
    initialQuery: String = "",
    initialProviderId: String = "",
    chat: Chat = PickerChat
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ConversationModelsContent(
            chat = chat,
            state = state,
            actions = ModelPickerActions,
            initialQuery = initialQuery,
            initialProviderId = initialProviderId,
            onDismiss = {}
        )
    }
}

@PreviewTest
@Preview(name = "第三方模型筛选后", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun conversationModelsThirdPartyPreview() {
    PocketTheme("light") {
        ModelPickerPreview(pickerSession(), initialProviderId = "cool-coffee")
    }
}

@PreviewTest
@Preview(name = "模型筛选空态与恢复", showBackground = true, widthDp = 420, heightDp = 860)
@androidx.compose.runtime.Composable
fun conversationModelsEmptyPreview() {
    PocketTheme("light") {
        ModelPickerPreview(pickerSession("刷新目录失败：请检查平台密钥"), initialQuery = "找不到的模型", initialProviderId = "cool-coffee")
    }
}

@PreviewTest
@Preview(name = "窄屏大字模型面板", showBackground = true, widthDp = 360, heightDp = 760, fontScale = 1.3f)
@androidx.compose.runtime.Composable
fun conversationModelsNarrowLargeTextPreview() {
    PocketTheme("light") {
        ModelPickerPreview(pickerSession(), initialProviderId = "cool-coffee")
    }
}

@PreviewTest
@Preview(name = "同名模型显示 ID 窄屏大字", showBackground = true, widthDp = 360, heightDp = 760, fontScale = 1.3f)
@androidx.compose.runtime.Composable
fun conversationModelsSameNamePreview() {
    PocketTheme("light") {
        ModelPickerPreview(pickerSession(providers = SameNameProviders, chat = SameNameChat), chat = SameNameChat)
    }
}

@PreviewTest
@Preview(name = "同名模型顶部显示 ID 窄屏", showBackground = true, widthDp = 320, heightDp = 640)
@androidx.compose.runtime.Composable
fun conversationHeaderSameNamePreview() {
    PocketApp(pickerSession(providers = SameNameProviders, chat = SameNameChat), ModelPickerActions)
}
