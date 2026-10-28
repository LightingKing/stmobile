package app.pockettavern.research

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Files prepared locally for the Doubao web research flow. Nothing is uploaded here. */
data class PreparedResearchMedia(
    val files: List<File>,
    val description: String
)

/**
 * Converts user-selected images and videos into bounded files that the web flow can attach.
 *
 * Doubao's ordinary upload control accepts images and audio, but not a video file. A video is
 * therefore represented by a small set of timestamped frames and, when Android can mux it,
 * an m4a audio track. The description deliberately describes only this local preprocessing;
 * callers must not claim that the entire video or audio was inspected.
 */
object ResearchMedia {
    private const val MAX_IMAGES = 4
    private const val MAX_IMAGE_SOURCE_BYTES = 20L * 1024 * 1024
    private const val MAX_VIDEO_SOURCE_BYTES = 100L * 1024 * 1024
    private const val MAX_AUDIO_BYTES = 20L * 1024 * 1024
    private const val MAX_OUTPUT_BYTES = 40L * 1024 * 1024
    private const val MAX_IMAGE_OUTPUT_BYTES = 4L * 1024 * 1024
    private const val MAX_VIDEO_FRAME_OUTPUT_BYTES = 2L * 1024 * 1024
    private const val MAX_SOURCE_PIXELS = 40_000_000L
    private const val MAX_SIDE = 1_600
    private const val MAX_VIDEO_FRAME_SIDE = 1_280
    private const val MAX_VIDEO_MILLIS = 120_000L
    private const val MAX_VIDEO_FRAMES = 8
    private const val COPY_BUFFER = 64 * 1024

    private val requestIdPattern = Regex("[A-Za-z0-9_-]{1,80}")
    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
    private val videoExtensions = setOf("mp4", "mov", "m4v", "webm", "mkv", "3gp")

    /** Prepares the selected files under a request-scoped, private cache directory. */
    suspend fun prepare(
        context: Context,
        requestId: String,
        uris: List<Uri>
    ): PreparedResearchMedia = withContext(Dispatchers.IO) {
        requireValidRequestId(requestId)
        if (uris.isEmpty()) return@withContext PreparedResearchMedia(emptyList(), "未选择图片或视频。")
        require(uris.size <= MAX_IMAGES) { "最多选择 $MAX_IMAGES 张图片；视频只能选择一个文件" }

        val directory = requireNotNull(requestDirectory(context, requestId, create = true))
        try {
            val kinds = uris.map { mediaKind(context, it) }
            require(kinds.none { it == MediaKind.UNKNOWN }) { "只能选择图片或视频文件" }
            require(kinds.count { it == MediaKind.VIDEO } <= 1) { "视频只能选择一个文件" }
            require(kinds.none { it == MediaKind.VIDEO } || uris.size == 1) { "视频不能与图片同时选择" }

            val prepared = if (kinds.singleOrNull() == MediaKind.VIDEO) {
                prepareVideo(context, uris.single(), directory)
            } else {
                prepareImages(context, uris, directory)
            }
            coroutineContext.ensureActive()
            val bytes = prepared.files.sumOf { it.length() }
            require(bytes < MAX_OUTPUT_BYTES) { "处理后的媒体文件超过 40 MB，请减少图片或缩短视频" }
            prepared
        } catch (error: Throwable) {
            cleanupDirectory(directory, context.cacheDir)
            throw error
        }
    }

    /** Removes only the exact request-owned cache directory. */
    fun cleanup(context: Context, requestId: String) {
        requireValidRequestId(requestId)
        val directory = requestDirectory(context, requestId, create = false) ?: return
        cleanupDirectory(directory, context.cacheDir)
    }

    internal fun requireValidRequestId(requestId: String) {
        require(requestIdPattern.matches(requestId)) { "媒体请求 ID 无效" }
    }

    private enum class MediaKind { IMAGE, VIDEO, UNKNOWN }

