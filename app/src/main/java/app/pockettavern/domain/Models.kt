package app.pockettavern.domain

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
data class ProviderConfig(val id: String = newId(), val name: String = "Venice", val kind: String = "venice", val baseUrl: String = "https://api.venice.ai/api/v1", val models: List<ModelInfo> = emptyList(), val refreshedAt: Long = 0)
data class ModelInfo(val id: String = "", val name: String = "", val type: String = "text", val contextTokens: Int = 32768, val supportsTools: Boolean = false, val supportsVision: Boolean = false, val supportsReasoning: Boolean = false, val offline: Boolean = false, val rawJson: String = "{}")
data class Attachment(val id: String = newId(), val name: String = "", val mime: String = "", val path: String = "", val dataUrl: String = "")
data class Message(val id: String = newId(), val role: String = "user", val content: String = "", val reasoning: String = "", val toolCallsJson: String = "[]", val toolCallId: String = "", val status: String = "complete", val createdAt: Long = System.currentTimeMillis(), val attachments: List<Attachment> = emptyList(), val errorMessage: String = "", val finishReason: String = "", val requestId: String = "", val contextExcluded: Boolean = false, val intent: String = "dialogue", val generationId: String = "", val revisionOf: String = "", val qualityNotice: String = "", val skillSnapshotJson: String = "")
/** Metadata for one incremental operation, separate from cumulative summary coverage. */
data class CompressionRecord(
    val baseSummaryId: String = "", val reusedThroughMessageId: String = "",
    val firstMessageId: String = "", val lastMessageId: String = "",
    val messageCount: Int = 0, val estimatedSourceTokens: Int = 0,
    val segmentCount: Int = 0, val reason: String = "", val requestCount: Int? = null,
    val budgetBreakdown: ContextBudgetBreakdown? = null
)
data class Summary(val id: String = newId(), val text: String = "", val throughMessageId: String = "", val sourceHash: String = "", val createdAt: Long = System.currentTimeMillis(), val modelId: String = "", val compression: CompressionRecord? = null, val format: String = "")
data class KnowledgeNote(val id: String = newId(), val text: String = "", val sourceMessageIds: List<String> = emptyList(), val sourceHash: String = "", val createdAt: Long = System.currentTimeMillis(), val modelId: String = "", val sourceKind: String = "conversation", val title: String = "", val kind: String = "legacy", val entity: String = "", val aliases: List<String> = emptyList()) {
    val isResearch: Boolean get() = sourceKind == "research"
}
/**
 * User-visible, per-chat scenario data.  It is deliberately generic: a card or
 * Skill can populate it for a particular story, while an empty value remains a
 * valid state for ordinary chats.  Values are facts recorded for this chat;
 * the UI does not invent missing balances or quest progress.
 */
