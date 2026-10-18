package app.pockettavern.provider

import app.pockettavern.domain.ModelInfo
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class ModelPriceInfo(val lines: List<String>, val note: String = "")

/**
 * Formats the pricing metadata already kept in ModelInfo.rawJson.
 *
 * This is deliberately a display-only parser. It never combines currencies and it never
 * treats an absent or malformed price as zero. Venice is the only provider whose units are
 * known here; other provider schemas are left uninterpreted.
 */
object ModelPricing {
    private val tokenFields = linkedMapOf(
        "input" to "输入",
        "output" to "输出",
        "cache_input" to "缓存输入",
        "cache_write" to "缓存写入",
        "cacheRead" to "缓存读取",
        "cacheWrite" to "缓存写入",
        "extended" to "扩展"
    )
    private val metadataKeys = setOf("min_seconds", "max_seconds", "included")
    private val currencyMetadataKeys = setOf("min_seconds", "max_seconds", "included")

    fun describe(model: ModelInfo, providerKind: String): ModelPriceInfo {
        if (!providerKind.equals("venice", ignoreCase = true)) {
            return ModelPriceInfo(
                lines = listOf("价格：该平台的定价格式尚未适配，无法安全解释计费单位。"),
                note = "非 Venice 定价字段不会按 Venice 规则解释或换算。"
            )
        }

        val root = runCatching { JSONObject(model.rawJson) }.getOrNull()
        val pricing = root?.let(::findPricing)
        if (pricing == null) {
            return unknownPrice(model.type)
        }

        val state = RenderState()
        when (model.type.trim().lowercase(Locale.US)) {
            "text", "embedding" -> renderTokenPricing(pricing, state)
            "decision" -> renderTokenLikePricing(pricing, state, null)
            "tts" -> renderTokenLikePricing(pricing, state, "百万字符")
            "asr" -> {
                pricing.optJSONObject("per_audio_second")?.let {
                    state.rate(it, "每音频秒", "音频秒")
                } ?: state.invalidIfPresent(pricing, "per_audio_second")
                renderUnknownChildren(pricing, setOf("per_audio_second"), state)
            }
            "image", "inpaint", "upscale" -> renderImagePricing(pricing, state)
            "music" -> renderMusicPricing(pricing, state)
            "video" -> renderUnknownPricing(pricing, state)
            else -> renderUnknownPricing(pricing, state)
        }

        if (state.lines.isEmpty()) {
            state.lines += "价格：目录定价格式无法解析，不能视为免费。"
        }
        if (state.invalid > 0) {
            state.lines += "价格：部分定价格式异常，不能视为免费。"
        }
        val notes = mutableListOf<String>()
        if (state.invalid > 0) notes += "部分定价字段无法解析，未将其视为免费。"
        if (model.type.equals("video", ignoreCase = true)) {
            notes += "视频价格通常需按时长、分辨率等参数报价，请调用报价接口确认。"
        }
        if (model.type.equals("music", ignoreCase = true)) {
            notes += "音乐价格可能按时长、字符数等参数变化，生成前请按请求参数确认报价。"
        }
        if (hasRenderableRate(pricing.optJSONObject("quality")) && pricing.has("resolutions") &&
            (model.type.equals("image", ignoreCase = true) || model.type.equals("inpaint", ignoreCase = true))) {
            notes += "图片价格按分辨率和质量分档，不同档位不叠加收费。"
        }
        return ModelPriceInfo(state.lines.distinct(), notes.joinToString(" "))
    }

    private fun findPricing(root: JSONObject): JSONObject? {
        val candidates = listOf(
            root.optJSONObject("model_spec")?.optJSONObject("pricing"),
            root.optJSONObject("spec")?.optJSONObject("pricing"),
            root.optJSONObject("pricing")
        )
        return candidates.firstOrNull { it != null && it.length() > 0 } ?: candidates.firstOrNull()
    }

    private fun unknownPrice(type: String): ModelPriceInfo {
        return if (type.equals("video", ignoreCase = true)) {
            ModelPriceInfo(
                listOf("价格：目录未提供固定价格，需按视频参数报价。"),
                "视频价格通常需按时长、分辨率等参数报价，请调用报价接口确认。"
            )
        } else {
            ModelPriceInfo(listOf("价格：目录未提供或无法解析，不能视为免费。"))
        }
    }

    private fun renderTokenPricing(pricing: JSONObject, state: RenderState) {
        renderTokenLikePricing(pricing, state, "百万 tokens")
    }

    private fun renderTokenLikePricing(pricing: JSONObject, state: RenderState, unit: String?) {
        tokenFields.forEach { (key, label) ->
            val value = pricing.opt(key)
            if (key == "extended" && value is JSONObject) renderExtendedPricing(value, label, state, unit)
            else if (value is JSONObject) state.rate(value, label, unit)
            else if (pricing.has(key)) state.invalid++
        }
        renderUnknownChildren(pricing, tokenFields.keys, state)
    }

