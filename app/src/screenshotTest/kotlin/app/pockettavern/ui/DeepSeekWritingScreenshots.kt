package app.pockettavern.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.ModelInfo
import app.pockettavern.provider.VeniceModelCapabilities
import com.android.tools.screenshot.PreviewTest

@Composable
private fun DeepSeekWritingPreviewFrame(catalog: VeniceModelCapabilities, ready: Boolean) {
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("DeepSeek 写作调优", style = MaterialTheme.typography.titleLarge)
                DeepSeekWritingSettings(
                    presetName = "港口故事",
                    currentEffort = "default",
                    capabilities = catalog,
                    effort = "low",
                    onEffortChange = {},
                    output = "8192",
                    onOutputChange = {},
                    includePlatformPrompt = false,
                    onPlatformPromptChange = {},
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {}, enabled = ready, modifier = Modifier.align(Alignment.End)) { Text("应用到本会话") }
            }
        }
    }
}

@PreviewTest
@Preview(name = "DeepSeek日常续写调优", showBackground = true, widthDp = 420, heightDp = 1040)
@Composable
fun deepSeekWritingReadyPreview() {
    DeepSeekWritingPreviewFrame(VeniceModelCapabilities.from(ModelInfo(
        id = "deepseek-v4-1-flash-1017",
        rawJson = """{"model_spec":{"capabilities":{"supportsReasoningEffort":true,"reasoningEffortOptions":["none","low","high","max"],"defaultReasoningEffort":"high"},"maxCompletionTokens":131072}}"""
    )), ready = true)
}

@PreviewTest
@Preview(name = "DeepSeek目录缺失窄屏", showBackground = true, widthDp = 320, heightDp = 1120, fontScale = 1.15f)
@Composable
fun deepSeekWritingMissingCatalogPreview() {
    DeepSeekWritingPreviewFrame(VeniceModelCapabilities.from(null), ready = false)
}