    private suspend fun prepareImages(context: Context, uris: List<Uri>, directory: File): PreparedResearchMedia {
        val files = mutableListOf<File>()
        val details = mutableListOf<String>()
        for ((index, uri) in uris.withIndex()) {
            coroutineContext.ensureActive()
            val source = File(directory, "source-$index.bin")
            try {
                copyUri(context, uri, source, MAX_IMAGE_SOURCE_BYTES)
                val output = File(directory, "image-${(index + 1).toString().padStart(2, '0')}.jpg")
                val bitmap = decodeImage(source)
                try {
                    writeBitmapBounded(bitmap, output, MAX_IMAGE_OUTPUT_BYTES, MAX_SIDE)
                } finally {
                    bitmap.recycle()
                }
                files += output
                details += "图片 ${index + 1} 已缩放至不超过 ${MAX_SIDE}px 并转为 JPEG"
            } finally {
                source.delete()
            }
        }
        return PreparedResearchMedia(files, details.joinToString("；") + "。仅处理了用户选择的图片，没有推断不可见或私密属性。")
    }

    private suspend fun prepareVideo(context: Context, uri: Uri, directory: File): PreparedResearchMedia {
        val source = File(directory, "video-source.bin")
        copyUri(context, uri, source, MAX_VIDEO_SOURCE_BYTES)
        val files = mutableListOf<File>()
        val details = mutableListOf<String>()
        var durationMillis = 0L
        var audioDescription = "未附带音轨"
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            durationMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("无法读取视频时长")
            require(durationMillis in 1..MAX_VIDEO_MILLIS) { "视频时长需在 120 秒以内" }
            val frameCount = min(MAX_VIDEO_FRAMES, max(1, ceil(durationMillis / 15_000.0).roundToInt()))
            val timestamps = (0 until frameCount).map { index ->
                if (frameCount == 1) 0L else (durationMillis - 1L) * index / (frameCount - 1)
            }
            for ((index, millis) in timestamps.withIndex()) {
                coroutineContext.ensureActive()
                val frame = retriever.getFrameAtTime(millis * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                try {
                    val output = File(directory, "frame-${(index + 1).toString().padStart(2, '0')}-${millis / 1000}s.jpg")
                    writeBitmapBounded(frame, output, MAX_VIDEO_FRAME_OUTPUT_BYTES, MAX_VIDEO_FRAME_SIDE)
                    files += output
                } finally {
                    frame.recycle()
                }
            }
            require(files.isNotEmpty()) { "视频无法提取可用画面" }
            details += "视频时长 ${durationMillis / 1000.0}s；抽取 ${files.size} 个时间点画面：${timestamps.take(files.size).joinToString(", ") { "${it / 1000.0}s" }}"
        } finally {
            retriever.release()
        }
        try {
            val audio = extractAudio(source, File(directory, "audio.m4a"))
            if (audio != null) {
                files += audio
                audioDescription = "已附带可读取的 m4a 音轨"
            }
        } catch (error: Exception) {
            audioDescription = "音轨未附带：本机提取失败（${error.message?.take(120) ?: "格式不兼容"}）"
        } finally {
            source.delete()
        }
        details += audioDescription
        details += "仅抽样画面${if (files.any { it.name.endsWith(".m4a") }) "并附带音轨文件" else ""}，没有声称已完整观看视频或听取全部音频"
        return PreparedResearchMedia(files, details.joinToString("；") + "。")
    }

