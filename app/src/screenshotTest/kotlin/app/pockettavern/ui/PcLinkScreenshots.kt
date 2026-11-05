package app.pockettavern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.PcBackup
import app.pockettavern.domain.PcLinkState
import app.pockettavern.domain.PcStatus
import app.pockettavern.domain.ProviderConfig
import com.android.tools.screenshot.PreviewTest
import java.time.ZoneId

private val PreviewZone = ZoneId.of("Asia/Shanghai")
private val PreviewServer = ProviderConfig(id = "pc", name = "电脑写作服务", kind = "writing-server", baseUrl = "http://192.168.1.22:8791/v1")

@Composable
private fun PcPreviewSurface(content: @Composable () -> Unit) {
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@Composable
private fun Card(link: PcLinkState, servers: List<ProviderConfig> = listOf(PreviewServer)) =
    PcLinkCard(link, servers, locked = false, onCheck = {}, onBackup = {}, onLoadBackups = {}, onRestore = {}, onAutoBackup = {}, onChooseProvider = {}, zone = PreviewZone)

@PreviewTest
@Preview(name = "电脑已连接", showBackground = true, widthDp = 360, heightDp = 760, fontScale = 1.3f)
@Composable
fun pcLinkConnectedPreview() = PcPreviewSurface {
    Card(PcLinkState(providerId = "pc", autoBackup = true, lastBackupAt = 1_793_005_800_000L, lastBackupSize = 3_355_443,
        status = PcStatus(version = "0.2.0", ready = true, upstreamHost = "api.venice.ai", upstreamModel = "deepseek-v4-1-flash-1017",
            backupCount = 4, backupBytes = 13_421_772, backupKeep = 20, defaultModelName = "DeepSeek V4.1 Flash", modelsAvailable = 27)))
}

@PreviewTest
@Preview(name = "电脑未连接", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
fun pcLinkOfflinePreview() = PcPreviewSurface {
    Card(PcLinkState(providerId = "pc", error = "连不上电脑上的写作服务。请确认电脑已开机、服务在运行，手机与电脑在同一网络或远程地址可用。",
        lastBackupError = "连不上电脑上的写作服务。"), servers = listOf(PreviewServer, PreviewServer.copy(id = "nps", name = "远程（NPS）", baseUrl = "https://pt.example.com/v1")))
    Card(PcLinkState(), servers = emptyList())
}

@PreviewTest
@Preview(name = "电脑上的备份", showBackground = true, widthDp = 360, heightDp = 300, fontScale = 1.3f)
@Composable
fun pcBackupListPreview() = PcPreviewSurface {
    Column(Modifier.padding(horizontal = 16.dp)) {
        PcBackupList(listOf(
            PcBackup(id = "a", createdAt = "2026-11-04T08:50:00.000Z", size = 3_355_443, device = "Xiaomi 14", appVersion = "0.1.68 (69)", reason = "auto"),
            PcBackup(id = "b", createdAt = "2026-11-03T13:05:00.000Z", size = 3_201_000, device = "Xiaomi 14", appVersion = "0.1.68 (69)", reason = "pre-restore"),
            PcBackup(id = "c", createdAt = "2026-11-02T01:30:00.000Z", size = 900_000, device = "Pixel 8", appVersion = "0.1.67 (68)", reason = "manual"),
        ), chosen = "b", zone = PreviewZone) {}
    }
}
