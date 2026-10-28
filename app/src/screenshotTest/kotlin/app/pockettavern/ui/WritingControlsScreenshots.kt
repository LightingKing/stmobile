package app.pockettavern.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.SkillRecord
import app.pockettavern.engine.SkillRuntimeSnapshot
import com.android.tools.screenshot.PreviewTest

@Composable
private fun WritingPreviewFrame(title: String, content: @Composable ColumnScope.() -> Unit) {
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                content()
            }
        }
    }
}

@PreviewTest
@Preview(name = "纠错与废稿保留", showBackground = true, widthDp = 420, heightDp = 800)
@Composable
fun writingCorrectionAndDraftPreview() {
    WritingPreviewFrame("港口往事") {
        WritingIntentLabel("correction")
        Text("林舟已经拿到钥匙；她还不知道信封放在哪里。", style = MaterialTheme.typography.bodyLarge)
        ExcludedMessageNotice(assistant = true, expanded = false, onExpand = {})
        WritingQualityNotice("新稿与上一稿高度相似，可能只改了少量词句。")
        Spacer(Modifier.weight(1f))
        WritingIntentSelector("correction", {})
    }
}

@PreviewTest
@Preview(name = "重写与排除窄屏大字", showBackground = true, widthDp = 320, heightDp = 900, fontScale = 1.3f)
@Composable
fun writingRewriteNarrowPreview() {
    WritingPreviewFrame("重新写这一幕") {
        WritingIntentLabel("rewrite")
        ExcludedMessageNotice(assistant = true, expanded = true, onExpand = {})
        Text("旧稿：钟声响起，林舟站在门口等待。", style = MaterialTheme.typography.bodyMedium)
        WritingQualityNotice("新稿保留了上一稿的大部分句子，请检查是否符合重写要求。")
        WritingIntentSelector("rewrite", {})
    }
}

@PreviewTest
@Preview(name = "当前场景与人物认知", showBackground = true, widthDp = 420, heightDp = 700)
@Composable
fun writingContinuityPreview() {
    WritingPreviewFrame("当前场景与连贯性") {
        ContinuityNotesEditor(
            "时间 / 地点：清晨，港口旧书店。\n在场人物：林舟、阿宁。\n各自已知与未知：林舟知道钥匙能开后门，不知道信封的位置。阿宁看过信封，但未告诉她。\n当前状态：后门已打开，两人尚未进屋。\n待解决线索：柜台下的航海日志。",
            onChange = {}
        )
    }
}

@PreviewTest
@Preview(name = "App记录的本轮Skills", showBackground = true, widthDp = 420, heightDp = 800)
@Composable
fun writingSkillSnapshotPreview() {
    val snapshot = SkillRuntimeSnapshot.manifest(listOf(
        SkillRecord(id = "preview-scene", name = "场景写作", files = mapOf("SKILL.md" to "", "references/scene.md" to "")),
        SkillRecord(id = "preview-dialogue", name = "对白辅助"),
        SkillRecord(id = "preview-continuity", name = "连续性检查", files = mapOf("references/check.md" to "", "references/timeline.md" to ""))
    ), toolsSupported = true)
    WritingPreviewFrame("本轮 Skills") { SkillSnapshotContent(snapshot) }
}
