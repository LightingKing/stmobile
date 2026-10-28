package app.pockettavern.ui

import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.Preset

/** Raw editing values stay separate from saved numbers, including empty and partial input. */
internal data class CompressionSettingsDraft(
    val inputBudget: String,
    val threshold: String,
    val keepRecent: String,
    val contextOverride: String,
    val toolSteps: String
) {
    fun validationError(): String? =
        integerInputError(inputBudget, "日常输入预算", 0..2_000_000)
            ?: decimalInputError(threshold, "压缩触发比例", 0.2..0.95)
            ?: integerInputError(keepRecent, "保留最近轮数", 1..100)
            ?: integerInputError(contextOverride, "上下文覆盖上限", 0..2_000_000)
            ?: integerInputError(toolSteps, "最大工具轮数", 1..16)

    fun applyTo(latest: AppSettings): AppSettings {
        val error = validationError()
        require(error == null) { error ?: "压缩设置无效" }
        return latest.copy(
            maxContextInputTokens = inputBudget.toInt(),
            compressionThreshold = threshold.toDouble(),
            keepRecentTurns = keepRecent.toInt(),
            contextOverride = contextOverride.toInt(),
            maxToolSteps = toolSteps.toInt()
        )
    }

    companion object {
        fun from(settings: AppSettings) = CompressionSettingsDraft(
            inputBudget = settings.maxContextInputTokens.toString(),
            threshold = settings.compressionThreshold.toString(),
            keepRecent = settings.keepRecentTurns.toString(),
            contextOverride = settings.contextOverride.toString(),
            toolSteps = settings.maxToolSteps.toString()
        )
    }
}

internal data class PresetNumbersDraft(
    val temperature: String,
    val maxOutput: String,
    val reasoningBudget: String,
    val generationBudgetEnabled: Boolean = false,
) {
    fun validationError(): String? =
        decimalInputError(temperature, "温度", 0.0..2.0)
            ?: if (generationBudgetEnabled) {
                integerInputError(maxOutput, "输出 token 上限", 1..131_072)
                    ?: integerInputError(reasoningBudget, "思考预留 token", 0..131_072)
            } else null

    fun applyTo(latest: Preset): Preset {
        val error = validationError()
        require(error == null) { error ?: "预设数值无效" }
        return latest.copy(
            temperature = temperature.toDouble(),
            maxOutput = if (generationBudgetEnabled) maxOutput.toInt() else latest.maxOutput,
            reasoningBudget = if (generationBudgetEnabled) reasoningBudget.toInt() else latest.reasoningBudget,
            generationBudgetEnabled = generationBudgetEnabled,
        )
    }

    companion object {
        fun from(preset: Preset) = PresetNumbersDraft(
            temperature = preset.temperature.toString(),
            maxOutput = preset.maxOutput.toString(),
            reasoningBudget = preset.reasoningBudget.toString(),
            generationBudgetEnabled = preset.generationBudgetEnabled,
        )
    }
}

internal fun integerInputError(text: String, label: String, range: IntRange): String? {
    if (text.isBlank()) return "${label}不能为空"
    val value = text.toIntOrNull() ?: return "${label}请输入 ${range.first}～${range.last} 之间的整数"
    return if (value in range) null else "${label}应在 ${range.first}～${range.last} 之间"
}

private fun decimalInputError(text: String, label: String, range: ClosedFloatingPointRange<Double>): String? {
    if (text.isBlank()) return "${label}不能为空"
    val value = text.toDoubleOrNull()
    return if (value != null && value.isFinite() && value in range) null
    else "${label}请输入 ${range.start}～${range.endInclusive} 之间的数值"
}
