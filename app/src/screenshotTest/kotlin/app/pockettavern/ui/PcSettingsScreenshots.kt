package app.pockettavern.ui

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
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.PcSettings
import com.android.tools.screenshot.PreviewTest

private val PcModels = listOf(
    ModelInfo(id = "deepseek-v4-1-flash-1017", name = "DeepSeek V4.1 Flash", contextTokens = 1_000_000, supportsTools = true, supportsReasoning = true),
    ModelInfo(id = "llama-3.3-70b", name = "Llama 3.3 70B", contextTokens = 65_536, supportsTools = true),
    ModelInfo(id = "qwen3-vl-235b", name = "Qwen3 VL 235B", contextTokens = 131_072, supportsVision = true, supportsReasoning = true),
    ModelInfo(id = "venice-uncensored", name = "Venice Uncensored", contextTokens = 32_768, offline = true),
)

@Composable
private fun PcSettingsSurface(content: @Composable () -> Unit) {
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
        }
    }
}

@PreviewTest
@Preview(name = "电脑设置", showBackground = true, widthDp = 360, heightDp = 1400, fontScale = 1.3f)
@Composable
fun pcSettingsFormPreview() = PcSettingsSurface {
    PcSettingsForm(PcSettings(upstreamModel = "deepseek-v4-1-flash-1017", reasoningEffort = "high", upstreamIdleTimeoutSec = 900, backupKeep = 20, upstreamHost = "api.venice.ai"),
        PcModels, onChange = {}, onChooseModel = {}, onRefreshModels = {})
}

@PreviewTest
@Preview(name = "电脑设置-手改过的值与空目录", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
fun pcSettingsCustomValuesPreview() = PcSettingsSurface {
    PcSettingsForm(PcSettings(upstreamModel = "my-model", upstreamIdleTimeoutSec = 120, backupKeep = 7, upstreamHost = "api.venice.ai"),
        emptyList(), onChange = {}, onChooseModel = {}, onRefreshModels = {})
}

@PreviewTest
@Preview(name = "选择默认模型", showBackground = true, widthDp = 360, heightDp = 420, fontScale = 1.3f)
@Composable
fun pcModelListPreview() = PcSettingsSurface {
    PcModelList(PcModels, selected = "llama-3.3-70b") {}
}
