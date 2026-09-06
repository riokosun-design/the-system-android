package com.thesystem.app.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── DESIGN TOKENS 2.6 — "QUIET POWER" + PRINCIPAL ARCHITECT GRID ────────────
// Philosophy: ONE accent, rest neutral. Color means something or it isn't used.
// 4dp baseline grid, 8/16/24 modules, 20dp screen margin. One font family.

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
val GridLine = Color(0x14FFFFFF)           // 8% white hairline — panels
val FloatingSurface = Color(0xCC0E121B)    // 80% navy — contrast-safe floaters over art/maps
val Hairline = Color(0x1AFFFFFF)           // 10% white — floating container border

/** THE GRID — every screen snaps here. No floating offsets, ever. */
object Grid {
    val S4 = 4.dp
    val S8 = 8.dp
    val S12 = 12.dp
    val S16 = 16.dp
    val S20 = 20.dp
    val S24 = 24.dp
    val S32 = 32.dp
    val Margin = S20          // spec: uniform horizontal screen margin
    val CardSpace = S12       // vertical rhythm between cards
    val CardPadding = S16     // inner panel padding
}

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

// ── TYPOGRAPHY — ONE family (system sans), weights carry the hierarchy ───────
// Headings: ExBold/Bold with -0.02em tracking (high-impact numbers & ranks).
// Body: Regular 14sp / lineHeight 20 (spec) in muted slate. Labels: defined.

private val SystemTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, letterSpacing = (-0.68).sp, lineHeight = 40.sp, color = TextPrimary),
    displayMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, letterSpacing = (-0.6).sp, lineHeight = 36.sp, color = TextPrimary),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.44).sp, lineHeight = 28.sp, color = TextPrimary),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = (-0.36).sp, lineHeight = 24.sp, color = TextPrimary),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = (-0.16).sp, lineHeight = 22.sp, color = TextPrimary),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, color = TextPrimary),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = TextMuted),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, color = TextMuted),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.8.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.6.sp, lineHeight = 14.sp, color = TextMuted),
)

@Composable
fun SystemTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SystemColors, typography = SystemTypography, content = content)
}
