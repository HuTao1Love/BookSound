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

// "Midnight": near-black graphite surfaces, a warm orange→coral accent, mint and violet helpers.
private val Amber = Color(0xFFFF9548)
private val Coral = Color(0xFFFF5E62)
private val AmberDeep = Color(0xFFE5641E)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1C0D02),
    primaryContainer = Color(0xFF3B2416),
    onPrimaryContainer = Color(0xFFFFD6BA),
    secondary = Color(0xFF6EE7C8),
    onSecondary = Color(0xFF00382D),
    secondaryContainer = Color(0xFF16352E),
    onSecondaryContainer = Color(0xFFB5F5E3),
    tertiary = Color(0xFFAFA2FF),
    onTertiary = Color(0xFF1E1452),
    tertiaryContainer = Color(0xFF2B2550),
    onTertiaryContainer = Color(0xFFE2DCFF),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF3D0707),
    errorContainer = Color(0xFF3A1518),
    onErrorContainer = Color(0xFFFFD6D6),
    background = Color(0xFF0A0B0F),
    onBackground = Color(0xFFF2F2F5),
    surface = Color(0xFF0A0B0F),
    onSurface = Color(0xFFF2F2F5),
    surfaceVariant = Color(0xFF1E2029),
    onSurfaceVariant = Color(0xFFA0A3B1),
    surfaceTint = Amber,
    surfaceBright = Color(0xFF2E3140),
    surfaceDim = Color(0xFF0A0B0F),
    surfaceContainerLowest = Color(0xFF060709),
    surfaceContainerLow = Color(0xFF111217),
    surfaceContainer = Color(0xFF16181F),
    surfaceContainerHigh = Color(0xFF1E2029),
    surfaceContainerHighest = Color(0xFF282B36),
    outline = Color(0xFF5C6070),
    outlineVariant = Color(0xFF2A2D38),
    inverseSurface = Color(0xFFF2F2F5),
    inverseOnSurface = Color(0xFF16181F),
    inversePrimary = AmberDeep,
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
    surfaceVariant = Color(0xFF17181D),
    surfaceBright = Color(0xFF26282F),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF07080A),
    surfaceContainer = Color(0xFF0D0E11),
    surfaceContainerHigh = Color(0xFF15161A),
    surfaceContainerHighest = Color(0xFF1E2025),
    outlineVariant = Color(0xFF22242B),
    inverseOnSurface = Color.Black,
)

private val LightColors = lightColorScheme(
    primary = AmberDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3D1),
    onPrimaryContainer = Color(0xFF3A1600),
    secondary = Color(0xFF0E8A6E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDF3E7),
    onSecondaryContainer = Color(0xFF00382D),
    tertiary = Color(0xFF5B4BD6),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE4DFFF),
    onTertiaryContainer = Color(0xFF1E1452),
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFFE0E0),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6F6F9),
    onBackground = Color(0xFF111217),
    surface = Color(0xFFF6F6F9),
    onSurface = Color(0xFF111217),
    surfaceVariant = Color(0xFFE6E7EE),
    onSurfaceVariant = Color(0xFF5B5F6E),
    surfaceTint = AmberDeep,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDDDEE5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F1F5),
    surfaceContainer = Color(0xFFEBECF1),
    surfaceContainerHigh = Color(0xFFE5E6EC),
    surfaceContainerHighest = Color(0xFFDDDEE6),
    outline = Color(0xFF8B8F9E),
    outlineVariant = Color(0xFFD5D7E0),
    inverseSurface = Color(0xFF1E2029),
    inverseOnSurface = Color(0xFFF2F2F5),
    inversePrimary = Amber,
    scrim = Color.Black,
)

/** Brand gradients used for the play button, primary actions and progress. */
@Immutable
data class AppGradients(val accent: Brush, val accentColors: List<Color>)

private val Gradients = AppGradients(
    accent = Brush.linearGradient(listOf(Amber, Coral)),
    accentColors = listOf(Amber, Coral),
)

val LocalGradients = staticCompositionLocalOf { Gradients }

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
    CompositionLocalProvider(LocalDarkTheme provides dark, LocalGradients provides Gradients) {
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
