package app.pockettavern.ui

import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.Sticker
import app.pockettavern.engine.StickerMarkup
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded sticker thumbnails, so scrolling a chat does not decode the same file again. */
private object StickerBitmapCache {
    val cache = LruCache<String, ImageBitmap>(64)
}

/** Downsamples to about [maxPx] on the long side; stickers never need full resolution. */
internal fun decodeStickerFile(file: File, maxPx: Int = 384): ImageBitmap? {
    val key = "${file.path}:${file.lastModified()}:$maxPx"
    StickerBitmapCache.cache.get(key)?.let { return it }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return bitmap.asImageBitmap().also { StickerBitmapCache.cache.put(key, it) }
}

/** True for a GIF, or a WebP whose VP8X header sets the animation flag; PNG and JPEG never move. */
internal fun mayAnimate(head: ByteArray): Boolean {
    fun ascii(from: Int, text: String) = head.size >= from + text.length && text.indices.all { head[from + it] == text[it].code.toByte() }
    if (ascii(0, "GIF8")) return true
    return ascii(0, "RIFF") && ascii(8, "WEBP") && ascii(12, "VP8X") && head.size > 20 && head[20].toInt() and 0x02 != 0
}

/** The decode size for an animation: its long side shrunk to [maxPx], never enlarged. */
internal fun animatedTargetSize(width: Int, height: Int, maxPx: Int): Pair<Int, Int> {
    val long = maxOf(width, height)
    if (long <= maxPx || width <= 0 || height <= 0) return width to height
    val scale = maxPx.toFloat() / long
    return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
}

/**
 * An animated GIF or WebP as a playing drawable, or null when the file is still, the system is older
 * than Android 9, or the user turned animations off; callers then show the first frame instead.
 */
internal fun decodeAnimatedSticker(file: File, maxPx: Int = 384): Drawable? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !ValueAnimator.areAnimatorsEnabled()) return null
    val head = ByteArray(32)
    val read = file.inputStream().use { it.read(head) }
    if (read <= 0 || !mayAnimate(head.copyOf(read))) return null
    return decodeAnimation(file, maxPx)
}

@RequiresApi(Build.VERSION_CODES.P)
private fun decodeAnimation(file: File, maxPx: Int): Drawable? {
    val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val (width, height) = animatedTargetSize(info.size.width, info.size.height, maxPx)
        decoder.setTargetSize(width, height)
    }
    // A single-frame GIF decodes to a plain bitmap, which the still path already shows.
    return (drawable as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE }
}

/**
 * Draws a playing drawable in Compose: it runs while on screen and stops when it leaves; every
 * frame the drawable asks for redraws the painter.
 */
private class AnimatedStickerPainter(private val drawable: Drawable) : Painter(), RememberObserver {
    private var frame by mutableIntStateOf(0)
    private val main = Handler(Looper.getMainLooper())
    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) { frame++ }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { main.postAtTime(what, `when`) }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) { main.removeCallbacks(what) }
    }

    override val intrinsicSize: Size get() = Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

    override fun DrawScope.onDraw() {
        frame // read so each new frame redraws
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }

    override fun onRemembered() {
        drawable.callback = callback
        (drawable as? Animatable)?.start()
    }

    override fun onForgotten() = stop()
    override fun onAbandoned() = stop()

    private fun stop() {
        (drawable as? Animatable)?.stop()
        drawable.callback = null
        main.removeCallbacksAndMessages(null)
    }
}

private sealed interface StickerPicture {
    class Still(val bitmap: ImageBitmap) : StickerPicture
    class Moving(val drawable: Drawable) : StickerPicture
}

/**
 * Loads off the main thread; [load] is injectable so previews can supply a bitmap directly. When
 * [loadAnimated] gives a playing drawable it is shown instead of the still first frame.
 */
@Composable
internal fun StickerImage(sticker: Sticker, load: (Sticker) -> ImageBitmap?, modifier: Modifier = Modifier, size: Dp = 120.dp,
                          loadAnimated: (Sticker) -> Drawable? = { null }) {
    // Previews render a single frame, so they load synchronously; the app decodes off the main thread.
    val inspection = LocalInspectionMode.current
    val picture by produceState<StickerPicture?>(if (inspection) runCatching { load(sticker) }.getOrNull()?.let { StickerPicture.Still(it) } else null, sticker.file) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { loadAnimated(sticker) }.getOrNull()?.let { StickerPicture.Moving(it) }
                ?: runCatching { load(sticker) }.getOrNull()?.let { StickerPicture.Still(it) }
        }
    }
    val framed = modifier.size(size).clip(RoundedCornerShape(12.dp))
    when (val shown = picture) {
        is StickerPicture.Moving -> Image(remember(shown) { AnimatedStickerPainter(shown.drawable) }, "表情：${sticker.key}", framed, contentScale = ContentScale.Fit)
        is StickerPicture.Still -> Image(shown.bitmap, "表情：${sticker.key}", framed, contentScale = ContentScale.Fit)
        null -> Surface(modifier.size(size).semantics { contentDescription = "表情：${sticker.key}" }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(contentAlignment = Alignment.Center) { Text(sticker.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * Assistant text with its sticker: the marker is removed from the prose and the
 * first known sticker is drawn under it. Half-written markers stay hidden while streaming.
 */
@Composable
internal fun AssistantTextWithSticker(content: String, streaming: Boolean, stickers: List<Sticker>, load: (Sticker) -> ImageBitmap?, modifier: Modifier = Modifier,
                                      loadAnimated: (Sticker) -> Drawable? = { null }) {
    val shown = if (streaming) StickerMarkup.hidePartial(content) else content
    val parsed = StickerMarkup.parse(shown)
    val sticker = parsed.names.firstNotNullOfOrNull { StickerMarkup.resolve(it, stickers) }
    Column(modifier) {
        if (parsed.text.isNotBlank()) ReadingMarkdown(parsed.text)
        if (sticker != null) StickerImage(sticker, load, Modifier.padding(top = 8.dp), loadAnimated = loadAnimated)
    }
}
