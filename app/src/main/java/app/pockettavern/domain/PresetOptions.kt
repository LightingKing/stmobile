package app.pockettavern.domain

/** Transport still validates explicit levels against the selected Venice catalog entry. */
object PresetOptions {
    val reasoningEfforts = setOf("default", "none", "minimal", "low", "medium", "high", "xhigh", "max")
}
