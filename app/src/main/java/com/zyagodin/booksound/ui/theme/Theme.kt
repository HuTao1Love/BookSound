package com.zyagodin.booksound.ui.theme

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.data.settings.ThemeMode

// "Dusk": ink-blue surfaces with a faint violet tint, a soft apricot→rose accent, sage and
// lavender helpers. The light scheme is warm paper with a terracotta accent.
private val Apricot = Color(0xFFFFB27D)
private val Rose = Color(0xFFF47C93)
private val Terracotta = Color(0xFFC65A33)
private val RoseDeep = Color(0xFFC0466B)

private val DarkColors = darkColorScheme(
    primary = Apricot,
    onPrimary = Color(0xFF2E1407),
    primaryContainer = Color(0xFF3A2A24),
    onPrimaryContainer = Color(0xFFFFDCC7),
    secondary = Color(0xFF8ED8BF),
    onSecondary = Color(0xFF00382C),
    secondaryContainer = Color(0xFF1B3430),
    onSecondaryContainer = Color(0xFFC2EFE0),
    tertiary = Color(0xFFBBB0FF),
    onTertiary = Color(0xFF221A55),
    tertiaryContainer = Color(0xFF2D2A4E),
    onTertiaryContainer = Color(0xFFE4DFFF),
    error = Color(0xFFFF8F8F),
    onError = Color(0xFF3D0707),
    errorContainer = Color(0xFF3B1A20),
    onErrorContainer = Color(0xFFFFD9D9),
    background = Color(0xFF0F1117),
    onBackground = Color(0xFFECEDF3),
    surface = Color(0xFF0F1117),
    onSurface = Color(0xFFECEDF3),
    surfaceVariant = Color(0xFF21242F),
    onSurfaceVariant = Color(0xFF9FA3B6),
    surfaceTint = Apricot,
    surfaceBright = Color(0xFF323646),
    surfaceDim = Color(0xFF0F1117),
    surfaceContainerLowest = Color(0xFF0A0B10),
    surfaceContainerLow = Color(0xFF151720),
    surfaceContainer = Color(0xFF1A1C26),
    surfaceContainerHigh = Color(0xFF21242F),
    surfaceContainerHighest = Color(0xFF2A2D3A),
    outline = Color(0xFF5E6377),
    outlineVariant = Color(0xFF2C2F3C),
    inverseSurface = Color(0xFFECEDF3),
    inverseOnSurface = Color(0xFF1A1C26),
    inversePrimary = Terracotta,
    scrim = Color.Black,
)

/**
 * AMOLED: the dark scheme on pure black. Pixels that are off use no power and blend into the
 * screen bezel; cards and sheets keep just enough lift to stay distinguishable.
 */
private val AmoledColors = DarkColors.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceVariant = Color(0xFF17181E),
    surfaceBright = Color(0xFF272930),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF07080B),
    surfaceContainer = Color(0xFF0D0E12),
    surfaceContainerHigh = Color(0xFF15161B),
    surfaceContainerHighest = Color(0xFF1E2026),
    outlineVariant = Color(0xFF22242C),
    inverseOnSurface = Color.Black,
)

private val LightColors = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2D3),
    onPrimaryContainer = Color(0xFF3D1404),
    secondary = Color(0xFF2C7D66),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2EEE3),
    onSecondaryContainer = Color(0xFF00382C),
    tertiary = Color(0xFF6352C4),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE7E1FF),
    onTertiaryContainer = Color(0xFF221A55),
    error = Color(0xFFC62F3A),
    onError = Color.White,
    errorContainer = Color(0xFFFFE0DF),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFAF7F3),
    onBackground = Color(0xFF1E1B18),
    surface = Color(0xFFFAF7F3),
    onSurface = Color(0xFF1E1B18),
    surfaceVariant = Color(0xFFEDE7E0),
    onSurfaceVariant = Color(0xFF6A625A),
    surfaceTint = Terracotta,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE2DBD2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F0EA),
    surfaceContainer = Color(0xFFF0EAE3),
    surfaceContainerHigh = Color(0xFFEBE4DC),
    surfaceContainerHighest = Color(0xFFE4DCD3),
    outline = Color(0xFF9A9087),
    outlineVariant = Color(0xFFDDD4CA),
    inverseSurface = Color(0xFF2A2724),
    inverseOnSurface = Color(0xFFF5F0EA),
    inversePrimary = Apricot,
    scrim = Color.Black,
)

/** Brand gradients used for the play button, primary actions and progress. */
@Immutable
data class AppGradients(val accent: Brush, val accentColors: List<Color>)

private fun gradientOf(from: Color, to: Color) = AppGradients(Brush.linearGradient(listOf(from, to)), listOf(from, to))

/** Light accents carry dark content; the deeper light-theme accents carry white (see onPrimary). */
private val DarkGradients = gradientOf(Apricot, Rose)
private val LightGradients = gradientOf(Terracotta, RoseDeep)

val LocalGradients = staticCompositionLocalOf { DarkGradients }

val BookSoundShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Spacing scale (4-pt grid). */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Horizontal screen padding. */
    val screen = 20.dp
}

/** Shapes beyond the Material scale. */
object Radii {
    val cover = RoundedCornerShape(16.dp)
    val coverLarge = RoundedCornerShape(28.dp)
    val card = RoundedCornerShape(24.dp)
    val sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val pill = RoundedCornerShape(percent = 50)
}

val LocalDarkTheme = staticCompositionLocalOf { true }

@Composable
fun BookSoundTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val activity = LocalContext.current as? Activity
    LaunchedEffect(dark, activity) {
        // System bar icons follow the app theme (which may differ from the system setting).
        (activity as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(Color.Transparent.toArgb())
            else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb()),
            navigationBarStyle = if (dark) SystemBarStyle.dark(Color.Transparent.toArgb())
            else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb()),
        )
    }
    CompositionLocalProvider(LocalDarkTheme provides dark, LocalGradients provides if (dark) DarkGradients else LightGradients) {
        MaterialTheme(
            colorScheme = when {
                !dark -> LightColors
                amoledBlack -> AmoledColors
                else -> DarkColors
            },
            typography = BookSoundTypography,
            shapes = BookSoundShapes,
            content = content,
        )
    }
}
