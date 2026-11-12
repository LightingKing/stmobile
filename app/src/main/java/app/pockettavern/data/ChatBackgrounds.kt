package app.pockettavern.data

import app.pockettavern.domain.AppData
import app.pockettavern.domain.ChatLook
import java.io.File

/**
 * Chat backgrounds: three scenes the app draws itself ("builtin:night" and so on, nothing to
 * license or ship) and pictures the user chose, stored under filesDir/backgrounds as JPEG.
 */
object ChatBackgrounds {
    const val DIR = "backgrounds"
    /** Built-in scenes in picker order, with their labels. */
    val BUILTIN = listOf("night" to "夜空", "lamp" to "暖灯", "sea" to "海底")
    private val builtinPattern = Regex("builtin:(night|lamp|sea)")
    private val filePattern = Regex("file:backgrounds/[A-Za-z0-9._-]{1,120}\\.jpg")
    /** Longest side of a stored picture: sharp on a phone, small in backups. */
    const val MAX_SIDE = 1440

    fun normalize(value: String?): String = value.orEmpty().trim().takeIf { builtinPattern.matches(it) || filePattern.matches(it) }.orEmpty()

    fun builtin(value: String): String? = builtinPattern.matchEntire(value)?.groupValues?.get(1)

    /** A look read from storage, cleaned; no usable background means no look at all. */
    fun normalizeLook(look: ChatLook?): ChatLook? {
        if (look == null) return null
        val background = normalize(runCatching { look.background }.getOrNull())
        if (background.isEmpty()) return null
        return ChatLook(background, look.dim.coerceIn(0, ChatLook.MAX_DIM), look.blur.coerceIn(0, ChatLook.MAX_BLUR), look.bubbleClear.coerceIn(0, ChatLook.MAX_CLEAR))
    }

    /** The stored picture of a chosen background, kept inside [filesDir]/backgrounds; null for anything else. */
    fun file(filesDir: File, value: String): File? {
        if (!filePattern.matches(value)) return null
        val root = File(filesDir, DIR).canonicalFile
        val file = File(filesDir, value.removePrefix("file:")).canonicalFile
        return file.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    fun newFileName(now: Long): String = "bg-$now.jpg"

    /** Every background file some chat or character still uses. */
    fun referenced(data: AppData): Set<String> =
        (data.chats.mapNotNull { it.look?.background } + data.characters.mapNotNull { it.look?.background })
            .filter { filePattern.matches(it) }.map { it.removePrefix("file:backgrounds/") }.toSet()

    /**
     * Width a picture is decoded to for a blur level. A small decode stretched over the screen is
     * a soft, even blur on every Android version, and costs less memory than the sharp picture.
     */
    fun decodeWidth(blur: Int): Int = when (blur.coerceIn(0, ChatLook.MAX_BLUR)) {
        0 -> MAX_SIDE
        1 -> 160
        2 -> 72
        else -> 32
    }

    /** The largest power-of-two sample size that keeps the decoded width at or above [target]. */
    fun sampleSize(width: Int, target: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= target.coerceAtLeast(1)) sample *= 2
        return sample
    }
}
