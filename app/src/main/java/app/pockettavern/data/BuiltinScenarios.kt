package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Character
import app.pockettavern.domain.Preset
import app.pockettavern.domain.WorldEntry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject

/** Loads and reconciles immutable scenario bundles shipped with the app. */
object BuiltinScenarios {
    private const val CATALOG_PATH = "scenarios/catalog.json"
    private val idPattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    private val hashPattern = Regex("[0-9a-fA-F]{64}")

    data class Entry(
        val id: String,
        val name: String,
        val description: String,
        val character: Character,
        val preset: Preset,
        val worldEntries: List<WorldEntry>
    )

    /** Reads catalog metadata and the referenced standard chara_card_v2 JSON files. */
    fun load(readText: (String) -> String): List<Entry> {
        val root = JSONObject(readText(CATALOG_PATH))
        require(root.optInt("version", -1) == 1) { "内置剧本 catalog 版本不受支持" }
        val items = root.optJSONArray("scenarios") ?: throw IllegalArgumentException("内置剧本 catalog 缺少 scenarios")
        val ids = mutableSetOf<String>()
        val loaded = ArrayList<Entry>(items.length())
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: throw IllegalArgumentException("内置剧本 catalog 条目无效")
            val id = item.optString("id").trim()
            require(idPattern.matches(id)) { "内置剧本 id 无效：$id" }
            require(ids.add(id)) { "内置剧本 id 重复：$id" }
            val name = item.optString("name").trim()
            val description = item.optString("description").trim()
            val path = item.optString("path").trim()
            val sha256 = item.optString("sha256").trim().lowercase()
            require(name.isNotBlank() && name.length <= 64) { "内置剧本名称无效：$id" }
            require(description.length <= 1024) { "内置剧本说明过长：$id" }
            require(isSafeRelativePath(path)) { "内置剧本路径无效：$id" }
            require(hashPattern.matches(sha256)) { "内置剧本 sha256 无效：$id" }

            val source = readText(path)
            require(sha256(source.toByteArray(StandardCharsets.UTF_8)) == sha256) { "内置剧本原文 SHA-256 不匹配：$id" }
            val parsed = Exchange.character(source.toByteArray(StandardCharsets.UTF_8))
            val characterId = characterId(id)
            val character = parsed.copy(
                id = characterId,
                name = name,
                description = description.ifBlank { parsed.description }
            )

            val presetJson = item.optJSONObject("preset") ?: throw IllegalArgumentException("内置剧本缺少 preset：$id")
            val preset = Exchange.preset(presetJson.toString(), "$name 预设").let {
                require(it.maxOutput in 1..131072 && it.temperature in 0.0..2.0) { "内置剧本预设参数无效：$id" }
                it.copy(id = presetId(id))
            }
            val book = runCatching {
                val cardRoot = JSONObject(parsed.originalJson)
                (cardRoot.optJSONObject("data") ?: cardRoot).optJSONObject("character_book")
            }.getOrNull()
            val worldEntries = if (book == null) emptyList() else {
                val imported = Exchange.worldbook(book.toString(), characterId)
                imported.mapIndexed { entryIndex, entry ->
                    require(entry.searchRange in 0..10000) { "内置剧本世界书 searchRange 无效：$id#$entryIndex" }
                    entry.copy(
                        id = worldEntryId(id, entryIndex),
                        characterId = characterId
                    )
                }
            }
            loaded += Entry(id, name, description, character, preset, worldEntries)
        }
        return loaded
    }

    /** Adds only never-seen scenarios; existing records and user edits are preserved. */
    fun merge(data: AppData, catalog: List<Entry>): AppData {
        val seen = data.settings.builtinScenariosSeen.orEmpty().toMutableList()
        val seenSet = seen.toMutableSet()
        val characters = data.characters.toMutableList()
        val presets = data.presets.toMutableList()
        val worldEntries = data.worldEntries.toMutableList()
        catalog.forEach { entry ->
            if (!seenSet.add(entry.id)) return@forEach
            val conflicts = characters.any { it.id == entry.character.id } ||
                presets.any { it.id == entry.preset.id } ||
                worldEntries.any { it.id in entry.worldEntries.map(WorldEntry::id) }
            if (!conflicts) {
                characters += entry.character.copy(defaultPresetId = entry.preset.id)
                presets += entry.preset
                worldEntries += entry.worldEntries
            }
            // A conflicting record is treated as an already installed scenario. This avoids
            // overwriting a user's edits or reintroducing a deliberately deleted component.
            seen += entry.id
        }
        return data.copy(
            characters = characters,
            presets = presets,
            worldEntries = worldEntries,
            settings = data.settings.copy(builtinScenariosSeen = seen.distinct())
        )
    }

    fun characterId(id: String): String = "builtin-scenario-$id"
    fun presetId(id: String): String = "builtin-scenario-$id-preset"
    fun worldEntryId(id: String, index: Int): String = "builtin-scenario-$id-world-$index"

    /** Resolves the preset used when a new chat is opened for a character. */
    fun presetIdForCharacter(character: Character?, presets: List<Preset>): String {
        val specific = character?.defaultPresetId?.takeIf { id -> presets.any { it.id == id } }
        return specific ?: if (character != null && presets.any { it.id == "roleplay" }) "roleplay"
        else presets.firstOrNull()?.id.orEmpty()
    }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
