package app.pockettavern.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.Chat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One image attachment from a chat message, ready for the gallery. */
data class GalleryItem(
    val chatId: String,
    val messageId: String,
    val attachmentId: String,
    val dataUrl: String,
    val name: String,
    val createdAt: Long
)

/** Collect all image attachments for a character by scanning their chat messages. */
fun collectGalleryItems(chats: List<Chat>, characterId: String): List<GalleryItem> =
    chats
        .filter { it.characterId == characterId }
        .flatMap { chat ->
            chat.messages.flatMap { msg ->
                msg.attachments
                    .filter { att -> att.mime.startsWith("image/") && att.dataUrl.isNotBlank() }
                    .map { att ->
                        GalleryItem(
                            chatId = chat.id,
                            messageId = msg.id,
                            attachmentId = att.id,
                            dataUrl = att.dataUrl,
                            name = att.name,
                            createdAt = msg.createdAt
                        )
                    }
            }
        }
        .sortedByDescending { it.createdAt }

/** Decode a base64 dataUrl to an ImageBitmap; returns null on failure. */
private fun decodeDataUrl(dataUrl: String): ImageBitmap? = runCatching {
    val comma = dataUrl.indexOf(',')
    val b64 = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/** Thumbnail that loads the bitmap off the main thread. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryThumbnail(item: GalleryItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, item.attachmentId) {
        value = withContext(Dispatchers.IO) { decodeDataUrl(item.dataUrl) }
    }
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true; onLongClick() }
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, item.name.ifBlank { "图片" }, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Outlined.PhotoLibrary, null, Modifier.align(Alignment.Center).size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("删除记录") },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { menuOpen = false; onLongClick() }
            )
        }
    }
}

/** Full-screen viewer — tap the close button or the background to dismiss. */
@Composable
private fun FullScreenViewer(item: GalleryItem, onDismiss: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, item.attachmentId) {
        value = withContext(Dispatchers.IO) { decodeDataUrl(item.dataUrl) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(bmp, item.name.ifBlank { "图片" },
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    contentScale = ContentScale.Fit)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
            ) {
                Icon(Icons.Outlined.Close, "关闭", tint = Color.White)
            }
        }
    }
}

/** Confirm before deleting a gallery item record. */
@Composable
private fun DeleteRecordDialog(item: GalleryItem, actions: AppActions, onDone: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除记录") },
        text = { Text("从这条消息中移除此图片附件记录（不会删除文件）。") },
        confirmButton = {
            TextButton(onClick = {
                actions.removeMessageAttachment(item.chatId, item.messageId, item.attachmentId)
                onDone()
            }) { Text("删除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** Gallery dialog shown from a character card — grid of thumbnails, full-screen viewer on tap, delete on long-press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CharacterGalleryDialog(
    characterId: String,
    characterName: String,
    chats: List<Chat>,
    actions: AppActions,
    onDismiss: () -> Unit
) {
    val items = remember(chats, characterId) { collectGalleryItems(chats, characterId) }
    var viewing by remember { mutableStateOf<GalleryItem?>(null) }
    var deleting by remember { mutableStateOf<GalleryItem?>(null) }
    // Track locally deleted ids so the grid updates immediately without waiting for a recompose from actions
    var deletedIds by remember { mutableStateOf(emptySet<String>()) }
    val visible = items.filter { it.attachmentId !in deletedIds }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            Modifier.fillMaxSize(),
            shape = RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("${characterName.ifBlank { "角色" }} · 图片库 (${visible.size})") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭") }
                    }
                )
                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Text("还没有图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("在与该角色的会话中发送图片后会显示在这里",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp))
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        items(visible, key = { it.attachmentId }) { item ->
                            GalleryThumbnail(
                                item = item,
                                onClick = { viewing = item },
                                onLongClick = { deleting = item }
                            )
                        }
                    }
                }
            }
        }
    }

    viewing?.let { item ->
        FullScreenViewer(item = item, onDismiss = { viewing = null })
    }

    deleting?.let { item ->
        DeleteRecordDialog(
            item = item,
            actions = actions,
            onDone = { deletedIds = deletedIds + item.attachmentId; deleting = null },
            onDismiss = { deleting = null }
        )
    }
}

/** Count of image attachments for a character — used as the badge label on the gallery button. */
fun countGalleryImages(chats: List<Chat>, characterId: String): Int =
    chats
        .filter { it.characterId == characterId }
        .sumOf { chat ->
            chat.messages.sumOf { msg ->
                msg.attachments.count { att -> att.mime.startsWith("image/") && att.dataUrl.isNotBlank() }
            }
        }
