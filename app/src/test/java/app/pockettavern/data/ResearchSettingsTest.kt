package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.AppSettings
import app.pockettavern.domain.UiSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResearchSettingsTest {
    @Test fun researchSelectionRoundTripsAndOldBackupsRemainUnconfigured() {
        val settings = AppSettings(researchProviderId = "ark-provider", researchModelId = "doubao-seed-model", researchSearchMode = "native")
        val encoded = DataCodec.encode(AppData(settings = settings))
        assertEquals(settings, DataCodec.decode(encoded).settings)
        val old = JSONObject(encoded).apply {
            getJSONObject("settings").remove("researchProviderId")
            getJSONObject("settings").remove("researchModelId")
            getJSONObject("settings").remove("researchSearchMode")
        }
        val migrated = DataCodec.decode(old.toString()).settings
        assertEquals("", migrated.researchProviderId)
        assertEquals("", migrated.researchModelId)
        assertEquals("direct", migrated.researchSearchMode)
    }

    @Test fun malformedResearchSelectionIsRejected() {
        listOf("researchProviderId", "researchModelId").forEach { field ->
            listOf<Any>(true, 123, JSONObject(), "x".repeat(513)).forEach { bad ->
                val root = JSONObject(DataCodec.encode(AppData()))
                root.getJSONObject("settings").put(field, bad)
                assertTrue(runCatching { DataCodec.decode(root.toString()) }.isFailure)
            }
        }
    }

    @Test fun searchModeAcceptsOnlySupportedValuesAndKeyPresenceIsTransient() {
        listOf("direct", "native").forEach { mode ->
            val data = AppData(settings = AppSettings(researchSearchMode = mode))
            assertEquals(mode, DataCodec.decode(DataCodec.encode(data)).settings.researchSearchMode)
        }
        listOf<Any>("", "browser", 1, true, JSONObject()).forEach { mode ->
            val root = JSONObject(DataCodec.encode(AppData()))
            root.getJSONObject("settings").put("researchSearchMode", mode)
            assertTrue(runCatching { DataCodec.decode(root.toString()) }.isFailure)
        }
        assertFalse(UiSession().hasResearchSearchKey)
        val session = UiSession(hasResearchSearchKey = true)
        val exported = DataCodec.encode(session.data)
        assertFalse(exported.contains("hasResearchSearchKey"))
        assertFalse(exported.contains("researchSearchKey"))
    }
}
