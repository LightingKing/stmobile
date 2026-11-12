package app.pockettavern.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.GroupChats
import app.pockettavern.domain.GroupRelation
import app.pockettavern.domain.GroupSetup
import app.pockettavern.domain.Sticker
import app.pockettavern.domain.UiSession

/** Up to three member pictures in one circle: the group's face in the list and the header. */
@Composable
internal fun GroupAvatar(members: List<Character>, size: Dp, load: (String) -> ImageBitmap?, modifier: Modifier = Modifier) {
    val shown = members.take(3)
    val ring = Modifier.border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
    Box(modifier.size(size)) {
        when (shown.size) {
            0 -> CharacterAvatar("", "群", size, load)
            1 -> CharacterAvatar(shown[0].avatar, GroupChats.displayName(shown[0]), size, load)
            2 -> {
                val small = size * 0.68f
                CharacterAvatar(shown[0].avatar, GroupChats.displayName(shown[0]), small, load, Modifier.align(Alignment.TopStart))
                CharacterAvatar(shown[1].avatar, GroupChats.displayName(shown[1]), small, load, Modifier.align(Alignment.BottomEnd).then(ring))
            }
            else -> {
                val small = size * 0.56f
                CharacterAvatar(shown[0].avatar, GroupChats.displayName(shown[0]), small, load, Modifier.align(Alignment.TopStart))
                CharacterAvatar(shown[1].avatar, GroupChats.displayName(shown[1]), small, load, Modifier.align(Alignment.TopEnd).then(ring))
                CharacterAvatar(shown[2].avatar, GroupChats.displayName(shown[2]), small, load, Modifier.align(Alignment.BottomCenter).then(ring))
            }
        }
    }
}

/** What the message list needs to draw a group reply: members by name, and who each reply was asked of. */
internal data class GroupView(val byName: Map<String, Character>, val names: List<String>, val namedFor: Map<String, String>)

private val partialHeading = Regex("\\n?\\s*【[^】\\n]{0,24}$")

/** A group reply drawn as one bubble per speaker, each with that character's picture and name. */
@Composable
internal fun GroupReplyView(messageId: String, content: String, streaming: Boolean, group: GroupView, stickers: List<Sticker>,
                            loadSticker: (Sticker) -> ImageBitmap?, animateSticker: (Sticker) -> AnimatedSticker?, loadAvatar: (String) -> ImageBitmap?) {
    val colors = MaterialTheme.colorScheme
    // While streaming, a half-written "【名" would show up as text for a moment.
    val shown = if (streaming) content.replace(partialHeading, "") else content
    val lines = remember(shown, group.names) { GroupChats.split(shown, group.names) }
    Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        lines.forEachIndexed { index, line ->
            Row(verticalAlignment = Alignment.Top) {
                val member = line.speaker?.let(group.byName::get)
                when {
                    member != null -> CharacterAvatar(member.avatar, GroupChats.displayName(member), 30.dp, loadAvatar)
                    line.speaker == GroupSetup.NARRATOR -> Surface(shape = CircleShape, color = colors.surfaceContainerHigh) {
                        Icon(Icons.Outlined.Movie, null, Modifier.padding(6.dp).size(18.dp), tint = colors.onSurfaceVariant)
                    }
                    else -> Spacer(Modifier.size(30.dp))
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    if (line.speaker != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(line.speaker, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                            color = if (line.speaker == GroupSetup.NARRATOR) colors.onSurfaceVariant else colors.primary)
                        if (group.namedFor[messageId] == line.speaker) Text("被点名", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    AssistantTextWithSticker(line.text, streaming && index == lines.lastIndex, stickers, loadSticker, loadAnimated = animateSticker)
                }
            }
        }
    }
}

/** Above the input of a group: how speakers are chosen, and who to @ for the next message. */
@Composable
internal fun GroupComposerRow(setup: GroupSetup, members: List<Character>, mention: String?, onMention: (String?) -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AssistChip(onClick = onSettings, label = { Text("谁接话：" + (GroupSetup.MODES.firstOrNull { it.first == setup.mode }?.second ?: "自动")) })
        members.forEach { member ->
            FilterChip(selected = mention == member.id, onClick = { onMention(if (mention == member.id) null else member.id) },
                label = { Text("@" + GroupChats.displayName(member)) })
        }
    }
}

