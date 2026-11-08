package app.pockettavern.engine

import app.pockettavern.domain.SkillRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * DSH 成人互动 (Adult Tension) keeps its story state in a local Python engine that its SKILL.md
 * runs from a shell. The phone cannot, so a chat on the PC writing server gets one tool that the
 * PC runs instead; elsewhere the Skill stays text rules only.
 */
object DshTool {
    // Installed records carry the bundled-catalog prefix; the bare catalog id never matches a chat's Skill.
    const val SKILL_ID = "builtin-writing-at-adult-tension-original"
    const val NAME = "dsh_runtime"

    val COMMANDS = listOf(
        "doctor", "version", "list-worlds", "new-game", "get-context", "commit-turn", "undo-turn", "save-slot", "load-slot",
        "list-slots", "delete-slot", "save-world", "my-worlds", "delete-world", "list-sessions", "export-save", "import-save",
        "set-boundary", "set-safety", "set-preferences", "guide", "status"
    )

    const val PROMPT = "【DSH 引擎】本会话使用电脑写作服务，DSH 成人互动（adult-tension）的本地运行时在电脑上可用。它的 SKILL.md 里“在 bash 中调用 <python> …/scripts/adult_tension.py <command> --json --input-file -”的步骤，在这里一律改用工具 dsh_runtime：command 填命令名，input 填原本写在 AT_JSON 里的 JSON 对象（encoding_check 可省略）；工具返回的就是运行时 stdout 的 JSON（ok、data、error）。导出存档不要给 path，导入存档只用 data。其余 Skill 里运行脚本的步骤仍然不执行。"

    /** Only while the chat talks to the PC, the model can call tools and DSH is selected. */
    fun available(skills: List<SkillRecord>, onPc: Boolean, toolsSupported: Boolean): Boolean =
        onPc && toolsSupported && skills.any { it.id == SKILL_ID }

    fun definition(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", NAME)
        .put("description", "运行 DSH 成人互动的本地引擎（在电脑上执行）。代替 SKILL.md 中的 bash 调用；返回运行时 stdout 的 JSON：{ok, data, error}。")
        .put("parameters", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("command", JSONObject().put("type", "string").put("enum", JSONArray(COMMANDS)).put("description", "运行时命令名，例如 doctor、new-game、commit-turn、get-context、status"))
                .put("input", JSONObject().put("type", "object").put("description", "该命令的 JSON 输入（原本写在 AT_JSON 里的对象）；不需要输入的命令可省略")))
            .put("required", JSONArray(listOf("command")))))
}
