package app.pockettavern.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pockettavern.data.ChatBackgrounds
import app.pockettavern.domain.ChatLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The picture behind a chat's messages, toned down by the chat's dim and blur settings. */
@Composable
internal fun ChatBackdrop(look: ChatLook, load: (String, Int) -> ImageBitmap?, modifier: Modifier = Modifier) {
    Box(modifier) {
        val scene = ChatBackgrounds.builtin(look.background)
        if (scene != null) {
            // Drawn scenes are blurred by the platform where it can (Android 12+); they are soft anyway.
            val blur = if (look.blur > 0 && Build.VERSION.SDK_INT >= 31) Modifier.blur((look.blur * 6).dp) else Modifier
            BuiltinScene(scene, Modifier.matchParentSize().then(blur))
        } else {
            val inspection = LocalInspectionMode.current
            val picture by produceState<ImageBitmap?>(null, look.background, look.blur) {
                if (!inspection) value = withContext(Dispatchers.IO) { runCatching { load(look.background, look.blur) }.getOrNull() }
            }
            picture?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High) }
        }
        if (look.dim > 0) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = look.dim / 100f)))
    }
}

@Composable
internal fun BuiltinScene(id: String, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawScene(id) }
}

/** Three quiet scenes, all dark enough that message cards stand out on them. */
internal fun DrawScope.drawScene(id: String) {
    val w = size.width
    val h = size.height
    val small = minOf(w, h)
    val random = java.util.Random(id.hashCode().toLong())
    when (id) {
        "night" -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF130F26), Color(0xFF231839), Color(0xFF3B2652))))
            repeat(90) {
                val x = random.nextFloat() * w
                val y = random.nextFloat() * h * 0.85f
                drawCircle(Color.White.copy(alpha = 0.25f + random.nextFloat() * 0.55f), (0.5f + random.nextFloat() * 1.3f) * density, Offset(x, y))
            }
            val moon = Offset(w * 0.78f, h * 0.15f)
            val radius = small * 0.12f
            drawCircle(Brush.radialGradient(listOf(Color(0x40F3E6B8), Color.Transparent), center = moon, radius = radius * 3.2f), radius * 3.2f, moon)
            drawCircle(Color(0xFFEFE2B4), radius, moon)
            drawCircle(Color(0x22000000), radius * 0.28f, Offset(moon.x - radius * 0.3f, moon.y + radius * 0.2f))
        }
        "lamp" -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF2A1A10), Color(0xFF4A2E1A), Color(0xFF24160D))))
            val lamp = Offset(w * 0.28f, h * 0.22f)
            drawCircle(Brush.radialGradient(listOf(Color(0xAAE9B26A), Color(0x33C9874A), Color.Transparent), center = lamp, radius = small * 0.9f), small * 0.9f, lamp)
            drawCircle(Color(0xFFF6D59A), small * 0.07f, lamp)
            // Out-of-focus lights.
            repeat(14) {
                val c = Offset(random.nextFloat() * w, h * (0.45f + random.nextFloat() * 0.5f))
                drawCircle(Color(0xFFE8B070).copy(alpha = 0.08f + random.nextFloat() * 0.12f), small * (0.04f + random.nextFloat() * 0.08f), c)
            }
        }
        else -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0B5A7A), Color(0xFF07364F), Color(0xFF031A28))))
            // Light from the surface.
            repeat(5) { index ->
                val top = w * (0.1f + index * 0.2f)
                val path = Path().apply {
                    moveTo(top, 0f); lineTo(top + w * 0.08f, 0f)
                    lineTo(top + w * 0.25f, h); lineTo(top + w * 0.05f, h); close()
                }
                drawPath(path, Color.White.copy(alpha = 0.035f + index % 2 * 0.02f))
            }
            repeat(26) {
                val c = Offset(random.nextFloat() * w, h * (0.3f + random.nextFloat() * 0.7f))
                val r = (2f + random.nextFloat() * 6f) * density
                drawCircle(Color.White.copy(alpha = 0.12f + random.nextFloat() * 0.15f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f * density))
            }
        }
    }
}

/** One choice in the background picker: a small preview with its name. */
@Composable
internal fun BackdropOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, preview: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier.size(width = 64.dp, height = 92.dp), shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant), color = colors.surfaceContainer) {
        Box {
            preview()
            Text(label, Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).padding(vertical = 3.dp),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
}