/**
 * Create a group (chat == null) or change one: name, members, scene, relations, how speakers
 * are chosen. Saving applies from the next reply.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GroupSetupDialog(state: UiSession, actions: AppActions, chat: Chat?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GroupSetupContent(state, actions, chat, onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GroupSetupContent(state: UiSession, actions: AppActions, chat: Chat?, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val original = chat?.group ?: GroupSetup()
    // Scenario cards are whole stories, not people to chat with.
    val candidates = remember(state.data.characters) { state.data.characters.filter { !it.id.startsWith("builtin-scenario-") } }
    val byId = remember(state.data.characters) { state.data.characters.associateBy { it.id } }
    val loadAvatar = remember(actions) { { avatar: String -> actions.characterAvatar(avatar) } }
    var title by rememberSaveable(chat?.id) { mutableStateOf(chat?.title.orEmpty()) }
    var members by remember(chat?.id) { mutableStateOf(original.members.filter { it in byId }) }
    var scene by rememberSaveable(chat?.id) { mutableStateOf(original.scene) }
    var relations by remember(chat?.id) { mutableStateOf(original.relations) }
    var mode by rememberSaveable(chat?.id) { mutableStateOf(original.mode) }
    var maxSpeakers by rememberSaveable(chat?.id) { mutableIntStateOf(original.maxSpeakers) }
    var narrator by rememberSaveable(chat?.id) { mutableStateOf(original.narrator) }
    var addingRelation by remember { mutableStateOf(false) }
    val setup = GroupSetup(members, scene, relations.filter { it.from in members + GroupRelation.USER && it.to in members + GroupRelation.USER }, mode,
        maxSpeakers.coerceIn(1, members.size.coerceAtLeast(1)), narrator)
    val problem = GroupChats.problem(setup, title)
    fun save() { if ((if (chat == null) actions.createGroupChat(title, setup) else actions.updateGroup(chat.id, title, setup))) onDismiss() }
    fun who(id: String) = if (id == GroupRelation.USER) "你" else byId[id]?.let(GroupChats::displayName) ?: "（已删除）"
    BackHandler { onDismiss() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (chat == null) "新建群聊" else "群设置") },
            navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = { TextButton(onClick = ::save, enabled = problem == null) { Text(if (chat == null) "创建" else "保存") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { OutlinedTextField(title, { title = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("群名") }, singleLine = true) }
            item {
                Text("成员（${members.size}/${GroupSetup.MAX_MEMBERS}，按点选顺序发言）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(6.dp))
                if (candidates.isEmpty()) Text("还没有角色卡。先到“角色”页创建或导入角色。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.forEach { character ->
                        val selected = character.id in members
                        FilterChip(selected = selected, enabled = selected || members.size < GroupSetup.MAX_MEMBERS,
                            onClick = { members = if (selected) members - character.id else members + character.id },
                            leadingIcon = { CharacterAvatar(character.avatar, GroupChats.displayName(character), 22.dp, loadAvatar) },
                            label = { Text(GroupChats.displayName(character)) })
                    }
                }
            }
            item {
                OutlinedTextField(scene, { scene = it.take(GroupSetup.MAX_SCENE) }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("场景") },
                    placeholder = { Text("现在在哪、在做什么。例如：周五深夜，几个人在语音频道里一起看新番，边看边吐槽。") })
            }
            item {
                Text("她们之间的关系", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("一条条写“谁对谁怎么看”，也可以写她们和你的关系。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            relations.forEach { relation ->
                item {
                    Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${who(relation.from)} → ${who(relation.to)}：${relation.text}", Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { relations = relations - relation }) { Icon(Icons.Outlined.DeleteOutline, "删除这条关系") }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { addingRelation = true }, enabled = members.isNotEmpty() && relations.size < GroupSetup.MAX_RELATIONS) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("添加一条关系")
                }
            }
            item {
                Text("发言方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupSetup.MODES.forEach { (value, label) -> FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(label) }) }
                }
                Text(when (mode) {
                    GroupSetup.NAMED -> "只有你在输入框上方 @ 的人回答；没点名时由最相关的一个人简短回应。"
                    GroupSetup.ROUND -> "每次按成员顺序，每人说一段。"
                    else -> "由模型按话题、性格和关系决定谁接话。随时可以在输入框上方 @ 某个人，只让她回答。"
                }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (mode == GroupSetup.AUTO) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("每轮最多几个人接话", style = MaterialTheme.typography.bodyLarge)
                        Text("人越多越热闹，回复也越长", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    IconButton(onClick = { maxSpeakers = (maxSpeakers - 1).coerceAtLeast(1) }, enabled = maxSpeakers > 1) { Icon(Icons.Outlined.Remove, "减少") }
                    Text("${setup.maxSpeakers} 人", style = MaterialTheme.typography.titleMedium, color = colors.primary)
                    IconButton(onClick = { maxSpeakers = (maxSpeakers + 1).coerceAtMost(members.size.coerceAtLeast(1)) },
                        enabled = maxSpeakers < members.size) { Icon(Icons.Outlined.Add, "增加") }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("旁白", style = MaterialTheme.typography.bodyLarge)
                        Text("由旁白交代动作和环境", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Switch(narrator, { narrator = it })
                }
            }
            item {
                if (problem != null) Text(problem, style = MaterialTheme.typography.bodySmall, color = colors.error)
                Button(onClick = ::save, enabled = problem == null, modifier = Modifier.fillMaxWidth()) { Text(if (chat == null) "创建群聊" else "保存") }
            }
        }
    }
    if (addingRelation) RelationDialog(members.mapNotNull(byId::get), onDismiss = { addingRelation = false }) { relation ->
        relations = relations.filterNot { it.from == relation.from && it.to == relation.to } + relation
        addingRelation = false
    }
}

@Composable
private fun RelationDialog(members: List<Character>, onDismiss: () -> Unit, onAdd: (GroupRelation) -> Unit) {
    val people = members.map { it.id to GroupChats.displayName(it) } + (GroupRelation.USER to "你")
    var from by remember { mutableStateOf(people.first().first) }
    var to by remember { mutableStateOf(people.getOrNull(1)?.first ?: people.first().first) }
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加一条关系") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PersonPicker(people, from, Modifier.weight(1f)) { from = it }
                Text("  →  ")
                PersonPicker(people, to, Modifier.weight(1f)) { to = it }
            }
            OutlinedTextField(text, { text = it.take(GroupSetup.MAX_RELATION) }, Modifier.fillMaxWidth(), minLines = 2,
                label = { Text("怎么看对方") }, placeholder = { Text("例如：觉得她嘴硬心软，最爱逗她") })
            if (from == to) Text("两边要选不同的人", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(onClick = { onAdd(GroupRelation(from, to, text.trim())) }, enabled = from != to && text.isNotBlank()) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun PersonPicker(people: List<Pair<String, String>>, selected: String, modifier: Modifier, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Text(people.firstOrNull { it.first == selected }?.second ?: "选择", maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            people.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); open = false }) }
        }
    }
}
