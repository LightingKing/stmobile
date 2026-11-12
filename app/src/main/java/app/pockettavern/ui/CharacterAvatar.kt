package app.pockettavern.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A round character picture; while it loads, or when there is none, the name's first character. */
@Composable
internal fun CharacterAvatar(avatar: String, name: String, size: Dp, load: (String) -> ImageBitmap?, modifier: Modifier = Modifier) {
    val picture by produceState<ImageBitmap?>(null, avatar) {
        value = if (avatar.isBlank()) null else withContext(Dispatchers.IO) { runCatching { load(avatar) }.getOrNull() }
    }
    val shape = modifier.size(size).clip(CircleShape)
    val image = picture
    if (image != null) {
        Image(image, "$name 的头像", shape, contentScale = ContentScale.Crop)
    } else {
        Box(shape.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            // The letter fills its circle at every font size: a picture does not grow with the text.
            Text(name.trim().take(1).ifBlank { "?" }, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f / androidx.compose.ui.platform.LocalDensity.current.fontScale).sp)
        }
    }
}
