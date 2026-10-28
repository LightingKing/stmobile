package app.pockettavern.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillOrderTest {
    @Test fun arrowMovesOnlyTheChosenSkillAndHonorsBoundaries() {
        val original = listOf("foundation", "style", "continuity")

        assertEquals(listOf("style", "foundation", "continuity"), moveSkillInOrder(original, "style", -1))
        assertEquals(listOf("foundation", "continuity", "style"), moveSkillInOrder(original, "style", 1))
        assertEquals(original, moveSkillInOrder(original, "foundation", -1))
        assertEquals(original, moveSkillInOrder(original, "continuity", 1))
        assertEquals(original, moveSkillInOrder(original, "missing", 1))
    }
}
