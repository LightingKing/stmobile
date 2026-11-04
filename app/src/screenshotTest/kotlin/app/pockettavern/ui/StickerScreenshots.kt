package app.pockettavern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.Sticker
import app.pockettavern.domain.StickerPack
import com.android.tools.screenshot.PreviewTest

// Drawn circles stand in for imported images; layout and markup handling are what is checked.
private fun previewBitmap(color: Color): ImageBitmap = ImageBitmap(96, 96).also { bitmap ->
    Canvas(bitmap).drawCircle(Offset(48f, 48f), 44f, Paint().apply { this.color = color })
}

private val PreviewStickers = listOf(
    Sticker(key = "hello", name = "hello", description = "挥手打招呼", file = "p/001.png"),
    Sticker(key = "干饭", name = "干饭", description = "戴锅盔吃白米饭", file = "p/002.png"),
    Sticker(key = "think", name = "think", description = "歪头思考", file = "p/003.png"),
    Sticker(key = "angry", name = "angry", description = "抱臂鼓脸", file = "p/004.png"),
)
private val PreviewColors = listOf(Color(0xFF4F8DF7), Color(0xFFF2A93B), Color(0xFF52B788), Color(0xFFE76F7A))
private val loadPreview: (Sticker) -> ImageBitmap? = { sticker -> previewBitmap(PreviewColors[PreviewStickers.indexOfFirst { it.key == sticker.key }.coerceAtLeast(0) % PreviewColors.size]) }

@Composable
private fun StickerPreviewSurface(content: @Composable () -> Unit) {
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@PreviewTest
@Preview(name = "回复中的表情", showBackground = true, widthDp = 360, heightDp = 520)
@Composable
fun stickerReplyPreview() = StickerPreviewSurface {
    AssistantTextWithSticker("早呀！今天也要好好吃饭哦，米饭已经帮你盛好了。\n\n[表情:干饭]", streaming = false, PreviewStickers, loadPreview)
    Text("流式输出中，半截标记不显示：", style = MaterialTheme.typography.labelSmall)
    AssistantTextWithSticker("嗯……让本鱼想想\n[表情:thi", streaming = true, PreviewStickers, loadPreview)
}

@PreviewTest
@Preview(name = "设置里的表情包", showBackground = true, widthDp = 360, heightDp = 640, fontScale = 1.3f)
@Composable
fun stickerSettingsPreview() = StickerPreviewSurface {
    StickerPacksCard(listOf(StickerPack(id = "p", name = "大肥鱼表情", license = "personal", stickers = PreviewStickers)), busy = false,
        onImport = {}, onEnable = { _, _ -> }, onDelete = {}, load = loadPreview)
}

@PreviewTest
@Preview(name = "会话表情频率", showBackground = true, widthDp = 360, heightDp = 360, fontScale = 1.3f)
@Composable
fun stickerFrequencyPreview() = StickerPreviewSurface {
    StickerFrequencyChooser("standard", available = 35, enabled = true) {}
    StickerFrequencyChooser("off", available = 0, enabled = true) {}
}
