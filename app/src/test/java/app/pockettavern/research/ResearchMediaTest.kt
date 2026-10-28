package app.pockettavern.research

import org.junit.Assert.assertThrows
import org.junit.Test

class ResearchMediaTest {
    @Test fun requestIdsAreBoundedAndPathSafe() {
        ResearchMedia.requireValidRequestId("request_01-abc")
        ResearchMedia.requireValidRequestId("a".repeat(80))
        assertThrows(IllegalArgumentException::class.java) { ResearchMedia.requireValidRequestId("") }
        assertThrows(IllegalArgumentException::class.java) { ResearchMedia.requireValidRequestId("../escape") }
        assertThrows(IllegalArgumentException::class.java) { ResearchMedia.requireValidRequestId("a".repeat(81)) }
        assertThrows(IllegalArgumentException::class.java) { ResearchMedia.requireValidRequestId("中文") }
    }
}
