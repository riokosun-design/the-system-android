package com.thesystem.app.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── GLOBAL DESIGN TOKENS (Section 1 of the spec) ────────────────────────────
val VoidBlack = Color(0xFF0B0E14)          // premium dark-mode base
val SurfaceDark = Color(0xFF11151F)
val SurfaceHigh = Color(0xFF181E2C)
val ElectricBlue = Color(0xFF00F0FF)       // primary accent
val NeonPurple = Color(0xFF9D00FF)         // secondary accent
val CrimsonRed = Color(0xFFFF0055)         // danger / rival / penalty accent
val HunterGold = Color(0xFFFFD700)         // S+ rewards, MASTERPIECE
val VenomGreen = Color(0xFF00FF9D)         // success / buffs
val WarningAmber = Color(0xFFFFB020)
val TextPrimary = Color(0xFFEAF6FF)
val TextMuted = Color(0xFF7A8499)
val GridLine = Color(0x2200F0FF)

private val SystemColors = darkColorScheme(
    background = VoidBlack,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextMuted,
    primary = ElectricBlue,
    onPrimary = Color(0xFF02141A),
    secondary = NeonPurple,
    onSecondary = Color(0xFFFFFFFF),
    tertiary = CrimsonRed,
    onTertiary = Color(0xFFFFFFFF),
    error = CrimsonRed,
    onError = Color(0xFFFFFFFF),
    outline = GridLine,
)

private val SystemTypography = Typography(
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = 4.sp, color = TextPrimary),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = 2.sp, color = TextPrimary),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 19.sp, letterSpacing = 1.2.sp, color = TextPrimary),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.8.sp, color = TextPrimary),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.sp, color = TextPrimary),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = TextMuted),
    labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.5.sp, color = TextMuted),
)

@Composable
fun SystemTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SystemColors, typography = SystemTypography, content = content)
}
