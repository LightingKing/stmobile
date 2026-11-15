package app.pockettavern.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.CardMaterial
import app.pockettavern.domain.CardPicture
import app.pockettavern.domain.CardSource
import app.pockettavern.domain.Character
import app.pockettavern.domain.DraftInputs
import app.pockettavern.domain.UiSession
import app.pockettavern.domain.chosenPcProvider
import app.pockettavern.engine.CharacterDrafts
import app.pockettavern.engine.CharacterResearch
import app.pockettavern.engine.ToolRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether the last form looked things up first; the choice stays while the app runs. */
private var lastResearchChoice = false

/**
 * The top of the character form: a sentence or two about the character, and a model writes the
 * whole card into the fields below for the user to review. With "先联网找资料" the PC first looks
 * up the character's pages, videos and pictures; what it found is listed here, so wrong finds can
 * be left out and a picture taken as the avatar. [form] is what the fields hold now.
 */
@Composable
internal fun CharacterDraftPanel(state: UiSession, actions: AppActions, form: Character, onDraft: (Character) -> Unit, onUsePicture: (String) -> Boolean = { false }) {
    val colors = MaterialTheme.colorScheme
    val editing = CharacterDrafts.hasContent(form)
    var idea by rememberSaveable(form.id) { mutableStateOf("") }
    val fallback = remember(state.data, state.selectedChatId, state.hasKeys) { CharacterDrafts.defaultModel(state.data, state.selectedChatId, state.hasKeys) }
    var chosen by remember(form.id) { mutableStateOf<Pair<String, String>?>(null) }
    val model = chosen ?: fallback
    val seesPictures = remember(state.data.providers, model) {
        state.data.providers.firstOrNull { it.id == model?.first }?.models?.firstOrNull { it.id == model?.second }?.supportsVision == true
    }
    val pcReady = remember(state.data.providers, state.pcLink.providerId, state.hasKeys) {
        chosenPcProvider(state.data.providers, state.pcLink.providerId)?.id?.let { it in state.hasKeys } == true
    }
    var research by rememberSaveable { mutableStateOf(lastResearchChoice) }
    // Text the user pastes for this character: a setting document, chat log, story excerpt.
    var notes by rememberSaveable(form.id) { mutableStateOf("") }
    var notesOpen by rememberSaveable(form.id) { mutableStateOf(false) }
    var skillsOpen by remember { mutableStateOf(false) }
    val inputs = state.draftInputs
    val hasOwn = !inputs.isEmpty || notes.isNotBlank()
    // A link in the idea is a request to read it.
    val linked = remember(idea) { CharacterResearch.links(idea).isNotEmpty() }
    val searching = pcReady && (research || linked)
    var material by remember(form.id) { mutableStateOf<CardMaterial?>(null) }
    var avatarPicture by remember(form.id) { mutableStateOf("") }
    val draft = state.characterDraft
    val running = draft?.running == true
    LaunchedEffect(draft?.material) { draft?.material?.let { material = it } }
    // A finished draft goes into the form once, then the shared state is cleared.
    LaunchedEffect(draft?.result) { draft?.result?.let { onDraft(it); actions.clearCharacterDraft() } }
    // Closing the form stops a draft still being written and forgets the pictures found for it.
    DisposableEffect(Unit) { onDispose { actions.closeCharacterDraft() } }
    Card(colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer.copy(alpha = 0.55f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp), tint = colors.primary)
                Spacer(Modifier.width(6.dp))
                Text(if (editing) "让 AI 改写或补全" else "让 AI 帮你写", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            OutlinedTextField(idea, { idea = it.take(1000) }, Modifier.fillMaxWidth(), minLines = 2,
                placeholder = { Text(when {
                    editing -> "想怎么改？例如：性格再傲娇一点，补几段示例对话"
                    searching -> "写角色名和作品名，也可以贴网页或视频链接。例如：我的妹妹哪有这么可爱 里的黑猫"
                    else -> "一两句话就行。例如：大学社团里毒舌的学姐，嘴上嫌弃我其实很照顾人"
                }) })
            OwnMaterialRow(inputs, running, notesOpen, onAdd = { actions.pickDraftMaterial() }, onNotes = { notesOpen = !notesOpen })
            if (notesOpen) OutlinedTextField(notes, { notes = it.take(app.pockettavern.data.DraftMedia.MAX_TEXT_CHARS) }, Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp),
                label = { Text("补充资料（${notes.length} / ${app.pockettavern.data.DraftMedia.MAX_TEXT_CHARS}）") }, enabled = !running,
                placeholder = { Text("可以粘贴设定集、聊天记录、小说片段……写卡时以这里为准。") })
            if (!inputs.isEmpty || inputs.notes.isNotEmpty()) OwnMaterialSection(inputs, running, seesPictures, avatarPicture, actions,
                onAvatar = { pictureId -> if (onUsePicture(pictureId)) avatarPicture = pictureId })
            DraftSkillsRow(state, running) { skillsOpen = true }
            Row(Modifier.fillMaxWidth().toggleable(value = searching, enabled = pcReady && !linked && !running, role = Role.Checkbox) { research = it; lastResearchChoice = it },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = searching, onCheckedChange = null, enabled = pcReady && !linked && !running)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("先联网找资料", style = MaterialTheme.typography.bodyMedium)
                    Text(when {
                        !pcReady -> "要用电脑写作服务：在“模型”页添加后才能用。"
                        linked -> "会先读你贴的链接，再找这个角色的网页、视频和图片。"
                        else -> "电脑先找这个角色的网页、视频、图片，以及电脑上登录过的抖音、微博、小红书等平台的动态，再按资料写。找不找、找什么由模型判断。"
                    }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            ModelPicker(state, model) { chosen = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(draft?.stage.orEmpty().ifBlank { "正在写，可能要几十秒…" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { actions.cancelCharacterDraft() }) { Text("停止") }
                } else {
                    Button(onClick = { model?.let {
                        // A new search replaces what was found; the user's own pictures stay, and so does one picked as the avatar.
                        if (searching) { material = null; if (inputs.pictures.none { it.id == avatarPicture }) avatarPicture = "" }
                        actions.draftCharacter(idea, form, it.first, it.second, research = searching, notes = notes)
                    } }, enabled = model != null && !inputs.busy && (idea.isNotBlank() || editing || hasOwn)) {
                        Text(when { searching && editing -> "找资料并改写"; searching -> "找资料并生成"; editing -> "改写"; else -> "生成角色卡" })
                    }
                    Text(if (searching) "找资料要半分钟到一分钟。写好后进下面的表格，可以再改。" else "生成后写进下面的表格，可以再改，保存才会建立角色。",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            draft?.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
            draft?.notice?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            material?.let { found ->
                MaterialSection(found, running, model != null, seesPictures, avatarPicture, actions,
                    onChange = { material = it },
                    onAvatar = { pictureId -> if (onUsePicture(pictureId)) avatarPicture = pictureId },
                    onRewrite = { model?.let { actions.draftCharacter(idea, form, it.first, it.second, material = found, notes = notes) } })
            }
        }
    }
    if (skillsOpen) DraftSkillsDialog(state, actions) { skillsOpen = false }
}

/** "添加素材" and the switch for the paste box. */
@Composable
private fun OwnMaterialRow(inputs: DraftInputs, running: Boolean, notesOpen: Boolean, onAdd: () -> Unit, onNotes: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onAdd, enabled = !running && !inputs.busy) { Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("添加素材") }
        TextButton(onClick = onNotes, enabled = !running) { Text(if (notesOpen) "收起补充资料" else "粘贴补充资料") }
        if (inputs.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    }
    if (inputs.isEmpty && !inputs.busy) Text("可以加你自己的照片（最多 ${app.pockettavern.data.DraftMedia.MAX_PHOTOS} 张）、一段 2 分钟以内的视频（取画面，不听声音）或文字文件。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The user's own photos, video frames and texts: each can be struck out, removed, or (pictures) taken as the avatar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnMaterialSection(inputs: DraftInputs, running: Boolean, seesPictures: Boolean, avatarPicture: String, actions: AppActions, onAvatar: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var selected by remember { mutableStateOf("") }
    if (!inputs.isEmpty) Text("你提供的素材", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    if (inputs.pictures.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            inputs.pictures.forEachIndexed { index, picture ->
                FoundPicture(picture, index, selected == picture.id, avatarPicture == picture.id, actions::researchPicture) { selected = if (selected == picture.id) "" else picture.id }
            }
        }
        val picked = inputs.pictures.firstOrNull { it.id == selected }
        if (picked == null) Text(if (seesPictures) "能看图的模型会先看你的照片和视频截图来写外貌。点一张图可以设为头像、划掉或移除。" else "这个模型不能看图，照片只能用来设头像；外貌按文字写。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onAvatar(picked.id) }, enabled = !running) { Text(if (avatarPicture == picked.id) "已设为头像" else "设为头像") }
            TextButton(onClick = { actions.toggleDraftInput(picked.id) }, enabled = !running) { Text(if (picked.used) "划掉不用" else "恢复使用") }
            TextButton(onClick = { actions.removeDraftInput(picked.id); selected = "" }, enabled = !running) { Text("移除") }
        }
    }
    inputs.texts.forEach { text ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(text.used, { actions.toggleDraftInput(text.id) }, enabled = !running)
            Column(Modifier.weight(1f)) {
                Text(text.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text("文字 · ${text.text.length} 字", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            TextButton(onClick = { actions.removeDraftInput(text.id) }, enabled = !running) { Text("移除") }
        }
    }
    inputs.notes.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
}

/** Which Skills the drafter writes with; opens the picker. */
@Composable
private fun DraftSkillsRow(state: UiSession, running: Boolean, onOpen: () -> Unit) {
    val skills = remember(state.data.skills, state.data.settings.draftSkillIds) { ToolRuntime.skillsInOrder(state.data.skills, state.data.settings.draftSkillIds) }
    Row(Modifier.fillMaxWidth().clickable(enabled = !running, onClickLabel = "选择写卡用的 Skills", role = Role.Button, onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (skills.isEmpty()) "使用 Skills" else "使用 Skills（${skills.size}）", style = MaterialTheme.typography.bodyMedium)
            Text(if (skills.isEmpty()) "可以选写角色卡、人物口吻、世界观相关的 Skill，写卡时照着它们的要求写。" else skills.joinToString("、") { it.name.ifBlank { "未命名 Skill" } },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ExpandMore, null)
    }
}

@Composable
private fun DraftSkillsDialog(state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(ToolRuntime.skillsInOrder(state.data.skills, state.data.settings.draftSkillIds).map { it.id }) }
    var pendingAdult by remember { mutableStateOf<String?>(null) }
    val costs = remember(state.data.skills) { skillCosts(state.data.skills, false) }
    val total = selected.sumOf { costs[it] ?: 0 }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("写卡用的 Skills") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("写卡时，所选 Skill 的正文会附在请求里。写卡是一次性请求，模型不能再去读 Skill 的参考文件，篇幅不大的会直接附上。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected.isNotEmpty()) Text("约占 ${total} tokens" + if (total > 20_000) "，偏大：会挤占资料的篇幅" else "", style = MaterialTheme.typography.bodySmall,
                color = if (total > 20_000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            ChatSkillPicker(state.data.skills, selected.toSet(), false, costs = costs, initialFilters = SkillFilters(), initialScenario = SkillScenario.VOICE, scope = "写卡", onSelect = { skill, checked ->
                if (checked && skill.adult && !state.data.settings.adultSkillsConfirmed) pendingAdult = skill.id
                else selected = if (checked) (selected + skill.id).distinct() else selected.filterNot { it == skill.id }
            })
        }
    }, confirmButton = { Button(onClick = { actions.setDraftSkills(selected); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    pendingAdult?.let { id ->
        AdultSkillConfirmDialog(onConfirm = { actions.confirmAdultSkills(true); selected = (selected + id).distinct(); pendingAdult = null }) { pendingAdult = null }
    }
}

/** What the PC found: pictures to look at and take as the avatar, and the pages and videos used. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaterialSection(material: CardMaterial, running: Boolean, canRewrite: Boolean, seesPictures: Boolean, avatarPicture: String, actions: AppActions,
    onChange: (CardMaterial) -> Unit, onAvatar: (String) -> Unit, onRewrite: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    var open by rememberSaveable { mutableStateOf(false) }
    var selected by remember(material.pictures.firstOrNull()?.id) { mutableStateOf("") }
    val posts = material.sources.count { it.kind == CardSource.POST || (it.kind == CardSource.LINK && it.platform.isNotEmpty()) }
    val videos = material.sources.count { it.kind == CardSource.VIDEO || (it.kind == CardSource.LINK && it.platform.isEmpty() && CharacterResearch.isVideo(it.url)) }
    val pages = material.sources.size - videos - posts
    val found = material.sources.isNotEmpty() || material.pictures.isNotEmpty()
    HorizontalDivider()
    if (found) Text("找到的资料" + if (material.subject.isNotBlank()) "：${material.subject}" else "", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    if (material.pictures.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            material.pictures.forEachIndexed { index, picture ->
                FoundPicture(picture, index, selected == picture.id, avatarPicture == picture.id, actions::researchPicture) { selected = if (selected == picture.id) "" else picture.id }
            }
        }
        val picked = material.pictures.firstOrNull { it.id == selected }
        if (picked == null) {
            Text(when {
                material.picturesSeen > 0 -> "模型看了前 ${material.picturesSeen} 张没划掉的图来写外貌。点一张图，可以设为头像或划掉不用。"
                seesPictures -> "点一张图，可以设为头像，或划掉不让模型参考。"
                else -> "这个模型不能看图，外貌按文字资料写。点一张图，可以设为头像。"
            }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = { onAvatar(picked.id) }, enabled = !running) { Text(if (avatarPicture == picked.id) "已设为头像" else "设为头像") }
            TextButton(onClick = { onChange(material.copy(pictures = material.pictures.map { if (it.id == picked.id) it.copy(used = !it.used) else it })) }, enabled = !running) {
                Text(if (picked.used) "划掉不用" else "恢复使用")
            }
            if (picked.pageUrl.isNotBlank()) TextButton(onClick = { runCatching { uriHandler.openUri(picked.pageUrl) } }) { Text("看出处") }
        }
    }
    if (material.sources.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "收起资料列表" else "展开资料列表", role = Role.Button) { open = !open }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull("$pages 个网页".takeIf { pages > 0 }, "$videos 个视频".takeIf { videos > 0 }, "$posts 条动态".takeIf { posts > 0 }).joinToString(" · ") +
                "（用了 ${material.sources.count { it.used }} 条）", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (open) material.sources.forEach { source ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(source.used, { ticked -> onChange(material.copy(sources = material.sources.map { if (it.id == source.id) it.copy(used = ticked) else it })) }, enabled = !running)
                Column(Modifier.weight(1f)) {
                    Text(source.title.ifBlank { source.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(sourceLine(source), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                IconButton(onClick = { runCatching { uriHandler.openUri(source.url) } }) { Icon(Icons.Outlined.OpenInNew, "在浏览器里打开") }
            }
        }
    }
    material.notes.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
    if (found) {
        OutlinedButton(onClick = onRewrite, enabled = !running && canRewrite) { Text("按勾选的资料重写") }
        Text("资料来自网络，可能有错，保存前请核对。图片仅供自己使用。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

private fun sourceLine(source: CardSource): String {
    val platform = CharacterResearch.SOCIAL[source.platform]
    val video = platform == null && (source.kind == CardSource.VIDEO || CharacterResearch.isVideo(source.url))
    val kind = when { source.kind == CardSource.LINK -> if (platform != null) "你贴的${platform}链接" else "你贴的链接"; platform != null -> platform; video -> "视频"; else -> "网页" }
    val depth = when { video && source.full -> "读了字幕或简介"; video -> "只有标题"; source.full -> "读了全文"; platform != null -> if (source.text.isBlank()) "只有标题" else "正文摘要"; else -> "只有摘要" }
    return listOf(depth, kind, source.author.ifBlank { CharacterResearch.site(source.url) }).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun FoundPicture(picture: CardPicture, index: Int, selected: Boolean, isAvatar: Boolean, load: (String) -> ImageBitmap?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val bitmap by produceState<ImageBitmap?>(null, picture.id) { value = withContext(Dispatchers.Default) { runCatching { load(picture.id) }.getOrNull() } }
    val shape = RoundedCornerShape(10.dp)
    val describe = "第 ${index + 1} 张图" + (if (picture.title.isNotBlank()) "：${picture.title}" else "") + (if (!picture.used) "，已划掉" else "") + (if (isAvatar) "，已设为头像" else "")
    Box(Modifier.size(width = 84.dp, height = 112.dp).clip(shape).background(colors.surfaceVariant)
        .then(if (selected) Modifier.border(BorderStroke(3.dp, colors.primary), shape) else Modifier)
        .selectable(selected = selected, role = Role.Button, onClick = onClick)) {
        bitmap?.let { Image(it, describe, Modifier.matchParentSize().alpha(if (picture.used) 1f else 0.3f), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter) }
            ?: Text(describe, Modifier.align(Alignment.Center).padding(6.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val badge = when { isAvatar -> "头像"; !picture.used -> "不用"; picture.source == CardPicture.FRAME -> "视频"; picture.mine -> "你的"; else -> "" }
        if (badge.isNotBlank()) Text(badge, Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(if (isAvatar) colors.primary else colors.inverseSurface.copy(alpha = 0.7f)).padding(vertical = 2.dp),
            color = if (isAvatar) colors.onPrimary else colors.inverseOnSurface, style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
