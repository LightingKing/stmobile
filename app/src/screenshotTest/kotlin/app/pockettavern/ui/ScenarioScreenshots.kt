package app.pockettavern.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.Character
import com.android.tools.screenshot.PreviewTest

private val FrostManorPreviewCharacter = Character(
    id = "builtin-scenario-frost-manor",
    name = "极寒末世：从继承庄园开始【重开】",
    description = "你扮演谷若尘，在极寒降临前回到栖云庄园。距离灾变还有29天15小时；零号刚刚绑定，空间能力尚未实测。由你决定第一步。"
)

@Composable
private fun ScenarioPreview(hasRecentChat: Boolean = true) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(top = 10.dp)) {
            ScenarioCard(
                character = FrostManorPreviewCharacter,
                worldEntryCount = 60,
                hasRecentChat = hasRecentChat,
                busy = false,
                onContinue = {},
                onNewGame = {}
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@PreviewTest
@Preview(name = "剧本入口卡片", showBackground = true, widthDp = 420, heightDp = 860)
@Composable
fun scenarioCardPreview() {
    PocketTheme("light") { ScenarioPreview() }
}

@PreviewTest
@Preview(name = "剧本入口无存档", showBackground = true, widthDp = 420, heightDp = 860)
@Composable
fun scenarioCardNewGamePreview() {
    PocketTheme("light") { ScenarioPreview(hasRecentChat = false) }
}

@PreviewTest
@Preview(name = "窄屏大字剧本入口", showBackground = true, widthDp = 360, heightDp = 760, fontScale = 1.3f)
@Composable
fun scenarioCardNarrowLargeTextPreview() {
    PocketTheme("light") { ScenarioPreview(hasRecentChat = true) }
}

@PreviewTest
@Preview(name = "320dp 双倍字体有历史", showBackground = true, widthDp = 320, heightDp = 980, fontScale = 2.0f)
@Composable
fun scenarioCardExtremeNarrowLargeTextPreview() {
    PocketTheme("light") { ScenarioPreview(hasRecentChat = true) }
}
