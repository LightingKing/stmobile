package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Character
import app.pockettavern.domain.Chat
import app.pockettavern.domain.ScenarioDraft
import app.pockettavern.domain.ScenarioDraftEntry
import app.pockettavern.domain.WorldEntry
import app.pockettavern.domain.WorldLimits
import app.pockettavern.domain.newId
import org.json.JSONArray
import org.json.JSONObject

/**
 * 剧本助手: a chat for writing a playable scenario with the user. The model saves each part the
 * user agrees to into the chat's [ScenarioDraft] (several small saves, so dozens of world entries
 * fit), and on the user's word builds a scenario card plus world-book entries from it, the same
 * shape as the built-in scenarios. Building again updates the same card.
 */
object ScenarioBuilder {
    const val TASK = "scenario_builder"
    const val SAVE = "scenario_draft_save"
    const val READ = "scenario_draft_read"
    const val FINISH = "scenario_finish"
    /** Ids of scenarios the user made; built-in ones start with [BUILTIN_PREFIX]. */
    const val USER_PREFIX = "user-scenario-"
    private const val BUILTIN_PREFIX = "builtin-scenario-"

    /** Default Skills for a new 剧本助手 chat. The user can override in the start dialog;
     *  these are remembered per-install once a chat opens, so they serve both fresh installs
     *  and existing installs where [AppSettings.scenarioSkillIds] is still empty. */
    val DEFAULT_BUILDER_SKILL_IDS = listOf(
        "builtin-writing-dv-roleplay-modes",   // DeepSeek 角色扮演思考模式
        "builtin-writing-pt-character-voice",   // 说话风格
        "builtin-writing-tm-dialogue",          // 网文对话与台词
    )

    /** Skills a new play chat for a user-made scenario starts with.
     *  Chosen for dialogue quality; different from builder skills which focus on plot framing. */
    val DEFAULT_PLAY_SKILL_IDS = listOf(
        "builtin-writing-pt-character-voice",   // 说话风格
        "builtin-writing-tm-dialogue",          // 网文对话与台词
    )

    const val MAX_WORLD = 80
    const val MAX_PEOPLE = 30
    private const val MAX_KEYWORDS = 12
    private const val MAX_KEYWORD_CHARS = 30
    private val FIELDS = listOf(
        Triple("title", "剧本名", 64),
        Triple("summary", "简介", 1000),
        Triple("user_role", "用户扮演的身份", 2000),
        Triple("setting", "核心设定", 8000),
        Triple("opening", "开场", 6000),
        Triple("rules", "叙事规则", 4000),
    )
    /** The world's own limits (see the PC's world.mjs); a draft that passes these builds. */
    private val WORLD_FIELDS = listOf(
        Triple("title", "世界名", 40),
        Triple("summary", "简介", 500),
        Triple("user_role", "用户在世界里的身份", 2000),
        Triple("user_name", "居民怎么称呼用户", 20),
        Triple("group_name", "群名", 30),
        Triple("setting", "核心设定", 8000),
        Triple("opening", "世界开始时的第一件事", 4000),
        Triple("rules", "叙事规则", 4000),
    )
    private const val RESIDENT_NAME_CHARS = 30
    private const val ENTRY_NAME_CHARS = 60
    private const val ENTRY_CONTENT_CHARS = 6000
    private const val PREVIEW_CHARS = 120

    fun isScenarioId(id: String): Boolean = id.startsWith(BUILTIN_PREFIX) || id.startsWith(USER_PREFIX)
    fun isScenario(character: Character): Boolean = isScenarioId(character.id)

    const val GREETING = "我是剧本助手。说说你想玩的故事：题材、时代和地点、基调，你想扮演谁，希望遇到哪些人。也可以发设定文档、图片或链接给我参考。\n\n" +
        "我会和你把世界观、主要人物和开场一项项定下来，每定一部分就记进“剧本草稿”（顶部随时可以看）。你说可以了，我就生成剧本，然后点“开始正式聊天”开一局。"

