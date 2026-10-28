package app.pockettavern.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.ScenarioPerson
import com.android.tools.screenshot.PreviewTest

private val PreviewRoster = listOf(
    ScenarioPerson(name = "谷若尘", role = "主角，栖云庄园继承人"),
    ScenarioPerson(name = "顾砚舟", role = "庄园管家，负责行政与预算", relationship = "长期管家"),
    ScenarioPerson(name = "骆歆", role = "起居与接待主管", relationship = "雇主与主管", activity = "主宅接待访客"),
    ScenarioPerson(name = "余霜", role = "餐饮主管", relationship = "雇员", activity = "厨房准备晚餐"),
    ScenarioPerson(name = "小楼", role = "园艺与清洁", relationship = "未记录"),
    ScenarioPerson(name = "王六堡", role = "安保队长", relationship = "护卫", activity = "门岗巡逻"),
    ScenarioPerson(name = "阿冷", role = "安保副队长", relationship = "与王六堡是搭档"),
    ScenarioPerson(name = "林岚", role = "拍摄合作方", relationship = "与顾砚舟是朋友"),
    ScenarioPerson(name = "程十安", role = "直播合作", relationship = "合作伙伴"),
    ScenarioPerson(name = "苏小妍", role = "常住客人", relationship = "暧昧"),
    ScenarioPerson(name = "瞳夕", role = "外部访客", relationship = "对立"),
    ScenarioPerson(name = "美依礼芽（MARiA）", role = "临时住客", relationship = "未记录"),
)

@Composable
private fun RelationshipPreview(mode: Int, selection: String? = null) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(top = 8.dp)) {
            RelationshipOverview(PreviewRoster, onOpenPerson = {}, initialMode = mode, initialSelection = selection)
        }
    }
}

@PreviewTest
@Preview(name = "组织架构图", showBackground = true, widthDp = 411, heightDp = 1000)
@Composable
fun relationshipTreePreview() {
    PocketTheme("light") { RelationshipPreview(mode = 0) }
}

@PreviewTest
@Preview(name = "组织架构图选中人物", showBackground = true, widthDp = 411, heightDp = 1100)
@Composable
fun relationshipTreeSelectedPreview() {
    PocketTheme("light") { RelationshipPreview(mode = 0, selection = "阿冷") }
}

@PreviewTest
@Preview(name = "关系网络图", showBackground = true, widthDp = 411, heightDp = 900)
@Composable
fun relationshipNetworkPreview() {
    PocketTheme("light") { RelationshipPreview(mode = 1) }
}

@PreviewTest
@Preview(name = "关系网络图选中人物", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun relationshipNetworkSelectedPreview() {
    PocketTheme("light") { RelationshipPreview(mode = 1, selection = "顾砚舟") }
}

@PreviewTest
@Preview(name = "深色关系网络图", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun relationshipNetworkDarkPreview() {
    PocketTheme("dark") { RelationshipPreview(mode = 1, selection = "林岚") }
}

@PreviewTest
@Preview(name = "窄屏大字组织架构图", showBackground = true, widthDp = 320, heightDp = 1500, fontScale = 1.5f)
@Composable
fun relationshipTreeNarrowLargeTextPreview() {
    PocketTheme("light") { RelationshipPreview(mode = 0) }
}

private val CrowdedRoster = listOf(ScenarioPerson(name = "谷若尘", role = "主角")) + listOf(
    "顾砚舟", "骆歆", "余霜", "小楼", "王六堡", "阿冷", "林小宅", "程十安", "苏小妍", "瞳夕", "小苍", "小钰", "希然", "易梦玲", "景甜",
    "杨幂", "赵露思", "虞书欣", "鞠婧祎", "周淑怡", "冯提莫", "周二珂", "咬人猫", "腐团儿", "赤九玖", "轩子巨2兔", "林岚", "桥本环奈", "斋藤飞鸟", "美依礼芽（MARiA）", "papi酱",
).mapIndexed { index, name ->
    val role = listOf("庄园管家", "安保队员", "起居接待", "直播合作", "常住客人", "外部访客")[index % 6]
    ScenarioPerson(name = name, role = role, relationship = if (index % 7 == 3) "与顾砚舟是朋友" else "雇员")
}

@PreviewTest
@Preview(name = "多人关系网络图", showBackground = true, widthDp = 411, heightDp = 1500)
@Composable
fun relationshipNetworkCrowdedPreview() {
    PocketTheme("light") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(top = 8.dp)) { RelationshipOverview(CrowdedRoster, onOpenPerson = {}, initialMode = 1) }
        }
    }
}
