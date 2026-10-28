package app.pockettavern.engine

import app.pockettavern.domain.*
import app.pockettavern.provider.VeniceModelCapabilities

/** Model-specific choices are opt-in and scoped to a conversation, never a shared preset. */
object DeepSeekWriting {
    const val MODEL_ID = "deepseek-v4-1-flash-1017"
    private const val PREFIX = "deepseek-writing-"

    fun isTarget(provider: ProviderConfig?, modelId: String): Boolean =
        provider?.kind == "venice" && modelId == MODEL_ID

    data class Selection(
        val effort: String,
        val maxOutput: Int,
        val includePlatformPrompt: Boolean,
        val reasoningBudget: Int = 0,
        val generationBudgetEnabled: Boolean = false
    )

    /**
     * A visible-text target needs a server-side reserve because Venice counts
     * reasoning and visible text in one max_completion_tokens value. These are
     * conservative starting points and remain editable in the UI.
     */
    fun suggestedReasoningBudget(effort: String): Int = when (effort) {
        "minimal" -> 4_096
        "low" -> 8_192
        "medium" -> 16_384
        "high" -> 32_768
        "xhigh" -> 49_152
        "max" -> 65_536
        else -> 0
    }

    fun initialSelection(preset: Preset?, limit: Int?): Selection {
        val tuned = preset?.id?.startsWith(PREFIX) == true
        val effort = preset?.reasoningEffort?.takeUnless { it == "default" || it.isBlank() } ?: "low"
        if (preset != null && !preset.generationBudgetEnabled) {
            return Selection(effort, preset.maxOutput, if (tuned) preset.includeVeniceSystemPrompt else false,
                preset.reasoningBudget, false)
        }
        val output = if (tuned) preset!!.maxOutput else preset?.maxOutput?.takeIf { it > 2048 } ?: 8192
        val limitValue = (limit ?: 131072).coerceIn(1, 131072)
        val savedReserve = preset?.reasoningBudget ?: 0
        val suggested = if (effort == "none") 0 else suggestedReasoningBudget(effort)
        val reserve = (if (tuned && savedReserve > 0) savedReserve else suggested)
            .coerceAtMost((limitValue - output.coerceIn(1, limitValue)).coerceAtLeast(0))
        return Selection(
            effort,
            output.coerceIn(1, limitValue),
            if (tuned) preset!!.includeVeniceSystemPrompt else false,
            reserve,
            preset?.generationBudgetEnabled ?: false
        )
    }

    fun apply(
        data: AppData,
        chatId: String,
        effort: String,
        maxOutput: Int,
        includePlatformPrompt: Boolean,
        reasoningBudget: Int = 0,
        generationBudgetEnabled: Boolean = false
    ): AppData {
        val chat = data.chats.find { it.id == chatId } ?: error("会话已不存在")
        val provider = data.providers.find { it.id == chat.providerId }
        require(isTarget(provider, chat.modelId)) { "此调优仅适用于 Venice 的 DeepSeek v4.1 Flash (1017)" }
        val model = provider!!.models.find { it.id == chat.modelId }
        require(model != null && !model.offline) { "模型不可用，请先刷新模型目录" }
        val capabilities = VeniceModelCapabilities.from(model)
        require(capabilities.supportsReasoningEffort && effort in capabilities.reasoningEffortOptions) {
            "当前模型目录未确认支持该思考强度，请刷新模型目录后重试"
        }
        val max = if (generationBudgetEnabled) {
            minOf(capabilities.maxCompletionTokens ?: error("目录缺少输出上限，请刷新模型目录后重试"), 131072)
        } else 131072
        require(maxOutput in 1..max) { "正文上限应为 1–$max tokens" }
        require(reasoningBudget in 0..max) { "思考预留应为 0–$max tokens" }
        val effectiveReserve = if (generationBudgetEnabled && effort == "none") 0 else reasoningBudget
        require(!generationBudgetEnabled || maxOutput.toLong() + effectiveReserve <= max.toLong()) {
            "正文上限 $maxOutput + 思考预留 $effectiveReserve 超过模型总上限 $max，请降低其中一项"
        }
        val original = data.presets.find { it.id == chat.presetId } ?: data.presets.first()
        val owned = original.id.startsWith(PREFIX) &&
            data.chats.none { it.id != chat.id && it.presetId == original.id } &&
            data.characters.none { it.defaultPresetId == original.id }
        val tuned = original.copy(
            id = if (owned) original.id else PREFIX + newId(),
            name = if (owned) original.name else "${original.name} · DeepSeek 写作",
            temperature = 1.0,
            maxOutput = maxOutput,
            reasoningEffort = effort,
            includeVeniceSystemPrompt = includePlatformPrompt,
            reasoningBudget = effectiveReserve,
            generationBudgetEnabled = generationBudgetEnabled
        )
        return data.copy(
            presets = data.presets.filterNot { it.id == tuned.id } + tuned,
            chats = data.chats.map { if (it.id == chatId) it.copy(presetId = tuned.id) else it }
        )
    }

