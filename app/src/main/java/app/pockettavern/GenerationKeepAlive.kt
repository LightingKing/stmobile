package app.pockettavern

import android.content.Context
import android.os.PowerManager

/**
 * A foreground service keeps the process alive but does not keep the CPU awake;
 * with the screen off the socket read stalls until the provider drops the stream.
 * One partial wake lock covers exactly one generation and always expires.
 */
internal class GenerationKeepAlive(private val create: () -> Lock?) {
    interface Lock {
        val isHeld: Boolean
        fun acquire(timeoutMs: Long)
        fun release()
    }

    private var lock: Lock? = null

    val held: Boolean get() = lock?.isHeld == true

    fun hold(): Boolean {
        if (held) return true
        lock = runCatching { create()?.also { it.acquire(TIMEOUT_MS) } }.getOrNull()
        return held
    }

    fun release() {
        lock?.let { current -> if (current.isHeld) runCatching { current.release() } }
        lock = null
    }

    companion object {
        const val TIMEOUT_MS = 30 * 60_000L

        fun forContext(context: Context): GenerationKeepAlive = GenerationKeepAlive {
            context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketTavern:generation")
                ?.apply { setReferenceCounted(false) }
                ?.let { wakeLock ->
                    object : Lock {
                        override val isHeld get() = wakeLock.isHeld
                        override fun acquire(timeoutMs: Long) = wakeLock.acquire(timeoutMs)
                        override fun release() = wakeLock.release()
                    }
                }
        }
    }
}