    const val PROMPT = "【剧本助手】本会话的任务是和用户一起写一个可以游玩的互动剧本：一张剧本卡（正式聊天时由模型担任旁白、扮演剧中所有人物），加上世界观和人物的世界书条目。" +
        "做法：先问清题材、基调、用户扮演的身份和想要的体验，再逐步提出世界观、主要人物、开场；每次提一部分，让用户确认或修改，不要一次把所有内容写完。" +
        "用户确认的部分马上用 scenario_draft_save 写进草稿：title 剧本名、summary 一两句简介、user_role 用户扮演的身份；setting 是每轮都要用的核心设定（世界、时代、关键规则）；" +
        "world 是世界观条目（地点、势力、规则、历史、物品等，一条一个主题，给出触发关键词，每轮都需要的设 always）；people 是主要人物，每人一条（身份、外貌、性格、说话方式、和用户的关系、动机或秘密）；" +
        "opening 是开场的第一条消息，由旁白来写，停在等用户行动的地方；rules 是叙事规则（模型扮演所有人物时的写法和节奏，例如只写 NPC 和环境、不替用户说话和行动）。" +
        "条目要短而具体；改某条就用同样的 name 覆盖，删掉用 remove。要看某条的全文用 scenario_draft_read。" +
        "用户明确说可以生成后，调用 scenario_finish；生成后还能接着改，改完再调用会更新同一个剧本。生成后告诉用户点顶部的“开始正式聊天”。" +
        "写什么题材、怎么写由你和用户决定。两条底线：涉及恋爱或成人内容的人物必须明确是成年人；以现实中的真人为人物时，只按公开资料写公开形象，不写性内容，也不写住址、联系方式、家人、未公开的身份这类隐私。"

    const val NO_TOOLS = "【剧本助手】当前模型不能调用工具，没法把内容写进剧本草稿。可以继续讨论；要保存草稿和生成剧本，请在会话设置里换一个支持工具调用的模型。"

    const val WORLD_GREETING = "我是世界助手。我们来建一个小世界：它在你的电脑上一直运行，住在里面的人按自己的作息生活，会在群里聊天，也会私聊你；你随时可以插话，也能暂停整个世界。\n\n" +
        "说说你想要什么样的世界：在哪、什么时代、什么氛围，你在里面是谁，想和哪些人住在一起（最多 8 位）。也可以发设定或图片给我参考。\n\n" +
        "我会和你一位一位定下居民的性格、作息、目标和关系，记进“世界草稿”（顶部随时可以看）；你说可以了，我就在电脑上把世界建出来（先暂停，不花钱）。"

    const val WORLD_PROMPT = "【世界助手】本会话的任务是和用户一起建一个“小世界”：它在用户电脑上的写作服务里一直运行，居民按各自的作息生活，会在群里聊天，也会私聊用户；用户随时可以插话，也能暂停整个世界。世界里最多 8 位居民。" +
        "做法：先问清世界在哪、什么时代和氛围、用户在这个世界里是谁、居民怎么称呼用户；再一位一位定居民；最后补世界观条目和群名。每次提一部分，让用户确认或修改，不要一次全写完。" +
        "用户确认的部分马上用 scenario_draft_save 写进草稿：title 世界名；summary 一两句简介；user_role 用户在世界里的身份；user_name 居民怎么称呼用户；group_name 大家所在的群名；" +
        "setting 每次推进都要用的核心设定（地点、时代、日常的样子、关键规则）；world 世界观条目（地点、组织、规则、物品等，一条一个主题，给关键词，常用的设 always）；" +
        "people 是居民，每人一条，name 就是居民的名字（30 字以内），content 依次写：【身份】【外貌】【性格与说话方式】【作息】（几点起床、上班上学、什么时候在家、几点睡）【目标】（最近想要什么、在为什么忙）【关系】（和其他居民、和用户的关系），keywords 写别的叫法；" +
        "rules 是世界的叙事规则（节奏、口吻、不会发生的事）；opening 是世界开始时发生的第一件事，可以不写。" +
        "作息、目标和关系要具体：世界会按这些安排居民什么时候说话、什么时候私聊用户、和谁起冲突。条目要短而具体；改某条用同样的 name 覆盖，删掉用 remove；要看全文用 scenario_draft_read。" +
        "用户明确说可以建成后，调用 scenario_finish：在电脑上建成小世界（先暂停，不花钱）。建成后还能接着改，再调用会更新同一个世界；居民按名字对应，改了名字的会当作新居民。建成后告诉用户点顶部的“打开小世界”，看过后按 ▶ 开始。" +
        "写什么由你和用户决定。两条底线：涉及恋爱或成人内容的居民必须明确是成年人；以现实中的真人为居民时，只按公开资料写公开形象，不写性内容，也不写住址、联系方式、家人、未公开的身份这类隐私。"

