package app.pockettavern

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.test.ActivityInstrumentationTestCase2
import app.pockettavern.data.DataCodec
import app.pockettavern.domain.Memory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Method

/**
 * Lifecycle-only checks for the user-visible foreground generation path.
 *
 * The action body is a local suspended gate; no provider, API key, or network
 * request is used. The test requires an Android emulator/device and is not part
 * of the regular JVM test suite.
 */
@Suppress("DEPRECATION", "UNCHECKED_CAST")
class BackgroundGenerationTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    fun testGenerationSurvivesFinishedActivityAndFlushesState() {
        val first = activity
        val app = first.application as PocketApplication
        val controller = app.controller
        awaitReady(controller)
        grantNotificationsIfNeeded()
        stopIfBusy(controller)

        val marker = "instrumentation-background-${System.currentTimeMillis()}"
        val memory = Memory(text = marker, scope = "global")
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        var reopened: MainActivity? = null
        val body: suspend () -> Unit = {
            release.await()
            controller.saveMemory(memory)
            completed.complete(Unit)
        }

        try {
            invokeBackgroundAction(controller, body)
            awaitState { it.busy }
            awaitNotification()
            assertTrue("CPU must stay awake while the request streams with the screen off", GenerationService.holdsWakeLock())

            instrumentation.runOnMainSync {
                first.moveTaskToBack(true)
                first.finish()
            }
            awaitActivityDestroyed(first)

            reopened = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            assertSame("controller must be application-owned", controller, (reopened!!.application as PocketApplication).controller)
            assertTrue("generation must remain busy after Activity recreation", controller.state.value.busy)

            release.complete(Unit)
            awaitState { !it.busy }
            assertTrue("test generation body must complete", completed.isCompleted)
            awaitNotificationGone()
            assertFalse("wake lock must be released with the foreground service", GenerationService.holdsWakeLock())

            val saved = runBlocking { app.database.state().load() }
            assertNotNull("final state must be persisted", saved)
            val restored = DataCodec.decode(saved!!.json)
            assertTrue("completed generation memory must be in Room", restored.memories.any { it.id == memory.id && it.text == marker })
            assertTrue("foreground notification must be removed after completion", activeGenerationNotification() == null)
        } finally {
            release.complete(Unit)
            awaitStateOrStop(controller)
            instrumentation.runOnMainSync { controller.deleteMemory(memory.id); controller.checkpoint() }
            Thread.sleep(700)
            reopened?.let { screen -> if (!screen.isFinishing) instrumentation.runOnMainSync { screen.finish() } }
            if (!first.isFinishing) instrumentation.runOnMainSync { first.finish() }
        }
    }

    fun testOldStopPendingIntentCannotStopNewGeneration() {
        val screen = activity
        val app = screen.application as PocketApplication
        val controller = app.controller
        awaitReady(controller)
        grantNotificationsIfNeeded()
        stopIfBusy(controller)

        val oldRelease = CompletableDeferred<Unit>()
        val oldDone = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        val newDone = CompletableDeferred<Unit>()
        val firstBody: suspend () -> Unit = { oldRelease.await(); oldDone.complete(Unit) }
        val secondBody: suspend () -> Unit = { newRelease.await(); newDone.complete(Unit) }

        try {
            invokeBackgroundAction(controller, firstBody)
            awaitState { it.busy }
            val oldNotification = awaitNotification()
            val oldStop = oldNotification.actions.firstOrNull()?.actionIntent
            assertNotNull("generation notification must expose a stop action", oldStop)

            oldRelease.complete(Unit)
            awaitState { !it.busy }
            assertTrue("first test generation body must complete", oldDone.isCompleted)
            awaitNotificationGone()

            invokeBackgroundAction(controller, secondBody)
            awaitState { it.busy }
            val newStop = awaitNotification().actions.firstOrNull()?.actionIntent
            assertNotNull("new generation notification must expose a stop action", newStop)

            oldStop!!.send()
            Thread.sleep(300)
            assertTrue("stale stop action must not cancel a newer generation", controller.state.value.busy)

            newStop!!.send()
            awaitState { !it.busy }
            assertFalse("current notification stop must cancel the active generation", newDone.isCompleted)
            awaitNotificationGone()
        } finally {
            oldRelease.complete(Unit)
            newRelease.complete(Unit)
            awaitStateOrStop(controller)
        }
    }

    private fun invokeBackgroundAction(controller: AppController, body: suspend () -> Unit) {
        val method: Method = AppController::class.java.getDeclaredMethod(
            "action",
            Boolean::class.javaPrimitiveType,
            Function0::class.java,
            Function2::class.java,
            Function1::class.java,
            Function1::class.java
        ).apply { isAccessible = true }
        val onStarted: () -> Unit = {}
        val onFinished: (String?, Boolean) -> Unit = { _, _ -> }
        instrumentation.runOnMainSync { method.invoke(controller, true, onStarted, onFinished, null, body) }
    }

    private fun awaitReady(controller: AppController) {
        awaitState { !it.loading }
        assertFalse("controller must not be in a failed load state", controller.state.value.status.startsWith("读取本地数据失败"))
    }

    private fun stopIfBusy(controller: AppController) {
        if (!controller.state.value.busy) return
        instrumentation.runOnMainSync { controller.stop() }
        awaitState { !it.busy }
    }

    private fun awaitState(predicate: (app.pockettavern.domain.UiSession) -> Boolean) {
        val controller = (activity.application as PocketApplication).controller
        val deadline = System.currentTimeMillis() + 15_000
        while (!predicate(controller.state.value) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("timed out waiting for controller state", predicate(controller.state.value))
    }

    private fun awaitStateOrStop(controller: AppController) {
        if (controller.state.value.busy) instrumentation.runOnMainSync { controller.stop() }
        val deadline = System.currentTimeMillis() + 5_000
        while (controller.state.value.busy && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun awaitActivityDestroyed(activity: MainActivity) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!activity.isDestroyed && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("finished Activity must be destroyed", activity.isDestroyed)
    }

    private fun grantNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation
                .executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun awaitNotification(): Notification {
        val deadline = System.currentTimeMillis() + 10_000
        var notification: Notification? = null
        while (notification == null && System.currentTimeMillis() < deadline) {
            notification = activeGenerationNotification()
            if (notification == null) Thread.sleep(50)
        }
        assertNotNull("foreground generation notification must be visible", notification)
        assertTrue("notification must belong to an actual foreground service", notification!!.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        return notification!!
    }

    private fun awaitNotificationGone() {
        val deadline = System.currentTimeMillis() + 10_000
        while (activeGenerationNotification() != null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertNull("foreground generation notification must be removed", activeGenerationNotification())
    }

    private fun activeGenerationNotification(): Notification? {
        val manager = instrumentation.targetContext.getSystemService(NotificationManager::class.java) ?: return null
        return manager.activeNotifications.firstOrNull { it.id == 7401 }?.notification
    }
}
