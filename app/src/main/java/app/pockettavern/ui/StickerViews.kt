package app.pockettavern.ui

import android.graphics.BitmapFactory
import android.util.LruCache
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pockettavern.domain.Sticker
import app.pockettavern.engine.StickerMarkup
import java.io.File
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

/** Loads off the main thread; [load] is injectable so previews can supply a bitmap directly. */
@Composable
internal fun StickerImage(sticker: Sticker, load: (Sticker) -> ImageBitmap?, modifier: Modifier = Modifier, size: Dp = 120.dp) {
    // Previews render a single frame, so they load synchronously; the app decodes off the main thread.
    val inspection = LocalInspectionMode.current
    val bitmap by produceState(if (inspection) runCatching { load(sticker) }.getOrNull() else null, sticker.file) {
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { load(sticker) }.getOrNull() }
    }
    val image = bitmap
    if (image != null) {
        Image(image, "表情：${sticker.key}", modifier.size(size).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
    } else {
        Surface(modifier.size(size).semantics { contentDescription = "表情：${sticker.key}" }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(contentAlignment = Alignment.Center) { Text(sticker.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * Assistant text with its sticker: the marker is removed from the prose and the
 * first known sticker is drawn under it. Half-written markers stay hidden while streaming.
 */
@Composable
internal fun AssistantTextWithSticker(content: String, streaming: Boolean, stickers: List<Sticker>, load: (Sticker) -> ImageBitmap?, modifier: Modifier = Modifier) {
    val shown = if (streaming) StickerMarkup.hidePartial(content) else content
    val parsed = StickerMarkup.parse(shown)
    val sticker = parsed.names.firstNotNullOfOrNull { StickerMarkup.resolve(it, stickers) }
    Column(modifier) {
        if (parsed.text.isNotBlank()) ReadingMarkdown(parsed.text)
        if (sticker != null) StickerImage(sticker, load, Modifier.padding(top = 8.dp))
    }
}