data class ScenarioResource(val id: String = newId(), val name: String = "", val value: String = "", val unit: String = "", val note: String = "")
data class ScenarioOption(val id: String = newId(), val name: String = "", val cost: String = "", val requirement: String = "", val result: String = "", val available: Boolean = true)
data class ScenarioUpgrade(val id: String = newId(), val name: String = "", val level: String = "", val cost: String = "", val requirement: String = "", val benefit: String = "", val status: String = "未解锁")
data class ScenarioQuest(val id: String = newId(), val title: String = "", val status: String = "未开始", val progress: String = "", val description: String = "", val reward: String = "")
data class ScenarioState(
    val location: String = "",
    val time: String = "",
    val systemLevel: String = "",
    val resources: List<ScenarioResource> = emptyList(),
    val options: List<ScenarioOption> = emptyList(),
    val upgrades: List<ScenarioUpgrade> = emptyList(),
    val quests: List<ScenarioQuest> = emptyList(),
    val note: String = "",
    val updatedAt: Long = 0L,
    val source: String = ""
)
/** A person or group member shown in the scenario relationship view. */
data class ScenarioPerson(
    val id: String = newId(), val name: String = "", val role: String = "", val relationship: String = "",
    val ability: String = "", val activity: String = "", val clothing: String = "", val personality: String = "",
    val description: String = "", val status: String = "未知", val source: String = ""
)
data class Chat(val id: String = newId(), val title: String = "新会话", val providerId: String = "venice", val modelId: String = "", val presetId: String = "default", val characterId: String = "", val messages: List<Message> = emptyList(), val summaries: List<Summary> = emptyList(), val activeSummaryId: String = "", val skillIds: List<String> = emptyList(), val updatedAt: Long = System.currentTimeMillis(), val knowledgeNotes: List<KnowledgeNote> = emptyList(), val continuityNotes: String = "", val expressionMode: String = "request", val scenarioState: ScenarioState = ScenarioState(), val scenarioRoster: List<ScenarioPerson> = emptyList(), val stickerFrequency: String = "off")
/**
 * Generation budgets are opt-in through [generationBudgetEnabled]. Stored
 * amounts stay editable while disabled and do not impose a request limit.
 * [maxOutput] is the visible正文 target; Venice reasoning
 * models receive [reasoningBudget] as an additional server-side reserve because
 * Venice's max_completion_tokens counts thinking and正文 together.
 */
