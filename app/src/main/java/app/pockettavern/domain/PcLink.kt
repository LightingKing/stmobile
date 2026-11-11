package app.pockettavern.domain

/** What the PC writing server reports about itself (GET /v1/pocket/status). */
data class PcStatus(
    val version: String = "",
    val ready: Boolean = false,
    val upstreamHost: String = "",
    val upstreamModel: String = "",
    val reviewSkill: String = "",
    val activeRequests: Int = 0,
    val clientAddress: String = "",
    val listenHosts: List<String> = emptyList(),
    val uptimeSeconds: Long = 0,
    val backupCount: Int = 0,
    val backupBytes: Long = 0,
    val backupKeep: Int = 0,
    val features: List<String> = emptyList(),
    val defaultModelId: String = "",
    val defaultModelName: String = "",
    /** null until the PC has read its upstream's model list once. */
    val modelsAvailable: Int? = null,
    val autoRevise: Boolean = false,
    /** Whether Bilibili is logged in on the PC (subtitles for web search); null on older servers. */
    val bilibiliLogin: Boolean? = null
)

/**
 * The PC settings the phone may change (GET/PATCH /v1/pocket/config). The upstream address,
 * keys and listening address stay PC-only; [upstreamHost] is shown, never edited.
 */
data class PcSettings(
    val upstreamModel: String = "",
    val reasoningEffort: String = "default",
    val reviewReasoningEffort: String = "none",
    val reviewSkill: String = "ss-stop-slop",
    val autoRevise: Boolean = false,
    val includeVeniceSystemPrompt: Boolean = false,
    val upstreamIdleTimeoutSec: Int = 900,
    val backupKeep: Int = 20,
    val upstreamHost: String = ""
) {
    /** Only what changed, in the server's field names, so a save never rewrites settings left alone. */
    fun changesFrom(original: PcSettings): Map<String, Any> = buildMap {
        if (upstreamModel != original.upstreamModel) put("upstreamModel", upstreamModel)
        if (reasoningEffort != original.reasoningEffort) put("reasoningEffort", reasoningEffort)
        if (reviewReasoningEffort != original.reviewReasoningEffort) put("reviewReasoningEffort", reviewReasoningEffort)
        if (reviewSkill != original.reviewSkill) put("reviewSkill", reviewSkill)
        if (autoRevise != original.autoRevise) put("autoRevise", autoRevise)
        if (includeVeniceSystemPrompt != original.includeVeniceSystemPrompt) put("includeVeniceSystemPrompt", includeVeniceSystemPrompt)
        if (upstreamIdleTimeoutSec != original.upstreamIdleTimeoutSec) put("upstreamIdleTimeoutSec", upstreamIdleTimeoutSec)
        if (backupKeep != original.backupKeep) put("backupKeep", backupKeep)
    }
}

/** One phone backup kept on the PC. [reason] is "manual", "auto" or "pre-restore". */
data class PcBackup(
    val id: String = "",
    val createdAt: String = "",
    val size: Long = 0,
    val sha256: String = "",
    val device: String = "",
    val appVersion: String = "",
    val reason: String = "manual"
)

/**
 * Settings → 电脑连接. [busy] names the job in progress ("check", "backup",
 * "list", "restore", "settings") or is blank. Backup bookkeeping is device-local and
 * never part of [AppData], so restoring a backup cannot rewrite it.
 */
data class PcLinkState(
    val providerId: String = "",
    val status: PcStatus? = null,
    val error: String = "",
    val busy: String = "",
    val backups: List<PcBackup>? = null,
    val autoBackup: Boolean = false,
    val lastBackupAt: Long = 0,
    val lastBackupSize: Long = 0,
    val lastBackupError: String = "",
    /** Loaded when the PC settings page opens; null until then. */
    val settings: PcSettings? = null,
    /** Sticker packs kept on the PC; null until read. Only chats on the PC writing server use them. */
    val stickerPacks: List<StickerPack>? = null,
    val stickersError: String = "",
    /** "lan" or "remote" after the last request to the chosen PC; blank before any. */
    val route: String = ""
)

object PcBackupPolicy {
    const val INTERVAL_MILLIS = 6L * 60 * 60 * 1000
    const val RETRY_MILLIS = 30L * 60 * 1000

    /** Auto backup runs when the app leaves the screen, at most every 6 h; a failure is retried after 30 min. */
    fun due(enabled: Boolean, now: Long, lastSuccessAt: Long, lastAttemptAt: Long): Boolean =
        enabled && now - lastSuccessAt >= INTERVAL_MILLIS && now - lastAttemptAt >= RETRY_MILLIS
}

/** The PC platform the phone talks to: the one chosen in 电脑连接, otherwise the first PC platform. */
fun chosenPcProvider(providers: List<ProviderConfig>, chosenId: String): ProviderConfig? {
    val servers = providers.filter { it.kind.equals("writing-server", ignoreCase = true) }
    return servers.firstOrNull { it.id == chosenId } ?: servers.firstOrNull()
}

/** "3.2 MB" / "640 KB" for backup sizes. */
fun readableSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
