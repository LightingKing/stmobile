@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.pockettavern.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.PcBackup
import app.pockettavern.domain.PcBackupPolicy
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.PcLinkState
import app.pockettavern.domain.PcSettings
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.readableSize
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val BackupTime = DateTimeFormatter.ofPattern("M月d日 HH:mm")

private fun backupTime(instant: Instant, zone: ZoneId) = BackupTime.format(instant.atZone(zone))

private fun backupTime(iso: String, zone: ZoneId) = runCatching { backupTime(Instant.parse(iso), zone) }.getOrDefault(iso)

/** Which address the last request used, and how to get the at-home LAN switch when it is not set up. */
internal fun routeText(server: ProviderConfig, route: String): String = when {
    server.lanBaseUrl.isBlank() -> "没有填家里的局域网地址：一直走上面这个地址。在平台里填上后，在家会自动走内网。"
    route == "lan" -> "当前线路：家里的局域网（已确认是你的电脑）"
    route == "remote" -> "当前线路：远程（不在家，或连不上家里的电脑）"
    else -> "下次请求时自动判断走内网还是远程"
}

private fun reasonLabel(reason: String) = when (reason) {
    "auto" -> "自动"
    "pre-restore" -> "恢复前自动保存"
    else -> "手动"
}

/** Settings → 电脑连接: whether the PC writing server answers, plus backups to and restores from it. */
@Composable
internal fun PcLinkCard(
    link: PcLinkState,
    servers: List<ProviderConfig>,
    locked: Boolean,
    onCheck: () -> Unit,
    onBackup: () -> Unit,
    onLoadBackups: () -> Unit,
    onRestore: (String) -> Unit,
    onAutoBackup: (Boolean) -> Unit,
    onChooseProvider: (String) -> Unit,
    models: List<ModelInfo> = emptyList(),
    onLoadSettings: () -> Unit = {},
    onSaveSettings: (PcSettings, PcSettings) -> Unit = { _, _ -> },
    onRefreshModels: () -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault()
) {
    var showRestore by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val idle = link.busy.isBlank()
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (servers.isEmpty()) {
                Text("还没有添加电脑写作服务。在“模型”页新增平台，协议选“本机写作服务”，地址和 Token 填电脑上“手机连接.txt”里的内容。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val server = servers.firstOrNull { it.id == link.providerId } ?: servers.first()
            if (servers.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                servers.forEach { FilterChip(selected = it.id == server.id, onClick = { onChooseProvider(it.id) }, enabled = idle, label = { Text(it.name) }) }
            }
            Column {
                Text(server.name, fontWeight = FontWeight.SemiBold)
                Text(server.baseUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (server.lanBaseUrl.isNotBlank()) Text("家里：${server.lanBaseUrl}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(routeText(server, link.route), style = MaterialTheme.typography.labelSmall,
                    color = if (link.route == "lan") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PcStatusLine(link)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onCheck, Modifier.weight(1f), enabled = idle) { Text(if (link.busy == "check") "正在检查…" else "检查连接") }
                OutlinedButton({ showSettings = true; onLoadSettings() }, Modifier.weight(1f), enabled = idle) { Text("电脑设置…") }
            }
            HorizontalDivider()
            Text("备份到电脑", fontWeight = FontWeight.SemiBold)
            Text(if (link.lastBackupAt > 0) "上次备份：${backupTime(Instant.ofEpochMilli(link.lastBackupAt), zone)}" +
                    (if (link.lastBackupSize > 0) " · ${readableSize(link.lastBackupSize)}" else "") else "这台手机还没有备份到电脑。",
                style = MaterialTheme.typography.bodySmall)
            if (link.lastBackupError.isNotBlank()) Text("上次备份失败：${link.lastBackupError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("离开 App 时自动备份", Modifier.weight(1f))
                Switch(link.autoBackup, onAutoBackup)
            }
            Text("距上次备份超过 ${PcBackupPolicy.INTERVAL_MILLIS / 3_600_000} 小时才会执行，数据没有变化时不重复上传。" +
                    "备份包含会话、角色、设置、媒体和表情图片，不含 API Key。电脑保留最近 ${link.status?.backupKeep?.takeIf { it > 0 } ?: 20} 份。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onBackup, Modifier.fillMaxWidth(), enabled = idle && !locked) { Text(if (link.busy == "backup") "正在备份…" else "立即备份到电脑") }
            OutlinedButton({ showRestore = true; onLoadBackups() }, Modifier.fillMaxWidth(), enabled = idle && !locked) {
                Text(if (link.busy == "restore") "正在恢复…" else "从电脑恢复…")
            }
            if (link.error.isNotBlank() && link.status != null) Text(link.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    if (showRestore) PcRestoreDialog(link, zone, onRefresh = onLoadBackups, onRestore = { onRestore(it); showRestore = false }) { showRestore = false }
    if (showSettings) PcSettingsDialog(link, models, onRetry = onLoadSettings, onRefreshModels = onRefreshModels,
        onSave = { original, edited -> onSaveSettings(original, edited); showSettings = false }) { showSettings = false }
}

@Composable
private fun PcStatusLine(link: PcLinkState) {
    val status = link.status
    val colors = MaterialTheme.colorScheme
    val (dot, title) = when {
        link.busy == "check" -> colors.outline to "正在检查连接…"
        status == null && link.error.isNotBlank() -> colors.error to "未连接"
        status == null -> colors.outline to "尚未检查"
        status.ready -> colors.primary to "已连接 · 写作服务 ${status.version}"
        else -> colors.tertiary to "已连接 · 写作服务 ${status.version}（不能生成）"
    }
    val details = when {
        link.busy == "check" -> emptyList()
        status == null && link.error.isNotBlank() -> listOf(link.error)
        status == null -> listOf("点“检查连接”确认电脑是否在线。")
        else -> listOfNotNull(
            if (status.ready) "默认模型：${status.defaultModelName.ifBlank { status.upstreamModel }}（${status.upstreamHost}）" else "电脑还没有配置上游 Key：可以备份，暂时不能生成。",
            status.modelsAvailable?.let { "可在模型页选用 $it 个上游模型；每次请求都在电脑上存档" },
            if (status.activeRequests > 0) "正在处理 ${status.activeRequests} 个请求" else null,
            "电脑上有 ${status.backupCount} 份备份" + if (status.backupCount > 0) "，共 ${readableSize(status.backupBytes)}" else ""
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(title, fontWeight = FontWeight.SemiBold)
        }
        details.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (status == null && link.error.isNotBlank()) colors.error else colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun PcRestoreDialog(link: PcLinkState, zone: ZoneId, onRefresh: () -> Unit, onRestore: (String) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf<String?>(null) }
    val backups = link.backups
    AlertDialog(onDismissRequest = onDismiss, title = { Text("从电脑恢复") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                link.busy == "list" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在读取电脑上的备份…")
                }
                backups == null -> {
                    Text(link.error.ifBlank { "还没有读取备份列表。" }, color = if (link.error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onRefresh, enabled = link.busy.isBlank()) { Text("重新读取") }
                }
                backups.isEmpty() -> Text("电脑上还没有备份。")
                else -> PcBackupList(backups, chosen, zone) { chosen = it }
            }
            if (chosen != null) Text("恢复会用这份备份覆盖手机上的全部数据。开始前会先把当前数据备份到电脑。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        Button({ chosen?.let(onRestore) }, enabled = chosen != null && link.busy.isBlank()) { Text("恢复") }
    }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

@Composable
internal fun PcBackupList(backups: List<PcBackup>, chosen: String?, zone: ZoneId, onChoose: (String) -> Unit) {
    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
        backups.forEach { backup ->
            Row(Modifier.fillMaxWidth().selectable(backup.id == chosen, role = Role.RadioButton) { onChoose(backup.id) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(backup.id == chosen, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("${backupTime(backup.createdAt, zone)} · ${readableSize(backup.size)}")
                    Text(listOf(backup.device, reasonLabel(backup.reason), backup.appVersion.substringBefore(' ')).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