data class Preset(val id: String = newId(), val name: String = "新预设", val systemPrompt: String = "", val temperature: Double = 0.8, val maxOutput: Int = 2048, val summaryPrompt: String = "用中文准确总结已有对话中的人物、事实、约定、未解决事项和当前进度。保留关键细节，不添加推测或新事实。", val summaryProviderId: String = "", val summaryModelId: String = "", val reasoningEffort: String = "default", val includeVeniceSystemPrompt: Boolean = true, val reasoningBudget: Int = 0, val generationBudgetEnabled: Boolean = false)
data class Character(val id: String = newId(), val name: String = "", val description: String = "", val personality: String = "", val scenario: String = "", val firstMessage: String = "", val examples: String = "", val systemPrompt: String = "", val userPersona: String = "", val originalJson: String = "", val defaultPresetId: String = "")
data class Memory(val id: String = newId(), val text: String = "", val scope: String = "chat", val targetId: String = "", val enabled: Boolean = true, val locked: Boolean = false, val sourceMessageId: String = "", val updatedAt: Long = System.currentTimeMillis())
data class WorldEntry(val id: String = newId(), val name: String = "", val content: String = "", val keywords: List<String> = emptyList(), val always: Boolean = false, val enabled: Boolean = true, val characterId: String = "", val priority: Int = 0, val searchRange: Int = 0)
data class SkillRecord(val id: String = newId(), val name: String = "", val description: String = "", val content: String = "", val files: Map<String,String> = emptyMap(), val enabled: Boolean = true, val permissions: List<String> = emptyList(), val source: String = "本地导入", val warnings: List<String> = emptyList(), val sourceUrl: String = "", val sourceCommit: String = "", val sourceLicense: String = "", val adult: Boolean = false)
data class MediaJob(val id: String = newId(), val providerId: String = "", val modelId: String = "", val type: String = "", val requestJson: String = "{}", val queueId: String = "", val status: String = "", val resultJson: String = "", val filePath: String = "", val createdAt: Long = System.currentTimeMillis())
data class AppSettings(val autoCompress: Boolean = true, val compressionThreshold: Double = 0.8, val keepRecentTurns: Int = 8, val contextOverride: Int = 0, val darkMode: String = "system", val userPersona: String = "", val maxToolSteps: Int = 8, val builtinSkillsSeen: List<String> = emptyList(), val builtinScenariosSeen: List<String> = emptyList(), val adultSkillsConfirmed: Boolean = false, val builtinSkillsRevision: Int = 0, val researchProviderId: String = "", val researchModelId: String = "", val researchSearchMode: String = "direct", val maxContextInputTokens: Int = 65_536)
data class AppData(val schemaVersion: Int = 1, val providers: List<ProviderConfig> = listOf(ProviderConfig(id="venice")), val chats: List<Chat> = emptyList(), val presets: List<Preset> = listOf(Preset(id="default",name="通用助手",systemPrompt="请用中文回答，遵循用户要求。准确区分事实与推测。"), Preset(id="roleplay",name="角色扮演",systemPrompt="遵循角色卡、世界设定和已发生的事实。用户控制自己的角色，不代替用户决定动作、台词、心理或选择。")), val characters: List<Character> = emptyList(), val memories: List<Memory> = emptyList(), val worldEntries: List<WorldEntry> = emptyList(), val skills: List<SkillRecord> = emptyList(), val mediaJobs: List<MediaJob> = emptyList(), val settings: AppSettings = AppSettings(), val stickerPacks: List<StickerPack> = emptyList())
data class MemoryRebuildState(
    val chatId: String,
    val phase: String = "running",
    val completed: Int = 0,
    val total: Int = 0,
    val message: String = "正在准备重建记忆…",
    val noteCount: Int = 0,
    val summaryId: String = "",
    val sourceMessageCount: Int = 0,
    val finishedAt: Long = 0
)
data class CompressionProgress(val chatId: String, val scope: String, val completed: Int = 0, val total: Int = 0, val message: String = "正在准备新增记忆…")
data class UiSession(val data: AppData = AppData(), val selectedChatId: String = "", val busy: Boolean = false, val loading: Boolean = true, val status: String = "", val contextPreview: String = "", val hasKeys: Set<String> = emptySet(), val pendingAttachments: List<Attachment> = emptyList(), val appVersion: String = "", val diagnostics: String = "", val skillStars: Map<String, GitHubStars> = emptyMap(), val missingBuiltinSkills: List<SkillRecord> = emptyList(), val repairBackupChatIds: Set<String> = emptySet(), val memoryRebuild: MemoryRebuildState? = null, val hasResearchSearchKey: Boolean = false, val veniceBalances: Map<String, VeniceBalanceState> = emptyMap(), val compressionProgress: CompressionProgress? = null, val pcLink: PcLinkState = PcLinkState())
/** GitHub star count recorded in the bundled catalog on [checkedAt]; null stars means it could not be read. */
data class GitHubStars(val repo: String, val stars: Int?, val checkedAt: String)
data class CompletionResult(val content: String = "", val reasoning: String = "", val toolCallsJson: String = "[]", val rawJson: String = "{}", val finishReason: String = "", val requestId: String = "")
data class StreamDelta(val content: String = "", val reasoning: String = "", val replaceContent: Boolean = false) {
    fun appendTo(text: StreamDelta): StreamDelta = StreamDelta(if (replaceContent) content else text.content + content, text.reasoning + reasoning)
}
data class ContextBuild(val messagesJson: String, val estimatedTokens: Int, val budget: Int, val needsCompression: Boolean, val preview: String, val toolDefinitions: String = "[]", val skillTokens: List<Pair<String, Int>> = emptyList(), val compression: CompressionSlice? = null, val budgetBreakdown: ContextBudgetBreakdown? = null)
/** [messages] are only the turns not yet covered by [previousSummary]; [sourceHash] covers the whole prefix. */
data class CompressionSlice(val messages: List<Message>, val throughMessageId: String, val sourceHash: String, val previousSummary: String = "", val projectedTokens: Int = 0)

/**
 * One image of an imported sticker pack. [key] is what the model writes in
 * "[表情:key]" and is unique across all packs; [file] is relative to
 * filesDir/stickers.
 */
data class Sticker(val key: String = "", val name: String = "", val description: String = "", val tags: List<String> = emptyList(), val file: String = "")

/** Metadata only; the images live on disk so state saves stay small. */
data class StickerPack(val id: String = newId(), val name: String = "", val license: String = "", val source: String = "", val enabled: Boolean = true, val stickers: List<Sticker> = emptyList(), val importedAt: Long = System.currentTimeMillis())
