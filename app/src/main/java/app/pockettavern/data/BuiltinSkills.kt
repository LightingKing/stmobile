package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.SkillRecord
import app.pockettavern.engine.SkillImporter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject

/** Loads and reconciles the immutable writing Skill catalog shipped with the app. */
object BuiltinSkills {
    private const val CATALOG_PATH = "writing-skills/catalog.json"
    private const val SKILL_PATH_PREFIX = "writing-skills/"
    private const val SKILL_PATH_SUFFIX = "/SKILL.md"
    private val categories = setOf("小说写作", "言情与关系")
    private val commitPattern = Regex("[0-9a-fA-F]{40}")
    private val hashPattern = Regex("[0-9a-fA-F]{64}")
    private val sourceUrlPattern = Regex("https://github\\.com/[^/\\s]+/[^/\\s]+/blob/([0-9a-fA-F]{40})/[^?#\\s]+/SKILL\\.md")

    /**
     * Reads the catalog and each referenced SKILL.md through the supplied asset reader.
     * Imported content is still passed through the normal SkillImporter validation path.
     */
    fun load(readText: (String) -> String): List<SkillRecord> {
        val root = JSONObject(readText(CATALOG_PATH))
        require(root.optInt("version", -1) == 1) { "内置 Skills catalog 版本不受支持" }
        val entries = root.optJSONArray("skills") ?: throw IllegalArgumentException("内置 Skills catalog 缺少 skills")
        val ids = mutableSetOf<String>()
        val loaded = ArrayList<SkillRecord>(entries.length())
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: throw IllegalArgumentException("内置 Skills catalog 条目无效")
            val id = item.optString("id").trim()
            require(ID_PATTERN.matches(id)) { "内置 Skill id 无效：$id" }
            require(ids.add(id)) { "内置 Skill id 重复：$id" }
            val name = item.optString("name").trim()
            val category = item.optString("category").trim()
            val description = item.optString("description").trim()
            val sourceUrl = item.optString("sourceUrl").trim()
            val commit = item.optString("commit").trim()
            val license = item.optString("license").trim()
            val licensePath = item.optString("licensePath").trim()
            val sha256 = item.optString("sha256").trim().lowercase()
            require(name.isNotBlank() && name.length <= 64) { "内置 Skill 名称无效：$id" }
            require(category in categories) { "内置 Skill 分类无效：$id" }
            require(description.isNotBlank() && description.length <= 1024) { "内置 Skill 说明无效：$id" }
            require(commitPattern.matches(commit)) { "内置 Skill commit 无效：$id" }
            require(hashPattern.matches(sha256)) { "内置 Skill sha256 无效：$id" }
            require(license == "MIT") { "内置 Skill 许可证无效：$id" }
            require(licensePath.startsWith("licenses/") && isSafeRelativePath(licensePath)) {
                "内置 Skill 许可证路径无效：$id"
            }
            val sourceMatch = sourceUrlPattern.matchEntire(sourceUrl)
                ?: throw IllegalArgumentException("内置 Skill sourceUrl 必须是固定 GitHub SKILL.md 地址：$id")
            require(sourceMatch.groupValues[1].equals(commit, ignoreCase = true)) {
                "内置 Skill sourceUrl commit 不匹配：$id"
            }

            val path = "$SKILL_PATH_PREFIX$id$SKILL_PATH_SUFFIX"
            val source = readText(path)
            val actualHash = sha256(source.toByteArray(StandardCharsets.UTF_8))
            require(actualHash == sha256) { "内置 Skill 原文 SHA-256 不匹配：$id" }
            val imported = SkillImporter.importFile("$id/SKILL.md", source.toByteArray(StandardCharsets.UTF_8))
            require(imported.size == 1) { "内置 Skill 必须包含一个 SKILL.md：$id" }
            val licenseText = readText("$SKILL_PATH_PREFIX$licensePath")
            loaded += imported.single().copy(
                id = "builtin-writing-$id",
                name = name,
                description = description,
                enabled = false,
                permissions = emptyList(),
                source = "GitHub精选 · $category",
                files = imported.single().files + ("LICENSE" to licenseText),
                sourceUrl = sourceUrl,
                sourceCommit = commit.lowercase(),
                sourceLicense = license
            )
        }
        return loaded
    }

    /** Adds only never-seen built-ins; existing records and user changes are left untouched. */
    fun merge(data: AppData, catalog: List<SkillRecord>): AppData {
        val seen = data.settings.builtinSkillsSeen.toMutableList()
        val seenSet = seen.toMutableSet()
        val skills = data.skills.toMutableList()
        catalog.forEach { builtin ->
            require(builtin.id.startsWith("builtin-writing-")) { "只能合并内置 writing Skill" }
            if (builtin.id !in seenSet) {
                if (skills.any { it.id == builtin.id }) {
                    // A prior installation may have the record but not the migration marker.
                    // Treat it as seen while preserving every user edit on that record.
                    seen += builtin.id
                    seenSet += builtin.id
                } else if (skills.size < MAX_SKILLS) {
                    skills += builtin.copy(enabled = false, permissions = emptyList())
                    seen += builtin.id
                    seenSet += builtin.id
                }
            }
        }
        return data.copy(skills = skills, settings = data.settings.copy(builtinSkillsSeen = seen.distinct()))
    }

    /** Changes only the requested chat selection; checking a Skill also enables it globally. */
    fun selectForChat(data: AppData, chatId: String, skillId: String, selected: Boolean): AppData {
        require(data.chats.any { it.id == chatId }) { "会话不存在：$chatId" }
        require(data.skills.any { it.id == skillId }) { "Skill 不存在：$skillId" }
        val chats = data.chats.map { chat ->
            if (chat.id != chatId) chat
            else if (selected) chat.copy(skillIds = (chat.skillIds + skillId).distinct())
            else chat.copy(skillIds = chat.skillIds.filterNot { it == skillId })
        }
        val skills = if (selected) data.skills.map { skill ->
            if (skill.id == skillId) skill.copy(enabled = true) else skill
        } else data.skills
        return data.copy(chats = chats, skills = skills)
    }

    private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    private const val MAX_SKILLS = 500

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
