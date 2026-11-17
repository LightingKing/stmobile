package app.pockettavern.engine

import app.pockettavern.domain.ModelEdit
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import org.json.JSONObject

/**
 * What the app believes a model can do. Relays often list bare ids, and those used to come in with
 * no vision, often no tools, and a 32,768 window, with no way to say otherwise. Now a refreshed list
 * borrows what the same model reports on another of the user's platforms, and the user's own
 * corrections ([ProviderConfig.modelEdits]) are applied on top and survive every refresh.
 */
object ModelCapabilities {
    private val CAPABILITY_KEYS = listOf("supports_vision", "supportsVision", "vision", "supports_tools", "supportsTools", "tool_calling", "capabilities")

    /** Whether the catalog entry said anything about vision or tools (false then really means no). */
    fun reportsCapabilities(model: ModelInfo): Boolean {
        val json = runCatching { JSONObject(model.rawJson) }.getOrNull() ?: return false
        val spec = json.optJSONObject("model_spec") ?: json.optJSONObject("spec")
        return CAPABILITY_KEYS.any { json.has(it) || spec?.has(it) == true }
    }

    /** The same model on another platform that does describe itself; null when there is none. */
    private fun twin(model: ModelInfo, providerId: String, providers: List<ProviderConfig>): ModelInfo? {
        val name = ModelWindows.normalized(model.id)
        return providers.asSequence().filter { it.id != providerId }.flatMap { it.models.asSequence() }
            .firstOrNull { reportsCapabilities(it) && ModelWindows.sameModel(name, ModelWindows.normalized(it.id)) }
    }

    /** A freshly fetched list for [providerId]: gaps filled from twins, then the user's corrections. */
    fun refreshed(providerId: String, fetched: List<ModelInfo>, providers: List<ProviderConfig>, edits: Map<String, ModelEdit>): List<ModelInfo> =
        applyEdits(fetched.map { model ->
            var result = model
            if (!reportsCapabilities(model)) twin(model, providerId, providers)?.let { other ->
                result = result.copy(supportsVision = result.supportsVision || other.supportsVision, supportsTools = result.supportsTools || other.supportsTools,
                    supportsReasoning = result.supportsReasoning || other.supportsReasoning)
            }
            if (!ModelWindows.reported(model)) ModelWindows.known(model, providers.filter { it.id != providerId })?.let { result = result.copy(contextTokens = it) }
            result
        }, edits)

    fun applyEdits(models: List<ModelInfo>, edits: Map<String, ModelEdit>): List<ModelInfo> = models.map { model ->
        val edit = edits[model.id] ?: return@map model
        model.copy(
            supportsVision = edit.vision ?: model.supportsVision, supportsTools = edit.tools ?: model.supportsTools,
            contextTokens = edit.contextTokens.takeIf { it > 0 } ?: model.contextTokens
        )
    }

    /** The provider with [edit] for [modelId] (null or empty removes it) and its list updated. */
    fun withEdit(provider: ProviderConfig, modelId: String, edit: ModelEdit?): ProviderConfig {
        val edits = if (edit == null || edit.isEmpty) provider.modelEdits - modelId else provider.modelEdits + (modelId to edit)
        return provider.copy(modelEdits = edits, models = applyEdits(provider.models, edits))
    }
}
