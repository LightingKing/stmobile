package app.pockettavern.data

import app.pockettavern.engine.CompactCheckpoint
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Private, resumable work. A checkpoint never replaces the conversation's committed memory. */
class CompactCheckpointStore(private val directory: File) {
    fun load(chatId: String, operation: String, identity: String): CompactCheckpoint? = runCatching {
        val file = location(chatId, operation)
        if (!file.isFile || file.length() > DataCodec.MAX_JSON_UTF8_BYTES) return null
        val root = JSONObject(file.readText(Charsets.UTF_8))
        if (root.getInt("version") != 1 || root.getString("identity") != digest(identity)) return null
        val body = root.getString("checkpoint")
        if (root.getString("checksum") != digest(body)) return null
        DataCodec.gson.fromJson(body, CompactCheckpoint::class.java)
    }.getOrNull()

    fun save(chatId: String, operation: String, identity: String, checkpoint: CompactCheckpoint) {
        val body = DataCodec.gson.toJson(checkpoint)
        val bytes = JSONObject().put("version", 1).put("identity", digest(identity))
            .put("checksum", digest(body)).put("checkpoint", body).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= DataCodec.MAX_JSON_UTF8_BYTES) { "压缩暂存超过本地存储上限；原记忆未替换" }
        require(directory.isDirectory || directory.mkdirs()) { "无法创建压缩暂存目录" }
        val destination = location(chatId, operation)
        val temporary = File.createTempFile("compact-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    fun clear(chatId: String, operation: String) { location(chatId, operation).delete() }

    private fun location(chatId: String, operation: String): File = File(directory, "${digest(chatId + "\u0000" + operation)}.json")
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
