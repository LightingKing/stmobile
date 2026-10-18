package app.pockettavern.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Forest = Color(0xFF235C4B)
private val ForestDark = Color(0xFF8ED6B9)
private val Ivory = Color(0xFFFFFBF4)
private val Ink = Color(0xFF1C211E)
private val Sage = Color(0xFFE4F1EA)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Sage,
    onPrimaryContainer = Color(0xFF073B2B),
    secondary = Color(0xFF6D5F33),
    background = Ivory,
    surface = Color(0xFFFFFCF8),
    onBackground = Ink,
    onSurface = Ink,
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = ForestDark,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF154D3B),
    onPrimaryContainer = Color(0xFFA8F2D0),
    secondary = Color(0xFFE3C98A),
    background = Color(0xFF111512),
    surface = Color(0xFF171C19),
    onBackground = Color(0xFFE6EEE8),
    onSurface = Color(0xFFE6EEE8),
    error = Color(0xFFFFB4AB)
)

@Composable
fun PocketTheme(darkMode: String = "system", content: @Composable () -> Unit) {
    val dark = when (darkMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
