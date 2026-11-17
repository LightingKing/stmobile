package app.pockettavern.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.Character
import app.pockettavern.domain.OpenWorld
import app.pockettavern.domain.UiSession
import app.pockettavern.domain.WorldDetail
import app.pockettavern.domain.WorldItem
import app.pockettavern.domain.WorldLimits
import app.pockettavern.domain.WorldResident
import app.pockettavern.domain.WorldSettings
import app.pockettavern.domain.WorldSummary
import app.pockettavern.domain.Worlds
import app.pockettavern.engine.ScenarioBuilder
import app.pockettavern.engine.WorldPack
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val ResidentTints = listOf(Color(0xFF5B4A8A), Color(0xFF3F6B5A), Color(0xFF7A5A32), Color(0xFF3F5E8C), Color(0xFF8A4A5E), Color(0xFF4F6B2F), Color(0xFF6B3F6B), Color(0xFF2F5F6B))
private fun tint(id: String) = ResidentTints[(id.filter { it.isDigit() }.toIntOrNull() ?: id.hashCode()).mod(ResidentTints.size)]
private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** One world in the chat list: its name, world time, last line and unread count. */
@Composable
internal fun WorldRow(world: WorldSummary, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(colors.tertiaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Public, null, tint = colors.onTertiaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(world.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Surface(shape = RoundedCornerShape(8.dp), color = colors.tertiaryContainer) {
                    Text(if (world.paused) "小世界 · 暂停" else "小世界", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = colors.onTertiaryContainer, maxLines = 1)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(Worlds.preview(world.lastMessage), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(world.worldTimeLabel.substringAfterLast(' '), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            if (world.unread > 0) Surface(shape = CircleShape, color = colors.error) {
                Text(if (world.unread > 99) "99+" else world.unread.toString(), Modifier.padding(horizontal = 7.dp, vertical = 1.dp).semantics { contentDescription = "${world.unread} 条新消息" },
                    style = MaterialTheme.typography.labelSmall, color = colors.onError)
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 86.dp), color = colors.outlineVariant.copy(alpha = 0.4f))
}

/**
 * A world, open: its group chat, the private chats with each resident, who is doing what, and the
 * world's clock and limits. Everything is read from the PC every 15 seconds while it is open.
 */
@Composable
internal fun WorldScreen(open: OpenWorld, state: UiSession, actions: AppActions) {
    BackHandler { actions.closeWorld() }
    LaunchedEffect(open.id) { while (true) { delay(15_000); actions.refreshOpenWorld() } }
    val detail = open.detail
    // A notification's tap lands on the private chat it is about.
    var tab by rememberSaveable(open.id) { mutableStateOf(if (open.focus.startsWith("dm:")) 1 else 0) }
    var dmTarget by rememberSaveable(open.id) { mutableStateOf(open.focus.takeIf { it.startsWith("dm:") }?.removePrefix("dm:")) }
    // The world may already be on screen (or kept from before) when a notification opens it again.
    LaunchedEffect(open.focusToken) { if (open.focus.startsWith("dm:")) { tab = 1; dmTarget = open.focus.removePrefix("dm:") } }
    Column(Modifier.fillMaxSize()) {
        WorldHeader(open, detail, onBack = { if (tab == 1 && dmTarget != null) dmTarget = null else actions.closeWorld() }, actions)
        if (open.error.isNotBlank()) Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.errorContainer) {
            Text(open.error, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        if (detail == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (open.busy == "load") CircularProgressIndicator()
                else OutlinedButton(onClick = { actions.openWorld(open.id) }) { Text("重新读取") }
            }
            return@Column
        }
        val summary = detail.summary
        val dmCount = open.items.count { it.channel.startsWith("dm:") && !it.fromUser }
        TabRow(selectedTabIndex = tab) {
            listOf("群聊", if (dmCount > 0) "私信 $dmCount" else "私信", "居民", "记忆", "世界").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index; if (index != 1) dmTarget = null }, text = { Text(label, maxLines = 1) })
            }
        }
        val blocked = when {
            summary.paused -> "世界已暂停，点右上角 ▶ 继续后才能说话"
            summary.hold == "calls" -> "今天的请求次数用完了，明天再来，或在“世界”里调高上限"
            open.pending != null -> "等回应…"
            else -> ""
        }
        when (tab) {
            0 -> WorldConversation(Worlds.groupLines(open.items), open.pending?.takeIf { it.channel == "group" }, detail, blocked,
                hint = "在「${detail.groupName}」里说话", Modifier.weight(1f)) { actions.sayInWorld("group", it) }
            1 -> {
                val target = dmTarget?.let { id -> summary.residents.firstOrNull { it.id == id } }
                if (target == null) DmThreads(open.items, summary.residents, Modifier.weight(1f)) { dmTarget = it }
                else WorldConversation(Worlds.dmLines(open.items, target.id), open.pending?.takeIf { it.channel == "dm:${target.id}" }, detail, blocked,
                    hint = "私聊 ${target.name}（别人看不到）", Modifier.weight(1f), title = "和 ${target.name} 的私信") { actions.sayInWorld("dm:${target.id}", it) }
            }
            2 -> ResidentsPane(summary.residents, state, actions, open.busy.isEmpty(), Modifier.weight(1f))
            3 -> MemoryPane(open, detail, actions, Modifier.weight(1f))
            else -> WorldPane(open, detail, actions, !state.data.settings.worldNotificationsOff, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WorldHeader(open: OpenWorld, detail: WorldDetail?, onBack: () -> Unit, actions: AppActions) {
    val summary = detail?.summary
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        Column(Modifier.weight(1f)) {
            Text(summary?.name ?: "小世界", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary != null) Text("${summary.worldTimeLabel} · ${if (summary.busy) "正在推进…" else Worlds.holdLabel(summary.hold)}",
                style = MaterialTheme.typography.labelSmall, color = if (summary.paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, maxLines = 1)
        }
        if (open.busy.isNotEmpty() && open.busy != "load") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        if (summary != null) IconButton(onClick = { actions.setWorldPaused(!summary.paused) }, enabled = open.busy.isEmpty()) {
            Icon(if (summary.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, if (summary.paused) "继续运行世界" else "暂停整个世界")
        }
    }
}

@Composable
private fun WorldConversation(lines: List<WorldItem>, pending: WorldItem?, detail: WorldDetail, blocked: String, hint: String, modifier: Modifier, title: String = "", onSend: (String) -> Unit) {
    val listState = rememberLazyListState()
    val count = lines.size + if (pending != null) 1 else 0
    LaunchedEffect(count) { if (count > 0) listState.animateScrollToItem(count - 1) }
    var text by rememberSaveable(hint) { mutableStateOf("") }
    Column(modifier) {
        if (title.isNotEmpty()) Text(title, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (count == 0) item { Text("这里还没有消息。世界推进时，她们会自己聊起来。", Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(lines, key = { it.seq }) { WorldLine(it, detail) }
            pending?.let { item(key = "pending") { WorldLine(it, detail, sending = true) } }
        }
        if (blocked.isNotEmpty() && pending == null) Text(blocked, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { if (it.length <= WorldLimits.USER_TEXT) text = it }, Modifier.weight(1f), placeholder = { Text(hint, maxLines = 1) },
                enabled = blocked.isEmpty(), maxLines = 4, shape = RoundedCornerShape(22.dp))
            IconButton(onClick = { onSend(text); text = "" }, enabled = blocked.isEmpty() && text.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.Send, "发送") }
        }
    }
}

@Composable
private fun WorldLine(item: WorldItem, detail: WorldDetail, sending: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    if (item.isEvent) {
        Text("${item.time}  ${item.text}", Modifier.fillMaxWidth().padding(horizontal = 24.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant)
        return
    }
    if (item.fromUser) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Text(if (sending) "发送中…" else item.time, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp), color = colors.primaryContainer, modifier = Modifier.widthIn(max = 300.dp)) {
                Text(item.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = colors.onPrimaryContainer)
            }
        }
        return
    }
    val name = item.name.ifBlank { detail.summary.residents.firstOrNull { it.id == item.from }?.name.orEmpty() }
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(tint(item.from)), contentAlignment = Alignment.Center) {
            Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text("$name  ${item.time}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp), color = colors.surfaceVariant, modifier = Modifier.widthIn(max = 300.dp)) {
                Text(item.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DmThreads(items: List<WorldItem>, residents: List<WorldResident>, modifier: Modifier, onOpen: (String) -> Unit) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp)) {
        item { Text("私信只有你和那个人知道，其他居民看不到。", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(Worlds.dmThreads(items, residents), key = { it.first.id }) { (resident, last) ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(resident.id) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(tint(resident.id)), contentAlignment = Alignment.Center) {
                    Text(resident.name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(resident.name, fontWeight = FontWeight.SemiBold)
                    Text(last?.let { (if (it.fromUser) "你：" else "") + it.text } ?: "还没有私信", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                last?.let { Text(it.time.substringAfterLast(' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun ResidentsPane(residents: List<WorldResident>, state: UiSession, actions: AppActions, idle: Boolean, modifier: Modifier) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WorldResident?>(null) }
    var creating by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<WorldResident?>(null) }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${residents.size}/${WorldLimits.RESIDENTS} 位居民", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { adding = true }, enabled = idle && residents.size < WorldLimits.RESIDENTS) { Text("添加居民") }
            }
        }
        items(residents, key = { it.id }) { resident ->
            var cardOpen by rememberSaveable(resident.id) { mutableStateOf(false) }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(tint(resident.id)), contentAlignment = Alignment.Center) {
                            Text(resident.name.take(1), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(resident.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (resident.card.isNotBlank()) TextButton(onClick = { cardOpen = !cardOpen }) { Text(if (cardOpen) "收起" else "设定") }
                        TextButton(onClick = { editing = resident }, enabled = idle) { Text("编辑") }
                        TextButton(onClick = { moving = resident }, enabled = idle && residents.size > 1) { Text("搬走", color = MaterialTheme.colorScheme.error) }
                    }
                    listOf("在哪" to resident.location, "在做" to resident.activity, "心情" to resident.mood, "打算" to resident.plan).filter { it.second.isNotBlank() }.forEach { (label, value) ->
                        Text("$label：$value", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (resident.notes.isNotBlank()) Text("记得的事：${resident.notes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (cardOpen) Text(resident.card, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (adding) {
        val cards = state.data.characters.filter { !ScenarioBuilder.isScenario(it) }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("添加居民") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { adding = false; creating = true }, Modifier.fillMaxWidth()) { Text("新建一位居民") }
                Text("或者把一张角色卡复制进来（复制的是卡上的文字，之后改角色卡不影响世界）：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (cards.isEmpty()) Text("还没有角色卡。", style = MaterialTheme.typography.bodySmall)
                cards.forEach { card ->
                    val taken = residents.any { it.name == card.name.trim() || card.name.trim() in it.aliases }
                    Surface(Modifier.fillMaxWidth().clickable(enabled = !taken) { adding = false; actions.addWorldResidentFromCharacter(card.id) },
                        shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (taken) 0.3f else 0.6f)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(card.name.ifBlank { "未命名角色" } + if (taken) "（世界里已有同名居民）" else "", fontWeight = FontWeight.SemiBold)
                            if (card.description.isNotBlank()) Text(card.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
    }
    if (creating) ResidentDialog(null, onDismiss = { creating = false }) { name, card, aliases, _ -> creating = false; actions.addWorldResident(name, card, aliases) }
    editing?.let { resident -> ResidentDialog(resident, onDismiss = { editing = null }) { name, card, aliases, notes -> editing = null; actions.editWorldResident(resident.id, name, card, aliases, notes) } }
    moving?.let { resident ->
        AlertDialog(onDismissRequest = { moving = null }, title = { Text("让 ${resident.name} 搬走？") },
            text = { Text("${resident.name} 说过的话会留在时间线里，私信也还在，但之后不再出现在世界里。") },
            confirmButton = { TextButton(onClick = { moving = null; actions.removeWorldResident(resident.id) }) { Text("搬走", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { moving = null }) { Text("取消") } })
    }
}

/** A new resident ([resident] null) or an existing one: name, other names, card, and what they remember. */
@Composable
private fun ResidentDialog(resident: WorldResident?, onDismiss: () -> Unit = {}, onSave: (String, String, List<String>, String) -> Unit) {
    var name by remember(resident?.id) { mutableStateOf(resident?.name.orEmpty()) }
    var aliases by remember(resident?.id) { mutableStateOf(resident?.aliases.orEmpty().joinToString("、")) }
    var card by remember(resident?.id) { mutableStateOf(resident?.card.orEmpty()) }
    var notes by remember(resident?.id) { mutableStateOf(resident?.notes.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (resident == null) "新建居民" else "编辑 ${resident.name}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("名字") })
            OutlinedTextField(aliases, { aliases = it.take(200) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("别的叫法（用、分开，可不填）") })
            OutlinedTextField(card, { card = it.take(6000) }, Modifier.fillMaxWidth().height(200.dp), label = { Text("设定：身份、外貌、性格、作息、和别人的关系") })
            if (resident != null) OutlinedTextField(notes, { notes = it.take(600) }, Modifier.fillMaxWidth().height(120.dp), label = { Text("记得的事（每天写日记时会更新）") })
        }
    }, confirmButton = {
        TextButton(enabled = name.isNotBlank() && card.isNotBlank(), onClick = {
            onSave(name, card, aliases.split('、', ',', '，', ' ').map { it.trim() }.filter { it.isNotEmpty() && it != name.trim() }.distinct().take(6), notes)
        }) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/**
 * What the world remembers: lines the user wants kept in every request (要记住的事), what each
 * resident remembers, and the diary, one chapter per world day. All of it can be changed.
 */
@Composable
private fun MemoryPane(open: OpenWorld, detail: WorldDetail, actions: AppActions, modifier: Modifier) {
    LaunchedEffect(open.id) { if (open.diary == null) actions.loadWorldDiary() }
    val idle = open.busy.isEmpty()
    var newFact by rememberSaveable(open.id) { mutableStateOf("") }
    var notesFor by remember { mutableStateOf<WorldResident?>(null) }
    var editingDay by remember { mutableStateOf<app.pockettavern.domain.WorldDiaryEntry?>(null) }
    var removingDay by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("要记住的事", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("写在这里的每一条，世界每次推进和回应都会带上，居民当它是确定的事实。比如你的名字和工作、你们之间说定的事。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(detail.facts.withIndex().toList(), key = { "fact-${it.index}-${it.value.hashCode()}" }) { (index, fact) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("· $fact", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { actions.saveWorldFacts(detail.facts.filterIndexed { i, _ -> i != index }) }, enabled = idle) { Text("删除") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newFact, { newFact = it.take(300) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("加一条") })
                TextButton(onClick = { actions.saveWorldFacts(detail.facts + newFact); newFact = "" }, enabled = idle && newFact.isNotBlank() && detail.facts.size < 30) { Text("添加") }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
        item { Text("每个人记得的事", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
        items(detail.summary.residents, key = { "notes-${it.id}" }) { resident ->
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(resident.name, fontWeight = FontWeight.SemiBold)
                    Text(resident.notes.ifBlank { "还没有；世界里过完一天、写日记时会记下" }, style = MaterialTheme.typography.bodySmall,
                        color = if (resident.notes.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
                TextButton(onClick = { notesFor = resident }, enabled = idle) { Text(if (resident.notes.isBlank()) "补充" else "修改") }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("日记", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (open.diary == null) Text("读取中…", style = MaterialTheme.typography.bodySmall)
            else if (open.diary.isEmpty()) Text("世界里每过一天，会写一章日记。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(open.diary.orEmpty().asReversed(), key = { "day-${it.day}" }) { entry ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.day, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { editingDay = entry }, enabled = idle) { Text("修改") }
                        TextButton(onClick = { removingDay = entry.day }, enabled = idle) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                    Text(entry.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    notesFor?.let { resident ->
        var notes by remember(resident.id) { mutableStateOf(resident.notes) }
        AlertDialog(onDismissRequest = { notesFor = null }, title = { Text("${resident.name} 记得的事") }, text = {
            OutlinedTextField(notes, { notes = it.take(600) }, Modifier.fillMaxWidth().height(180.dp), label = { Text("对你的印象、答应过的事、关系的变化") })
        }, confirmButton = {
            TextButton(onClick = { notesFor = null; actions.editWorldResident(resident.id, resident.name, resident.card, resident.aliases, notes) }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = { notesFor = null }) { Text("取消") } })
    }
    editingDay?.let { entry ->
        var text by remember(entry.day) { mutableStateOf(entry.text) }
        AlertDialog(onDismissRequest = { editingDay = null }, title = { Text("${entry.day} 的日记") }, text = {
            OutlinedTextField(text, { text = it.take(1500) }, Modifier.fillMaxWidth().height(260.dp))
        }, confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { editingDay = null; actions.editWorldDiary(entry.day, text) }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = { editingDay = null }) { Text("取消") } })
    }
    removingDay?.let { day ->
        AlertDialog(onDismissRequest = { removingDay = null }, title = { Text("删除 $day 的日记？") },
            text = { Text("之后的推进不会再看到这一章。时间线里的聊天和事件不受影响。") },
            confirmButton = { TextButton(onClick = { removingDay = null; actions.removeWorldDiary(day) }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removingDay = null }) { Text("取消") } })
    }
}

@Composable
private fun WorldPane(open: OpenWorld, detail: WorldDetail, actions: AppActions, notificationsOn: Boolean, modifier: Modifier) {
    val summary = detail.summary
    var confirmTick by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var settings by remember(summary.settings) { mutableStateOf(summary.settings) }
    var ticks by remember(summary.settings) { mutableStateOf(summary.settings.dailyTicks.toString()) }
    var calls by remember(summary.settings) { mutableStateOf(summary.settings.dailyCalls.toString()) }
    val zone = remember { ZoneId.systemDefault() }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val next = summary.nextTickAt?.let { ClockFormat.format(Instant.ofEpochMilli(it).atZone(zone)) }
        WorldFact("世界时间", summary.worldTimeLabel)
        WorldFact("状态", if (summary.busy) "正在推进…" else Worlds.holdLabel(summary.hold))
        WorldFact("下次推进", when { summary.paused -> "暂停中"; next != null -> "约 $next"; else -> "—" })
        WorldFact("推进间隔", "${summary.intervalMinutes} 分钟" + if (summary.intervalMinutes > summary.settings.tickMinutes) "（你超过一天没看，已放慢）" else "")
        WorldFact("今天", "推进 ${summary.usage.ticks}/${summary.settings.dailyTicks} 次 · 请求 ${summary.usage.calls}/${summary.settings.dailyCalls} 次 · ${"%,d".format(summary.usage.tokens)} tokens")
        WorldFact("累计", "请求 ${summary.totals.calls} 次 · ${"%,d".format(summary.totals.tokens)} tokens")
        if (summary.lastError.isNotBlank()) Text("上次出错：${summary.lastError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text("每次推进、每次回应、每天的日记都是一次付费请求，计入上面的上限。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { confirmTick = true }, enabled = open.busy.isEmpty() && !summary.paused) { Text("现在推进一次") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("有人私聊我时通知")
                Text("手机约每 15 分钟问一次电脑有没有新私信，只是查询，不花钱；对所有小世界生效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(notificationsOn, { actions.setWorldNotifications(it) })
        }
        HorizontalDivider()
        Text("设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text("推进间隔", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WorldLimits.TICK_MINUTES.forEach { minutes -> FilterChip(selected = settings.tickMinutes == minutes, onClick = { settings = settings.copy(tickMinutes = minutes) }, label = { Text("$minutes 分") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(settings.quietStart, { settings = settings.copy(quietStart = it.take(5)) }, Modifier.weight(1f), label = { Text("安静时段开始") }, singleLine = true)
            OutlinedTextField(settings.quietEnd, { settings = settings.copy(quietEnd = it.take(5)) }, Modifier.weight(1f), label = { Text("结束") }, singleLine = true)
        }
        Text("安静时段按世界时间算，这段时间不推进；两个时间相同就没有安静时段。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ticks, { ticks = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("每天最多推进") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(calls, { calls = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("每天最多请求") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("你不在时放慢"); Text("超过一天没看，间隔变成 2 倍；两天变成 4 倍", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(settings.slowWhenAway, { settings = settings.copy(slowWhenAway = it) })
        }
        val edited = settings.copy(dailyTicks = ticks.toIntOrNull() ?: -1, dailyCalls = calls.toIntOrNull() ?: -1)
        val problem = Worlds.settingsProblem(edited)
        problem?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { actions.saveWorldSettings(edited) }, enabled = problem == null && edited != summary.settings && open.busy.isEmpty()) { Text("保存设置") }
        HorizontalDivider()
        TextButton(onClick = { confirmDelete = true }, enabled = open.busy.isEmpty()) { Text("删除这个世界", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmTick) AlertDialog(onDismissRequest = { confirmTick = false }, title = { Text("现在推进一次？") },
        text = { Text("这是一次付费请求，计入今天的上限。") },
        confirmButton = { TextButton(onClick = { confirmTick = false; actions.tickWorld() }) { Text("推进") } },
        dismissButton = { TextButton(onClick = { confirmTick = false }) { Text("取消") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除「${summary.name}」？") },
        text = { Text("世界在电脑上的全部聊天、事件和日记都会删除，无法恢复。原来的剧本不受影响。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; actions.deleteWorld() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

@Composable
private fun WorldFact(label: String, value: String) {
    Row {
        Text(label, Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** ＋ → 新建小世界: pick the scenario first. */
@Composable
internal fun WorldScenarioPicker(state: UiSession, onPick: (Character) -> Unit, onDismiss: () -> Unit, onAssistant: () -> Unit = {}) {
    val scenarios = state.data.characters.filter { ScenarioBuilder.isScenario(it) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("新建小世界") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onAssistant, Modifier.fillMaxWidth()) { Text("和世界助手一起从零建") }
            Text("助手会和你一位一位定下居民的性格、作息、目标和关系，然后在电脑上建成世界。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("或者从已有的剧本做：剧本里的人物住进世界，世界观照搬。选一个剧本：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (scenarios.isEmpty()) Text("还没有剧本。")
            scenarios.forEach { scenario ->
                Surface(Modifier.fillMaxWidth().clickable { onPick(scenario) }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(scenario.name.ifBlank { "未命名剧本" }, fontWeight = FontWeight.SemiBold)
                        if (scenario.description.isNotBlank()) Text(scenario.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/**
 * 做成小世界: which of the scenario's world book entries move in as residents (up to 8), what they
 * call the user, and whether the world waits for a first "continue".
 */
@Composable
internal fun MakeWorldDialog(scenario: Character, state: UiSession, actions: AppActions, onDismiss: () -> Unit, onMade: () -> Unit = {}) {
    val candidates = remember(scenario.id, state.data.worldEntries) { WorldPack.candidates(scenario, state.data.worldEntries) }
    var picked by remember(scenario.id) { mutableStateOf(WorldPack.suggested(candidates)) }
    var userName by remember(scenario.id) { mutableStateOf(WorldPack.guessUserName(scenario.userPersona)) }
    var startPaused by remember(scenario.id) { mutableStateOf(true) }
    val hasPc = state.data.providers.any { it.kind.equals("writing-server", ignoreCase = true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("做成小世界") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(scenario.name.ifBlank { "未命名剧本" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("世界在电脑上一直运行：居民按自己的作息生活，在群里聊天，也会私聊你；暂停会让整个世界停住。每次推进和回应都是一次付费请求，默认每天最多 80 次，可以在世界页调整。住进世界的人是复制过去的，改了不影响这个剧本。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!hasPc) Text("要先在模型页添加“本机写作服务”平台，世界才有地方运行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text("选住进世界的人（最多 ${WorldLimits.RESIDENTS} 位，已选 ${picked.size}）", style = MaterialTheme.typography.labelLarge)
            if (candidates.isEmpty()) Text("这个剧本没有世界书条目，没法选人。可以先在剧本助手里加上人物。", style = MaterialTheme.typography.bodySmall)
            candidates.forEach { entry ->
                val checked = entry.id in picked
                val canAdd = checked || picked.size < WorldLimits.RESIDENTS
                Row(Modifier.fillMaxWidth().clickable(enabled = canAdd) { picked = if (checked) picked - entry.id else picked + entry.id }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, { picked = if (checked) picked - entry.id else picked + entry.id }, enabled = canAdd)
                    Column(Modifier.weight(1f)) {
                        Text(WorldPack.personName(entry) + if (WorldPack.isPerson(entry)) "" else "（世界书条目）", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.content.replace(Regex("^\\s*【角色：[^】]+】\\s*"), "").replace('\n', ' ').take(60), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("没选的条目留作世界书。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(userName, { userName = it.take(20) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("居民怎么称呼你") }, placeholder = { Text("不填就叫“用户”") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("建好后先暂停"); Text("看过设定再点“继续”，之前不花钱", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(startPaused, { startPaused = it })
            }
        }
    }, confirmButton = {
        TextButton(enabled = hasPc && picked.isNotEmpty(), onClick = {
            actions.makeWorld(scenario.id, candidates.filter { it.id in picked }.map { it.id }, userName, startPaused)
            onDismiss(); onMade()
        }) { Text("做成小世界") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
