package app.pockettavern.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.AppActions
import app.pockettavern.domain.Chat
import app.pockettavern.domain.UiSession
import app.pockettavern.engine.DeepSeekWriting
import app.pockettavern.provider.VeniceModelCapabilities

@Composable
internal fun DeepSeekWritingDialog(chat: Chat, state: UiSession, actions: AppActions, onDismiss: () -> Unit) {
    val provider = state.data.providers.firstOrNull { it.id == chat.providerId }
    val model = provider?.models?.firstOrNull { it.id == chat.modelId }
    val preset = state.data.presets.firstOrNull { it.id == chat.presetId }
    val capabilities = remember(model) { VeniceModelCapabilities.from(model) }
    val limit = capabilities.maxCompletionTokens?.takeIf { it > 0 }?.coerceAtMost(131072)
    val initial = DeepSeekWriting.initialSelection(preset, limit)
    var effort by rememberSaveable(chat.id, preset?.id, preset?.reasoningEffort) {
        mutableStateOf(initial.effort)
    }
    var output by rememberSaveable(chat.id, preset?.id, preset?.maxOutput, limit) {
        mutableStateOf(initial.maxOutput.toString())
    }
    var reasoningBudget by rememberSaveable(chat.id, preset?.id, preset?.reasoningBudget, limit) {
        mutableStateOf(initial.reasoningBudget.toString())
    }
    var generationBudgetEnabled by rememberSaveable(chat.id, preset?.id, preset?.generationBudgetEnabled, limit) {
        mutableStateOf(initial.generationBudgetEnabled)
    }
    var includePlatformPrompt by rememberSaveable(chat.id, preset?.id, preset?.includeVeniceSystemPrompt) { mutableStateOf(initial.includePlatformPrompt) }
    val maxOutput = output.toIntOrNull()
    val reasoningReserve = reasoningBudget.toIntOrNull()
    val reasoningCatalogReady = capabilities.supportsReasoningEffort && capabilities.reasoningEffortOptions.isNotEmpty()
    val effectiveReserve = if (effort == "none") 0 else reasoningReserve
    val budgetValid = !generationBudgetEnabled || (maxOutput != null && effectiveReserve != null && limit != null &&
        maxOutput in 1..limit && effectiveReserve in 0..limit && maxOutput.toLong() + effectiveReserve <= limit.toLong())
    val canApply = !state.chatBusy(chat.id) && !state.loading && model?.offline == false && preset != null && DeepSeekWriting.isTarget(provider, chat.modelId) &&
        reasoningCatalogReady && effort in capabilities.reasoningEffortOptions && budgetValid
    val appliedOutput = if (generationBudgetEnabled) maxOutput ?: initial.maxOutput else preset?.maxOutput ?: initial.maxOutput
    val appliedReserve = if (generationBudgetEnabled) {
        if (effort == "none") 0 else reasoningReserve ?: initial.reasoningBudget
    } else preset?.reasoningBudget ?: initial.reasoningBudget

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DeepSeek 写作调优") },
        text = {
            DeepSeekWritingSettings(
                presetName = preset?.name ?: "预设未找到",
                currentEffort = preset?.reasoningEffort ?: "default",
                capabilities = capabilities,
                effort = effort,
                onEffortChange = { effort = it },
                output = output,
                onOutputChange = { output = it },
                reasoningBudget = reasoningBudget,
                onReasoningBudgetChange = { reasoningBudget = it },
                generationBudgetEnabled = generationBudgetEnabled,
                onGenerationBudgetEnabledChange = { generationBudgetEnabled = it },
                includePlatformPrompt = includePlatformPrompt,
                onPlatformPromptChange = { includePlatformPrompt = it },
                offline = model?.offline == true,
                enabled = !state.chatBusy(chat.id),
                modifier = Modifier.heightIn(max = 560.dp)
            )
        },
        confirmButton = {
            Button(onClick = {
                actions.applyDeepSeekWriting(chat.id, effort, appliedOutput, includePlatformPrompt, appliedReserve, generationBudgetEnabled)
                onDismiss()
            }, enabled = canApply) { Text("应用到本会话") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun DeepSeekWritingSettings(
    presetName: String,
    currentEffort: String,
    capabilities: VeniceModelCapabilities,
    effort: String,
    onEffortChange: (String) -> Unit,
    output: String,
    onOutputChange: (String) -> Unit,
    reasoningBudget: String = "0",
    onReasoningBudgetChange: (String) -> Unit = {},
    generationBudgetEnabled: Boolean = false,
    onGenerationBudgetEnabledChange: (Boolean) -> Unit = {},
    includePlatformPrompt: Boolean,
    onPlatformPromptChange: (Boolean) -> Unit,
    offline: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val limit = capabilities.maxCompletionTokens?.takeIf { it > 0 }?.coerceAtMost(131072)
    val reasoningCatalogReady = capabilities.supportsReasoningEffort && capabilities.reasoningEffortOptions.isNotEmpty()
    val invalidOutput = generationBudgetEnabled && (output.toIntOrNull()?.let { it < 1 || (limit != null && it > limit) } ?: true)
    val reserve = reasoningBudget.toIntOrNull()
    val effectiveReserve: Int? = if (effort == "none") 0 else reserve
    val outputValue = output.toIntOrNull()
    val invalidReserve = generationBudgetEnabled && (effectiveReserve == null || effectiveReserve < 0 || (limit != null && effectiveReserve > limit) ||
        (outputValue != null && effectiveReserve != null && outputValue.toLong() + effectiveReserve > (limit ?: Int.MAX_VALUE).toLong()))
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Venice · DeepSeek V4.1 Flash", style = MaterialTheme.typography.titleSmall)
        Text("当前预设：$presetName\n当前思考设置：${reasoningEffortLabel(currentEffort)}\n目录默认思考：${capabilities.defaultReasoningEffort ?: "未获取"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (offline) Text("目录标记此模型已离线，暂时无法应用。请刷新 Venice 目录或选择其他模型。", color = MaterialTheme.colorScheme.error)
        if (!reasoningCatalogReady) {
            Text("模型目录未确认思考档位，暂时无法应用。请到模型页刷新 Venice 目录后重试。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        VeniceReasoningChoice(
            value = effort,
            options = capabilities.reasoningEffortOptions,
            onChange = onEffortChange,
            includeDefault = false,
            enabled = enabled && capabilities.reasoningEffortOptions.isNotEmpty()
        )
        if (reasoningCatalogReady && effort !in capabilities.reasoningEffortOptions) {
            Text("当前预设的思考档位不在此模型目录中，请选择可用档位。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("自定义生成预算", Modifier.weight(1f))
            Switch(
                checked = generationBudgetEnabled,
                onCheckedChange = onGenerationBudgetEnabledChange,
                enabled = enabled,
            )
        }
        Text(
            if (generationBudgetEnabled) {
                "开启后编辑正文上限和思考预留；需要模型目录提供总上限。"
            } else {
                "关闭时跟随平台，平台仍有自身上限；需要时开启预算编辑。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (generationBudgetEnabled) {
            if (limit == null) {
                Text("尚未获取模型总上限；开启预算后请到模型页刷新 Venice 目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = output,
                onValueChange = { onOutputChange(it.filter(Char::isDigit).take(10)) },
                label = { Text("正文上限（token）") },
                supportingText = { Text(limit?.let { "可见正文目标；模型总上限为 $it token" } ?: "需刷新目录，确认模型支持的上限") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = enabled && limit != null,
                isError = invalidOutput,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = reasoningBudget,
                onValueChange = { onReasoningBudgetChange(it.filter(Char::isDigit).take(10)) },
                label = { Text("思考预留（token）") },
                supportingText = { Text(limit?.let { "请求总预算 = 正文上限 + 思考预留；范围：0–$it" } ?: "需刷新目录，确认模型支持的上限") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = enabled && limit != null,
                isError = invalidReserve,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Venice 没有把思考从总预算中排除的 API。这里把思考单独预留出来，再发送两者之和；预留不足时，模型仍可能在正文完成前达到总上限。选择 none 时思考预留按 0 处理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("启用思考时不发送温度，温度不能用来调节此模式的文风。选择 none 直接写作时，使用温度 1.0。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        VenicePlatformPromptToggle(includePlatformPrompt, onPlatformPromptChange, enabled)
        HorizontalDivider()
        Text("应用后，本会话使用独立的预设副本，保留原系统提示词、角色卡、Skills 和会话原文。其他会话不受影响。", style = MaterialTheme.typography.bodySmall)
        Text("原预设仍保留，可从聊天顶部的预设菜单切回。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun reasoningEffortLabel(value: String): String = when (value) {
    "default" -> "跟随平台默认"
    "none" -> "none · 直接写作"
    "low" -> "low · 日常续写"
    "high" -> "high · 复杂剧情"
    "max" -> "max · 深度推敲"
    "medium" -> "medium · 适中思考"
    "minimal" -> "minimal · 少量思考"
    else -> value.ifBlank { "未设置" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VeniceReasoningChoice(
    value: String,
    options: List<String>,
    onChange: (String) -> Unit,
    includeDefault: Boolean = true,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val choices = (if (includeDefault) listOf("default") + options else options).distinct()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ExposedDropdownMenuBox(expanded && enabled, { if (enabled) expanded = !expanded }) {
            OutlinedTextField(
                value = reasoningEffortLabel(value),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
                label = { Text("思考档位（Venice 专用）") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) }
            )
            ExposedDropdownMenu(expanded && enabled, { expanded = false }) {
                choices.forEach { option ->
                    DropdownMenuItem(text = { Text(reasoningEffortLabel(option)) }, onClick = { onChange(option); expanded = false })
                }
            }
        }
        Text(if (includeDefault) "跟随平台默认不指定档位；其他选项来自已下载的 Venice 目录，以会话模型实际支持为准。" else "日常续写建议 low；复杂剧情可选 high。选项以此模型目录为准。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun VenicePlatformPromptToggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("平台附加提示（Venice 专用）", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
        Text("关闭后，由你的预设、角色卡和已启用 Skills 主导风格。此开关仅控制平台附加提示，不涉及安全设置。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
