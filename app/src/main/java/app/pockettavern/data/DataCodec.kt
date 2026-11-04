package app.pockettavern.data

import app.pockettavern.domain.*
import com.google.gson.*
import java.security.MessageDigest

object DataCodec {
    const val MAX_JSON_UTF8_BYTES = 64L * 1024L * 1024L
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun encode(data: AppData): String {
        // expressionMode was a temporary conversation UI switch. Keep the
        // nullable model field so older backups can still be decoded, but do
        // not persist it in new exports or let it affect fingerprints.
        val root = JsonParser.parseString(gson.toJson(data)).asJsonObject
        root.getAsJsonArray("chats")?.forEach { chat -> chat.asJsonObject.remove("expressionMode") }
        val json = gson.toJson(root)
        requireJsonSize(json)
        return json
    }

    fun fingerprint(data: AppData): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(data).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun decode(json: String): AppData {
        requireJsonSize(json)
        val root = JsonParser.parseString(json)
        require(root.isJsonObject && root.asJsonObject.has("schemaVersion")) { "不是口袋酒馆备份" }
        prepareBudgetMetadata(root.asJsonObject)
        validateTree(root, 0)
        require(root.asJsonObject.get("schemaVersion").asInt == 1) { "不支持该备份版本，请升级应用" }
        root.asJsonObject.getAsJsonArray("presets")?.forEach { entry ->
            val preset = entry.asJsonObject
            preset.get("reasoningEffort")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "预设思考强度必须为字符串" } }
            preset.get("includeVeniceSystemPrompt")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "Venice 平台提示开关必须为布尔值" } }
            preset.get("reasoningBudget")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "预设思考预留必须为数字" } }
            preset.get("generationBudgetEnabled")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "生成预算开关必须为布尔值" } }
            // Old saved amounts remain available, but imposing a generation cap now requires opt-in.
            if (!preset.has("generationBudgetEnabled")) preset.addProperty("generationBudgetEnabled", false)
        }
        root.asJsonObject.getAsJsonObject("settings")?.let { settings ->
            // Legacy saves must get the new spending budget; explicit 0 still means model capacity.
            if (!settings.has("maxContextInputTokens")) settings.addProperty("maxContextInputTokens", AppSettings().maxContextInputTokens)
            settings.get("maxContextInputTokens")?.let { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.matches(Regex("[0-9]+")) &&
                    value.asLong in 0L..2_000_000L) { "日常输入预算无效" }
            }
            settings.get("researchSearchMode")?.let { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString in setOf("direct", "native")) { "研究搜索模式无效" }
            }
            listOf("researchProviderId", "researchModelId").forEach { field ->
                settings.get(field)?.let { value ->
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.length <= 512) { "研究平台或模型 ID 格式无效" }
                }
            }
        }
        // Gson does not invoke Kotlin constructor defaults when a legacy or
        // hand-written backup omits a field.  The resulting null in a
        // non-null Kotlin property can crash Compose on the first redraw after
        // sending (for example, `message.content.isNotBlank()`).  Normalize
        // the decoded graph before any code reads it.  Explicit JSON nulls are
        // still rejected above; this only fills fields that older backups did
        // not contain at all.
        val data = normalize(gson.fromJson(root, AppData::class.java))
        require(data.providers.size <= 100 && data.chats.size <= 10000 && data.skills.size <= 500) { "备份条目过多" }
        require(data.presets.isNotEmpty()) { "备份必须至少包含一个预设" }
        require(data.providers.map { it.id }.distinct().size == data.providers.size) { "平台 ID 重复" }
        require(data.chats.map { it.id }.distinct().size == data.chats.size) { "会话 ID 重复" }
        data.providers.forEach {
            require(it.kind in listOf("venice", "openai", "anthropic", "writing-server")) { "未知平台协议" }
            // Same policy the provider editor enforces on save; a stricter check
            // here would make a saved LAN writing-server block the next load.
            require(app.pockettavern.provider.ProviderUrlPolicy.violation(it) == null) { "备份平台地址无效" }
        }
        data.chats.forEach { chat -> chat.messages.flatMap { it.attachments }.forEach { require(it.path.isBlank()) { "备份附件必须嵌入数据，不能引用本地路径" } } }
        require(data.stickerPacks.size <= 20 && data.stickerPacks.all { it.stickers.size <= StickerImporter.MAX_STICKERS }) { "表情包数量过多" }
        data.stickerPacks.flatMap { it.stickers }.forEach { sticker ->
            require(StickerStore.isSafe(sticker.file) && sticker.key.isNotBlank() && sticker.key.length <= 24 && sticker.key.none { it in "[]【】:：\n" }) { "表情包条目无效" }
        }
        data.chats.forEach { chat ->
            chat.knowledgeNotes.forEach { note ->
                require(note.sourceKind in setOf("conversation", "research")) { "知识笔记来源类型无效" }
                if (note.isResearch) require(app.pockettavern.engine.KnowledgeVault.validResearchNote(note)) { "研究笔记内容或来源记录无效" }
            }
            val research = chat.knowledgeNotes.filter { it.isResearch }
            require(research.size <= app.pockettavern.engine.KnowledgeVault.MAX_RESEARCH_NOTES &&
                research.sumOf { it.text.length } <= app.pockettavern.engine.KnowledgeVault.MAX_RESEARCH_TOTAL_CHARS) { "研究资料容量过大" }
        }
        data.presets.forEach { require(it.maxOutput in 1..131072 && it.reasoningBudget in 0..131072 && it.temperature in 0.0..2.0 && it.reasoningEffort in PresetOptions.reasoningEfforts) { "预设参数无效" } }
        require(data.settings.keepRecentTurns in 1..100 && data.settings.compressionThreshold in 0.2..0.95 && data.settings.maxToolSteps in 1..16 && data.settings.maxContextInputTokens in 0..2_000_000) { "压缩或工具设置无效" }
        val normalized = normalizeDuplicateSkillIds(data)
        return normalized.copy(providers = normalized.providers.map { provider ->
            provider.copy(models = provider.models.map { ModelTypes.normalize(it, provider.kind) })
        })
    }

    private fun normalize(data: AppData): AppData = data.copy(
        providers = data.providers.orEmpty().map { provider ->
            provider.copy(
                id = provider.id.orEmpty().ifBlank { "provider-${newId()}" },
                name = provider.name.orEmpty(),
                kind = provider.kind.orEmpty().ifBlank { "openai" },
                baseUrl = provider.baseUrl.orEmpty().ifBlank { "https://api.venice.ai/api/v1" },
                models = provider.models.orEmpty().map { model ->
                    model.copy(
                        id = model.id.orEmpty(), name = model.name.orEmpty(), type = model.type.orEmpty().ifBlank { "text" },
                        rawJson = model.rawJson.orEmpty().ifBlank { "{}" }
                    )
                }
            )
        },
        chats = data.chats.orEmpty().map { chat ->
            chat.copy(
                id = chat.id.orEmpty().ifBlank { "chat-${newId()}" },
                title = chat.title.orEmpty().ifBlank { "新会话" },
                providerId = chat.providerId.orEmpty(), modelId = chat.modelId.orEmpty(), presetId = chat.presetId.orEmpty(),
                characterId = chat.characterId.orEmpty(),
                messages = chat.messages.orEmpty().map(::normalizeMessage),
                summaries = chat.summaries.orEmpty().map { summary ->
                    summary.copy(id = summary.id.orEmpty().ifBlank { newId() }, text = summary.text.orEmpty(), throughMessageId = summary.throughMessageId.orEmpty(), sourceHash = summary.sourceHash.orEmpty(), format = summary.format.orEmpty(),
                        compression = summary.compression?.let { record -> record.copy(
                            baseSummaryId = record.baseSummaryId.orEmpty(), reusedThroughMessageId = record.reusedThroughMessageId.orEmpty(),
                            firstMessageId = record.firstMessageId.orEmpty(), lastMessageId = record.lastMessageId.orEmpty(), reason = record.reason.orEmpty(),
                            messageCount = record.messageCount.coerceAtLeast(0), estimatedSourceTokens = record.estimatedSourceTokens.coerceAtLeast(0),
                            segmentCount = record.segmentCount.coerceAtLeast(0), requestCount = record.requestCount?.coerceAtLeast(0),
                            budgetBreakdown = record.budgetBreakdown?.let(::normalizeBudgetBreakdown)
                        ) })
                },
                activeSummaryId = chat.activeSummaryId.orEmpty(), skillIds = chat.skillIds.orEmpty(),
                knowledgeNotes = chat.knowledgeNotes.orEmpty().map { note ->
                    note.copy(id = note.id.orEmpty().ifBlank { newId() }, text = note.text.orEmpty(), sourceMessageIds = note.sourceMessageIds.orEmpty(), sourceHash = note.sourceHash.orEmpty(), modelId = note.modelId.orEmpty(), sourceKind = note.sourceKind.orEmpty().ifBlank { "conversation" }, title = note.title.orEmpty(), kind = note.kind.orEmpty().ifBlank { "legacy" }, entity = note.entity.orEmpty(), aliases = note.aliases.orEmpty())
                },
                continuityNotes = chat.continuityNotes.orEmpty(),
                scenarioState = normalizeScenarioState(runCatching { chat.scenarioState }.getOrNull()),
                scenarioRoster = chat.scenarioRoster.orEmpty().map(::normalizeScenarioPerson),
                // Legacy value is intentionally ignored; writing guidance is
                // now always supplied by NarrativePolicy.
                expressionMode = "request",
                stickerFrequency = app.pockettavern.engine.StickerFrequency.of(chat.stickerFrequency).id
            )
        },
        presets = data.presets.orEmpty().map { preset ->
            val reasoningBudget = runCatching { preset.reasoningBudget }.getOrNull() ?: 0
            preset.copy(id = preset.id.orEmpty().ifBlank { newId() }, name = preset.name.orEmpty(), systemPrompt = preset.systemPrompt.orEmpty(), summaryPrompt = preset.summaryPrompt.orEmpty(), summaryProviderId = preset.summaryProviderId.orEmpty(), summaryModelId = preset.summaryModelId.orEmpty(), reasoningEffort = preset.reasoningEffort.orEmpty().ifBlank { "default" }, reasoningBudget = reasoningBudget)
        }.ifEmpty { listOf(Preset(id = "default", name = "通用助手")) },
        characters = data.characters.orEmpty().map { character -> character.copy(id = character.id.orEmpty().ifBlank { newId() }, name = character.name.orEmpty(), description = character.description.orEmpty(), personality = character.personality.orEmpty(), scenario = character.scenario.orEmpty(), firstMessage = character.firstMessage.orEmpty(), examples = character.examples.orEmpty(), systemPrompt = character.systemPrompt.orEmpty(), userPersona = character.userPersona.orEmpty(), originalJson = character.originalJson.orEmpty(), defaultPresetId = character.defaultPresetId.orEmpty()) },
        memories = data.memories.orEmpty().map { memory -> memory.copy(id = memory.id.orEmpty().ifBlank { newId() }, text = memory.text.orEmpty(), scope = memory.scope.orEmpty().ifBlank { "chat" }, targetId = memory.targetId.orEmpty(), sourceMessageId = memory.sourceMessageId.orEmpty()) },
        worldEntries = data.worldEntries.orEmpty().map { entry -> entry.copy(id = entry.id.orEmpty().ifBlank { newId() }, name = entry.name.orEmpty(), content = entry.content.orEmpty(), keywords = entry.keywords.orEmpty(), characterId = entry.characterId.orEmpty()) },
        skills = data.skills.orEmpty().map { skill -> skill.copy(id = skill.id.orEmpty().ifBlank { newId() }, name = skill.name.orEmpty(), description = skill.description.orEmpty(), content = skill.content.orEmpty(), files = skill.files.orEmpty(), permissions = skill.permissions.orEmpty(), source = skill.source.orEmpty(), warnings = skill.warnings.orEmpty(), sourceUrl = skill.sourceUrl.orEmpty(), sourceCommit = skill.sourceCommit.orEmpty(), sourceLicense = skill.sourceLicense.orEmpty()) },
        // Sticker metadata only; the images are separate files under filesDir/stickers.
        stickerPacks = data.stickerPacks.orEmpty().map { pack ->
            pack.copy(id = pack.id.orEmpty().ifBlank { newId() }, name = pack.name.orEmpty(), license = pack.license.orEmpty(), source = pack.source.orEmpty(),
                stickers = pack.stickers.orEmpty().map { sticker ->
                    sticker.copy(key = sticker.key.orEmpty(), name = sticker.name.orEmpty(), description = sticker.description.orEmpty(), tags = sticker.tags.orEmpty(), file = sticker.file.orEmpty())
                })
        },
        mediaJobs = data.mediaJobs.orEmpty().map { job -> job.copy(id = job.id.orEmpty().ifBlank { newId() }, providerId = job.providerId.orEmpty(), modelId = job.modelId.orEmpty(), type = job.type.orEmpty(), requestJson = job.requestJson.orEmpty().ifBlank { "{}" }, queueId = job.queueId.orEmpty(), status = job.status.orEmpty(), resultJson = job.resultJson.orEmpty(), filePath = job.filePath.orEmpty()) },
        // Keep the nullable bridge explicit: Gson may leave an omitted object
        // null even though the Kotlin model declares it non-null.
        settings = normalizeSettings(runCatching { data.settings }.getOrNull())
    )

    /** Optional numeric diagnostics can be absent/null in older exports; other null fields stay invalid. */
    private fun prepareBudgetMetadata(root: JsonObject) {
        root.getAsJsonArray("chats")?.forEach { chat ->
            chat.asJsonObject.getAsJsonArray("summaries")?.forEach summaryLoop@ { summary ->
                val compression = summary.asJsonObject.get("compression")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@summaryLoop
                val budget = compression.get("budgetBreakdown") ?: return@summaryLoop
                if (budget.isJsonNull) compression.remove("budgetBreakdown")
                else if (budget.isJsonObject) {
                    budget.asJsonObject.entrySet().filter { it.value.isJsonNull }.map { it.key }.forEach(budget.asJsonObject::remove)
                }
            }
        }
    }

    internal fun normalizeBudgetBreakdown(value: ContextBudgetBreakdown): ContextBudgetBreakdown = value.copy(
        modelWindow = value.modelWindow.coerceAtLeast(0), effectiveWindow = value.effectiveWindow.coerceAtLeast(0),
        contextOverride = value.contextOverride.coerceAtLeast(0), configuredInputLimit = value.configuredInputLimit.coerceAtLeast(0),
        modelInputBudget = value.modelInputBudget.coerceAtLeast(0), inputBudget = value.inputBudget.coerceAtLeast(0),
        triggerTokens = value.triggerTokens.coerceAtLeast(0), threshold = value.threshold.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
        safetyReserve = value.safetyReserve.coerceAtLeast(0), outputReserve = value.outputReserve.coerceAtLeast(0),
        reasoningReserve = value.reasoningReserve.coerceAtLeast(0), estimatedInputTokens = value.estimatedInputTokens.coerceAtLeast(0),
        presetTokens = value.presetTokens.coerceAtLeast(0), characterTokens = value.characterTokens.coerceAtLeast(0),
        memoryTokens = value.memoryTokens.coerceAtLeast(0), worldTokens = value.worldTokens.coerceAtLeast(0),
        skillTokens = value.skillTokens.coerceAtLeast(0), policyTokens = value.policyTokens.coerceAtLeast(0),
        toolTokens = value.toolTokens.coerceAtLeast(0), summaryTokens = value.summaryTokens.coerceAtLeast(0),
        recentTokens = value.recentTokens.coerceAtLeast(0), projectedTokens = value.projectedTokens?.coerceAtLeast(0),
        triggerReason = value.triggerReason.orEmpty().takeIf { it in ContextBudgetBreakdown.TRIGGER_REASONS } ?: "UNKNOWN",
        blockedReason = value.blockedReason.orEmpty().takeIf { it in ContextBudgetBreakdown.BLOCKED_REASONS } ?: "UNKNOWN"
    )

    private fun normalizeSettings(value: AppSettings?): AppSettings {
        val settings = value ?: AppSettings()
        return settings.copy(
            darkMode = settings.darkMode.orEmpty().ifBlank { "system" },
            userPersona = settings.userPersona.orEmpty(),
            builtinSkillsSeen = settings.builtinSkillsSeen.orEmpty(),
            builtinScenariosSeen = settings.builtinScenariosSeen.orEmpty(),
            researchProviderId = settings.researchProviderId.orEmpty(),
            researchModelId = settings.researchModelId.orEmpty(),
            researchSearchMode = settings.researchSearchMode.orEmpty().ifBlank { "direct" }
        )
    }

    private fun normalizeMessage(message: Message): Message = message.copy(
        id = message.id.orEmpty().ifBlank { newId() }, role = message.role.orEmpty().ifBlank { "user" }, content = message.content.orEmpty(),
        reasoning = message.reasoning.orEmpty(), toolCallsJson = message.toolCallsJson.orEmpty().ifBlank { "[]" }, toolCallId = message.toolCallId.orEmpty(),
        status = message.status.orEmpty().ifBlank { "complete" }, attachments = message.attachments.orEmpty().map { attachment ->
            attachment.copy(id = attachment.id.orEmpty().ifBlank { newId() }, name = attachment.name.orEmpty(), mime = attachment.mime.orEmpty(), path = attachment.path.orEmpty(), dataUrl = attachment.dataUrl.orEmpty())
        }, errorMessage = message.errorMessage.orEmpty(), finishReason = message.finishReason.orEmpty(), requestId = message.requestId.orEmpty(),
        intent = message.intent.orEmpty().ifBlank { "dialogue" }, generationId = message.generationId.orEmpty(), revisionOf = message.revisionOf.orEmpty(), qualityNotice = message.qualityNotice.orEmpty(), skillSnapshotJson = message.skillSnapshotJson.orEmpty()
    )

    private fun cleanScenarioText(value: String?, limit: Int = 8000): String = value.orEmpty().take(limit)

    private fun normalizeScenarioState(value: ScenarioState?): ScenarioState {
        val state = value ?: ScenarioState()
        return state.copy(
            location = cleanScenarioText(state.location, 512), time = cleanScenarioText(state.time, 256),
            systemLevel = cleanScenarioText(state.systemLevel, 256), note = cleanScenarioText(state.note, 8000),
            source = cleanScenarioText(state.source, 256), updatedAt = state.updatedAt.coerceAtLeast(0L),
            resources = state.resources.orEmpty().take(200).map { item ->
                item.copy(id = item.id.orEmpty().ifBlank { newId() }, name = cleanScenarioText(item.name, 256), value = cleanScenarioText(item.value, 256), unit = cleanScenarioText(item.unit, 64), note = cleanScenarioText(item.note, 1000))
            },
            options = state.options.orEmpty().take(200).map { item ->
                item.copy(id = item.id.orEmpty().ifBlank { newId() }, name = cleanScenarioText(item.name, 256), cost = cleanScenarioText(item.cost, 256), requirement = cleanScenarioText(item.requirement, 1000), result = cleanScenarioText(item.result, 2000))
            },
            upgrades = state.upgrades.orEmpty().take(200).map { item ->
                item.copy(id = item.id.orEmpty().ifBlank { newId() }, name = cleanScenarioText(item.name, 256), level = cleanScenarioText(item.level, 128), cost = cleanScenarioText(item.cost, 256), requirement = cleanScenarioText(item.requirement, 1000), benefit = cleanScenarioText(item.benefit, 2000), status = cleanScenarioText(item.status, 128))
            },
            quests = state.quests.orEmpty().take(200).map { item ->
                item.copy(id = item.id.orEmpty().ifBlank { newId() }, title = cleanScenarioText(item.title, 256), status = cleanScenarioText(item.status, 128), progress = cleanScenarioText(item.progress, 256), description = cleanScenarioText(item.description, 2000), reward = cleanScenarioText(item.reward, 1000))
            }
        )
    }

    private fun normalizeScenarioPerson(value: ScenarioPerson): ScenarioPerson = value.copy(
        id = value.id.orEmpty().ifBlank { newId() }, name = cleanScenarioText(value.name, 256), role = cleanScenarioText(value.role, 256),
        relationship = cleanScenarioText(value.relationship, 512), ability = cleanScenarioText(value.ability, 1000),
        activity = cleanScenarioText(value.activity, 1000), clothing = cleanScenarioText(value.clothing, 1000),
        personality = cleanScenarioText(value.personality, 1000), description = cleanScenarioText(value.description, 2000),
        status = cleanScenarioText(value.status, 128), source = cleanScenarioText(value.source, 256)
    )

    /**
     * Older or external backups may contain duplicate Skill IDs. Keep every record
     * and expand chat selections so the migration does not silently discard prompts.
     */
    private fun normalizeDuplicateSkillIds(data: AppData): AppData {
        if (data.skills.map { it.id }.distinct().size == data.skills.size) return data
        val reserved = data.skills.mapTo(mutableSetOf()) { it.id }.apply {
            data.chats.forEach { addAll(it.skillIds) }
        }
        val assigned = mutableSetOf<String>()
        val replacements = linkedMapOf<String, MutableList<String>>()
        val skills = data.skills.map { skill ->
            val replacementIds = replacements.getOrPut(skill.id) { mutableListOf() }
            val replacementId = if (replacementIds.isEmpty()) {
                skill.id.also(assigned::add)
            } else {
                val stem = skill.id.ifBlank { "skill" }
                var suffix = replacementIds.size + 1
                var candidate: String
                do {
                    candidate = "$stem-duplicate-$suffix"
                    suffix++
                } while (candidate in reserved || candidate in assigned)
                assigned += candidate
                candidate
            }
            replacementIds += replacementId
            if (replacementId == skill.id) skill else skill.copy(id = replacementId)
        }
        val chats = data.chats.map { chat ->
            val expanded = chat.skillIds.flatMap { id -> replacements[id].orEmpty().ifEmpty { listOf(id) } }.distinct()
            if (expanded == chat.skillIds) chat else chat.copy(skillIds = expanded)
        }
        return data.copy(skills = skills, chats = chats)
    }

    private fun requireJsonSize(json: String) {
        require(json.toByteArray(Charsets.UTF_8).size.toLong() <= MAX_JSON_UTF8_BYTES) {
            "状态 JSON 超过 64 MiB"
        }
    }

    private fun validateTree(element: JsonElement, depth: Int) {
        require(depth <= 40) { "JSON 嵌套过深" }
        require(!element.isJsonNull) { "备份包含空字段" }
        if (element.isJsonObject) element.asJsonObject.entrySet().forEach { validateTree(it.value, depth + 1) }
        if (element.isJsonArray) element.asJsonArray.forEach { validateTree(it, depth + 1) }
    }
}
