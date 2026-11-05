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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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

/**
 * Settings → 表情包: packs kept on the PC writing server (null until read). [legacy] are packs
 * imported onto the phone before 0.1.71; chats no longer use them and they can move to the PC.
 */
@Composable
internal fun StickerPacksCard(packs: List<StickerPack>?, busy: Boolean, onImport: () -> Unit, onEnable: (String, Boolean) -> Unit, onDelete: (String) -> Unit,
                              load: (Sticker) -> ImageBitmap?, pcAvailable: Boolean = true, error: String = "",
                              legacy: List<StickerPack> = emptyList(), onMigrate: () -> Unit = {}) {
    var pendingDelete by remember { mutableStateOf<StickerPack?>(null) }
    val hint = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("表情包保存在电脑写作服务上。导入 ZIP 后，在用电脑写作服务的会话里选择发图频率，模型就会在合适时发一张；用其他平台聊天时不发图。" +
                "支持带 stickers.json、Markdown 表格索引或 manifest.json 的包，也可以是只放图片的 ZIP（用文件名当表情名）。",
                style = MaterialTheme.typography.bodySmall, color = hint)
            if (!pcAvailable) Text("还没有添加电脑写作服务平台。在模型页新增“本机写作服务”平台后，才能导入和使用表情包。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onImport, Modifier.fillMaxWidth(), enabled = !busy && pcAvailable) { Text("导入表情包到电脑（ZIP）") }
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            when {
                !pcAvailable -> Unit
                packs == null -> Text(if (error.isBlank()) "正在读取电脑上的表情包…" else "读不到电脑上的表情包，请先在“电脑连接”检查连接。",
                    style = MaterialTheme.typography.bodySmall, color = hint)
                packs.isEmpty() -> Text("电脑上还没有表情包。", style = MaterialTheme.typography.bodySmall, color = hint)
            }
            packs.orEmpty().forEach { pack ->
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
            if (legacy.isNotEmpty()) {
                HorizontalDivider()
                Text("手机上还有 ${legacy.size} 个旧表情包（共 ${legacy.sumOf { it.stickers.size }} 张），现在不会再用于聊天：" +
                    legacy.joinToString("、") { it.name.ifBlank { "未命名" } }, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onMigrate, Modifier.weight(1f), enabled = !busy && pcAvailable) { Text("移到电脑") }
                    OutlinedButton({ legacy.forEach { onDelete(it.id) } }, Modifier.weight(1f), enabled = !busy) { Text("直接删除") }
                }
                Text("移到电脑后手机上的副本会删除。和电脑上已有表情同名的，会自动加上数字。", style = MaterialTheme.typography.labelSmall, color = hint)
            }
            Text("图片保存在电脑上，手机只缓存看过的图。许可标注为个人使用或非商用的表情包，请勿再分发。",
                style = MaterialTheme.typography.labelSmall, color = hint)
        }
    }
    pendingDelete?.let { pack ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除表情包") },
            text = { Text("从电脑上删除“${pack.name}”和它的 ${pack.stickers.size} 张图片？聊天里已经发过的这些表情将不再显示。") },
            confirmButton = { TextButton({ onDelete(pack.id); pendingDelete = null }) { Text("删除") } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("取消") } })
    }
}

/** Chat options: how often this chat may carry a sticker. The app enforces it from recent replies. */
@Composable
internal fun StickerFrequencyChooser(current: String, available: Int, enabled: Boolean, onPc: Boolean = true, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StickerFrequency.entries.forEach { frequency ->
                FilterChip(selected = StickerFrequency.of(current) == frequency, onClick = { onSelect(frequency.id) },
                    enabled = enabled && (available > 0 || frequency == StickerFrequency.OFF), label = { Text(frequency.label) })
            }
        }
        Text(
            if (!onPc) "表情包存放在电脑写作服务上，这个会话改用电脑写作服务的模型后才能发图。"
            else if (available == 0) "电脑上还没有启用的表情包，可到“设置 → 表情包”导入。"
            else "共 $available 张可用。“少/标准/多”分别约为最近 5 条回复里最多 1/2/4 张，由 App 控制；剧情外纠错和重写不发。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
