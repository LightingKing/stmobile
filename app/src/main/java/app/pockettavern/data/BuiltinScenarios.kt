package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Character
import app.pockettavern.domain.Preset
import app.pockettavern.domain.WorldEntry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Loads and reconciles immutable scenario bundles shipped with the app, and the built-in
 * characters (characters/catalog.json) that ship the same way but list under 角色卡.
 */
object BuiltinScenarios {
    private const val CATALOG_PATH = "scenarios/catalog.json"
    private const val CHARACTER_CATALOG_PATH = "characters/catalog.json"
    private const val SCENARIO_PREFIX = "builtin-scenario-"
    private const val CHARACTER_PREFIX = "builtin-character-"
    private val idPattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    private val hashPattern = Regex("[0-9a-fA-F]{64}")

    data class Entry(
        val id: String,
        val name: String,
        val description: String,
        val character: Character,
        val preset: Preset,
        val worldEntries: List<WorldEntry>,
        /** Fingerprints ([contentHash]) of earlier shipped versions that an untouched install may swap for this one. */
        val replaces: Set<String> = emptySet()
    )

    /** Reads catalog metadata and the referenced standard chara_card_v2 JSON files. */
    fun load(readText: (String) -> String): List<Entry> = loadCatalog(readText, CATALOG_PATH, "scenarios", SCENARIO_PREFIX)

    /**
     * Built-in characters, such as 大肥鱼; their ids start with builtin-character-. With [readBytes]
     * each avatar picture is checked against its pinned hash.
     */
    fun loadCharacters(readText: (String) -> String, readBytes: ((String) -> ByteArray)? = null): List<Entry> =
        loadCatalog(readText, CHARACTER_CATALOG_PATH, "characters", CHARACTER_PREFIX, readBytes)

    private fun loadCatalog(readText: (String) -> String, catalogPath: String, listKey: String, prefix: String, readBytes: ((String) -> ByteArray)? = null): List<Entry> {
        val root = JSONObject(readText(catalogPath))
        require(root.optInt("version", -1) == 1) { "内置剧本 catalog 版本不受支持" }
        val items = root.optJSONArray(listKey) ?: throw IllegalArgumentException("内置剧本 catalog 缺少 $listKey")
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
            val characterId = "$prefix$id"
            val avatarPath = item.optString("avatar").trim()
            if (avatarPath.isNotEmpty()) {
                require(avatarPath.startsWith("characters/") && avatarPath.endsWith(".png") && isSafeRelativePath(avatarPath)) { "内置角色头像路径无效：$id" }
                val expected = item.optString("avatar_sha256").trim().lowercase()
                require(hashPattern.matches(expected)) { "内置角色头像 sha256 无效：$id" }
                if (readBytes != null) require(sha256(readBytes(avatarPath)) == expected) { "内置角色头像 SHA-256 不匹配：$id" }
            }
            val stickerPacks = item.optJSONArray("sticker_packs")?.let { list -> (0 until list.length()).map { list.optString(it).trim() } }.orEmpty().filter { it.isNotBlank() }
            val character = parsed.copy(
                id = characterId,
                name = name,
                description = description.ifBlank { parsed.description },
                avatar = if (avatarPath.isEmpty()) "" else "asset:$avatarPath",
                stickerPacks = stickerPacks
            )

            val presetJson = item.optJSONObject("preset") ?: throw IllegalArgumentException("内置剧本缺少 preset：$id")
            val preset = Exchange.preset(presetJson.toString(), "$name 预设").let {
                require(it.maxOutput in 1..131072 && it.temperature in 0.0..2.0) { "内置剧本预设参数无效：$id" }
                it.copy(id = "$prefix$id-preset")
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
                        id = "$prefix$id-world-$entryIndex",
                        characterId = characterId
                    )
                }
            }
            val replaces = item.optJSONArray("replaces")?.let { list -> (0 until list.length()).map { list.optString(it).lowercase() } }.orEmpty()
            require(replaces.all { hashPattern.matches(it) }) { "内置剧本 replaces 无效：$id" }
            loaded += Entry(id, name, description, character, preset, worldEntries, replaces.toSet())
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
            if (!seenSet.add(entry.id)) {
                val index = characters.indexOfFirst { it.id == entry.character.id }
                if (index >= 0) {
                    val installed = characters[index]
                    // An earlier shipped version the user never edited is swapped for the new card.
                    var updated = if (contentHash(installed) in entry.replaces) entry.character.copy(defaultPresetId = installed.defaultPresetId) else installed
                    // Avatars and own sticker packs came later: each is filled in once, never forced back.
                    if (entry.character.avatar.isNotBlank() && seenSet.add("${entry.id}#avatar")) { seen += "${entry.id}#avatar"; if (updated.avatar.isBlank()) updated = updated.copy(avatar = entry.character.avatar) }
                    if (entry.character.stickerPacks.isNotEmpty() && seenSet.add("${entry.id}#stickers")) { seen += "${entry.id}#stickers"; if (updated.stickerPacks.isEmpty()) updated = updated.copy(stickerPacks = entry.character.stickerPacks) }
                    characters[index] = updated
                }
                return@forEach
            }
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
            if (entry.character.avatar.isNotBlank()) { seen += "${entry.id}#avatar"; seenSet += "${entry.id}#avatar" }
            if (entry.character.stickerPacks.isNotEmpty()) { seen += "${entry.id}#stickers"; seenSet += "${entry.id}#stickers" }
        }
        return data.copy(
            characters = characters,
            presets = presets,
            worldEntries = worldEntries,
            settings = data.settings.copy(builtinScenariosSeen = seen.distinct())
        )
    }

    /** The character's visible content, so an untouched shipped card can be recognised later. */
    fun contentHash(character: Character): String = sha256(listOf(character.name, character.description, character.personality,
        character.scenario, character.firstMessage, character.examples, character.systemPrompt).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8))

    fun characterId(id: String): String = "$SCENARIO_PREFIX$id"
    fun presetId(id: String): String = "$SCENARIO_PREFIX$id-preset"
    fun worldEntryId(id: String, index: Int): String = "$SCENARIO_PREFIX$id-world-$index"

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
