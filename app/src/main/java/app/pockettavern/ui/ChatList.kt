package app.pockettavern.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.ChatTask
import app.pockettavern.domain.ChatTasks
import app.pockettavern.domain.UiSession
import java.time.ZoneId

/** "Now" for the time labels; previews pin it so their pictures do not change with the date. */
internal val LocalClock = staticCompositionLocalOf<() -> Long> { { System.currentTimeMillis() } }

/** Letter avatars: dark enough for white text in both themes. */
private val AvatarTints = listOf(Color(0xFF5B4A8A), Color(0xFF3F6B5A), Color(0xFF7A5A32), Color(0xFF3F5E8C), Color(0xFF8A4A5E))

/**
 * Home: every chat with its picture, its last line and what it is doing right now. Several chats
 * can be replying at once; each row shows its own progress and unread replies.
 */
@Composable
internal fun ChatList(chats: List<Chat>, state: UiSession, actions: AppActions, onDelete: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var addOpen by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    val characters = remember(state.data.characters) { state.data.characters.associateBy { it.id } }
    val loadAvatar = remember(actions) { { avatar: String -> actions.characterAvatar(avatar) } }
    val shown = remember(chats, characters, query) { chats.filter { ChatListModel.matches(it, characters[it.characterId], query) } }
    val zone = remember { ZoneId.systemDefault() }
    val clock = LocalClock.current
    val now by produceState(clock(), clock) { while (true) { kotlinx.coroutines.delay(30_000); value = clock() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("口袋酒馆", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val working = ChatTasks.summary(state.chatTasks)
                Text(working.ifEmpty { if (chats.isEmpty()) "随身的创作空间" else "${chats.size} 个会话" },
                    style = MaterialTheme.typography.labelMedium, color = if (working.isEmpty()) colors.onSurfaceVariant else colors.primary)
            }
            Box {
                IconButton(onClick = { addOpen = true }) { Icon(Icons.Outlined.Add, "新建") }
                DropdownMenu(addOpen, { addOpen = false }) {
                    DropdownMenuItem(text = { Text("新建会话") }, leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, null) }, onClick = { addOpen = false; actions.createChat() })
                    DropdownMenuItem(text = { Text("角色卡助手") }, leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) }, onClick = { addOpen = false; actions.startCharacterBuilder() })
                    DropdownMenuItem(text = { Text("导入会话") }, leadingIcon = { Icon(Icons.Outlined.FileUpload, null) }, onClick = { addOpen = false; actions.importFile("chat") })
                }
            }
        }
        if (chats.isEmpty()) {
            ConversationWelcome(Modifier.weight(1f), "给想象，留一点空间。", "聊天、创作，或继续一个故事。\n你的会话会保存在这台设备上。") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.createChat() }, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text("开始新对话")
                    }
                    OutlinedButton(onClick = { actions.importFile("chat") }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                        Icon(Icons.Outlined.FileUpload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("导入会话")
                    }
                }
            }
            return@Column
        }
        ChatSearchField(query) { query = it }
        if (shown.isEmpty()) Text("没有找到“${query.trim()}”", Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
            items(shown, key = { it.id }) { chat ->
                ChatRow(chat, characters[chat.characterId], state.chatTasks[chat.id], ChatListModel.time(chat.updatedAt, now, zone), loadAvatar,
                    onOpen = { actions.selectChat(chat.id) }, onStop = { actions.stopChat(chat.id) },
                    onRename = { renameId = chat.id }, onDelete = { onDelete(chat.id) })
            }
        }
    }
    renameId?.let { id -> chats.firstOrNull { it.id == id }?.let { chat -> RenameChatDialog(chat, actions) { renameId = null } } }
}

