package app.pockettavern

import android.test.ActivityInstrumentationTestCase2
import app.pockettavern.data.BuiltinSkills
import app.pockettavern.data.DataCodec
import app.pockettavern.domain.AppData
import app.pockettavern.domain.Chat
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.UiSession
import app.pockettavern.provider.ProviderClient
import java.io.Closeable
import java.lang.reflect.Field
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-level check that a selected built-in Skill reaches the request, that the
 * model's skill_read call is executed against the bundled asset, and that the
 * tool result is sent back before the final answer is persisted to Room.
 * The provider is a local two-response fixture; no real account or key is used.
 */
@Suppress("DEPRECATION", "UNCHECKED_CAST")
class SkillInvocationInstrumentedTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    fun testSelectedSkillReferenceIsReadAndSentBack() {
        val app = activity.application as PocketApplication
        val controller = app.controller
        awaitState(controller) { !it.loading }
        if (controller.state.value.busy) { instrumentation.runOnMainSync { controller.stop() }; awaitState(controller) { !it.busy } }

        val originalState = controller.state.value
        val readAsset = { path: String -> app.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }
        val catalog = BuiltinSkills.load(readAsset)
        val selected = catalog.single { it.id == "builtin-writing-tt-sp-roleplay" }.copy(enabled = true)
        val unselected = catalog.single { it.id == "builtin-writing-fv-chat-doctor" }.copy(enabled = true)
        val expectedReference = readAsset("writing-skills/tt-sp-roleplay/example.md")
        val server = SkillFixtureServer(selected.id)
        var previousProviderClient: Any? = null
        var credential = ""
        try {
            server.start()
            val provider = ProviderConfig(id = "instrumentation-skill", name = "本地 Skill Fixture", kind = "venice", baseUrl = server.baseUrl,
                models = listOf(ModelInfo(id = "fixture-tools", name = "Fixture", type = "text", contextTokens = 200_000, supportsTools = true)))
            val chat = Chat(id = "instrumentation-skill-chat", title = "Skill 调用验收", providerId = provider.id, modelId = "fixture-tools",
                presetId = originalState.data.presets.first().id, skillIds = listOf(selected.id))
            credential = credentialId(provider)
            app.secrets.put(credential, "sk-instrumentation-skill-fixture")
            val skills = originalState.data.skills.filterNot { it.id == selected.id || it.id == unselected.id } + selected + unselected
            installState(controller, originalState.copy(
                data = originalState.data.copy(providers = listOf(provider) + originalState.data.providers, chats = listOf(chat) + originalState.data.chats, skills = skills),
                selectedChatId = chat.id, loading = false, busy = false, hasKeys = originalState.hasKeys + provider.id, status = ""
            ))
            previousProviderClient = replaceProviderClient(controller, ProviderClient(allowInsecureHttpForTests = true, timeoutMillis = 5_000L))

            var accepted = false
            instrumentation.runOnMainSync { accepted = controller.send("照 Skill 示例开始") }
            assertTrue(accepted)
            awaitState(controller) { !it.busy }
            assertEquals("controller must not report an error: " + controller.state.value.status, 2, server.bodies.size)

            val document = runBlocking { app.database.state().load() }
            val persisted = DataCodec.gson.fromJson(document!!.json, AppData::class.java)
            val messages = persisted.chats.first { it.id == chat.id }.messages
            assertEquals(listOf("user", "assistant", "tool", "assistant"), messages.map { it.role })
            assertTrue(messages[1].toolCallsJson.contains("skill_read"))
            assertEquals("call_skill", messages[2].toolCallId)
            assertEquals(expectedReference, messages[2].content)
            assertEquals("按示例开始。", messages[3].content)
            assertEquals("complete", messages[3].status)

            val first = server.bodies[0]
            val tools = JSONObject(first).getJSONArray("tools")
            assertEquals("skill_read", tools.getJSONObject(0).getJSONObject("function").getString("name"))
            assertTrue(first.contains("skillId=${selected.id}"))
            assertFalse(first.contains(unselected.id))
            val sent = JSONObject(server.bodies[1]).getJSONArray("messages")
            val tool = (0 until sent.length()).map { sent.getJSONObject(it) }.single { it.optString("role") == "tool" }
            assertEquals(expectedReference, tool.getString("content"))
        } finally {
            if (controller.state.value.busy) { instrumentation.runOnMainSync { controller.stop() }; awaitState(controller) { !it.busy } }
            if (credential.isNotBlank()) runCatching { app.secrets.delete(credential) }
            previousProviderClient?.let { runCatching { replaceProviderClient(controller, it) } }
            installState(controller, originalState.copy(busy = false))
            instrumentation.runOnMainSync { controller.checkpoint() }
            awaitPersisted(app, originalState.data)
            server.close()
        }
    }

    private fun awaitState(controller: AppController, predicate: (UiSession) -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000L
        while (!predicate(controller.state.value) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("timed out waiting for controller state", predicate(controller.state.value))
    }

    private fun awaitPersisted(app: PocketApplication, expected: AppData) {
        val deadline = System.currentTimeMillis() + 5_000L
        var actual: AppData? = null
        while (System.currentTimeMillis() < deadline) {
            actual = runBlocking { app.database.state().load()?.let { DataCodec.decode(it.json) } }
            if (actual == expected) return
            Thread.sleep(100)
        }
        assertEquals("controller state must be flushed after fixture cleanup", expected, actual)
    }

    private fun installState(controller: AppController, state: UiSession) {
        val field = declaredField(AppController::class.java, "mutable")
        instrumentation.runOnMainSync { (field.get(controller) as MutableStateFlow<UiSession>).value = state }
    }

    private fun replaceProviderClient(controller: AppController, replacement: Any): Any? {
        val field = declaredField(AppController::class.java, "providerClient")
        var previous: Any? = null
        instrumentation.runOnMainSync { previous = field.get(controller); field.set(controller, replacement) }
        return previous
    }

    private fun declaredField(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply { isAccessible = true }

    private fun credentialId(provider: ProviderConfig): String = MessageDigest.getInstance("SHA-256")
        .digest((provider.id + "|" + provider.kind + "|" + provider.baseUrl.trim().trimEnd('/')).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** Answers the first request with a skill_read call and the second with final text. */
    private class SkillFixtureServer(private val skillId: String) : Closeable {
        private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        private var thread: Thread? = null
        val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val baseUrl: String get() = "http://127.0.0.1:" + server.localPort + "/api/v1"

        fun start() {
            thread = Thread {
                try {
                    repeat(2) { index ->
                        server.accept().use { socket ->
                            bodies += readBody(socket)
                            respond(socket, if (index == 0) toolCallResponse() else finalResponse())
                        }
                    }
                } catch (_: Exception) {
                    // Closing the fixture while its accept loop is idle is expected.
                }
            }.apply { name = "pocket-tavern-skill-fixture"; isDaemon = true; start() }
        }

        private fun toolCallResponse(): String {
            val arguments = JSONObject().put("skillId", skillId).put("path", "example.md").toString()
            val call = JSONObject().put("id", "call_skill").put("type", "function")
                .put("function", JSONObject().put("name", "skill_read").put("arguments", arguments))
            return JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(call)))
                .put("finish_reason", "tool_calls"))).toString()
        }

        private fun finalResponse(): String = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"按示例开始。\"},\"finish_reason\":\"stop\"}]}"

        private fun readBody(socket: Socket): String {
            val input = socket.getInputStream()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next < 0) return ""
                header.append(next.toChar())
            }
            val length = header.lines().firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(body, read, length - read)
                if (count < 0) break
                read += count
            }
            return String(body, 0, read, Charsets.UTF_8)
        }

        private fun respond(socket: Socket, json: String) {
            val body = json.toByteArray(Charsets.UTF_8)
            val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nx-request-id: skill-fixture\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(headers.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
        }

        override fun close() {
            runCatching { server.close() }
            thread?.join(1_000L)
        }
    }
}
