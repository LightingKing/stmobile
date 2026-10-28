package app.pockettavern.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Chat
import app.pockettavern.domain.KnowledgeNote
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.UiSession
import app.pockettavern.provider.DoubaoResearchClient
import com.android.tools.screenshot.PreviewTest

private val researchProvider = ProviderConfig(
    id = "ark-research", name = "豆包·火山方舟", kind = "openai",
    baseUrl = DoubaoResearchClient.DEFAULT_BASE_URL,
    models = listOf(ModelInfo(id = DoubaoResearchClient.DEFAULT_MODEL, name = "豆包研究模型"))
)

private fun researchPreviewChat(): Chat = Chat(
    id = "research-preview", title = "港口往事", modelId = "deepseek-v4.1-flash-1017",
    knowledgeNotes = listOf(KnowledgeNote(
        id = "research-note", sourceKind = "research", createdAt = 1_792_790_000_000,
        text = """【联网研究：角色林舟的穿衣风格】
            |研究时间：2026-10-24 12:00
            |示例数据，用于界面预览。
            |
            |【资料事实】风格：资料中描述了深色外套与常用的帆布包。
            |来源：角色资料示例
            |https://example.org/characters/linzhou
            |
            |【推测】性格：从公开对话的表现看，他可能更习惯先观察再表达。
            |依据：对话中多次先询问细节，再提出自己的意见；这不是性格定论。
            |
            |【虚构设定】风格：为了港口场景，补写外套袖口留有盐渍、包带因长期使用而褪色。
            |这部分为小说补写，不是现实资料。
        """.trimMargin()
    ))
)

@Composable
private fun ResearchPreviewFrame(configured: Boolean = true, details: Boolean = false, searchMode: String = "direct", searchKey: Boolean = configured) {
    val chat = researchPreviewChat()
    PocketTheme("light") {
        ResearchContent(
            chat = chat,
            state = UiSession(
                data = AppData(providers = if (configured) listOf(researchProvider) else emptyList(), settings = AppSettings(researchSearchMode = searchMode)),
                loading = false, hasKeys = if (configured) setOf(researchProvider.id) else emptySet(), hasResearchSearchKey = searchKey
            ),
            onConfigure = {}, onStart = { _, _, _, _, _, _, _ -> }, onDismiss = {}, onSaveSearchKey = {},
            initialSubject = "林舟（《港口往事》中的虚构角色）",
            initialSection = if (details) 1 else 0,
            initialNoteId = if (details) "research-note" else null
        )
    }
}

@PreviewTest
@Preview(name = "豆包联网研究配置", showBackground = true, widthDp = 420, heightDp = 1000)
@Composable
fun researchReadyPreview() { ResearchPreviewFrame() }

@PreviewTest
@Preview(name = "豆包研究窄屏大字", showBackground = true, widthDp = 320, heightDp = 900, fontScale = 1.3f)
@Composable
fun researchNarrowPreview() { ResearchPreviewFrame() }

@PreviewTest
@Preview(name = "豆包研究未配置API", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
fun researchMissingProviderPreview() { ResearchPreviewFrame(configured = false) }

@PreviewTest
@Preview(name = "研究资料全文与来源", showBackground = true, widthDp = 360, heightDp = 1050)
@Composable
fun researchNotePreview() { ResearchPreviewFrame(details = true) }

@PreviewTest
@Preview(name = "方舟标准搜索无需独立Key", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
fun researchNativeSearchPreview() { ResearchPreviewFrame(searchMode = "native", searchKey = false) }

@PreviewTest
@Preview(name = "Custom搜索未保存Key", showBackground = true, widthDp = 320, heightDp = 1050, fontScale = 1.15f)
@Composable
fun researchMissingSearchKeyPreview() { ResearchPreviewFrame(searchKey = false) }