    /** The standing instructions for this builder chat: scenario or world. */
    fun prompt(draft: ScenarioDraft?): String = if (draft?.worldMode == true) WORLD_PROMPT else PROMPT

    fun available(chat: Chat, toolsSupported: Boolean): Boolean = chat.task == TASK && toolsSupported

    private fun entrySchema(what: String): JSONObject = JSONObject().put("type", "array").put("description", what).put("items", JSONObject().put("type", "object")
        .put("properties", JSONObject()
            .put("name", JSONObject().put("type", "string").put("description", "条目名，最多 $ENTRY_NAME_CHARS 字；同名覆盖"))
            .put("content", JSONObject().put("type", "string").put("description", "正文，最多 $ENTRY_CONTENT_CHARS 字"))
            .put("keywords", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "触发关键词，最多 $MAX_KEYWORDS 个"))
            .put("always", JSONObject().put("type", "boolean").put("description", "每轮都带上（常驻）")))
        .put("required", JSONArray(listOf("name", "content"))))

    fun definitions(worldMode: Boolean = false): List<JSONObject> = listOf(
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", SAVE)
            .put("description", if (worldMode) "把用户确认过的部分写进本会话的世界草稿。只传要改的字段；条目按 name 覆盖同名条目，没有同名的就新增。"
                else "把用户确认过的部分写进本会话的剧本草稿。只传要改的字段；条目按 name 覆盖同名条目，没有同名的就新增。")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().apply {
                (if (worldMode) WORLD_FIELDS else FIELDS).forEach { (key, label, max) -> put(key, JSONObject().put("type", "string").put("description", "$label，最多 $max 字")) }
                put("world", entrySchema("世界观条目，最多 $MAX_WORLD 条"))
                put("people", if (worldMode) entrySchema("居民，每人一条，name 是名字（最多 $RESIDENT_NAME_CHARS 字），最多 ${WorldLimits.RESIDENTS} 位")
                    else entrySchema("人物，每人一条，最多 $MAX_PEOPLE 个"))
                put("remove", JSONObject().put("type", "array").put("description", "要删掉的条目").put("items", JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("section", JSONObject().put("type", "string").put("enum", JSONArray(listOf("world", "people"))))
                        .put("name", JSONObject().put("type", "string")))
                    .put("required", JSONArray(listOf("name")))))
            }))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", READ)
            .put("description", "读剧本草稿里条目的全文。names 为空时返回概要、核心设定、开场和叙事规则的全文。")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("names", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "条目名，最多 10 个"))))),
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", FINISH)
            .put("description", if (worldMode) "用当前草稿在电脑上建成小世界（建好后先暂停）；已经建过的会更新同一个世界。只在用户明确同意后调用。"
                else "用当前草稿生成剧本（剧本卡和世界书条目）；已经生成过的会更新同一个剧本。只在用户明确同意后调用。")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
    )

    /** The draft with the save applied, and what was saved in words; all or nothing. */
    fun applySave(draft: ScenarioDraft, args: JSONObject, now: Long = System.currentTimeMillis()): Result<Pair<ScenarioDraft, String>> = runCatching {
        var next = draft
        val said = mutableListOf<String>()
        val fields = (if (draft.worldMode) WORLD_FIELDS else FIELDS).filter { (key) -> args.has(key) && !args.isNull(key) }.map { (key, label, max) ->
            val value = args.optString(key).trim()
            require(value.length <= max) { "$label 超过 $max 字" }
            Triple(key, label, value)
        }
        fields.forEach { (key, _, value) ->
            next = when (key) {
                "title" -> next.copy(title = value)
                "summary" -> next.copy(summary = value)
                "user_role" -> next.copy(userRole = value)
                "setting" -> next.copy(setting = value)
                "opening" -> next.copy(opening = value)
                "user_name" -> next.copy(userName = value)
                "group_name" -> next.copy(groupName = value)
                else -> next.copy(rules = value)
            }
        }
        if (fields.isNotEmpty()) said += fields.joinToString("、") { it.second }
        fun entries(key: String): List<ScenarioDraftEntry> {
            val list = args.optJSONArray(key) ?: return emptyList()
            return (0 until list.length()).map { index ->
                val item = list.optJSONObject(index) ?: throw IllegalArgumentException("$key 第 ${index + 1} 项不是对象")
                val name = item.optString("name").trim()
                require(name.isNotBlank()) { "$key 第 ${index + 1} 项缺少 name" }
                require(name.length <= ENTRY_NAME_CHARS) { "条目名“${name.take(20)}…”超过 $ENTRY_NAME_CHARS 字" }
                val content = item.optString("content").trim()
                require(content.isNotBlank()) { "条目“$name”没有内容" }
                require(content.length <= ENTRY_CONTENT_CHARS) { "条目“$name”超过 $ENTRY_CONTENT_CHARS 字" }
                val keywords = item.optJSONArray("keywords")?.let { words -> (0 until words.length()).map { words.optString(it).trim().take(MAX_KEYWORD_CHARS) } }.orEmpty()
                    .filter { it.isNotBlank() }.distinct().take(MAX_KEYWORDS)
                ScenarioDraftEntry(name, content, keywords, item.optBoolean("always", false))
            }
        }
        fun merge(current: List<ScenarioDraftEntry>, added: List<ScenarioDraftEntry>): List<ScenarioDraftEntry> =
            added.fold(current) { list, entry -> if (list.any { it.name == entry.name }) list.map { if (it.name == entry.name) entry else it } else list + entry }
        val world = entries("world")
        val people = entries("people")
        if (draft.worldMode) people.firstOrNull { it.name.length > RESIDENT_NAME_CHARS }?.let { throw IllegalArgumentException("居民名字“${it.name.take(20)}…”超过 $RESIDENT_NAME_CHARS 字") }
        next = next.copy(world = merge(next.world, world), people = merge(next.people, people))
        if (world.isNotEmpty()) said += "${world.size} 条世界观"
        if (people.isNotEmpty()) said += "${people.size} 个人物"
        var removed = 0
        args.optJSONArray("remove")?.let { list ->
            for (index in 0 until list.length()) {
                val item = list.opt(index)
                val name = ((item as? JSONObject)?.optString("name") ?: item as? String).orEmpty().trim()
                val section = (item as? JSONObject)?.optString("section").orEmpty()
                if (name.isBlank()) continue
                val before = next.world.size + next.people.size
                next = next.copy(world = if (section == "people") next.world else next.world.filterNot { it.name == name },
                    people = if (section == "world") next.people else next.people.filterNot { it.name == name })
                removed += before - next.world.size - next.people.size
            }
        }
        if (removed > 0) said += "删掉 $removed 条"
        require(next.world.size <= MAX_WORLD) { "世界观条目最多 $MAX_WORLD 条" }
        require(next.people.size <= MAX_PEOPLE) { "人物最多 $MAX_PEOPLE 个" }
        if (draft.worldMode) require(next.people.size <= WorldLimits.RESIDENTS) { "小世界最多 ${WorldLimits.RESIDENTS} 位居民，现在会有 ${next.people.size} 位；先删掉几位，或合并成一位" }
        require(said.isNotEmpty()) { "没有要保存的内容" }
        next.copy(updatedAt = now) to said.joinToString("；")
    }

    /** Full text of the named entries, or of the overview when no names are given. */
    fun read(draft: ScenarioDraft, args: JSONObject): String {
        val names = args.optJSONArray("names")?.let { list -> (0 until list.length()).map { list.optString(it).trim() } }.orEmpty().filter { it.isNotBlank() }.distinct().take(10)
        if (names.isEmpty()) return JSONObject().put("ok", true).put("title", draft.title).put("summary", draft.summary).put("user_role", draft.userRole)
            .put("setting", draft.setting).put("opening", draft.opening).put("rules", draft.rules)
            .apply { if (draft.worldMode) { put("user_name", draft.userName); put("group_name", draft.groupName) } }.toString()
        val found = JSONArray()
        val missing = JSONArray()
        names.forEach { name ->
            val world = draft.world.firstOrNull { it.name == name }
            val person = draft.people.firstOrNull { it.name == name }
            val entry = world ?: person
            if (entry == null) missing.put(name)
            else found.put(JSONObject().put("section", if (world != null) "world" else "people").put("name", entry.name).put("keywords", JSONArray(entry.keywords))
                .put("always", entry.always).put("content", entry.content))
        }
        return JSONObject().put("ok", true).put("entries", found).apply { if (missing.length() > 0) put("missing", missing) }.toString()
    }

    /** The draft as the model sees it every turn: short fields in full, entries as a list with their first lines. */
    fun view(draft: ScenarioDraft?): String {
        if (draft?.worldMode == true) return worldView(draft)
        if (draft == null || draft.isEmpty) return "【当前剧本草稿】还是空的。"
        fun preview(text: String) = text.replace('\n', ' ').let { if (it.length > PREVIEW_CHARS) it.take(PREVIEW_CHARS) + "…（共 ${text.length} 字）" else it }
        fun line(entry: ScenarioDraftEntry) = "- ${entry.name}${if (entry.always) "〔常驻〕" else ""}" +
            (if (entry.keywords.isEmpty()) "" else "（关键词：${entry.keywords.joinToString("、")}）") + "：" + preview(entry.content)
        return buildString {
            append("【当前剧本草稿】生成剧本时以这里为准；条目只列开头，全文用 scenario_draft_read 读。")
            fun field(label: String, value: String, full: Boolean = true) { append("\n$label：").append(if (value.isBlank()) "（未写）" else if (full) value else preview(value)) }
            field("剧本名", draft.title)
            field("简介", draft.summary)
            field("用户扮演", draft.userRole)
            field("核心设定", draft.setting)
            field("开场", draft.opening, full = false)
            field("叙事规则", draft.rules)
            append("\n世界观条目（${draft.world.size} 条）：")
            draft.world.forEach { append("\n").append(line(it)) }
            append("\n人物（${draft.people.size} 个）：")
            draft.people.forEach { append("\n").append(line(it)) }
            append("\n状态：").append(when {
                draft.scenarioId.isBlank() -> "还没有生成剧本。"
                draft.changedSinceBuilt -> "已生成过剧本，之后的改动还没有更新进去。"
                else -> "已生成剧本，和草稿一致。"
            })
        }
    }

    private fun worldView(draft: ScenarioDraft): String {
        if (draft.isEmpty) return "【当前世界草稿】还是空的。世界最多 ${WorldLimits.RESIDENTS} 位居民。"
        fun preview(text: String) = text.replace('\n', ' ').let { if (it.length > PREVIEW_CHARS) it.take(PREVIEW_CHARS) + "…（共 ${text.length} 字）" else it }
        fun line(entry: ScenarioDraftEntry) = "- ${entry.name}${if (entry.always) "〔常驻〕" else ""}" +
            (if (entry.keywords.isEmpty()) "" else "（关键词：${entry.keywords.joinToString("、")}）") + "：" + preview(entry.content)
        return buildString {
            append("【当前世界草稿】建成世界时以这里为准；条目只列开头，全文用 scenario_draft_read 读。")
            fun field(label: String, value: String, full: Boolean = true) { append("\n$label：").append(if (value.isBlank()) "（未写）" else if (full) value else preview(value)) }
            field("世界名", draft.title)
            field("简介", draft.summary)
            field("用户的身份", draft.userRole)
            field("居民怎么称呼用户", draft.userName)
            field("群名", draft.groupName)
            field("核心设定", draft.setting)
            field("叙事规则", draft.rules)
            field("世界开始时的第一件事", draft.opening, full = false)
            append("\n世界观条目（${draft.world.size} 条）：")
            draft.world.forEach { append("\n").append(line(it)) }
            append("\n居民（${draft.people.size}/${WorldLimits.RESIDENTS} 位）：")
            draft.people.forEach { append("\n").append(line(it)) }
            append("\n状态：").append(when {
                draft.worldId.isBlank() -> "还没有建成世界。"
                draft.changedSinceBuilt -> "已建成世界，之后的改动还没有更新进去。"
                else -> "已建成世界，和草稿一致。"
            })
        }
    }

    /** What is still missing before the draft can be built; empty when it can. */
    fun missing(draft: ScenarioDraft?): List<String> = if (draft?.worldMode == true) buildList {
        if (draft.title.isBlank()) add("世界名")
        if (draft.setting.isBlank() && draft.world.isEmpty()) add("世界观（核心设定或世界观条目）")
        if (draft.people.isEmpty()) add("至少一位居民")
    } else buildList {
        if (draft?.title.isNullOrBlank()) add("剧本名")
        if (draft?.setting.isNullOrBlank() && draft?.world.isNullOrEmpty()) add("世界观（核心设定或世界观条目）")
        if (draft?.opening.isNullOrBlank()) add("开场")
    }

    /** The scenario card and its world-book entries made from [draft]; [existing] keeps what the draft does not cover. */
    fun build(draft: ScenarioDraft, id: String, existing: Character?, presetId: String): Pair<Character, List<WorldEntry>> {
        val missing = missing(draft)
        require(missing.isEmpty()) { "还缺：${missing.joinToString("、")}" }
        val base = existing ?: Character(id = id, defaultPresetId = presetId)
        val character = base.copy(id = id, name = draft.title, description = draft.summary.ifBlank { draft.title }, scenario = draft.setting,
            firstMessage = draft.opening, systemPrompt = draft.rules, userPersona = draft.userRole,
            defaultPresetId = base.defaultPresetId.ifBlank { presetId })
        val world = draft.world.mapIndexed { index, entry ->
            WorldEntry(id = "$id-w-$index", name = entry.name, content = entry.content, keywords = entry.keywords, always = entry.always, characterId = id, priority = 400)
        }
        // People use the 【角色：名字】 heading the scenario roster reads, and their name as a keyword.
        val people = draft.people.mapIndexed { index, entry ->
            WorldEntry(id = "$id-p-$index", name = entry.name, content = if (entry.content.contains("【角色：")) entry.content else "【角色：${entry.name}】\n${entry.content}",
                keywords = (listOf(entry.name) + entry.keywords).distinct().take(MAX_KEYWORDS), always = entry.always, characterId = id, priority = 350)
        }
        return character to (world + people)
    }

    /**
     * Builds or updates the scenario of the builder chat [chatId]. World entries the user added to
     * the scenario by hand are kept; the ones made from the draft are replaced.
     */
    fun finish(data: AppData, chatId: String, now: Long = System.currentTimeMillis()): Result<Pair<AppData, Character>> = runCatching {
        val chat = data.chats.firstOrNull { it.id == chatId } ?: throw IllegalArgumentException("会话不存在")
        require(chat.task == TASK) { "只有剧本助手会话可以生成剧本" }
        val draft = chat.scenarioDraft?.takeIf { !it.isEmpty } ?: throw IllegalArgumentException("剧本草稿还是空的")
        require(!draft.worldMode) { "这是世界草稿，要建成小世界，不生成剧本" }
        val id = draft.scenarioId.ifBlank { USER_PREFIX + newId() }
        val existing = data.characters.firstOrNull { it.id == id }
        val presetId = data.presets.firstOrNull { it.id == "roleplay" }?.id ?: data.presets.firstOrNull()?.id.orEmpty()
        val (character, entries) = build(draft, id, existing, presetId)
        val made = listOf("$id-w-", "$id-p-")
        val kept = data.worldEntries.filterNot { entry -> entry.characterId == id && made.any { entry.id.startsWith(it) } }
        val characters = if (existing == null) data.characters + character else data.characters.map { if (it.id == id) character else it }
        val chats = data.chats.map { if (it.id == chatId) it.copy(scenarioDraft = draft.copy(scenarioId = id, builtAt = maxOf(now, draft.updatedAt))) else it }
        data.copy(characters = characters, worldEntries = kept + entries, chats = chats) to character
    }

    /** A draft read from storage, with every missing field filled in. */
    fun normalize(draft: ScenarioDraft?): ScenarioDraft? {
        if (draft == null) return null
        fun entry(value: ScenarioDraftEntry?): ScenarioDraftEntry? = value?.let {
            ScenarioDraftEntry(name = it.name.orEmpty(), content = it.content.orEmpty(), keywords = it.keywords.orEmpty().filter { word -> !word.isNullOrBlank() }, always = it.always)
        }?.takeIf { it.name.isNotBlank() }
        return ScenarioDraft(title = draft.title.orEmpty(), summary = draft.summary.orEmpty(), userRole = draft.userRole.orEmpty(), setting = draft.setting.orEmpty(),
            opening = draft.opening.orEmpty(), rules = draft.rules.orEmpty(), world = draft.world.orEmpty().mapNotNull(::entry).take(MAX_WORLD),
            people = draft.people.orEmpty().mapNotNull(::entry).take(MAX_PEOPLE), scenarioId = draft.scenarioId.orEmpty(),
            builtAt = draft.builtAt.coerceAtLeast(0), updatedAt = draft.updatedAt.coerceAtLeast(0),
            worldMode = draft.worldMode, userName = draft.userName.orEmpty(), groupName = draft.groupName.orEmpty(), worldId = draft.worldId.orEmpty())
    }
}
