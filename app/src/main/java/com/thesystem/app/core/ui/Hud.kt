package com.thesystem.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import com.thesystem.app.core.theme.ElectricBlue
import com.thesystem.app.core.theme.SurfaceDark

// ═══════════════════════════════════════════════════════════════════════════
// DESIGN 2.5 "MONARCH EDGE" — sci-fi HUD chrome for key surfaces.
// Philosophy: Quiet Power stays; only KEY SYSTEM WINDOWS earn the hologram
// frame (quests, live battles). Chamfer + 1dp hairline + corner ticks + a
// whisper of localized corner glow. Everything cached via drawWithCache.
// ═══════════════════════════════════════════════════════════════════════════

/** All-four-corners chamfer — the System-window silhouette. Density-aware Shape. */
fun chamferShape(cut: Dp): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }.coerceAtMost(size.minDimension / 3f)
        return Outline.Generic(chamferOutlinePath(size.width, size.height, c))
    }
}

private fun chamferOutlinePath(w: Float, h: Float, c: Float): Path = Path().apply {
    moveTo(c, 0f); lineTo(w - c, 0f); lineTo(w, c); lineTo(w, h - c)
    lineTo(w - c, h); lineTo(c, h); lineTo(0f, h - c); lineTo(0f, c); close()
}

/**
 * Hologram frame: chamfered 1dp accent hairline + corner ticks + faint corner
 * glow — all metrics solved ONCE per size change (drawWithCache), zero
 * per-frame allocation. Border sits behind content; glow never floods inward.
 */
fun Modifier.hudFrame(
    accent: Color = ElectricBlue,
    cut: Dp = 12.dp,
    glow: Boolean = true,
): Modifier = this.drawWithCache {
    val c = cut.toPx()
    val outline = chamferOutlinePath(size.width, size.height, c)
    val tick = (c * 1.9f).coerceAtLeast(14f)
    val glowDots = if (glow) listOf(Offset(0f, 0f), Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height)) else emptyList()
    val glowBrush = Brush.radialGradient(0f to accent.copy(alpha = 0.16f), 1f to Color.Transparent)
    val tickStroke = Stroke(2.6f)
    val hairStroke = Stroke(1.dp.toPx())
    val corners = listOf(Offset(0f, 0f), Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
    onDrawBehind {
        // localized corner glow — the only bloom allowed indoors
        for (o in glowDots) drawCircle(glowBrush, radius = c * 3.6f, center = o)
        // 1dp chamfer hairline
        drawPath(outline, accent.copy(alpha = 0.42f), style = hairStroke)
        // corner ticks — crisp L brackets at all four corners
        for ((i, o) in corners.withIndex()) {
            val sx = if (i % 2 == 0) 1f else -1f
            val sy = if (i < 2) 1f else -1f
            drawLine(accent.copy(alpha = 0.95f), o, Offset(o.x + sx * tick, o.y), strokeWidth = tickStroke.width)
            drawLine(accent.copy(alpha = 0.95f), o, Offset(o.x, o.y + sy * tick), strokeWidth = tickStroke.width)
        }
    }
}

/**
 * Key System window: chamfered dark slab + hologram frame.
 * Drop-in sibling of GlowCard — same call shape, colder heart.
 */
@Composable
fun HudFrameCard(
    accent: Color = ElectricBlue,
    modifier: Modifier = Modifier,
    cut: Dp = 12.dp,
    glow: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(chamferShape(cut))
            .background(SurfaceDark.copy(alpha = 0.94f))
            .hudFrame(accent = accent, cut = cut, glow = glow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

/** Bracket status tag — [COMPLETE] / [PENALTY RISK] / [IN PROGRESS]. */
@Composable
fun HudTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(chamferShape(5.dp))
            .background(color.copy(alpha = 0.10f))
            .hudFrame(accent = color, cut = 5.dp, glow = false)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            "[$text]",
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
        )
    }
}
