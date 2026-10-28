package app.pockettavern.ui

import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig

/** Catalogs can list several snapshots under one display name; the id is what tells them apart. */
internal data class ModelRowLabel(val title: String, val meta: String, val id: String?)

internal fun modelRowLabel(provider: ProviderConfig, model: ModelInfo): ModelRowLabel {
    val title = model.name.ifBlank { model.id }
    return ModelRowLabel(title, "${provider.name} · ${model.contextTokens / 1024}K 上下文", model.id.takeIf { it != title })
}

/** The compact header shows the id only when another available chat model has the same name. */
internal fun selectedModelId(providers: List<ProviderConfig>, model: ModelInfo): String? {
    val title = model.name.ifBlank { model.id }
    val twin = providers.flatMap { it.models }.any {
        it.id != model.id && !it.offline && it.type.equals("text", true) && it.name.ifBlank { it.id } == title
    }
    return model.id.takeIf { twin && it != title }
}
