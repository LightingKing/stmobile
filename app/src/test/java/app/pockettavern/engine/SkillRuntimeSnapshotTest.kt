package app.pockettavern.engine

import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.SkillRecord
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRuntimeSnapshotTest {
    private val first = SkillRecord(
        id = "first", name = "第一项", content = "BODY_NOT_FOR_MANIFEST",
        files = mapOf("SKILL.md" to "BODY_NOT_FOR_MANIFEST", "LICENSE" to "LICENSE_NOT_FOR_MANIFEST", "guide.md" to "REFERENCE_NOT_READ")
    )
    private val second = SkillRecord(id = "second", name = "第二项", content = "SECOND_BODY")

    @Test fun listReportsCurrentSelectionInsteadOfEarlierToolResultAndDoesNotReadFiles() = runBlocking {
        val runtime = ToolRuntime(Files.createTempDirectory("skill-list").toFile(), { _, _ -> error("Listing must not read a reference") })
        val disabled = first.copy(id = "disabled", enabled = false)
        val adult = first.copy(id = "adult", adult = true)
        val unselected = first.copy(id = "unselected")
        val data = AppData(skills = listOf(first, second, disabled, adult, unselected))
        val initial = Chat(skillIds = listOf(first.id, second.id))
        val original = JSONObject(runtime.execute("skill_list", "{}", data.skills, data, initial).content)
        assertEquals(listOf("first", "second"), ids(original))

        val changed = initial.copy(skillIds = listOf(second.id, "disabled", "missing", "adult", first.id, second.id))
        val result = runtime.execute("skill_list", "{}", data.skills, data, changed)
        val listed = JSONObject(result.content)
        assertEquals(2, listed.getInt("count"))
        assertEquals(listOf("second", "first"), ids(listed))
        assertEquals(1, listed.getJSONArray("skills").getJSONObject(0).getInt("order"))
        assertEquals("第二项", listed.getJSONArray("skills").getJSONObject(0).getString("name"))
        assertEquals(2, listed.getJSONArray("skills").getJSONObject(1).getInt("order"))
        assertTrue(listed.getBoolean("readOnly"))
        assertEquals(listOf("second", "disabled", "missing", "adult", "first", "second"), changed.skillIds)
        assertEquals(null, result.memories)
        assertFalse(result.content.contains("BODY_NOT_FOR_MANIFEST"))
        assertFalse(result.content.contains("REFERENCE_NOT_READ"))
        assertFalse(result.content.contains("LICENSE_NOT_FOR_MANIFEST"))
        assertFalse(result.content.contains("unselected"))
    }

    @Test fun emptySelectionStillExposesReadOnlyListWithoutGrantingOtherTools() = runBlocking {
        val definitions = JSONArray(ToolRuntime.definitionsForSkills(emptyList()))
        val names = (0 until definitions.length()).map { definitions.getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(listOf("skill_list"), names)
        val data = AppData(skills = listOf(first))
        val result = ToolRuntime(Files.createTempDirectory("empty-skill-list").toFile())
            .execute("skill_list", "{}", data.skills, data, Chat(skillIds = emptyList()))
        val listed = JSONObject(result.content)
        assertEquals(0, listed.getInt("count"))
        assertEquals(0, listed.getJSONArray("skills").length())
        assertTrue(listed.getBoolean("readOnly"))
        assertFalse(result.content.contains(first.id))
    }

    @Test fun manifestDistinguishesInjectedBodyFromUnprovenReferenceReads() {
        val list = JSONObject(SkillRuntimeSnapshot.manifest(listOf(first, second), toolsSupported = true))
        val entries = list.getJSONArray("skills")
        assertTrue(entries.getJSONObject(0).getBoolean("bodyInjected"))
        assertEquals(1, entries.getJSONObject(0).getInt("referenceFileCount"))
        assertTrue(entries.getJSONObject(0).getBoolean("referencesCanBeRead"))
        assertFalse(entries.getJSONObject(0).getBoolean("referencesAutomaticallyInjected"))
        assertEquals(0, entries.getJSONObject(1).getInt("referenceFileCount"))
        assertFalse(entries.getJSONObject(1).getBoolean("referencesCanBeRead"))
        assertTrue(list.getString("selectionNotice").contains("不会修改启用开关"))
        assertTrue(list.getString("referenceReadEvidence").contains("成功 skill_read 工具结果"))
        assertTrue(list.getString("referenceReadEvidence").contains("不是真实执行日志"))

        val noTools = JSONObject(SkillRuntimeSnapshot.manifest(listOf(first), toolsSupported = false))
        assertTrue(noTools.getJSONArray("skills").getJSONObject(0).getBoolean("bodyInjected"))
        assertFalse(noTools.getJSONArray("skills").getJSONObject(0).getBoolean("referencesCanBeRead"))
        assertTrue(SkillRuntimeSnapshot.prompt(listOf(first), toolsSupported = false).contains("当前模型不支持工具调用"))
    }

    @Test fun adultConfirmationChangesTheNextListWithoutMutatingStoredSelection() = runBlocking {
        val adult = first.copy(id = "confirmed-only", adult = true)
        val data = AppData(skills = listOf(second, adult))
        val chat = Chat(skillIds = listOf(adult.id, second.id))
        val runtime = ToolRuntime(Files.createTempDirectory("skill-list-adult").toFile())
        val before = JSONObject(runtime.execute("skill_list", "{}", data.skills, data, chat).content)
        val confirmed = data.copy(settings = data.settings.copy(adultSkillsConfirmed = true))
        val after = JSONObject(runtime.execute("skill_list", "{}", confirmed.skills, confirmed, chat).content)
        assertEquals(listOf(second.id), ids(before))
        assertEquals(listOf(adult.id, second.id), ids(after))
        assertEquals(2, after.getInt("count"))
        assertEquals(listOf(adult.id, second.id), chat.skillIds)
    }

    private fun ids(manifest: JSONObject): List<String> = manifest.getJSONArray("skills").let { skills ->
        (0 until skills.length()).map { skills.getJSONObject(it).getString("id") }
    }
}
