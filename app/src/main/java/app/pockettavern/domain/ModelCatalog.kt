package app.pockettavern.domain

/** Shared catalog filtering without network requests or changes to the saved model list. */
object ModelCatalog {
    fun filter(
        providers: List<ProviderConfig>,
        query: String = "",
        type: String = "全部",
        providerId: String = ""
    ): List<Pair<ProviderConfig, ModelInfo>> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return providers.asSequence()
            .filter { providerId.isBlank() || it.id == providerId }
            .flatMap { provider ->
                val platformFields = listOf(provider.name, provider.id, provider.kind, provider.baseUrl)
                provider.models.asSequence().filter { model ->
                    (type.isBlank() || type == "全部" || model.type.equals(type, ignoreCase = true)) &&
                        terms.all { term ->
                            (platformFields + listOf(model.name, model.id, model.type))
                                .any { it.contains(term, ignoreCase = true) }
                        }
                }.map { provider to it }
            }.toList()
    }
}
