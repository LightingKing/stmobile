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
    val features: List<String> = emptyList()
)

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
 * "list", "restore") or is blank. Backup bookkeeping is device-local and
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
    val lastBackupError: String = ""
)

object PcBackupPolicy {
    const val INTERVAL_MILLIS = 6L * 60 * 60 * 1000
    const val RETRY_MILLIS = 30L * 60 * 1000

    /** Auto backup runs when the app leaves the screen, at most every 6 h; a failure is retried after 30 min. */
    fun due(enabled: Boolean, now: Long, lastSuccessAt: Long, lastAttemptAt: Long): Boolean =
        enabled && now - lastSuccessAt >= INTERVAL_MILLIS && now - lastAttemptAt >= RETRY_MILLIS
}

/** "3.2 MB" / "640 KB" for backup sizes. */
fun readableSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
