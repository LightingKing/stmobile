package app.pockettavern.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pockettavern.data.ChatBackgrounds
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.ChatLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatAppearanceSheet(chat: Chat, character: Character?, settings: AppSettings, actions: AppActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ChatAppearanceContent(chat, character, settings, actions)
    }
}

/**
 * Background, its dim and blur, and how see-through the message cards are. Every change applies
 * at once; sliders apply when let go.
 */
@Composable
internal fun ChatAppearanceContent(chat: Chat, character: Character?, settings: AppSettings, actions: AppActions) {
    val colors = MaterialTheme.colorScheme
    val look = chat.look
    fun apply(next: ChatLook?) = actions.setChatLook(chat.id, next)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("聊天外观", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("背景", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val chosenFile = look?.background?.takeIf { ChatBackgrounds.builtin(it) == null }
            BackdropOption("相册", chosenFile != null, onClick = { actions.pickChatBackground(chat.id) }) {
                if (chosenFile != null) ChosenPicture(chosenFile, actions::chatBackground, Modifier.matchParentSize())
                else Icon(Icons.Outlined.AddPhotoAlternate, "从相册选择", Modifier.align(Alignment.Center), tint = colors.onSurfaceVariant)
            }
            ChatBackgrounds.BUILTIN.forEach { (id, label) ->
                val value = "builtin:$id"
                BackdropOption(label, look?.background == value, onClick = { apply((look ?: ChatLook()).copy(background = value)) }) {
                    BuiltinScene(id, Modifier.matchParentSize())
                }
            }
            BackdropOption("无", look == null, onClick = { apply(null) }) { }
        }
        AppearanceSlider("背景变暗", look?.dim ?: ChatLook.DEFAULT_DIM, 0..ChatLook.MAX_DIM, look != null, { "$it%" }) { apply(look?.copy(dim = it)) }
        AppearanceSlider("背景模糊", look?.blur ?: 0, 0..ChatLook.MAX_BLUR, look != null, { ChatLook.BLUR_LABELS[it] }, steps = ChatLook.MAX_BLUR - 1) { apply(look?.copy(blur = it)) }
        AppearanceSlider("气泡透明", look?.bubbleClear ?: ChatLook.DEFAULT_CLEAR, 0..ChatLook.MAX_CLEAR, look != null, { "$it%" }) { apply(look?.copy(bubbleClear = it)) }
        if (character != null) {
            val isDefault = look != null && character.look == look
            AppearanceSwitch("设为「${character.name.ifBlank { "这个角色" }}」的默认背景",
                if (look == null) "先选一张背景" else "以后和她新建的会话都用这张", isDefault, enabled = look != null) { on ->
                actions.setCharacterLook(character.id, if (on) look else null)
            }
        }
        AppearanceSwitch("显示我的头像", if (settings.userAvatar.isBlank()) "在「设置」里选一张" else "显示在你的每条消息旁",
            settings.showUserAvatar && settings.userAvatar.isNotBlank(), enabled = settings.userAvatar.isNotBlank()) { actions.setShowUserAvatar(it) }
    }
}

@Composable
private fun ChosenPicture(value: String, load: (String, Int) -> ImageBitmap?, modifier: Modifier) {
    val inspection = LocalInspectionMode.current
    val picture by produceState<ImageBitmap?>(null, value) { if (!inspection) this.value = withContext(Dispatchers.IO) { runCatching { load(value, 2) }.getOrNull() } }
    picture?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) }
}

@Composable
private fun AppearanceSlider(label: String, value: Int, range: IntRange, enabled: Boolean, format: (Int) -> String, steps: Int = 0, onDone: (Int) -> Unit) {
    // The slider moves freely while dragged; the chat changes once, when it is let go.
    var dragged by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(dragged, { dragged = it }, Modifier.weight(1f), enabled = enabled, valueRange = range.first.toFloat()..range.last.toFloat(), steps = steps,
            onValueChangeFinished = { onDone(dragged.roundToInt()) })
        Text(format(dragged.roundToInt()), Modifier.width(44.dp).padding(start = 8.dp), style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppearanceSwitch(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}
