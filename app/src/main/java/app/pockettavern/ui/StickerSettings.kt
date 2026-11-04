@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pockettavern.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.Sticker
import app.pockettavern.domain.StickerPack
import app.pockettavern.engine.StickerFrequency

/** Settings → 表情包: import, enable and delete packs. Packs ship with no images; the user imports them. */
@Composable
internal fun StickerPacksCard(packs: List<StickerPack>, busy: Boolean, onImport: () -> Unit, onEnable: (String, Boolean) -> Unit, onDelete: (String) -> Unit,
                              load: (Sticker) -> ImageBitmap?) {
    var pendingDelete by remember { mutableStateOf<StickerPack?>(null) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("导入 ZIP 表情包后，在会话设置里选择发图频率，模型就会在合适时发一张，由 App 显示图片。" +
                "支持带 stickers.json、Markdown 表格索引或 manifest.json 的包，也可以是只放图片的 ZIP（用文件名当表情名）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onImport, Modifier.fillMaxWidth(), enabled = !busy) { Text("导入表情包（ZIP）") }
            if (packs.isEmpty()) Text("还没有表情包。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            packs.forEach { pack ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pack.name.ifBlank { "未命名表情包" }, fontWeight = FontWeight.SemiBold)
                        Text("${pack.stickers.size} 张 · 许可：${pack.license.ifBlank { "未声明" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(pack.enabled, { onEnable(pack.id, it) }, enabled = !busy)
                    IconButton({ pendingDelete = pack }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "删除表情包“${pack.name}”") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pack.stickers.take(12).forEach { StickerImage(it, load, size = 52.dp) }
                }
                Text(pack.stickers.take(24).joinToString("、") { it.key } + if (pack.stickers.size > 24) " 等" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("图片只保存在本机，并随备份一起导出。许可标注为个人使用或非商用的表情包，请勿再分发。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    pendingDelete?.let { pack ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除表情包") },
            text = { Text("删除“${pack.name}”和它的 ${pack.stickers.size} 张图片？聊天里已经发过的这些表情将不再显示。") },
            confirmButton = { TextButton({ onDelete(pack.id); pendingDelete = null }) { Text("删除") } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("取消") } })
    }
}

/** Chat options: how often this chat may carry a sticker. The app enforces it from recent replies. */
@Composable
internal fun StickerFrequencyChooser(current: String, available: Int, enabled: Boolean, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StickerFrequency.entries.forEach { frequency ->
                FilterChip(selected = StickerFrequency.of(current) == frequency, onClick = { onSelect(frequency.id) },
                    enabled = enabled && (available > 0 || frequency == StickerFrequency.OFF), label = { Text(frequency.label) })
            }
        }
        Text(
            if (available == 0) "还没有启用的表情包，可到“设置 → 表情包”导入。"
            else "共 $available 张可用。“少/标准/多”分别约为最近 5 条回复里最多 1/2/4 张，由 App 控制；剧情外纠错和重写不发。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