    private fun renderExtendedPricing(node: JSONObject, label: String, state: RenderState, unit: String?) {
        val nestedFields = tokenFields.filterKeys { it != "extended" }
        val threshold = number(node.opt("context_token_threshold"))
        val condition = threshold?.let { "（上下文阈值 ${formatAmount(it)} tokens）" }.orEmpty()
        val direct = currencyEntries(node)
        if (direct.isNotEmpty()) {
            state.addRate(label + condition, direct, unit)
        }
        var rendered = false
        nestedFields.forEach { (key, childLabel) ->
            val child = node.opt(key)
            if (child is JSONObject) {
                state.rate(child, "$label $childLabel$condition", unit)
                rendered = true
            } else if (node.has(key)) {
                state.invalid++
            }
        }
        if (!rendered && node.has("context_token_threshold") && threshold == null) state.invalid++
        renderUnknownChildren(node, nestedFields.keys + "context_token_threshold", state)
    }

    private fun renderImagePricing(pricing: JSONObject, state: RenderState) {
        pricing.optJSONObject("generation")?.let { state.rate(it, "生成", "张图片") }
            ?: state.invalidIfPresent(pricing, "generation")
        pricing.optJSONObject("inpaint")?.let { state.rate(it, "局部重绘", "张图片") }
            ?: state.invalidIfPresent(pricing, "inpaint")

        pricing.optJSONObject("upscale")?.let { node ->
            renderNestedRates(node, state, "放大", "张图片")
        } ?: state.invalidIfPresent(pricing, "upscale")
        val qualityObject = pricing.optJSONObject("quality")
        val qualityRendered = qualityObject?.let {
            renderNestedRates(it, state, "质量", "张图片")
        } == true
        if (pricing.has("quality") && qualityObject == null) state.invalid++
        if (!qualityRendered) {
            val resolutionObject = pricing.optJSONObject("resolutions")
            val resolutionRendered = resolutionObject?.let {
                renderNestedRates(it, state, "分辨率", "张图片")
            } == true
            if (pricing.has("resolutions") && resolutionObject == null) state.invalid++
            if (resolutionObject != null && !resolutionRendered) state.invalid++
        }
        pricing.optJSONObject("inputImages")?.let { node ->
            val included = number(node.opt("included"))
            if (included != null) state.lines += "包含输入图片：${formatAmount(included)} 张"
            else if (node.has("included")) state.invalid++
            node.optJSONObject("additional")?.let { state.rate(it, "额外输入图片", "张图片") }
                ?: state.invalidIfPresent(node, "additional")
            renderUnknownChildren(node, setOf("included", "additional"), state)
        } ?: state.invalidIfPresent(pricing, "inputImages")

        renderUnknownChildren(pricing, setOf("generation", "inpaint", "upscale", "resolutions", "quality", "inputImages"), state)
    }

    private fun renderMusicPricing(pricing: JSONObject, state: RenderState) {
        pricing.optJSONObject("generation")?.let { state.rate(it, "生成", "次") }
            ?: state.invalidIfPresent(pricing, "generation")
        pricing.optJSONObject("per_second")?.let { state.rate(it, "音频时长", "音频秒") }
            ?: state.invalidIfPresent(pricing, "per_second")
        pricing.optJSONObject("per_thousand_characters")?.let { state.rate(it, "文本输入", "千字符") }
            ?: state.invalidIfPresent(pricing, "per_thousand_characters")
        pricing.optJSONObject("durations")?.let { durations ->
            val keys = durations.keys().asSequence().toList().sortedWith { left, right ->
                val leftNumber = left.toBigDecimalOrNull()
                val rightNumber = right.toBigDecimalOrNull()
                when {
                    leftNumber != null && rightNumber != null -> {
                        val numeric = leftNumber.compareTo(rightNumber)
                        if (numeric != 0) numeric else left.compareTo(right, ignoreCase = true)
                    }
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> left.compareTo(right, ignoreCase = true)
                }
            }
            keys.forEach { key ->
                val band = durations.optJSONObject(key)
                if (band == null) {
                    state.invalid++
                    return@forEach
                }
                val min = number(band.opt("min_seconds"))
                val max = number(band.opt("max_seconds"))
                val range = when {
                    min != null && max != null -> "（${formatAmount(min)}–${formatAmount(max)} 秒）"
                    min != null -> "（${formatAmount(min)} 秒起）"
                    max != null -> "（最长 ${formatAmount(max)} 秒）"
                    else -> ""
                }
                state.rate(band, "时长档 $key 秒$range", "次", currencyMetadataKeys)
            }
        } ?: state.invalidIfPresent(pricing, "durations")
        renderUnknownChildren(pricing, setOf("generation", "per_second", "per_thousand_characters", "durations"), state)
    }

    private fun renderNestedRates(node: JSONObject, state: RenderState, prefix: String, unit: String, path: List<String> = emptyList()): Boolean {
        val direct = currencyEntries(node)
        var rendered = direct.isNotEmpty()
        if (direct.isNotEmpty()) state.addRate(pathLabel(prefix, path), direct, unit)
        if (direct.isEmpty() && node.keys().asSequence().any { it !in currencyMetadataKeys && node.opt(it) !is JSONObject && node.opt(it) !is JSONArray }) {
            state.invalid++
        }
        node.keys().asSequence().toList().sorted().forEach { key ->
            if (key in currencyMetadataKeys || key in knownCurrencyNames) return@forEach
            val child = node.optJSONObject(key)
            if (child != null) rendered = renderNestedRates(child, state, prefix, unit, path + key) || rendered
            else if (node.opt(key) is JSONArray) state.invalid++
        }
        return rendered
    }

