package app.pockettavern.domain

/** One world-book entry or one person in a scenario draft. */
data class ScenarioDraftEntry(val name: String = "", val content: String = "", val keywords: List<String> = emptyList(), val always: Boolean = false)

/**
 * What the 剧本助手 has written so far, kept on its own chat and filled in a part at a time.
 * [scenarioId] is the scenario card made from it, so building again updates that card instead
 * of adding another. [updatedAt] later than [builtAt] means the draft changed since.
 */
data class ScenarioDraft(
    val title: String = "",
    val summary: String = "",
    /** Who the user plays. */
    val userRole: String = "",
    /** The core setting every turn needs: world, era, key rules. */
    val setting: String = "",
    /** The narrator's first message. */
    val opening: String = "",
    /** How the narrator plays every character. */
    val rules: String = "",
    val world: List<ScenarioDraftEntry> = emptyList(),
    val people: List<ScenarioDraftEntry> = emptyList(),
    val scenarioId: String = "",
    val builtAt: Long = 0,
    val updatedAt: Long = 0,
    /** 世界助手: the draft becomes a 小世界 on the PC instead of a scenario card ([people] are its residents, at most 8). */
    val worldMode: Boolean = false,
    /** What the residents call the user (world mode). */
    val userName: String = "",
    /** The group chat everyone is in (world mode). */
    val groupName: String = "",
    /** The PC world built from this draft; building again updates it. */
    val worldId: String = ""
) {
    val isEmpty: Boolean get() = title.isBlank() && summary.isBlank() && userRole.isBlank() && setting.isBlank() &&
        opening.isBlank() && rules.isBlank() && world.isEmpty() && people.isEmpty() && userName.isBlank() && groupName.isBlank()
    /** What was built from it: the scenario card's id, or the world's in world mode. */
    val builtId: String get() = if (worldMode) worldId else scenarioId
    val changedSinceBuilt: Boolean get() = builtId.isNotBlank() && updatedAt > builtAt
}
