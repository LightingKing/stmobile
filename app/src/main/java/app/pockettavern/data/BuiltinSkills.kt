package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.GitHubStars
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
    const val ADULT_CATEGORY = "成人向"
    private const val FIRST_PARTY_CATALOG = "first-party-skills/catalog.json"
    private const val FIRST_PARTY_PREFIX = "first-party-skills/"
    const val FIRST_PARTY_SOURCE = "本应用编写"
    const val FIRST_PARTY_LICENSE = "AGPL-3.0（随应用源码）"
    /** Upstream has no license; the copy is kept for the owner's private use only. */
    const val PERSONAL_USE_LICENSE = "无许可证 · 仅限个人自用，未获再分发授权"
    private const val UNLICENSED = "UNLICENSED"
    /** Reference files stay in APK assets; state stores only this hash-pinned marker. */
    const val ASSET_PREFIX = "builtin-asset:sha256="
    val CATEGORIES = listOf("小说写作", "言情与关系", "角色扮演", "世界观", "社会常识", ADULT_CATEGORY)
    private val categories = CATEGORIES.toSet()
    private val licenses = setOf("MIT", "GPL-3.0", "AGPL-3.0", "CC-BY-4.0")
    private val commitPattern = Regex("[0-9a-fA-F]{40}")
    private val hashPattern = Regex("[0-9a-fA-F]{64}")
    private val blobPattern = Regex("[0-9a-f]{40}")
    private val sourceUrlPattern = Regex("https://github\\.com/([^/\\s]+/[^/\\s]+)/blob/([0-9a-fA-F]{40})/[^?#\\s]+\\.(?:md|ya?ml|txt)")

    /** One-time refresh requested for the verbatim catalog and its Chinese display metadata. */
    private const val GITHUB_CATALOG_REVISION = 4
    /** 0.1.16 shipped line-trimmed copies of these withdrawn Skills. */
    private val WITHDRAWN_PATCHED = setOf("builtin-writing-dp-chapter-adult", "builtin-writing-at-adult-tension")
    /**
     * Dropped from the catalog in 0.1.65: project workflows, outline and card tools, genre rules,
     * social etiquette and article rewriting that did not improve chat writing. Their installed
     * records point at reference assets that are no longer shipped, so they leave the store and
     * every chat selection together.
     */
    internal val RETIRED = setOf(
        "wf-fantasy", "wf-mystery", "wf-dialogue", "wf-scene", "wf-romance", "sm-story-structure",
        "sm-fiction-scene", "qm-apocalypse", "qm-outline-builder", "qm-world-rules", "qm-supporting-cast",
        "ss-hard-sci-fi", "fv-card-author", "an-awesome-novel", "os-story", "os-story-long-write",
        "os-story-short-write", "os-story-review", "os-story-long-analyze", "os-story-short-analyze",
        "os-story-long-scan", "os-story-short-scan", "os-story-import", "cw-webnovel", "vn-booming", "vn-fuck-it",
        "vn-bookplan", "vn-discuss", "vn-name", "vn-polish", "vn-revise", "vn-sync", "vn-taste",
        "vn-training-ground", "vn-write", "ns-novel-studio", "ns-chief-editor", "ns-architect", "ns-lore-master",
        "ns-writer", "ns-editor", "ns-designer", "iw-ip-worldbuilding", "tc-tavern-design", "tc-tavern-cards",
        "tc-tavern-ui", "rs-renqing-shi-gu", "rq-renqing-shigu", "gq-workplace-eq", "tz-tizhi", "sp-socialization",
        "dp-chapter-draft", "dp-chapter-summary", "dp-review-consistency", "dp-review-final-report",
        "dp-review-reader", "dp-set-concept", "dp-set-outline", "dp-set-style", "dp-tool-research",
        "dp-using-dreampowers", "cn-chinese-novelist", "sh-shuorenhua"
    ).mapTo(HashSet()) { "builtin-writing-$it" }
    /** 0.1.77 shipped web search as a Skill; since 0.1.79 every chat on the PC has the tool without it. */
    private val RETIRED_TOOLS = setOf("builtin-writing-pt-web-reach")
    private val WITHDRAWN: Set<String> = WITHDRAWN_PATCHED + RETIRED + RETIRED_TOOLS

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
            val personalUse = item.optBoolean("personalUse", false)
            require(license in licenses || (license == UNLICENSED && personalUse)) { "内置 Skill 许可证无效：$id" }
            require(!personalUse || license == UNLICENSED) { "内置 Skill 自用标记与许可证不一致：$id" }
            require(!item.has("omittedLines") && !item.has("upstreamSha256")) { "内置 Skill 必须是上游原文，不能删节：$id" }
            require(blobPattern.matches(item.optString("gitBlob"))) { "内置 Skill gitBlob 无效：$id" }
            val adult = item.optBoolean("adult", false)
            require(adult == (category == ADULT_CATEGORY)) { "内置 Skill 成人向标记与分类不一致：$id" }
            val resources = readResourceEntries(id, item)
            require(licensePath.startsWith("licenses/") && isSafeRelativePath(licensePath)) {
                "内置 Skill 许可证路径无效：$id"
            }
            val sourceMatch = sourceUrlPattern.matchEntire(sourceUrl)
                ?: throw IllegalArgumentException("内置 Skill sourceUrl 必须是固定提交的 GitHub 文件地址：$id")
            require(sourceMatch.groupValues[2].equals(commit, ignoreCase = true)) {
                "内置 Skill sourceUrl commit 不匹配：$id"
            }

            val path = "$SKILL_PATH_PREFIX$id$SKILL_PATH_SUFFIX"
            val source = readText(path)
            val actualHash = sha256(source.toByteArray(StandardCharsets.UTF_8))
            require(actualHash == sha256) { "内置 Skill 原文 SHA-256 不匹配：$id" }
            // Upstream files without YAML frontmatter get a header for the importer only; the stored
            // SKILL.md and the prompt body remain the upstream bytes.
            val importText = if (item.optBoolean("wrapFrontmatter", false)) {
                require(!description.contains('\n')) { "内置 Skill 说明无效：$id" }
                "---\nname: $id\ndescription: $description\n---\n$source"
            } else source
            val imported = SkillImporter.importFile("$id/SKILL.md", importText.toByteArray(StandardCharsets.UTF_8))
            require(imported.size == 1) { "内置 Skill 必须包含一个 SKILL.md：$id" }
            val licenseText = readText("$SKILL_PATH_PREFIX$licensePath")
            loaded += imported.single().copy(
                id = "builtin-writing-$id",
                name = name,
                description = description,
                enabled = false,
                permissions = emptyList(),
                source = "GitHub精选 · $category",
                files = imported.single().files + ("SKILL.md" to source) + resources + ("LICENSE" to licenseText),
                sourceUrl = sourceUrl,
                sourceCommit = commit.lowercase(),
                sourceLicense = if (license == UNLICENSED) PERSONAL_USE_LICENSE else license,
                adult = adult
            )
        }
        return loaded
    }

    /** GitHub catalog followed by the Skills written for this app. */
    fun loadAll(readText: (String) -> String): List<SkillRecord> = load(readText) + loadFirstParty(readText)

    /** Star counts recorded in the catalog, keyed by built-in Skill id; never fetched at runtime. */
    fun loadStars(readText: (String) -> String): Map<String, GitHubStars> {
        val root = JSONObject(readText(CATALOG_PATH))
        val checkedAt = root.optString("starsCheckedAt").trim()
        val entries = root.optJSONArray("skills") ?: return emptyMap()
        return (0 until entries.length()).mapNotNull { index ->
            val item = entries.optJSONObject(index) ?: return@mapNotNull null
            val repo = sourceUrlPattern.matchEntire(item.optString("sourceUrl").trim())?.groupValues?.get(1) ?: return@mapNotNull null
            val stars = if (item.has("githubStars")) item.optInt("githubStars", -1).takeIf { it >= 0 } else null
            "builtin-writing-${item.optString("id").trim()}" to GitHubStars(repo, stars, checkedAt)
        }.toMap()
    }

    /**
     * Loads Skills authored for this app. They have no upstream repository or commit, so the
     * catalog pins only their own bytes; ids carry the `pt-` marker and cannot be adult Skills.
     */
    fun loadFirstParty(readText: (String) -> String): List<SkillRecord> {
        val root = JSONObject(readText(FIRST_PARTY_CATALOG))
        require(root.optInt("version", -1) == 1) { "自编 Skills catalog 版本不受支持" }
        val entries = root.optJSONArray("skills") ?: throw IllegalArgumentException("自编 Skills catalog 缺少 skills")
        val licenseText = readText("${FIRST_PARTY_PREFIX}LICENSE-NOTICE")
        val ids = mutableSetOf<String>()
        return (0 until entries.length()).map { index ->
            val item = entries.optJSONObject(index) ?: throw IllegalArgumentException("自编 Skills catalog 条目无效")
            val id = item.optString("id").trim()
            require(ID_PATTERN.matches(id) && id.startsWith("pt-")) { "自编 Skill id 无效：$id" }
            require(ids.add(id)) { "自编 Skill id 重复：$id" }
            val name = item.optString("name").trim()
            val category = item.optString("category").trim()
            val description = item.optString("description").trim()
            val sha256 = item.optString("sha256").trim().lowercase()
            require(name.isNotBlank() && name.length <= 64) { "自编 Skill 名称无效：$id" }
            require(category in categories && category != ADULT_CATEGORY && !item.optBoolean("adult", false)) { "自编 Skill 分类无效：$id" }
            require(description.isNotBlank() && description.length <= 1024) { "自编 Skill 说明无效：$id" }
            require(hashPattern.matches(sha256)) { "自编 Skill sha256 无效：$id" }
            val source = readText("$FIRST_PARTY_PREFIX$id$SKILL_PATH_SUFFIX")
            require(sha256(source.toByteArray(StandardCharsets.UTF_8)) == sha256) { "自编 Skill 原文 SHA-256 不匹配：$id" }
            val imported = SkillImporter.importFile("$id/SKILL.md", source.toByteArray(StandardCharsets.UTF_8)).single()
            imported.copy(
                id = "builtin-writing-$id",
                name = name,
                description = description,
                enabled = false,
                permissions = emptyList(),
                source = "$FIRST_PARTY_SOURCE · $category",
                files = imported.files + ("LICENSE" to licenseText),
                sourceUrl = "",
                sourceCommit = "",
                sourceLicense = FIRST_PARTY_LICENSE,
                adult = false
            )
        }
    }

    /** Category shown in the Skills filter for bundled Skills; null for user-installed ones. */
    fun bundledCategory(skill: SkillRecord): String? =
        listOf("GitHub精选 · ", "$FIRST_PARTY_SOURCE · ").firstNotNullOfOrNull { prefix ->
            skill.source.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        }

    private fun readResourceEntries(id: String, item: JSONObject): Map<String, String> {
        val entries = item.optJSONArray("files") ?: return emptyMap()
        require(entries.length() <= MAX_RESOURCE_FILES) { "内置 Skill 引用文件过多：$id" }
        val resources = LinkedHashMap<String, String>()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: throw IllegalArgumentException("内置 Skill 引用文件条目无效：$id")
            val path = entry.optString("path").trim()
            val hash = entry.optString("sha256").trim().lowercase()
            require(isSafeRelativePath(path) && path != "SKILL.md" && path != "LICENSE") { "内置 Skill 引用文件路径无效：$id/$path" }
            require(hashPattern.matches(hash)) { "内置 Skill 引用文件 sha256 无效：$id/$path" }
            require(!entry.has("omittedLines") && !entry.has("upstreamSha256")) { "内置 Skill 引用文件必须是上游原文：$id/$path" }
            require(blobPattern.matches(entry.optString("gitBlob"))) { "内置 Skill 引用文件 gitBlob 无效：$id/$path" }
            require(resources.put(path, "$ASSET_PREFIX$hash") == null) { "内置 Skill 引用文件重复：$id/$path" }
        }
        return resources
    }

    /**
     * Returns the text of one Skill file. Built-in reference markers are resolved from the
     * bundled assets and must still match the catalog hash recorded in the Skill.
     */
    fun readResource(readText: (String) -> String, skill: SkillRecord, path: String): String? {
        val stored = skill.files[path] ?: return null
        if (!stored.startsWith(ASSET_PREFIX)) return stored
        val expected = stored.removePrefix(ASSET_PREFIX)
        val id = skill.id.removePrefix("builtin-writing-")
        require(skill.id.startsWith("builtin-writing-") && ID_PATTERN.matches(id) && hashPattern.matches(expected) && isSafeRelativePath(path)) {
            "内置 Skill 引用文件无效：$path"
        }
        val text = readText("$SKILL_PATH_PREFIX$id/$path")
        require(sha256(text.toByteArray(StandardCharsets.UTF_8)) == expected) { "内置 Skill 引用文件 SHA-256 不匹配：$path" }
        return text
    }

    /**
     * Adds never-seen built-ins and refreshes installed GitHub records once for this catalog
     * revision. Enabled switches, permissions and chat selections survive the refresh; deleted
     * records stay deleted. Later launches preserve edits, as do all first-party/local records.
     */
    fun merge(data: AppData, catalog: List<SkillRecord>): AppData {
        val seen = data.settings.builtinSkillsSeen.toMutableList()
        val seenSet = seen.toMutableSet()
        val byId = catalog.associateBy { it.id }
        val hasGitHubCatalog = catalog.any { it.source.startsWith("GitHub精选 · ") }
        val refreshGitHub = hasGitHubCatalog && data.settings.builtinSkillsRevision < GITHUB_CATALOG_REVISION
        val refreshAllGitHub = data.settings.builtinSkillsRevision < 1
        val skills = data.skills.filterNot { it.id in WITHDRAWN }.map { skill ->
            val verbatim = byId[skill.id]
            if (refreshGitHub && verbatim != null && verbatim.source.startsWith("GitHub精选 · ") &&
                (refreshAllGitHub || skill.id == "builtin-writing-dn-deep-novel")) {
                verbatim.copy(enabled = skill.enabled, permissions = skill.permissions)
            } else skill
        }.toMutableList()
        val chats = data.chats.map { chat ->
            if (chat.skillIds.none { it in WITHDRAWN }) chat else chat.copy(skillIds = chat.skillIds.filterNot { it in WITHDRAWN })
        }
        catalog.forEach { builtin ->
            require(builtin.id.startsWith("builtin-writing-")) { "只能合并内置 writing Skill" }
            if (builtin.id !in seenSet) {
                if (skills.any { it.id == builtin.id }) {
                    // A prior installation may have the record but not the migration marker.
                    // Treat it as seen without duplicating the installed record.
                    seen += builtin.id
                    seenSet += builtin.id
                } else if (skills.size < MAX_SKILLS) {
                    skills += builtin.copy(enabled = false, permissions = emptyList())
                    seen += builtin.id
                    seenSet += builtin.id
                }
            }
        }
        return data.copy(skills = skills, chats = chats, settings = data.settings.copy(
            builtinSkillsSeen = seen.distinct(),
            builtinSkillsRevision = if (refreshGitHub) GITHUB_CATALOG_REVISION else data.settings.builtinSkillsRevision
        ))
    }

    /** Missing bundled entries include deletions and entries deferred by the store limit. */
    fun missing(data: AppData, catalog: List<SkillRecord>): List<SkillRecord> {
        val installed = data.skills.mapTo(mutableSetOf()) { it.id }
        return catalog.filter { it.id !in installed && it.id !in WITHDRAWN }
    }

    /** Explicitly restores one original without changing any installed record or chat selection. */
    fun restore(data: AppData, catalog: List<SkillRecord>, id: String): AppData {
        val builtin = catalog.firstOrNull { it.id == id && it.id !in WITHDRAWN }
            ?: throw IllegalArgumentException("内置 Skill 不存在：$id")
        if (data.skills.any { it.id == id }) return data
        require(data.skills.size < MAX_SKILLS) { "Skills 已达 500 项，请先删除不需要的项目" }
        return data.copy(
            skills = data.skills + builtin.copy(enabled = false, permissions = emptyList()),
            settings = data.settings.copy(builtinSkillsSeen = (data.settings.builtinSkillsSeen + id).distinct())
        )
    }

    /** Changes only the requested chat selection; checking a Skill also enables it globally. */
    fun selectForChat(data: AppData, chatId: String, skillId: String, selected: Boolean): AppData {
        require(data.chats.any { it.id == chatId }) { "会话不存在：$chatId" }
        val skill = data.skills.firstOrNull { it.id == skillId } ?: throw IllegalArgumentException("Skill 不存在：$skillId")
        require(!selected || !skill.adult || data.settings.adultSkillsConfirmed) { "成人向 Skill 需要先确认已年满 18 岁，并仅用于成年角色" }
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
    // The pinned DSH text bundle contains 221 lazily loaded references.
    private const val MAX_RESOURCE_FILES = 300

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
