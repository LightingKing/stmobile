package app.pockettavern

import android.test.ActivityInstrumentationTestCase2
import android.os.Build
import app.pockettavern.data.DataCodec
import app.pockettavern.data.RequestLogStore
import app.pockettavern.domain.Chat
import app.pockettavern.domain.AppData
import app.pockettavern.domain.Message
import app.pockettavern.domain.ModelInfo
import app.pockettavern.domain.ProviderConfig
import app.pockettavern.domain.UiSession
import app.pockettavern.provider.ProviderClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.File
import java.lang.reflect.Field
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * Device-level acceptance checks for persisted request diagnostics.
 *
 * The provider and HTTP endpoint are local fixtures. No real account, prompt,
 * or API key is used. This class is intentionally kept separate from the JVM
 * tests because it exercises the application-owned controller and Room state.
 */
@Suppress("DEPRECATION", "UNCHECKED_CAST")
class RequestDiagnosticsTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    fun testEmptyResponsePersistsStructuredFailureAndSafeDiagnostics() {
        val screen = activity
        val app = screen.application as PocketApplication
        val controller = app.controller
        awaitReady(controller)
        stopIfBusy(controller)

        val originalState = controller.state.value
        val existingChats = originalState.data.chats.associateBy { it.id }
        val fixtureKey = "sk-instrumentation-fixture-key"
        val fixtureProvider = ProviderConfig(
            id = "instrumentation-venice",
            name = "本地 Venice Fixture",
            kind = "venice",
            baseUrl = "http://127.0.0.1:0/api/v1",
            models = listOf(ModelInfo(id = "fixture-model", name = "Fixture", type = "text")),
        )
        val fixtureChat = Chat(
            id = "instrumentation-chat",
            title = "本地诊断验收",
            providerId = fixtureProvider.id,
            modelId = "fixture-model",
            presetId = originalState.data.presets.first().id,
            messages = listOf(Message(role = "user", content = "fixture prompt")),
        )
        val server = EmptyResponseServer()
        var previousProviderClient: Any? = null
        val previousHost = controller.host
        val diagnosticsFile = File(app.filesDir, "diagnostics/request-diagnostics.jsonl")
        val diagnosticsTempFile = File(app.filesDir, "diagnostics/request-diagnostics.jsonl.tmp")
        val diagnosticsBefore = diagnosticsFile.takeIf { it.isFile }?.readBytes()
        try {
            server.start()
            val provider = fixtureProvider.copy(baseUrl = server.baseUrl)
            val chat = fixtureChat.copy(providerId = provider.id)
            val credential = credentialId(provider)
            app.secrets.put(credential, fixtureKey)
            installState(
                controller,
                originalState.copy(
                    data = originalState.data.copy(
                        providers = listOf(provider) + originalState.data.providers,
                        chats = listOf(chat) + originalState.data.chats,
                    ),
                    selectedChatId = chat.id,
                    loading = false,
                    busy = false,
                    hasKeys = originalState.hasKeys + provider.id,
                    status = "",
                    diagnostics = "",
                ),
            )
            val testLogs = RequestLogStore(
                File(app.filesDir, "diagnostics"),
                installedVersion(app),
                Build.VERSION.SDK_INT,
            )
            previousProviderClient = replaceProviderClient(controller, ProviderClient(
                allowInsecureHttpForTests = true,
                timeoutMillis = 2_000L,
                onDiagnostic = { event -> testLogs.record(JSONObject(event).put("purpose", "chat").toString()) },
            ))

            var accepted = false
            instrumentation.runOnMainSync { accepted = controller.send("fixture prompt") }
            assertTrue("fixture message must enter the controller generation path", accepted)
            awaitState(controller) { !it.busy }

            val document = runBlocking { app.database.state().load() }
            assertNotNull("failed generation must be persisted", document)
            // This local fixture intentionally uses HTTP; production backup
            // validation remains HTTPS-only, so inspect the persisted model
            // without invoking DataCodec.decode's import policy here.
            val persisted = DataCodec.gson.fromJson(document!!.json, AppData::class.java)
            val saved = persisted.chats.first { it.id == chat.id }
            val assistant = saved.messages.last { it.role == "assistant" }
            assertEquals("error", assistant.status)
            assertTrue("empty response must retain an actionable error", assistant.errorMessage.isNotBlank())
            assertEquals("length", assistant.finishReason)
            assertEquals("fixture-request-id", assistant.requestId)

            instrumentation.runOnMainSync { controller.showDiagnostics() }
            awaitState(controller) { it.diagnostics.isNotBlank() }
            val summary = controller.state.value.diagnostics
            assertTrue(summary.contains("版本 "))
            assertFalse(summary.contains("fixture prompt"))
            assertFalse(summary.contains(fixtureKey))

            val exported = AtomicReference("")
            instrumentation.runOnMainSync { controller.host = object : NativeHost {
                override fun exportText(name: String, mime: String, content: String) {
                    exported.set(content)
                }
                override fun importFile(kind: String) = Unit
                override fun exportBinary(name: String, mime: String, file: File) = Unit
                override fun pickAttachment() = Unit
                override fun openFile(file: File, mime: String) = Unit
            } }
            instrumentation.runOnMainSync { controller.exportFile("diagnostics", "") }
            awaitUntil { exported.get().isNotBlank() }
            assertTrue(exported.get().contains("app_version"))
            assertFalse(exported.get().contains("fixture prompt"))
            assertFalse(exported.get().contains(fixtureKey))

            val beforeBillingCheck = controller.state.value.data.chats.filter { it.id in existingChats }
            instrumentation.runOnMainSync { controller.checkBilling() }
            awaitState(controller) { !it.busy && it.diagnostics.contains("官方 Venice") }
            assertEquals(
                "existing chats must not be touched by diagnostics or billing",
                beforeBillingCheck,
                controller.state.value.data.chats.filter { it.id in existingChats },
            )
        } finally {
            stopIfBusy(controller)
            val credential = credentialId(fixtureProvider.copy(baseUrl = server.baseUrl))
            runCatching { app.secrets.delete(credential) }
            previousProviderClient?.let { runCatching { replaceProviderClient(controller, it) } }
            instrumentation.runOnMainSync { controller.host = previousHost }
            installState(controller, originalState.copy(busy = false, diagnostics = ""))
            instrumentation.runOnMainSync { controller.checkpoint() }
            awaitPersistedData(app, originalState.data)
            restoreDiagnosticsFile(diagnosticsFile, diagnosticsTempFile, diagnosticsBefore)
            server.close()
        }
    }

    private fun awaitReady(controller: AppController) {
        awaitState(controller) { !it.loading }
        assertFalse("controller must not be in a failed load state", controller.state.value.status.startsWith("读取本地数据失败"))
    }

    private fun stopIfBusy(controller: AppController) {
        if (!controller.state.value.busy) return
        instrumentation.runOnMainSync { controller.stop() }
        awaitState(controller) { !it.busy }
    }

    private fun awaitState(controller: AppController, predicate: (UiSession) -> Boolean) {
        awaitUntil { predicate(controller.state.value) }
        assertTrue("timed out waiting for controller state", predicate(controller.state.value))
    }

    private fun awaitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000L
        while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun installState(controller: AppController, state: UiSession) {
        val field = declaredField(AppController::class.java, "mutable")
        instrumentation.runOnMainSync {
            (field.get(controller) as MutableStateFlow<UiSession>).value = state
        }
    }

    private fun replaceProviderClient(controller: AppController, replacement: Any): Any? {
        val field = declaredField(AppController::class.java, "providerClient")
        var previous: Any? = null
        instrumentation.runOnMainSync {
            previous = field.get(controller)
            field.set(controller, replacement)
        }
        return previous
    }

    private fun installedVersion(app: PocketApplication): String {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return info.versionName + " (" + code + ")"
    }

    private fun awaitPersistedData(app: PocketApplication, expected: app.pockettavern.domain.AppData) {
        val deadline = System.currentTimeMillis() + 5_000L
        var actual: app.pockettavern.domain.AppData? = null
        while (System.currentTimeMillis() < deadline) {
            actual = runBlocking { app.database.state().load()?.let { DataCodec.decode(it.json) } }
            if (actual == expected) return
            Thread.sleep(100)
        }
        assertEquals("controller state must be flushed after fixture cleanup", expected, actual)
    }

    private fun restoreDiagnosticsFile(file: File, temporary: File, bytes: ByteArray?) {
        temporary.delete()
        if (bytes == null) file.delete() else {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    private fun declaredField(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply { isAccessible = true }

    private fun credentialId(provider: ProviderConfig): String = MessageDigest.getInstance("SHA-256")
        .digest((provider.id + "|" + provider.kind + "|" + provider.baseUrl.trim().trimEnd('/')).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private class EmptyResponseServer : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private var thread: Thread? = null
        val baseUrl: String get() = "http://127.0.0.1:" + server.localPort + "/api/v1"

        fun start() {
            thread = Thread {
                try {
                    server.accept().use { socket ->
                        readRequest(socket)
                        val body = "{\"choices\":[{\"message\":{\"content\":null},\"finish_reason\":\"length\"}]}".toByteArray(Charsets.UTF_8)
                        val headers = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: application/json\r\n")
                            append("Content-Length: ").append(body.size).append("\r\n")
                            append("x-request-id: fixture-request-id\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        socket.getOutputStream().use { output ->
                            output.write(headers.toByteArray(Charsets.ISO_8859_1))
                            output.write(body)
                            output.flush()
                        }
                    }
                } catch (_: Exception) {
                    // Closing the fixture while its accept loop is idle is expected.
                }
            }.apply {
                name = "pocket-tavern-diagnostics-fixture"
                isDaemon = true
                start()
            }
        }

        private fun readRequest(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            repeat(contentLength) { reader.read() }
        }

        override fun close() {
            runCatching { server.close() }
            thread?.join(1_000L)
        }
    }
}
