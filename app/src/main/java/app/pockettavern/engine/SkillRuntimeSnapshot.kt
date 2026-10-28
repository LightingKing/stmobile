package app.pockettavern.engine

import app.pockettavern.domain.SkillRecord
import org.json.JSONArray
import org.json.JSONObject

/** Metadata for the same filtered, ordered Skills whose bodies ContextEngine sends this turn. */
object SkillRuntimeSnapshot {
    private const val SELECTION_NOTICE = "选择由 App 会话设置决定，本工具只读；查询或聊天中的口头承诺不会修改启用开关。" +
        "数量、ID 和顺序以本轮清单为准，不沿用历史回复中的清单；需要永久启停时请用户在会话设置中操作，不得声称已经替用户修改开关。"
    private const val EVIDENCE_NOTICE = "正文已注入表示正文随本轮请求发送，不代表引用文件已读取或规则已被正确执行。" +
        "引用不会自动全部注入；只有对应的成功 skill_read 工具结果能证明引用已读取。" +
        "技能对照表和逐段解释是模型说明，不是真实执行日志，不得声称能据此证明某一句由某个 Skill 产生。"

    /** Reads metadata only: no Skill body, reference contents, credentials, or resource reader. */
    fun manifest(activeSkills: List<SkillRecord>, toolsSupported: Boolean): String {
        val entries = JSONArray()
        activeSkills.forEachIndexed { index, skill ->
            val references = ToolRuntime.referencePaths(skill)
            entries.put(JSONObject()
                .put("order", index + 1)
                .put("id", skill.id)
                .put("name", skill.name)
                .put("bodyInjected", true)
                .put("referenceFileCount", references.size)
                .put("referencesCanBeRead", toolsSupported && references.isNotEmpty())
                .put("referencesAutomaticallyInjected", false))
        }
        return JSONObject()
            .put("source", "current_request_app_selection")
            .put("readOnly", true)
            .put("count", activeSkills.size)
            .put("selectionNotice", SELECTION_NOTICE)
            .put("referenceReadEvidence", EVIDENCE_NOTICE)
            .put("skills", entries)
            .toString()
    }

    fun prompt(activeSkills: List<SkillRecord>, toolsSupported: Boolean): String =
        "【本轮 Skills 清单（App 生成）】\n" + manifest(activeSkills, toolsSupported) +
            if (toolsSupported) "\n需要核对时调用只读 skill_list；无需故意读取不存在的文件来查询清单。"
            else "\n当前模型不支持工具调用，引用文件无法按需读取；清单和正文仍随本轮请求发送。"
}
