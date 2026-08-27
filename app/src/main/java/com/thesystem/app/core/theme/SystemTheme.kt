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

// ── DESIGN TOKENS 2.0 — "QUIET POWER" ────────────────────────────────────────
// Philosophy: ONE accent, rest neutral. Color means something or it isn't used.
// No neon rainbow, no glow chrome, generous whitespace, rows over cards.

val VoidBlack = Color(0xFF0A0B0E)          // true neutral near-black (no blue tint)
val SurfaceDark = Color(0xFF101318)        // raised surface, flat
val SurfaceHigh = Color(0xFF151920)        // overlay / inputs
val ElectricBlue = Color(0xFF38BDF8)       // THE accent — smooth sky (Solo Leveling window blue, refined)
val NeonPurple = Color(0xFF818CF8)         // soft indigo — rare: boss arcs, gradient partner only
val CrimsonRed = Color(0xFFFB7185)         // soft rose — danger/rival only (never decoration)
val HunterGold = Color(0xFFF5C26B)         // champagne — VC currency + premium rewards only
val VenomGreen = Color(0xFF34D399)         // mint — success states only
val WarningAmber = Color(0xFFFBBF24)       // standard amber — timers/warnings only
val TextPrimary = Color(0xFFF1F5F9)        // slate-100
val TextMuted = Color(0xFF94A3B8)          // slate-400
val GridLine = Color(0x14FFFFFF)           // 8% white hairline — the ONLY border tone

private val SystemColors = darkColorScheme(
    background = VoidBlack,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextMuted,
    primary = ElectricBlue,
    onPrimary = Color(0xFF06202E),
    secondary = NeonPurple,
    onSecondary = Color(0xFF0A0B0E),
    tertiary = CrimsonRed,
    onTertiary = Color(0xFF0A0B0E),
    error = CrimsonRed,
    onError = Color(0xFF0A0B0E),
    outline = GridLine,
)

private val SystemTypography = Typography(
    displayMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = 0.sp, color = TextPrimary),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.2.sp, color = TextPrimary),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = 0.1.sp, color = TextPrimary),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 0.1.sp, color = TextPrimary),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.sp, color = TextPrimary),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = TextMuted),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.6.sp, color = TextMuted),
)

@Composable
fun SystemTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SystemColors, typography = SystemTypography, content = content)
}
