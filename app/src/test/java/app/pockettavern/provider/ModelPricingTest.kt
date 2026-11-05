package app.pockettavern.provider

import app.pockettavern.domain.ModelInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPricingTest {
    @Test fun textPricingReadsNestedModelSpecAndKeepsZeroAndSmallDecimals() {
        val model = model("text", """
            {"model_spec":{"pricing":{"input":{"usd":2.5,"diem":2.5},"output":{"usd":0},"cache_input":{"usd":0.0001},"cache_write":{"usd":0.25},"extended":{"usd":1.125}}}}
        """)
        val result = ModelPricing.describe(model, "venice")

        assertTrue(result.lines.any { it.contains("输入") && it.contains("2.5 USD") && it.contains("百万 tokens") })
        assertTrue(result.lines.any { it.contains("输出") && it.contains("0 USD") })
        assertTrue(result.lines.any { it.contains("缓存输入") && it.contains("0.0001 USD") })
        assertTrue(result.lines.any { it.contains("缓存写入") })
        assertTrue(result.lines.any { it.contains("扩展") })
    }

    @Test fun extendedTextPricingKeepsThresholdOnNestedRates() {
        val model = model("text", """
            {"model_spec":{"pricing":{"input":{"usd":1.0},"output":{"usd":2.0},"extended":{"context_token_threshold":256000,"input":{"usd":1.5},"output":{"usd":6.0},"cache_input":{"usd":0.15},"cache_write":{"usd":1.875}}}}}
        """)
        val result = ModelPricing.describe(model, "venice")

        assertTrue(result.lines.any { it.contains("扩展 输入") && it.contains("1.5 USD") && it.contains("256000 tokens") })
        assertTrue(result.lines.any { it.contains("扩展 输出") && it.contains("6 USD") && it.contains("百万 tokens") })
        assertFalse(result.lines.any { it.contains("CONTEXT_TOKEN_THRESHOLD") })
    }

    @Test fun extendedDirectCurrenciesDoNotHideNestedRates() {
        val model = model("text", """
            {"pricing":{"extended":{"usd":0.5,"input":{"usd":1.5},"output":{"usd":6.0}}}}
        """)
        val result = ModelPricing.describe(model, "venice")

        assertTrue(result.lines.any { it.contains("扩展") && it.contains("0.5 USD") })
        assertTrue(result.lines.any { it.contains("扩展 输入") && it.contains("1.5 USD") })
        assertTrue(result.lines.any { it.contains("扩展 输出") && it.contains("6 USD") })
    }

    @Test fun tinyPositivePricesAreNotRoundedToFree() {
        val result = ModelPricing.describe(model("text", """{"pricing":{"input":{"usd":0.000000000000001}}}"""), "venice")

        assertTrue(result.lines.single().contains("0.000000000000001 USD"))
        assertFalse(result.lines.single().contains("0 USD"))
    }

    @Test fun imagePricingRendersResolutionQualityUpscaleAndInpaintInputImages() {
        val image = model("image", """
            {"spec":{"pricing":{"resolutions":{"1K":{"usd":0.07}},"quality":{"2K":{"low":{"usd":0.1}}},"upscale":{"2x":{"usd":0.02},"4x":{"usd":0.08}}}}}
        """)
        val inpaint = model("inpaint", """
            {"pricing":{"inpaint":{"usd":0.12},"inputImages":{"included":1,"additional":{"usd":0.0345}}}}
        """)

        val imageResult = ModelPricing.describe(image, "venice")
        val inpaintResult = ModelPricing.describe(inpaint, "venice")
        assertTrue(imageResult.lines.any { it.contains("质量 2K 低") && it.contains("0.1 USD") })
        assertFalse(imageResult.lines.any { it.contains("分辨率 1K") })
        assertTrue(imageResult.lines.any { it.contains("放大 2x") && it.contains("0.02 USD") })
        assertTrue(imageResult.lines.any { it.contains("放大 4x") && it.contains("0.08 USD") })
        assertTrue(inpaintResult.lines.any { it.contains("局部重绘") && it.contains("0.12 USD") })
        assertTrue(inpaintResult.lines.any { it.contains("额外输入图片") && it.contains("0.0345 USD") })
        assertTrue(inpaintResult.lines.any { it.contains("包含输入图片") && it.contains("1") })
    }

    @Test fun malformedQualityFallsBackToValidResolutions() {
        val model = model("image", """{"pricing":{"quality":{},"resolutions":{"1K":{"usd":0.07}}}}""")
        val result = ModelPricing.describe(model, "venice")

        assertTrue(result.lines.any { it.contains("分辨率 1K") && it.contains("0.07 USD") })
        assertFalse(result.lines.any { it.contains("质量") })
    }

    @Test fun musicDurationsKeepBothBoundsAndOtherBillingShapes() {
        val model = model("music", """
            {"model_spec":{"pricing":{"durations":{"60":{"usd":0.03,"min_seconds":60,"max_seconds":60},"120":{"usd":0.05,"min_seconds":91,"max_seconds":120}},"generation":{"usd":0.1},"per_second":{"usd":0.0028750000000000004},"per_thousand_characters":{"usd":0.115}}}}
        """)
        val result = ModelPricing.describe(model, "venice")

        assertTrue(result.lines.any { it.contains("时长档 60") && it.contains("60–60 秒") })
        assertTrue(result.lines.any { it.contains("时长档 120") && it.contains("91–120 秒") })
        assertTrue(result.lines.any { it.contains("音频时长") && it.contains("0.002875 USD") })
        assertTrue(result.lines.any { it.contains("文本输入") && it.contains("0.115 USD") })
        assertTrue(result.lines.any { it.contains("生成") && it.contains("0.1 USD") })
        assertTrue(result.lines.indexOfFirst { it.contains("时长档 60") } < result.lines.indexOfFirst { it.contains("时长档 120") })
    }

    @Test fun speechAndTranscriptionUseTheirExplicitUnits() {
        val tts = model("tts", """{"model_spec":{"pricing":{"input":{"usd":3.5}}}}""")
        val asr = model("asr", """{"pricing":{"per_audio_second":{"usd":0.0001,"diem":0.0001}}}""")

        val ttsResult = ModelPricing.describe(tts, "venice")
        val asrResult = ModelPricing.describe(asr, "venice")
        assertTrue(ttsResult.lines.single().contains("百万字符"))
        assertTrue(asrResult.lines.single().contains("音频秒"))
        assertTrue(asrResult.lines.single().contains("0.0001 USD"))
        assertTrue(asrResult.lines.single().contains("0.0001 Diem"))
    }

    @Test fun decisionPricingKeepsAmountButDoesNotInventTokenUnit() {
        val result = ModelPricing.describe(model("decision", """{"pricing":{"input":{"usd":0.042},"output":{"usd":0}}}"""), "venice")

        assertTrue(result.lines.any { it.contains("输入") && it.contains("0.042 USD") && it.contains("计费单位未提供") })
        assertTrue(result.lines.any { it.contains("输出") && it.contains("0 USD") })
        assertFalse(result.lines.any { it.contains("百万 tokens") })
    }

    @Test fun videoMissingPricingIsExplicitlyUnknownAndNotFree() {
        val result = ModelPricing.describe(ModelInfo(id = "video", type = "video", rawJson = "{}"), "venice")

        assertTrue(result.lines.single().contains("未提供固定价格"))
        assertTrue(result.lines.single().contains("报价"))
        assertTrue(result.note.contains("视频"))
        assertFalse(result.lines.any { it.contains("免费") })
    }

    @Test fun unknownProviderAndMalformedPricingAreNotInterpretedAsVenice() {
        val raw = """{"pricing":{"input":{"usd":"not-a-number"},"mystery":{"eur":0.25}}}"""
        val unknownProvider = ModelPricing.describe(model("text", raw), "openai")
        val malformed = ModelPricing.describe(model("text", raw), "venice")

        assertTrue(unknownProvider.lines.single().contains("没有提供 App 能识别的价格"))
        assertFalse(unknownProvider.lines.any { it.contains("USD") })
        assertTrue(malformed.lines.any { it.contains("不能视为免费") })
        assertFalse(malformed.lines.any { it.contains("0 USD") })
    }

    @Test fun topLevelAndSpecPricingRemainCompatibleWithOldCaches() {
        val topLevel = model("text", """{"pricing":{"input":{"usd":1.25}}}""")
        val spec = model("text", """{"spec":{"pricing":{"output":{"usd":2.5}}}}""")
        val topResult = ModelPricing.describe(topLevel, "venice")
        val specResult = ModelPricing.describe(spec, "venice")

        assertTrue(topResult.lines.single().contains("输入"))
        assertTrue(topResult.lines.single().contains("1.25 USD"))
        assertTrue(specResult.lines.single().contains("输出"))
        assertTrue(specResult.lines.single().contains("2.5 USD"))
    }

    @Test fun unknownPricingLeavesAmountAndUnitUnspecifiedWithoutSummingCurrencies() {
        val model = model("video", """{"model_spec":{"pricing":{"custom_charge":{"usd":0.0001,"eur":0.2}}}}""")
        val result = ModelPricing.describe(model, "venice")
        val line = result.lines.single { it.contains("custom_charge") }

        assertTrue(line.contains("0.0001 USD"))
        assertTrue(line.contains("0.2 EUR"))
        assertTrue(line.contains("计费单位未提供"))
        assertFalse(line.contains("0.2001"))
    }

    @Test fun unknownDirectCurrencyPricingIsDisplayedWithoutInventingUnit() {
        val model = model("video", """{"pricing":{"usd":0.2,"diem":0.2}}""")
        val result = ModelPricing.describe(model, "venice")

        assertTrue(result.lines.single().contains("0.2 USD"))
        assertTrue(result.lines.single().contains("0.2 Diem"))
        assertTrue(result.lines.single().contains("计费单位未提供"))
    }

    @Test fun pcWritingServerSaysWhoseAccountPaysInPlainWords() {
        val upstream = """{"id":"pocket-direct","upstream":{"host":"api.venice.ai","model":"deepseek-v4-1-flash-1017"}}"""
        val direct = ModelPricing.describe(ModelInfo(id = "pocket-direct", type = "text", rawJson = upstream), "writing-server")
        assertTrue(direct.lines.single().contains("按Venice 上 deepseek-v4-1-flash-1017 的价格计费，从电脑上配置的 Venice 账户扣费"))
        assertTrue(direct.note.isBlank())
        val writer = ModelPricing.describe(ModelInfo(id = "pocket-writer", type = "text", rawJson = upstream), "writing-server")
        assertTrue(writer.lines.last().contains("多发一次审校请求"))
        // A catalog cached before the server reported its upstream still reads sensibly.
        val cached = ModelPricing.describe(ModelInfo(id = "pocket-direct", type = "text", rawJson = """{"id":"pocket-direct"}"""), "writing-server")
        assertTrue(cached.lines.single().contains("电脑写作服务配置的上游模型"))
        assertTrue(cached.note.contains("刷新模型目录"))
        val other = ModelPricing.describe(model("text", """{"pricing":{"input":1}}"""), "openai")
        assertFalse(other.lines.single().contains("定价格式尚未适配"))
        assertTrue(other.lines.single().contains("以平台账单为准"))
    }

    @Test fun veniceModelsPassedThroughThePcShowTheirRealPrices() {
        val upstream = """{"id":"llama-3.3-70b","model_spec":{"pricing":{"input":{"usd":0.7},"output":{"usd":2.8}}},"upstream":{"host":"api.venice.ai","model":"llama-3.3-70b"}}"""
        val result = ModelPricing.describe(ModelInfo(id = "llama-3.3-70b", type = "text", rawJson = upstream), "writing-server")
        assertTrue(result.lines.any { it.contains("0.7") && it.contains("USD") })
        assertTrue(result.note.contains("经电脑转发"))
        // The default-model alias carries the default model's prices at the top level.
        val alias = """{"id":"pocket-writer","pricing":{"input":{"usd":0.3},"output":{"usd":1.2}},"upstream":{"host":"api.venice.ai","model":"deepseek-v4-1-flash-1017"}}"""
        val writer = ModelPricing.describe(ModelInfo(id = "pocket-writer", type = "text", rawJson = alias), "writing-server")
        assertTrue(writer.lines.first().contains("多发一次审校请求"))
        assertTrue(writer.lines.any { it.contains("1.2") })
    }

    private fun model(type: String, rawJson: String): ModelInfo = ModelInfo(id = "test", type = type, rawJson = rawJson)
}