    private fun mediaKind(context: Context, uri: Uri): MediaKind {
        val mime = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT).orEmpty()
        val name = displayName(context, uri)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            mime.startsWith("image/") || extension in imageExtensions -> MediaKind.IMAGE
            mime.startsWith("video/") || extension in videoExtensions -> MediaKind.VIDEO
            else -> MediaKind.UNKNOWN
        }
    }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault(uri.lastPathSegment.orEmpty())

    private suspend fun copyUri(context: Context, uri: Uri, target: File, maxBytes: Long) {
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        input.use { stream ->
            BufferedInputStream(stream).use { buffered ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = buffered.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "所选文件超过 ${maxBytes / 1024 / 1024} MB 限制" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
    }

    private fun decodeImage(source: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式无法读取" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS) { "图片像素过高，无法安全处理" }
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeFile(source.absolutePath, options) ?: error("图片解码失败")
    }

    private fun writeBitmapBounded(bitmap: Bitmap, target: File, maxBytes: Long, maxSide: Int) {
        var current = bitmap
        try {
            if (max(current.width, current.height) > maxSide) {
                val scale = maxSide.toFloat() / max(current.width, current.height)
                current = Bitmap.createScaledBitmap(current, max(1, (current.width * scale).roundToInt()), max(1, (current.height * scale).roundToInt()), true)
            }
            var quality = 92
            var factor = 1f
            while (true) {
                target.delete()
                FileOutputStream(target).use { output -> current.compress(Bitmap.CompressFormat.JPEG, quality, output) }
                if (target.length() in 1..maxBytes) return
                if (quality > 40) {
                    quality -= 8
                    continue
                }
                factor *= 0.8f
                if (factor < 0.25f) break
                val next = Bitmap.createScaledBitmap(current, max(1, (current.width * 0.8f).roundToInt()), max(1, (current.height * 0.8f).roundToInt()), true)
                if (current !== bitmap) current.recycle()
                current = next
                quality = 82
            }
            throw IllegalArgumentException("图片压缩后仍超过 ${maxBytes / 1024 / 1024} MB")
        } finally {
            target.takeIf { it.exists() && it.length() > maxBytes }?.delete()
            if (current !== bitmap) current.recycle()
        }
    }

    private suspend fun extractAudio(source: File, target: File): File? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        return try {
            extractor.setDataSource(source.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(index)
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(extractor.getTrackFormat(index))
            muxer.start(); started = true
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                coroutineContext.ensureActive()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                require(target.length() < MAX_AUDIO_BYTES) { "音轨超过 20 MB" }
                info.offset = 0; info.size = size; info.presentationTimeUs = extractor.sampleTime
                // MediaExtractor and MediaCodec use different flag sets.  Passing
                // sampleFlags through directly is both semantically wrong for the
                // muxer and rejected by Android lint (WrongConstant).  Preserve the
                // flags that have an equivalent MediaCodec representation.
                info.flags = extractor.sampleFlags.toMuxerBufferFlags()
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop(); started = false
            require(target.length() <= MAX_AUDIO_BYTES) { "音轨超过 20 MB" }
            target
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
            if (target.exists() && target.length() > MAX_AUDIO_BYTES) target.delete()
        }
    }

    private fun Int.toMuxerBufferFlags(): Int {
        var result = 0
        if (this and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            result = result or android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (this and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            result = result or android.media.MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return result
    }

    private fun requestDirectory(context: Context, requestId: String, create: Boolean): File? {
        requireValidRequestId(requestId)
        val root = File(context.cacheDir, "research-media")
        if (create) root.mkdirs()
        val rootCanonical = root.canonicalFile
        val directory = File(rootCanonical, requestId).canonicalFile
        require(directory.parentFile == rootCanonical) { "媒体缓存路径无效" }
        if (!create && !directory.exists()) return null
        if (create) directory.mkdirs()
        return directory
    }

    private fun cleanupDirectory(directory: File, cacheRoot: File) {
        val researchRoot = File(cacheRoot, "research-media").canonicalFile
        val target = directory.canonicalFile
        require(target.parentFile == researchRoot) { "拒绝清理非媒体请求目录" }
        if (!target.exists()) return
        target.listFiles()?.forEach { child -> deleteOwnedChild(child, target) }
        target.delete()
    }

    private fun deleteOwnedChild(child: File, parent: File) {
        val canonical = child.canonicalFile
        require(canonical.parentFile == parent.canonicalFile) { "拒绝清理媒体目录之外的文件" }
        if (canonical.isDirectory) canonical.listFiles()?.forEach { nested -> deleteOwnedChild(nested, canonical) }
        canonical.delete()
    }
}