    /** Keep the saved 1017 preset available when a different Venice model needs other settings. */
    fun selectModel(data: AppData, chatId: String, providerId: String, modelId: String): AppData {
        val chat = data.chats.find { it.id == chatId } ?: error("会话已不存在")
        val provider = data.providers.find { it.id == providerId } ?: error("平台已不存在")
        val model = provider.models.find { it.id == modelId }
        require(model != null && model.type == "text" && !model.offline) { "请选择目录中可用的聊天模型" }
        val active = data.presets.find { it.id == chat.presetId }
        val caps = VeniceModelCapabilities.from(model)
        val adapted = if (active?.id?.startsWith(PREFIX) == true && provider.kind == "venice") {
            val effort = active.reasoningEffort.takeIf { it == "default" || it in caps.reasoningEffortOptions } ?: "default"
            val output = if (active.generationBudgetEnabled) minOf(active.maxOutput, caps.maxCompletionTokens ?: active.maxOutput) else active.maxOutput
            val reserve = if (!active.generationBudgetEnabled) active.reasoningBudget else if (effort == "none") 0 else {
                minOf(active.reasoningBudget, (caps.maxCompletionTokens ?: Int.MAX_VALUE) - output).coerceAtLeast(0)
            }
            if (effort != active.reasoningEffort || output != active.maxOutput || reserve != active.reasoningBudget) active.copy(
                id = PREFIX + newId(), name = "${active.name} · 兼容 ${model.name.ifBlank { model.id }}",
                reasoningEffort = effort, maxOutput = output, reasoningBudget = reserve
            ) else null
        } else null
        return data.copy(
            presets = if (adapted == null) data.presets else data.presets + adapted,
            chats = data.chats.map { if (it.id == chatId) it.copy(providerId = providerId, modelId = modelId,
                presetId = adapted?.id ?: it.presetId, updatedAt = System.currentTimeMillis()) else it }
        )
    }

    /** A separate summary model must not inherit another model's explicit reasoning levels. */
    fun summaryPreset(active: Preset, provider: ProviderConfig, model: ModelInfo, output: Int): Preset {
        val capabilities = VeniceModelCapabilities.from(model)
        // Venice counts visible working state, knowledge deltas and hidden
        // reasoning in max_completion_tokens, so allowing the 1017 model's
        // low/default thinking mode here can consume the configured budget before
        // the summary is finished and return finish_reason=length. Summaries are
        // extraction tasks; when the catalog supports it, spend the whole budget
        // on visible text instead of inheriting the chat's thinking settings.
        val effort = if (provider.kind == "venice" && capabilities.supportsReasoningEffort && "none" in capabilities.reasoningEffortOptions) "none" else "default"
        val limit = if (provider.kind == "venice") capabilities.maxCompletionTokens ?: output else output
        return active.copy(maxOutput = minOf(output, limit), temperature = 0.2, reasoningEffort = effort, reasoningBudget = 0,
            generationBudgetEnabled = true)
    }
}
