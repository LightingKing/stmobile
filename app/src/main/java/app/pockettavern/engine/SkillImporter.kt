package app.pockettavern.engine

import app.pockettavern.domain.SkillRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Imports the portable SKILL.md format without ever executing package contents. */
object SkillImporter {
    private const val MAX_INPUT = 25L * 1024 * 1024
    private const val MAX_UNPACKED = 50L * 1024 * 1024
    private const val MAX_FILE = 10L * 1024 * 1024
    private const val MAX_ENTRIES = 512
    private val scriptExtensions = setOf("py", "sh", "bash", "zsh", "js", "ts", "mjs", "ps1", "bat", "cmd")
    private val supportedPermissions = setOf("files.read", "files.write", "network", "memory")

    fun importFile(fileName: String, bytes: ByteArray): List<SkillRecord> {
        require(bytes.size.toLong() <= MAX_INPUT) { "技能包超过大小限制" }
        return if (fileName.substringAfterLast('.', "").equals("zip", ignoreCase = true) || isZip(bytes)) {
            importZip(fileName, bytes)
        } else {
            listOf(parseSkill(fileName, bytes.toString(StandardCharsets.UTF_8), mapOf("SKILL.md" to bytes.toString(StandardCharsets.UTF_8))))
        }
    }

    private fun importZip(fileName: String, bytes: ByteArray): List<SkillRecord> {
        val files = linkedMapOf<String, String>()
        var unpacked = 0L
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw IllegalArgumentException("技能包文件数量超过限制")
                if (entry.isDirectory) {
                    if (!isSafeZipPath(entry.name, true)) throw IllegalArgumentException("技能包包含不安全路径")
                    continue
                }
                val name = normalizeEntry(entry)
                if (name == null) {
                    throw IllegalArgumentException("技能包包含不安全路径")
                }
                val declared = entry.size
                if (declared > MAX_FILE || (declared >= 0 && unpacked + declared > MAX_UNPACKED)) throw IllegalArgumentException("技能包解压体积超过限制")
                val bytesOut = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    unpacked += read
                    if (read > MAX_FILE || unpacked > MAX_UNPACKED || bytesOut.size() > MAX_FILE - read) throw IllegalArgumentException("技能包解压体积超过限制")
                    bytesOut.write(buffer, 0, read)
                }
                files[name] = storageString(name, bytesOut.toByteArray())
                zip.closeEntry()
            }
        }
        val skillFiles = files.keys.filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }
        if (skillFiles.isEmpty()) throw IllegalArgumentException("ZIP 中缺少 SKILL.md")
        return skillFiles.map { skillPath ->
            val prefix = skillPath.removeSuffix("SKILL.md")
            val selected = files.filterKeys { prefix.isEmpty() || it == skillPath || it.startsWith(prefix) }
                .mapKeys { (key, _) -> key.removePrefix(prefix).ifBlank { "SKILL.md" } }
            parseSkill("$fileName!/$skillPath", selected.getValue("SKILL.md"), selected)
        }
    }

    private fun parseSkill(source: String, raw: String, files: Map<String, String>): SkillRecord {
        val front = parseFrontMatter(raw)
        val name = front.values["name"].orEmpty().trim()
        val description = front.values["description"].orEmpty().trim()
        require(name.isNotBlank() && name.length <= 64 && !name.contains('\n')) { "SKILL.md 的 name 无效" }
        require(description.isNotBlank() && description.length <= 1024) { "SKILL.md 的 description 无效" }
        val warnings = front.warnings.toMutableList()
        files.keys.filter { it.substringAfterLast('.', "").lowercase() in scriptExtensions }.forEach {
            warnings += "检测到脚本文件 $it；Android 版不会执行 Python/Bash/Node 等桌面脚本"
        }
        val requestedPermissions = front.listValues("permissions")
        requestedPermissions.forEach { permission ->
            if (permission !in supportedPermissions) {
                warnings += "忽略未知权限 $permission"
            } else {
                warnings += "Skill 请求权限 $permission；需用户在应用中明确授予"
            }
        }
        return SkillRecord(
            name = name,
            description = description,
            content = front.body,
            files = files,
            permissions = emptyList(),
            enabled = false,
            source = source,
            warnings = warnings.distinct()
        )
    }

    private data class FrontMatter(val values: Map<String, String>, val lists: Map<String, List<String>>, val body: String, val warnings: List<String>) {
        fun listValues(key: String): List<String> = lists[key].orEmpty()
    }

    private fun parseFrontMatter(raw: String): FrontMatter {
        val normalized = raw.removePrefix("\uFEFF").replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) throw IllegalArgumentException("SKILL.md 必须以 YAML frontmatter 开始")
        val end = normalized.indexOf("\n---", startIndex = 4)
        if (end < 0) throw IllegalArgumentException("SKILL.md 缺少 YAML frontmatter 结束标记")
        val header = normalized.substring(4, end).lines()
        val values = linkedMapOf<String, String>()
        val lists = linkedMapOf<String, MutableList<String>>()
        val warnings = mutableListOf<String>()
        var index = 0
        while (index < header.size) {
            val line = header[index]
            if (line.isBlank() || line.trimStart().startsWith("#")) { index++; continue }
            val colon = line.indexOf(':')
            if (colon <= 0) { warnings += "无法解析 frontmatter 行：${line.take(80)}"; index++; continue }
            val key = line.substring(0, colon).trim()
            val rawValue = line.substring(colon + 1).trim()
            if (rawValue.startsWith("|") || rawValue.startsWith(">")) {
                val folded = rawValue.startsWith(">")
                val lines = mutableListOf<String>()
                index++
                while (index < header.size && (header[index].startsWith(" ") || header[index].startsWith("\t") || header[index].isBlank())) {
                    lines += header[index].trimStart(); index++
                }
                values[key] = if (folded) lines.joinToString(" ").trim() else lines.joinToString("\n").trim()
                continue
            }
            if (rawValue.startsWith("[") && rawValue.endsWith("]")) {
                lists[key] = rawValue.removePrefix("[").removeSuffix("]").split(',').mapNotNull { it.trim().trim('"', '\'').takeIf(String::isNotBlank) }.toMutableList()
            } else if (rawValue.isNotBlank()) {
                values[key] = rawValue.trim('"', '\'')
            } else {
                val items = mutableListOf<String>(); index++
                while (index < header.size && header[index].trimStart().startsWith("-")) {
                    items += header[index].trim().removePrefix("-").trim().trim('"', '\''); index++
                }
                if (items.isNotEmpty()) lists[key] = items
                continue
            }
            index++
        }
        val bodyStart = end + "\n---".length
        val body = normalized.substring(bodyStart).removePrefix("\n")
        return FrontMatter(values, lists, body, warnings)
    }

    private fun normalizeEntry(entry: ZipEntry): String? {
        val name = entry.name.replace('\\', '/')
        return name.takeIf { isSafeZipPath(it, false) }
    }

    private fun isSafeZipPath(raw: String, directory: Boolean): Boolean {
        val name = raw.replace('\\', '/').let { if (directory) it.trimEnd('/') else it }
        if (name.isBlank() || name.startsWith('/') || name.contains(":")) return false
        return name.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    private fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()

    private fun storageString(name: String, bytes: ByteArray): String {
        val textLike = name.equals("SKILL.md", true) || name.substringAfterLast('.', "").lowercase() in
            setOf("md", "txt", "json", "yaml", "yml", "xml", "csv", "html", "css", "js", "ts", "py", "sh")
        val decoded = runCatching {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        return if (decoded != null && textLike) decoded
        else "base64:${Base64.getEncoder().encodeToString(bytes)}"
    }
}
