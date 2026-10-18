package app.pockettavern

import android.test.ActivityInstrumentationTestCase2
import androidx.lifecycle.ViewModelProvider
import app.pockettavern.domain.*
import app.pockettavern.data.*
import kotlinx.coroutines.runBlocking

@Suppress("DEPRECATION")
class AppSmokeTest: ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    fun testLaunchChatPersistenceAndKeystore() {
        val screen=activity
        val controller=ViewModelProvider(screen)[AppController::class.java]
        val deadline=System.currentTimeMillis()+15000
        while(controller.state.value.loading && System.currentTimeMillis()<deadline) Thread.sleep(100)
        assertFalse("Database load must complete",controller.state.value.loading)
        instrumentation.runOnMainSync { controller.createChat(); controller.saveMemory(Memory(text="仪器测试专用记忆",targetId=controller.state.value.selectedChatId)) }
        instrumentation.waitForIdleSync()
        assertEquals(1,controller.state.value.data.memories.count { it.text=="仪器测试专用记忆" })
        val id=controller.state.value.selectedChatId
        assertTrue(controller.state.value.data.chats.any { it.id==id })
        Thread.sleep(600)
        val app=screen.application as PocketApplication
        val restored=runBlocking { app.database.state().load() }
        assertNotNull(restored)
        assertTrue(DataCodec.decode(restored!!.json).chats.any { it.id==id })
        val secretId="instrumentation-verification"
        app.secrets.put(secretId,"test-only-not-a-real-key")
        assertEquals("test-only-not-a-real-key",app.secrets.get(secretId))
        app.secrets.delete(secretId)
        assertFalse(app.secrets.has(secretId))
        instrumentation.runOnMainSync { controller.deleteChat(id) }
    }
}
