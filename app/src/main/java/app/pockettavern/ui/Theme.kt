package app.pockettavern.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/* Warm paper, forest ink, and a small terracotta accent keep long reading sessions calm. */
private val Forest = Color(0xFF1F5B47)
private val ForestOn = Color(0xFFFFFFFF)
private val ForestContainer = Color(0xFFD5E9DC)
private val ForestOnContainer = Color(0xFF0B392A)
private val Moss = Color(0xFF6C6241)
private val MossOn = Color(0xFFFFFFFF)
private val MossContainer = Color(0xFFEDE4C8)
private val MossOnContainer = Color(0xFF28220B)
private val Clay = Color(0xFF8A4F3D)
private val ClayOn = Color(0xFFFFFFFF)
private val ClayContainer = Color(0xFFFFDBCE)
private val ClayOnContainer = Color(0xFF3A0B03)
private val Paper = Color(0xFFFFFBF4)
private val PaperBright = Color(0xFFFFFDF8)
private val PaperVariant = Color(0xFFF0EDE3)
private val Ink = Color(0xFF1B211D)
private val MutedInk = Color(0xFF626961)
private val Rule = Color(0xFF767B72)
private val RuleSoft = Color(0xFFD0D4CA)
private val Error = Color(0xFFB3261E)
private val ErrorContainer = Color(0xFFFFDAD6)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = ForestOn,
    primaryContainer = ForestContainer,
    onPrimaryContainer = ForestOnContainer,
    inversePrimary = Color(0xFF9AD6B8),
    secondary = Moss,
    onSecondary = MossOn,
    secondaryContainer = MossContainer,
    onSecondaryContainer = MossOnContainer,
    tertiary = Clay,
    onTertiary = ClayOn,
    tertiaryContainer = ClayContainer,
    onTertiaryContainer = ClayOnContainer,
    background = Paper,
    onBackground = Ink,
    surface = PaperBright,
    onSurface = Ink,
    surfaceDim = Color(0xFFE0DDD5),
    surfaceBright = Color(0xFFFFFDF8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF8F2),
    surfaceContainer = Color(0xFFF4F2EA),
    surfaceContainerHigh = Color(0xFFEEEEE6),
    surfaceContainerHighest = Color(0xFFE8E7DF),
    surfaceVariant = PaperVariant,
    onSurfaceVariant = MutedInk,
    surfaceTint = Forest,
    inverseSurface = Color(0xFF30352F),
    inverseOnSurface = Color(0xFFF1F4EC),
    error = Error,
    onError = Color.White,
    errorContainer = ErrorContainer,
    onErrorContainer = Color(0xFF410002),
    outline = Rule,
    outlineVariant = RuleSoft,
    scrim = Color(0xFF000000)
)

private val DarkForest = Color(0xFF9BD7B8)
private val DarkForestOn = Color(0xFF073929)
private val DarkForestContainer = Color(0xFF20513E)
private val DarkForestOnContainer = Color(0xFFB7EBCB)
private val DarkMoss = Color(0xFFD9C994)
private val DarkMossOn = Color(0xFF3B3318)
private val DarkMossContainer = Color(0xFF554B2D)
private val DarkMossOnContainer = Color(0xFFF1E5B9)
private val DarkClay = Color(0xFFFFB59F)
private val DarkClayOn = Color(0xFF541F13)
private val DarkClayContainer = Color(0xFF70382A)
private val DarkClayOnContainer = Color(0xFFFFDBCE)
private val Night = Color(0xFF111712)
private val NightSurface = Color(0xFF171D18)
private val NightVariant = Color(0xFF414941)
private val NightInk = Color(0xFFE1E9E1)
private val NightMutedInk = Color(0xFFB8C4B9)
private val NightRule = Color(0xFF89948A)
private val NightRuleSoft = Color(0xFF414A42)
private val DarkError = Color(0xFFFFB4AB)
private val DarkErrorContainer = Color(0xFF93000A)

private val DarkColors = darkColorScheme(
    primary = DarkForest,
    onPrimary = DarkForestOn,
    primaryContainer = DarkForestContainer,
    onPrimaryContainer = DarkForestOnContainer,
    inversePrimary = Forest,
    secondary = DarkMoss,
    onSecondary = DarkMossOn,
    secondaryContainer = DarkMossContainer,
    onSecondaryContainer = DarkMossOnContainer,
    tertiary = DarkClay,
    onTertiary = DarkClayOn,
    tertiaryContainer = DarkClayContainer,
    onTertiaryContainer = DarkClayOnContainer,
    background = Night,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceDim = Color(0xFF111712),
    surfaceBright = Color(0xFF3B433C),
    surfaceContainerLowest = Color(0xFF0C110D),
    surfaceContainerLow = Color(0xFF171D18),
    surfaceContainer = Color(0xFF1B221C),
    surfaceContainerHigh = Color(0xFF252D26),
    surfaceContainerHighest = Color(0xFF303930),
    surfaceVariant = NightVariant,
    onSurfaceVariant = NightMutedInk,
    surfaceTint = DarkForest,
    inverseSurface = Color(0xFFE1E9E1),
    inverseOnSurface = Color(0xFF2E332E),
    error = DarkError,
    onError = Color(0xFF690005),
    errorContainer = DarkErrorContainer,
    onErrorContainer = Color(0xFFFFDAD6),
    outline = NightRule,
    outlineVariant = NightRuleSoft,
    scrim = Color(0xFF000000)
)

private val PocketTypography = Typography(
    displayLarge = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
)

private val PocketShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun PocketTheme(darkMode: String = "system", content: @Composable () -> Unit) {
    val dark = when (darkMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = PocketTypography,
        shapes = PocketShapes,
        content = content
    )
}
