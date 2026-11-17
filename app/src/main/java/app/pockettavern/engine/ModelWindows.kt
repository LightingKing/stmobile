package app.pockettavern.engine

import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import org.json.JSONObject

/**
 * How many tokens a model takes. Many OpenAI-compatible relays list models without a window, and the
 * app used to treat all of those as 32,768: a relay's "k3.1-pro" (Kimi K3.1 Pro, a million tokens on
 * Venice) could then not even carry the chat's Skills. Now, in order:
 * 1. the window the model's own catalog entry reports;
 * 2. the window the same model reports on another of the user's platforms (matched by its name);
 * 3. the window set in 设置 when the model reports none (it can raise the fallback, not only lower);
 * 4. 32,768.
 */
object ModelWindows {
    const val FALLBACK = 32_768
    private val KEYS = listOf("context_tokens", "context_length", "max_context_tokens", "contextTokens", "availableContextTokens")
    private val PREFIXES = listOf("e2ee", "openai", "venice")

    /** Whether the catalog entry itself named a window (the parsed number is otherwise the fallback). */
    fun reported(model: ModelInfo): Boolean {
        val json = runCatching { JSONObject(model.rawJson) }.getOrNull() ?: return false
        val spec = json.optJSONObject("model_spec") ?: json.optJSONObject("spec")
        return KEYS.any { key -> json.optLong(key, 0) > 0 || (spec?.optLong(key, 0) ?: 0) > 0 }
    }

    /** "kimi-k3-1-pro" and "k3.1-pro" both end in "k31pro". */
    internal fun normalized(id: String): String {
        var value = id.substringAfterLast('/').lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        PREFIXES.forEach { prefix -> if (value.startsWith(prefix) && value.length > prefix.length + 3) value = value.removePrefix(prefix) }
        return value
    }

    internal fun sameModel(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        // A bare "pro" or "4o" says too little; a name with a version number in it is specific enough.
        return short.length >= 5 && short.any { it.isDigit() } && long.endsWith(short)
    }

    /** The window from the entry itself or from the same model elsewhere; null when nobody says. */
    fun known(model: ModelInfo?, providers: List<ProviderConfig>): Int? {
        if (model == null) return null
        // A window the user typed in for this model wins over everything.
        providers.firstOrNull { provider -> provider.models.any { it === model } }?.modelEdits?.get(model.id)?.contextTokens?.takeIf { it > 0 }?.let { return it }
        if (reported(model) && model.contextTokens > 0) return model.contextTokens
        val name = normalized(model.id)
        return providers.asSequence().flatMap { it.models.asSequence() }
            .filter { it !== model && it.contextTokens > 0 && reported(it) && sameModel(name, normalized(it.id)) }
            .map { it.contextTokens }
            .minOrNull()
    }

    /** The model's window as best known, ignoring the user's setting. */
    fun of(model: ModelInfo?, providers: List<ProviderConfig>): Int = known(model, providers) ?: model?.contextTokens?.takeIf { it > 0 } ?: FALLBACK

    /**
     * The window a request is planned against: the user's setting caps a known window, and stands in
     * for one nobody reported.
     */
    fun effective(model: ModelInfo?, providers: List<ProviderConfig>, contextOverride: Int): Int {
        val known = known(model, providers)
        return when {
            known != null -> contextOverride.takeIf { it > 0 }?.let { minOf(it, known) } ?: known
            contextOverride > 0 -> contextOverride
            else -> model?.contextTokens?.takeIf { it > 0 } ?: FALLBACK
        }
    }
}
