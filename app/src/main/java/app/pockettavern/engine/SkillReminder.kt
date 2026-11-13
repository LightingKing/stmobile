package app.pockettavern.engine

import app.pockettavern.domain.SkillRecord

/**
 * Skill bodies go at the very top of a request. In a long chat that is tens of thousands of tokens
 * away from the latest message, and many models simply write without them; almost none read a
 * Skill's reference files unless told to. So every turn, the latest user message carries a short
 * note naming the Skills this reply must follow. Only the model sees it; the stored message and
 * its source hash are unchanged.
 */
object SkillReminder {
    const val HEADING = "【本轮写作要求（App 附加，不是用户原话）】"

    /** The note for this turn, or "" when no Skill is selected or the turn is an author's correction. */
    fun note(skills: List<SkillRecord>, toolsSupported: Boolean, intent: String): String {
        if (skills.isEmpty() || intent == "correction") return ""
        val names = skills.joinToString("、") { "《${it.name.trim().ifBlank { it.id }}》" }
        val withFiles = skills.filter { ToolRuntime.referencePaths(it).isNotEmpty() }
        val read = when {
            withFiles.isEmpty() -> ""
            toolsSupported -> "其中${withFiles.joinToString("、") { "《${it.name.trim().ifBlank { it.id }}》" }}带参考文件：正文要求先读的，动笔前用 skill_read 读取；本会话已经读过、内容还在上文里的不必重读。"
            else -> ""
        }
        return HEADING + "这一条回复按已启用的 Skill 来写：$names。动笔前对照它们的规则和检查要点；$read" +
            "规则之间有冲突时，以角色卡和用户本轮的要求为准。不要在正文里提到 Skill 或这段要求。"
    }
}
