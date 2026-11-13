package app.pockettavern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.Character
import app.pockettavern.domain.UiSession
import app.pockettavern.engine.CharacterDrafts

/**
 * The top of the character form: a sentence or two about the character, and a model writes the
 * whole card into the fields below for the user to review. [form] is what the fields hold now.
 */
@Composable
internal fun CharacterDraftPanel(state: UiSession, actions: AppActions, form: Character, onDraft: (Character) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val editing = CharacterDrafts.hasContent(form)
    var idea by rememberSaveable(form.id) { mutableStateOf("") }
    val fallback = remember(state.data, state.selectedChatId, state.hasKeys) { CharacterDrafts.defaultModel(state.data, state.selectedChatId, state.hasKeys) }
    var chosen by remember(form.id) { mutableStateOf<Pair<String, String>?>(null) }
    val model = chosen ?: fallback
    val draft = state.characterDraft
    // A finished draft goes into the form once, then the shared state is cleared.
    LaunchedEffect(draft?.result) { draft?.result?.let { onDraft(it); actions.clearCharacterDraft() } }
    // Closing the form stops a draft still being written.
    DisposableEffect(Unit) { onDispose { actions.cancelCharacterDraft() } }
    Card(colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer.copy(alpha = 0.55f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp), tint = colors.primary)
                Spacer(Modifier.width(6.dp))
                Text(if (editing) "让 AI 改写或补全" else "让 AI 帮你写", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            OutlinedTextField(idea, { idea = it.take(1000) }, Modifier.fillMaxWidth(), minLines = 2,
                placeholder = { Text(if (editing) "想怎么改？例如：性格再傲娇一点，补几段示例对话" else "一两句话就行。例如：大学社团里毒舌的学姐，嘴上嫌弃我其实很照顾人") })
            ModelPicker(state, model) { chosen = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft?.running == true) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在写，可能要几十秒…", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { actions.cancelCharacterDraft() }) { Text("停止") }
                } else {
                    Button(onClick = { model?.let { actions.draftCharacter(idea, form, it.first, it.second) } },
                        enabled = model != null && (idea.isNotBlank() || editing)) { Text(if (editing) "改写" else "生成角色卡") }
                    Text("生成后写进下面的表格，可以再改，保存才会建立角色。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            draft?.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        }
    }
}

@Composable
private fun ModelPicker(state: UiSession, model: Pair<String, String>?, onPick: (Pair<String, String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val choices = remember(state.data.providers, state.hasKeys) {
        state.data.providers.filter { it.id in state.hasKeys }.flatMap { provider ->
            provider.models.filter { it.type == "text" && !it.offline }.map { Triple(provider.id, it.id, "${it.name.ifBlank { it.id }} · ${provider.name}") }
        }
    }
    val label = choices.firstOrNull { it.first == model?.first && it.second == model.second }?.third
    Box {
        TextButton(onClick = { open = true }, enabled = choices.isNotEmpty()) {
            Text(if (label == null) "还没有可用的模型：先在“模型”页添加平台并刷新" else "用 $label 生成", maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (choices.isNotEmpty()) Icon(Icons.Outlined.ExpandMore, "换一个模型", Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }) {
            choices.take(60).forEach { (providerId, modelId, text) ->
                DropdownMenuItem(text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { onPick(providerId to modelId); open = false })
            }
        }
    }
}
