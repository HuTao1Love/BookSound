package com.zyagodin.booksound.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zyagodin.booksound.R

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Modern geometric sans with Cyrillic support, used for everything. */
val Manrope = FontFamily(
    variable(R.font.manrope, 400),
    variable(R.font.manrope, 500),
    variable(R.font.manrope, 600),
    variable(R.font.manrope, 700),
    variable(R.font.manrope, 800),
)

private fun style(weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) =
    TextStyle(fontFamily = Manrope, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em)

// Headlines are heavy and tightly tracked; body text stays airy for readability.
val BookSoundTypography = Typography(
    displayLarge = style(FontWeight.ExtraBold, 48, 54, -0.035),
    displayMedium = style(FontWeight.ExtraBold, 40, 46, -0.03),
    displaySmall = style(FontWeight.ExtraBold, 34, 40, -0.03),
    headlineLarge = style(FontWeight.ExtraBold, 32, 38, -0.03),
    headlineMedium = style(FontWeight.ExtraBold, 27, 33, -0.025),
    headlineSmall = style(FontWeight.Bold, 23, 29, -0.02),
    titleLarge = style(FontWeight.Bold, 20, 26, -0.015),
    titleMedium = style(FontWeight.Bold, 16, 22, -0.01),
    titleSmall = style(FontWeight.Bold, 14, 20, -0.005),
    bodyLarge = style(FontWeight.Medium, 16, 24),
    bodyMedium = style(FontWeight.Medium, 14, 20),
    bodySmall = style(FontWeight.Medium, 12, 16, 0.005),
    labelLarge = style(FontWeight.Bold, 15, 20, 0.0),
    labelMedium = style(FontWeight.SemiBold, 13, 16, 0.01),
    labelSmall = style(FontWeight.Bold, 11, 14, 0.06),
)
