package app.pockettavern.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The user's own photos, video and text files for the character drafter. Photos are turned upright
 * and shrunk, a video gives a few still frames (its sound is not used), and text files are read as
 * UTF-8. Everything stays on the phone until the user asks for a draft; nothing is kept on disk.
 */
object DraftMedia {
    const val MAX_PHOTOS = 6
    const val MAX_VIDEOS = 1
    const val MAX_VIDEO_MILLIS = 120_000L
    const val MAX_FRAMES = 8
    const val FRAME_EVERY_MILLIS = 15_000L
    const val MAX_TEXT_CHARS = 20_000

    private const val MAX_PHOTO_SOURCE_BYTES = 30L * 1024 * 1024
    private const val MAX_VIDEO_SOURCE_BYTES = 100L * 1024 * 1024
    private const val MAX_TEXT_SOURCE_BYTES = 2L * 1024 * 1024
    private const val MAX_SOURCE_PIXELS = 60_000_000L
    private const val PHOTO_SIDE = 1_280
    private const val FRAME_SIDE = 1_024
    private const val MAX_PICTURE_BYTES = 700 * 1024

    private val photoExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "gif")
    private val videoExtensions = setOf("mp4", "mov", "m4v", "webm", "mkv", "3gp")
    private val textExtensions = setOf("txt", "md", "markdown", "json", "csv", "log")

    enum class Kind { PHOTO, VIDEO, TEXT, OTHER }

    /** One prepared piece: a picture (JPEG bytes) or a text. */
    class Piece(val name: String, val kind: Kind, val jpeg: ByteArray? = null, val text: String = "")

    class Prepared(val pieces: List<Piece>, val notes: List<String>)

    /** What a file is, from its type or, failing that, its name. */
    fun kindOf(mime: String, name: String): Kind {
        val type = mime.lowercase(Locale.ROOT)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            type.startsWith("image/") || extension in photoExtensions -> Kind.PHOTO
            type.startsWith("video/") || extension in videoExtensions -> Kind.VIDEO
            type.startsWith("text/") || type == "application/json" || extension in textExtensions -> Kind.TEXT
            else -> Kind.OTHER
        }
    }

    /** Evenly spaced frame times: one per 15 seconds, at least one, at most [MAX_FRAMES]. */
    fun frameTimes(durationMillis: Long): List<Long> {
        if (durationMillis <= 0) return listOf(0L)
        val count = min(MAX_FRAMES, max(1, ceil(durationMillis / FRAME_EVERY_MILLIS.toDouble()).toInt()))
        if (count == 1) return listOf(durationMillis / 2)
        return (0 until count).map { index -> (durationMillis - 1) * index / (count - 1) }
    }

    /** A text file's content, or null when it is not readable text. */
    fun textOf(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        // Mostly replacement or control characters means it was not UTF-8 text.
        val bad = text.count { it == '\uFFFD' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') }
        return if (bad > text.length / 20) null else text.replace("\r\n", "\n").trim()
    }

    /**
     * Prepares [uris] within what is left of the limits: [photos] photos and [videos] videos already
     * added, [textChars] characters of text already added. What does not fit is skipped with a note.
     */
    suspend fun prepare(context: Context, uris: List<Uri>, photos: Int, videos: Int, textChars: Int): Prepared = withContext(Dispatchers.IO) {
        val pieces = mutableListOf<Piece>()
        val notes = mutableListOf<String>()
        var photoCount = photos
        var videoCount = videos
        var chars = textChars
        for (uri in uris) {
            coroutineContext.ensureActive()
            val name = displayName(context, uri)
            val kind = kindOf(context.contentResolver.getType(uri).orEmpty(), name)
            try {
                when (kind) {
                    Kind.PHOTO -> {
                        if (photoCount >= MAX_PHOTOS) { notes += "照片最多 $MAX_PHOTOS 张，“$name”没有加入"; continue }
                        pieces += Piece(name, Kind.PHOTO, jpeg = photo(context, uri))
                        photoCount++
                    }
                    Kind.VIDEO -> {
                        if (videoCount >= MAX_VIDEOS) { notes += "视频最多 $MAX_VIDEOS 段，“$name”没有加入"; continue }
                        val frames = video(context, uri)
                        frames.forEach { (seconds, jpeg) -> pieces += Piece("$name · 第 $seconds 秒", Kind.VIDEO, jpeg = jpeg) }
                        notes += "“$name”取了 ${frames.size} 帧画面；视频里的声音没有用上"
                        videoCount++
                    }
                    Kind.TEXT -> {
                        val text = textOf(readBounded(context, uri, MAX_TEXT_SOURCE_BYTES)) ?: run { notes += "“$name”不是可读的文字文件"; null } ?: continue
                        val room = MAX_TEXT_CHARS - chars
                        if (room <= 0) { notes += "文字资料最多约 ${MAX_TEXT_CHARS / 10000} 万字，“$name”没有加入"; continue }
                        if (text.length > room) notes += "“$name”太长，只用了前 $room 字"
                        pieces += Piece(name, Kind.TEXT, text = text.take(room))
                        chars += min(text.length, room)
                    }
                    Kind.OTHER -> notes += "“$name”不是照片、视频或文字文件"
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                notes += "“$name”没能读取：${error.message?.take(80) ?: "格式不支持"}"
            }
        }
        Prepared(pieces, notes)
    }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }.orEmpty()
    }.getOrDefault("").ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/').ifBlank { "文件" } }

    private fun readBounded(context: Context, uri: Uri, maxBytes: Long): ByteArray =
        (context.contentResolver.openInputStream(uri) ?: error("无法读取")).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes) { "超过 ${maxBytes / 1024 / 1024} MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    private fun copyToCache(context: Context, uri: Uri, maxBytes: Long, suffix: String): File {
        val file = File.createTempFile("draft-media-", suffix, context.cacheDir)
        try {
            (context.contentResolver.openInputStream(uri) ?: error("无法读取")).use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "超过 ${maxBytes / 1024 / 1024} MB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            return file
        } catch (error: Throwable) { file.delete(); throw error }
    }

    /** A photo, turned upright from its EXIF data, at most [PHOTO_SIDE] on its long side. */
    private fun photo(context: Context, uri: Uri): ByteArray {
        val file = copyToCache(context, uri, MAX_PHOTO_SOURCE_BYTES, ".img")
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式无法读取" }
            require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS) { "图片像素过高" }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_SIDE) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("图片解码失败")
            val degrees = runCatching {
                when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }.getOrDefault(0f)
            val upright = if (degrees == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
            return jpeg(upright, PHOTO_SIDE)
        } finally { file.delete() }
    }

    /** Frames of a video of at most two minutes, each with the second it was taken at. */
    private fun video(context: Context, uri: Uri): List<Pair<Long, ByteArray>> {
        val file = copyToCache(context, uri, MAX_VIDEO_SOURCE_BYTES, ".video")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("读不到视频时长")
            require(duration <= MAX_VIDEO_MILLIS) { "视频要在 ${MAX_VIDEO_MILLIS / 60_000} 分钟以内" }
            val frames = frameTimes(duration).mapNotNull { millis ->
                retriever.getFrameAtTime(millis * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame -> millis / 1000 to jpeg(frame, FRAME_SIDE) }
            }
            require(frames.isNotEmpty()) { "视频里取不到画面" }
            return frames
        } finally {
            runCatching { retriever.release() }
            file.delete()
        }
    }

    /** A JPEG of at most [side] pixels on the long side and about [MAX_PICTURE_BYTES]. */
    private fun jpeg(source: Bitmap, side: Int): ByteArray {
        val scale = min(1f, side.toFloat() / max(source.width, source.height))
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(source, max(1, (source.width * scale).roundToInt()), max(1, (source.height * scale).roundToInt()), true) else source
        var quality = 88
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_PICTURE_BYTES || quality <= 50) return out.toByteArray()
            quality -= 8
        }
    }
}