    private fun renderUnknownPricing(pricing: JSONObject, state: RenderState) {
        val direct = currencyEntries(pricing)
        if (direct.isNotEmpty()) state.addRate("价格", direct, null)
        renderUnknownChildren(pricing, emptySet(), state)
    }

    private fun hasRenderableRate(node: JSONObject?): Boolean {
        if (node == null) return false
        if (currencyEntries(node).isNotEmpty()) return true
        return node.keys().asSequence().any { key ->
            node.optJSONObject(key)?.let(::hasRenderableRate) == true
        }
    }

    private fun renderUnknownChildren(pricing: JSONObject, known: Set<String>, state: RenderState, path: List<String> = emptyList()) {
        pricing.keys().asSequence().toList().sorted().forEach { key ->
            if (key in known) return@forEach
            if (key in knownCurrencyNames || key in metadataKeys) return@forEach
            val value = pricing.opt(key)
            when (value) {
                is JSONObject -> {
                    val direct = currencyEntries(value)
                    if (direct.isNotEmpty()) {
                        state.addRate(pathLabel(key, path), direct, null)
                        if (value.keys().asSequence().any {
                            it !in currencyMetadataKeys && it !in knownCurrencyNames &&
                                value.opt(it) !is JSONObject && value.opt(it) !is JSONArray && number(value.opt(it)) == null
                        }) state.invalid++
                    } else {
                        if (value.keys().asSequence().any {
                            it !in currencyMetadataKeys && value.opt(it) !is JSONObject && value.opt(it) !is JSONArray &&
                                number(value.opt(it)) == null
                        }) state.invalid++
                        renderUnknownChildren(value, emptySet(), state, path + key)
                    }
                }
                is JSONArray -> state.invalid++
                else -> if (number(value) != null) state.lines += "${pathLabel(key, path)}：${formatAmount(number(value)!!)}（计费单位未提供）"
            }
        }
    }

    private fun pathLabel(prefix: String, path: List<String>): String =
        (listOf(prefix) + path).joinToString(" ") { part ->
            when (part.lowercase(Locale.US)) {
                "low" -> "低"
                "medium" -> "中"
                "high" -> "高"
                else -> part
            }
        }

    private fun currencyEntries(node: JSONObject, excluded: Set<String> = currencyMetadataKeys): List<Pair<String, BigDecimal>> =
        node.keys().asSequence().toList().sorted().mapNotNull { key ->
            if (key in excluded || key.lowercase(Locale.US) !in knownCurrencyNames) return@mapNotNull null
            number(node.opt(key))?.let { key to it }
        }

    private fun number(value: Any?): BigDecimal? {
        if (value !is Number) return null
        return runCatching { BigDecimal(value.toString()) }
            .getOrNull()
            ?.takeIf { it.signum() >= 0 }
    }

    private fun formatAmount(value: BigDecimal): String =
        value.round(MathContext(16, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString().ifBlank { "0" }

    private fun formatCurrency(key: String, value: BigDecimal): String {
        val currency = when (key.lowercase(Locale.US)) {
            "usd" -> "USD"
            "diem" -> "Diem"
            else -> key.uppercase(Locale.US)
        }
        return "${formatAmount(value)} $currency"
    }

    private val knownCurrencyNames = setOf(
        "usd", "eur", "gbp", "cny", "jpy", "krw", "inr", "rub", "brl", "mxn", "aud", "cad", "nzd",
        "sgd", "hkd", "twd", "chf", "sek", "nok", "dkk", "pln", "try", "zar", "ils", "thb", "idr",
        "myr", "php", "vnd", "uah", "czk", "huf", "ron", "bgn", "isk", "aed", "sar", "ars", "clp",
        "cop", "pen", "ngn", "egp", "kes", "qar", "kwd", "bhd", "diem"
    )

    private class RenderState {
        val lines = mutableListOf<String>()
        var invalid: Int = 0

        fun rate(node: JSONObject, label: String, unit: String?, excluded: Set<String> = currencyMetadataKeys) {
            val values = currencyEntries(node, excluded)
            if (values.isNotEmpty()) addRate(label, values, unit) else invalid++
            val bad = node.keys().asSequence().any { key ->
                key !in excluded && node.has(key) && number(node.opt(key)) == null
            }
            if (bad) invalid++
        }

        fun addRate(label: String, values: List<Pair<String, BigDecimal>>, unit: String?) {
            val suffix = unit?.let { " / $it" } ?: "（计费单位未提供）"
            lines += "$label：${values.joinToString("；") { "${formatCurrency(it.first, it.second)}$suffix" }}"
        }

        fun invalidIfPresent(parent: JSONObject, key: String) {
            if (parent.has(key)) invalid++
        }
    }
}
