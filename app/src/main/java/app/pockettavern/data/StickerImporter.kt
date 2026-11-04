package app.pockettavern.data

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a sticker pack ZIP without touching disk. Names come from, in order:
 * our own stickers.json, a Markdown table index (dafeifish), a manifest.json
 * file list, a manifest.json folder (dsh-meme), then file or folder names.
 */
object StickerImporter {
    const val MAX_STICKERS = 300
    const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
    private const val MAX_ENTRIES = 3000
    private const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
    private const val MAX_TEXT_BYTES = 512L * 1024

    data class Item(val key: String, val name: String, val description: String, val tags: List<String>, val extension: String, val bytes: ByteArray)
    data class Pack(val name: String, val license: String, val items: List<Item>)

    private data class Meta(val name: String, val description: String = "", val tags: List<String> = emptyList())

    fun read(fileName: String, bytes: ByteArray, takenKeys: Set<String> = emptySet()): Pack {
        val images = LinkedHashMap<String, Pair<String, ByteArray>>()
        val texts = HashMap<String, String>()
        var total = 0L
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= MAX_ENTRIES) { "表情包条目过多" }
                val path = entry.name.replace('\\', '/')
                if (entry.isDirectory) continue
                require(isSafe(path)) { "表情包包含不安全路径：$path" }
                if (path.split('/').any { it.startsWith('.') || it == "__MACOSX" }) continue
                val lower = path.lowercase()
                val isText = lower.endsWith(".json") || lower.endsWith(".md")
                val limit = if (isText) MAX_TEXT_BYTES else MAX_IMAGE_BYTES
                val data = readLimited(zip, limit) ?: continue // oversized: skip, do not fail the pack
                total += data.size
                require(total <= MAX_TOTAL_BYTES) { "表情包解压后超过 ${MAX_TOTAL_BYTES / 1024 / 1024}MB" }
                if (isText) texts[path] = data.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                else imageExtension(data)?.let { images[path] = it to data }
            }
        }
        require(images.isNotEmpty()) { "表情包里没有可识别的图片（支持 PNG、JPEG、GIF、WebP）" }

        val ownIndex = texts.entries.firstOrNull { it.key.substringAfterLast('/') == "stickers.json" }
        val manifests = texts.filterKeys { it.substringAfterLast('/') == "manifest.json" }
            .mapNotNull { (path, text) -> runCatching { path to JSONObject(text) }.getOrNull() }
        val tableIndex = texts.filterKeys { it.endsWith(".md") }.mapValues { (path, text) -> markdownIndex(path, text) }
            .values.maxByOrNull { it.size }.orEmpty()

        // Which images belong to the pack, with what metadata; packJson names the pack.
        var packJson: JSONObject? = ownIndex?.let { runCatching { JSONObject(it.value) }.getOrNull() } ?: manifests.firstOrNull()?.second
        val chosen: List<Pair<String, Meta>> = when {
            ownIndex != null -> ownEntries(ownIndex.key, JSONObject(ownIndex.value), images.keys)
            tableIndex.isNotEmpty() -> tableIndex.mapNotNull { (file, meta) -> matchImage(file, images.keys)?.let { it to meta } }
            manifests.any { it.second.optJSONArray("files") != null } -> {
                val (path, manifest) = manifests.filter { it.second.optJSONArray("files") != null }.maxBy { it.second.getJSONArray("files").length() }
                packJson = manifest
                manifestFiles(path, manifest, images.keys)
            }
            manifests.isNotEmpty() -> {
                val (path, manifest) = manifests.maxBy { (path, _) -> images.keys.count { it.startsWith(path.substringBeforeLast('/', "")) } }
                packJson = manifest
                val folder = path.substringBeforeLast('/', "")
                images.keys.filter { folder.isEmpty() || it.startsWith("$folder/") }.map { it to fallbackMeta(it, folder) }
            }
            else -> images.keys.map { it to fallbackMeta(it, "") }
        }.distinctBy { it.first }
        require(chosen.isNotEmpty()) { "表情包索引没有对应到任何图片" }

        val packMeta = packJson
        val packName = packMeta?.optString("name").orEmpty().trim().ifBlank { fileName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "表情包" } }.take(40)
        val license = packMeta?.optString("license").orEmpty().trim().ifBlank { "未声明" }.take(60)

        val used = takenKeys.toMutableSet()
        val items = chosen.take(MAX_STICKERS).map { (path, meta) ->
            val (extension, data) = images.getValue(path)
            val key = uniqueKey(cleanKey(meta.name).ifBlank { "表情" }, used)
            used += key
            Item(key, meta.name.take(40), meta.description.take(80), meta.tags.map { it.take(16) }.filter { it.isNotBlank() }.distinct().take(6), extension, data)
        }
        return Pack(packName, license, items)
    }

    // --- index formats -------------------------------------------------------

    private fun ownEntries(indexPath: String, json: JSONObject, images: Set<String>): List<Pair<String, Meta>> {
        val base = indexPath.substringBeforeLast('/', "")
        val list = json.optJSONArray("stickers") ?: JSONArray()
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val file = item.optString("file")
            val image = matchImage(if (base.isEmpty()) file else "$base/$file", images) ?: matchImage(file, images) ?: return@mapNotNull null
            val tags = item.optJSONArray("tags")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
            image to Meta(item.optString("name").ifBlank { stem(image) }, item.optString("description"), tags)
        }
    }

    private fun manifestFiles(manifestPath: String, json: JSONObject, images: Set<String>): List<Pair<String, Meta>> {
        val base = manifestPath.substringBeforeLast('/', "")
        val files = json.getJSONArray("files")
        return (0 until files.length()).mapNotNull { index ->
            val item = files.optJSONObject(index) ?: return@mapNotNull null
            val file = item.optString("path").ifBlank { item.optString("file") }
            val image = matchImage(if (base.isEmpty()) file else "$base/$file", images) ?: matchImage(file, images) ?: return@mapNotNull null
            image to Meta(item.optString("id").ifBlank { item.optString("name") }.ifBlank { stem(image) }, item.optString("description"))
        }
    }

    /**
     * A Markdown table whose header has an ID column and an image path column,
     * e.g. dafeifish-codex references/memes.md. Returns path → metadata.
     */
    private fun markdownIndex(mdPath: String, text: String): List<Pair<String, Meta>> {
        val rows = text.lines().map { it.trim() }.filter { it.startsWith("|") && it.endsWith("|") }
            .map { line -> line.trim('|').split('|').map { it.trim() } }
        if (rows.size < 3) return emptyList()
        val header = rows.first()
        val idColumn = header.indexOfFirst { it.equals("ID", true) || it == "名称" || it.equals("name", true) }
        val pathColumn = header.indexOfFirst { it.contains("路径") || it.equals("path", true) || it.equals("file", true) || it.contains("图片") }
        if (idColumn < 0 || pathColumn < 0) return emptyList()
        val descriptionColumn = header.indexOfFirst { it.contains("描述") || it.equals("description", true) }
        val tagColumn = header.indexOfFirst { it.contains("关键词") || it.equals("tags", true) || it.equals("keywords", true) }
        val useColumn = header.indexOfFirst { it.contains("语境") || it.contains("用途") || it.contains("使用") }
        val base = mdPath.substringBeforeLast('/', "").substringBeforeLast("/references", "")
        return rows.drop(2).mapNotNull { cells ->
            val id = cells.getOrNull(idColumn).orEmpty().trim('`')
            val path = cells.getOrNull(pathColumn).orEmpty().trim('`')
            if (id.isBlank() || path.isBlank()) return@mapNotNull null
            val description = listOfNotNull(cells.getOrNull(descriptionColumn), cells.getOrNull(useColumn)).filter { it.isNotBlank() }.joinToString("；")
            val tags = cells.getOrNull(tagColumn).orEmpty().split(Regex("[\\s、,，/]+")).filter { it.isNotBlank() }
            (if (base.isEmpty()) path else "$base/$path") to Meta(id, description, tags)
        }
    }

    // --- helpers ---------------------------------------------------------------

    private fun matchImage(path: String, images: Set<String>): String? {
        val wanted = path.trim().removePrefix("./")
        if (wanted in images) return wanted
        return images.firstOrNull { it.endsWith("/$wanted") } ?: images.singleOrNull { it.substringAfterLast('/') == wanted.substringAfterLast('/') }
    }

    /** Generic file names ("01.png", hashes) borrow their folder name, e.g. memes/happy/01.png → happy. */
    private fun fallbackMeta(path: String, root: String): Meta {
        val name = stem(path)
        val folder = path.substringBeforeLast('/', "").substringAfterLast('/')
        val folderIsCategory = folder.isNotBlank() && path.substringBeforeLast('/', "") != root && folder !in setOf("memes", "stickers", "images", "img")
        val generic = name.matches(Regex("[0-9_-]+")) || name.matches(Regex("[0-9a-f]{12,}", RegexOption.IGNORE_CASE))
        return Meta(if (generic && folderIsCategory) folder else name, tags = if (folderIsCategory) listOf(folder) else emptyList())
    }

    private fun stem(path: String) = path.substringAfterLast('/').substringBeforeLast('.')

    /** Keys are written by the model inside [表情:…], so brackets, colons and line breaks are not allowed. */
    private fun cleanKey(name: String): String = name.replace(Regex("[\\[\\]【】:：\\r\\n|]"), " ").replace(Regex("\\s+"), " ").trim().take(24)

    private fun uniqueKey(base: String, used: Set<String>): String {
        if (base !in used) return base
        var index = 2
        while ("$base$index" in used) index++
        return "$base$index"
    }

    private fun isSafe(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') && !path.contains(':') && path.split('/').none { it == ".." }

    private fun readLimited(zip: ZipInputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var read = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            read += count
            if (read > limit) { while (zip.read(buffer) >= 0) Unit; return null }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /** Identifies images by their signature, never by the file extension. */
    internal fun imageExtension(data: ByteArray): String? = when {
        data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte() && data[3] == 'G'.code.toByte() -> "png"
        data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> "jpg"
        data.size >= 6 && String(data, 0, 6, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" } -> "gif"
        data.size >= 12 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" && String(data, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> null
    }
}