@Composable
private fun ChatSearchField(query: String, onQuery: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = CircleShape, color = colors.surfaceVariant.copy(alpha = 0.6f)) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("搜索会话、角色或聊天内容", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
                BasicTextField(query, onQuery, Modifier.fillMaxWidth().semantics { contentDescription = "搜索会话" }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary))
            }
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "清除搜索", Modifier.size(18.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: Chat, character: Character?, task: ChatTask?, time: String, loadAvatar: (String) -> ImageBitmap?,
    onOpen: () -> Unit, onStop: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val preview = remember(chat.messages) { ChatListModel.preview(chat) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }, onLongClickLabel = "会话操作")
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChatAvatar(chat, character, loadAvatar)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                TitleWithTag(
                    title = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
                    tag = if (chat.browsePerDay > 0) ({ ChatTag("自己刷视频") }) else null
                )
                Spacer(Modifier.height(2.dp))
                if (task != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                    Text(ChatTasks.label(task), style = MaterialTheme.typography.bodyMedium, color = colors.primary, maxLines = 1)
                } else Text(preview, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(time, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                if (chat.unread > 0) Surface(shape = CircleShape, color = colors.error) {
                    Text(if (chat.unread > 99) "99+" else chat.unread.toString(), Modifier.padding(horizontal = 7.dp, vertical = 1.dp)
                        .semantics { contentDescription = "${chat.unread} 条新回复" }, style = MaterialTheme.typography.labelSmall, color = colors.onError)
                }
            }
        }
        DropdownMenu(menuOpen, { menuOpen = false }, offset = DpOffset(86.dp, (-12).dp)) {
            if (task != null) DropdownMenuItem(text = { Text("停止") }, leadingIcon = { Icon(Icons.Outlined.Stop, null) }, onClick = { menuOpen = false; onStop() })
            // A busy chat is finished or stopped first; the controller refuses these otherwise.
            DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, enabled = task == null, onClick = { menuOpen = false; onRename() })
            DropdownMenuItem(text = { Text("删除会话", color = if (task == null) colors.error else colors.onSurface.copy(alpha = 0.38f)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = if (task == null) colors.error else colors.onSurface.copy(alpha = 0.38f)) },
                enabled = task == null, onClick = { menuOpen = false; onDelete() })
        }
    }
    HorizontalDivider(Modifier.padding(start = 86.dp), color = colors.outlineVariant.copy(alpha = 0.4f))
}

/**
 * The name comes first. The tag sits beside it only when it fits whole next to a name that keeps
 * at least a good half of the line; on a narrow screen with large text the tag is left out.
 */
@Composable
private fun TitleWithTag(title: @Composable () -> Unit, tag: (@Composable () -> Unit)?) {
    Layout(content = { title(); tag?.invoke() }) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val wanted = measurables[0].maxIntrinsicWidth(constraints.maxHeight)
        val tagWidth = measurables.getOrNull(1)?.maxIntrinsicWidth(constraints.maxHeight) ?: 0
        val showTag = measurables.size > 1 && minOf(wanted, constraints.maxWidth * 55 / 100) + gap + tagWidth <= constraints.maxWidth
        val name = measurables[0].measure(loose.copy(maxWidth = if (showTag) constraints.maxWidth - gap - tagWidth else constraints.maxWidth))
        val label = if (showTag) measurables[1].measure(loose) else null
        val height = maxOf(name.height, label?.height ?: 0)
        layout(constraints.maxWidth, height) {
            name.placeRelative(0, (height - name.height) / 2)
            label?.placeRelative(name.width + gap, (height - label.height) / 2)
        }
    }
}

@Composable
private fun ChatAvatar(chat: Chat, character: Character?, loadAvatar: (String) -> ImageBitmap?) {
    val avatar = character?.avatar.orEmpty()
    if (avatar.isNotBlank()) {
        CharacterAvatar(avatar, character?.name.orEmpty(), 52.dp, loadAvatar)
    } else {
        Box(Modifier.size(52.dp).clip(CircleShape).background(AvatarTints[ChatListModel.tintIndex(chat.id, AvatarTints.size)]), contentAlignment = Alignment.Center) {
            Text(ChatListModel.initial(chat, character), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}

@Composable
private fun ChatTag(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer, maxLines = 1)
    }
}
