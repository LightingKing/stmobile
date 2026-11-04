package app.pockettavern.data

import android.content.SharedPreferences
import androidx.core.content.edit
import app.pockettavern.domain.AppData
import app.pockettavern.domain.PcBackup
import app.pockettavern.domain.PcBackupPolicy
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.newId
import app.pockettavern.provider.PcLinkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Device-local record of PC backups. Kept outside [AppData] so a restore never rewrites it. */
interface PcLinkStore {
    var providerId: String
    var autoBackup: Boolean
    var lastBackupAt: Long
    var lastAttemptAt: Long
    var lastBackupSha: String
    var lastBackupSize: Long
    var lastError: String
}

class SharedPreferencesPcLinkStore(private val prefs: SharedPreferences) : PcLinkStore {
    override var providerId: String
        get() = prefs.getString("provider_id", "").orEmpty()
        set(value) { prefs.edit { putString("provider_id", value) } }
    override var autoBackup: Boolean
        get() = prefs.getBoolean("auto_backup", false)
        set(value) { prefs.edit { putBoolean("auto_backup", value) } }
    override var lastBackupAt: Long
        get() = prefs.getLong("last_backup_at", 0)
        set(value) { prefs.edit { putLong("last_backup_at", value) } }
    override var lastAttemptAt: Long
        get() = prefs.getLong("last_attempt_at", 0)
        set(value) { prefs.edit { putLong("last_attempt_at", value) } }
    override var lastBackupSha: String
        get() = prefs.getString("last_backup_sha", "").orEmpty()
        set(value) { prefs.edit { putString("last_backup_sha", value) } }
    override var lastBackupSize: Long
        get() = prefs.getLong("last_backup_size", 0)
        set(value) { prefs.edit { putLong("last_backup_size", value) } }
    override var lastError: String
        get() = prefs.getString("last_error", "").orEmpty()
        set(value) { prefs.edit { putString("last_error", value) } }
}

/**
 * Builds the same ZIP as “导出备份”, sends it to the PC and fetches it back.
 * An automatic backup whose bytes match the last one sent is skipped.
 */
class PcBackups(
    private val client: PcLinkClient,
    private val store: PcLinkStore,
    private val filesDir: File,
    private val cacheDir: File,
    private val device: String,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis
) {
    sealed interface Upload {
        data class Stored(val backup: PcBackup, val duplicate: Boolean, val size: Long) : Upload
        data object Unchanged : Upload
    }

    private val mutex = Mutex()
    private val workDir get() = File(cacheDir, "pc-backup")

    fun autoBackupDue(): Boolean = PcBackupPolicy.due(store.autoBackup, now(), store.lastBackupAt, store.lastAttemptAt)

    suspend fun upload(provider: ProviderConfig, key: String, data: AppData, reason: String): Upload = mutex.withLock {
        store.lastAttemptAt = now()
        val file = File(workDir, "backup-${newId()}.zip")
        try {
            val sha = withContext(Dispatchers.IO) {
                BackupArchive.write(data, filesDir, file)
                sha256(file)
            }
            if (reason == "auto" && sha == store.lastBackupSha) {
                store.lastBackupAt = now()
                store.lastError = ""
                return@withLock Upload.Unchanged
            }
            val size = file.length()
            val (backup, duplicate) = client.upload(provider, key, file, reason, device, appVersion)
            store.lastBackupAt = now()
            store.lastBackupSha = sha
            store.lastBackupSize = size
            store.lastError = ""
            Upload.Stored(backup, duplicate, size)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            store.lastError = error.message ?: "备份到电脑失败"
            throw error
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    suspend fun fetch(provider: ProviderConfig, key: String, backup: PcBackup): ByteArray {
        val file = File(workDir, "restore-${newId()}.zip")
        return try {
            client.download(provider, key, backup, file)
            withContext(Dispatchers.IO) { file.readBytes() }
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
