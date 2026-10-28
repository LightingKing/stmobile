package app.pockettavern.provider

import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.Preset
import org.json.JSONObject

/** Read the selected catalog entry; a model name alone never grants a capability. */
data class VeniceModelCapabilities(
    val supportsReasoningEffort: Boolean = false,
    val reasoningEffortOptions: List<String> = emptyList(),
    val defaultReasoningEffort: String? = null,
    val maxCompletionTokens: Int? = null
) {
    /**
     * Venice's server-side completion limit includes reasoning and visible text.
     * The app treats Preset.maxOutput as the visible正文 target and adds the
     * explicit reserve only while reasoning is enabled on Venice.
     */
    fun totalCompletionBudget(preset: Preset): Long =
        preset.maxOutput.toLong() + if (preset.reasoningEffort == "none") 0L else preset.reasoningBudget.toLong()

    companion object {
        private val EFFORTS = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

        fun from(model: ModelInfo?): VeniceModelCapabilities {
            val root = model?.let { runCatching { JSONObject(it.rawJson) }.getOrNull() } ?: return VeniceModelCapabilities()
            val spec = root.optJSONObject("model_spec") ?: return VeniceModelCapabilities()
            val caps = spec.optJSONObject("capabilities")
            val supported = caps?.opt("supportsReasoningEffort") == true
            val options = if (supported) caps?.optJSONArray("reasoningEffortOptions")?.let { array ->
                (0 until array.length()).mapNotNull { array.opt(it) as? String }.filter { it in EFFORTS }.distinct()
            }.orEmpty() else emptyList()
            val default = if (supported) (caps?.opt("defaultReasoningEffort") as? String)?.takeIf { it in options } else null
            val max = spec.opt("maxCompletionTokens")?.let { value ->
                if (value is Int || value is Long) (value as Number).toLong().takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() else null
            }
            return VeniceModelCapabilities(supported, options, default, max)
        }
    }
}
