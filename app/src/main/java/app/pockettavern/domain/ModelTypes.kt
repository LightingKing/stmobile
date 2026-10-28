package app.pockettavern.domain

import com.google.gson.JsonParser
import java.util.Locale

/** Canonical capability types shared by fresh directories and previously saved catalogs. */
object ModelTypes {
    fun normalize(model: ModelInfo, providerKind: String): ModelInfo {
        var type = model.type.trim().lowercase(Locale.ROOT)
        if (type.isBlank() || type == "model") {
            // Anthropic-compatible gateways use "model" as a resource discriminator.
            // An explicit nested capability (e.g. image) still takes precedence.
            val declaredType = runCatching {
                val raw = JsonParser.parseString(model.rawJson).asJsonObject
                val rawType = raw.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (type.isBlank() && rawType.isNotBlank() && rawType != "model") return@runCatching rawType
                val spec = listOf("model_spec", "spec").firstNotNullOfOrNull { key ->
                    raw.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
                }
                spec?.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.trim()?.lowercase(Locale.ROOT).orEmpty()
            }.getOrDefault("")
            if (declaredType.isNotBlank() && declaredType != "model") type = declaredType
            else if (type.isBlank() || providerKind in listOf("openai", "anthropic", "writing-server")) type = "text"
        }
        return if (type == model.type) model else model.copy(type = type)
    }
}
