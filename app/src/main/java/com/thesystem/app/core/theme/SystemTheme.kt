package com.thesystem.app.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.R

// ═══════════════════════════════════════════════════════════════════════════
// DESIGN TOKENS 3.0 — "MONOCHROME HUD"
// Swiss-watch / field-terminal discipline. NO color anywhere: pure black,
// white, and grays only. Hierarchy comes from weight, spacing and shade —
// never from hue. Hairlines over borders, spacing over chrome.
// ═══════════════════════════════════════════════════════════════════════════

// ── Grayscale ramp (the only palette that exists) ───────────────────────────
val InkBlack   = Color(0xFF000000)  // pitch-black canvas
val PanelGray  = Color(0xFF0F0F0F)  // flat section surface
val RaisedGray = Color(0xFF1A1A1A)  // inputs, raised tracks
val TrackGray  = Color(0xFF262626)  // progress tracks, inactive fills
val LineStrong = Color(0x33FFFFFF)  // explicit hairline (≈ #555 on black)
val LineSoft   = Color(0x1AFFFFFF)  // divider between groups
val LabelGray  = Color(0xFF888888)  // secondary text / labels (spec #888)
val FaintGray  = Color(0xFF555555)  // tertiary / disabled text
val PaperWhite = Color(0xFFFFFFFF)  // primary text + primary actions

// Phase-2 TRAINING accent — sky-blue is intentionally restricted to the
// training surfaces (course carousel, quest window, system notifications).
// The rest of the app stays pure black/white/gray.
val SkyBlue = Color(0xFF38BDF8)
val SkyBlueDim = Color(0x1F38BDF8)   // frosted-blue glass fill ~12%

// ── Legacy token names — retained so the whole app inherits the re-skin.
// Every former hue now resolves to a gray of the same semantic weight.
val VoidBlack   = InkBlack
val SurfaceDark = PanelGray
val SurfaceHigh = RaisedGray
val ElectricBlue = PaperWhite   // primary accent → pure white
val NeonPurple   = Color(0xFFC4C4C4) // secondary accent → light gray
val CrimsonRed   = Color(0xFFEDEDED) // danger → near-white (carried by type/shape)
val HunterGold   = PaperWhite
val VenomGreen   = PaperWhite
val WarningAmber = LabelGray
val TextPrimary  = PaperWhite
val TextMuted    = LabelGray
val GridLine     = LineSoft
val FloatingSurface = Color(0xF2000000) // near-solid black shell over art/map
val Hairline     = LineStrong

/** THE GRID — every screen snaps here. Lateral padding 16dp per spec. */
object Grid {
    val S4 = 4.dp
    val S8 = 8.dp
    val S12 = 12.dp
    val S16 = 16.dp
    val S20 = 20.dp
    val S24 = 24.dp
    val S32 = 32.dp
    val Margin = S16         // uniform lateral screen margin (8–16 spec)
    val CardSpace = S16      // equal breathing room between sections
    val CardPadding = S16
}

// ── TYPE FACES ───────────────────────────────────────────────────────────────
// Chakra Petch — geometric/technical sans for every label & heading.
// JetBrains Mono — instrument readouts only: XP, VC, levels, zones, scores.
val SystemSans = FontFamily(
    Font(R.font.chakra_petch_regular, FontWeight.Normal),
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

val SystemMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private val SystemColors = darkColorScheme(
    background = InkBlack,
    onBackground = PaperWhite,
    surface = PanelGray,
    onSurface = PaperWhite,
    surfaceVariant = RaisedGray,
    onSurfaceVariant = LabelGray,
    primary = PaperWhite,
    onPrimary = InkBlack,
    secondary = Color(0xFFC4C4C4),
    onSecondary = InkBlack,
    tertiary = PaperWhite,
    onTertiary = InkBlack,
    error = PaperWhite,
    onError = InkBlack,
    outline = LineSoft,
)

// ── TYPE SCALE ───────────────────────────────────────────────────────────────
// Headings: bold, all-caps via call sites. Body: regular. Data: always mono.

private val SystemTypography = Typography(
    displayLarge = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = 0.5.sp, lineHeight = 38.sp, color = PaperWhite),
    displayMedium = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 0.8.sp, lineHeight = 32.sp, color = PaperWhite),
    headlineMedium = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.2.sp, lineHeight = 26.sp, color = PaperWhite),
    titleLarge = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 0.6.sp, lineHeight = 23.sp, color = PaperWhite),
    titleMedium = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.4.sp, lineHeight = 21.sp, color = PaperWhite),
    bodyLarge = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, color = PaperWhite),
    bodyMedium = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp, color = LabelGray),
    bodySmall = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 16.sp, color = LabelGray),
    // section / control labels — bold, tracked, uppercase by call sites
    labelLarge = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.7.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(fontFamily = SystemSans, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.5.sp, lineHeight = 14.sp, color = LabelGray),
)

// ── MONO INSTRUMENT STYLES (XP / VC / scores / coordinates) ─────────────────
val MonoDisplay = TextStyle(fontFamily = SystemMono, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 0.sp, color = PaperWhite)
val MonoTitle   = TextStyle(fontFamily = SystemMono, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 0.sp, color = PaperWhite)
val MonoData    = TextStyle(fontFamily = SystemMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.sp, color = PaperWhite)
val MonoLabel   = TextStyle(fontFamily = SystemMono, fontWeight = FontWeight.Normal, fontSize = 10.sp, letterSpacing = 0.5.sp, color = LabelGray)

@Composable
fun SystemTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SystemColors, typography = SystemTypography, content = content)
}
