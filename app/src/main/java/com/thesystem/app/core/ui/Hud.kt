package com.thesystem.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.core.theme.LabelGray
import com.thesystem.app.core.theme.PanelGray
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.SystemMono
import com.thesystem.app.core.theme.TextMuted
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════════════
// MONOCHROME WINDOW CHROME 3.0
// Key system windows now use the SAME flat panel language as everything else:
// #0F0F0F fill, 12dp corners, no chamfers, no corner ticks, no glow.
// Status is communicated by type and shade only.
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Flat key window. Signature-compatible drop-in for the old chamfered frame.
 * [accent]/[glow] are ignored — panels never carry hue.
 */
@Composable
fun HudFrameCard(
    accent: Color = PaperWhite,
    modifier: Modifier = Modifier,
    cut: androidx.compose.ui.unit.Dp = 12.dp,
    glow: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PanelGray)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

/**
 * Status label — [COMPLETE] / [IN PROGRESS] / [PENALTY RISK].
 * Pure mono text; a whiter shade = more attention. No boxes, no brackets fill.
 */
@Composable
fun HudTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        "[$text]",
        modifier = modifier,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = SystemMono,
        letterSpacing = 1.2.sp,
    )
}
