package com.thesystem.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp

/**
 * vC50 §1 — VIDEO-CONTRAST LAW primitives.
 * Over the hunter's anime aura (splash / awakening / rank chamber), every
 * floating panel and headline obeys ONE contract:
 *
 *  • Panels: PanelInk plate at 78% + a cyan hairline — text never floats raw
 *    over moving footage.
 *  • Titles: pure white or high-luminance silver-cyan WITH [VideoTextShadow]
 *    — a hard black drop shadow, so glyphs survive any frame beneath them.
 */
val PanelInk = Color(0xFF0B0E14)
val CyanEdge = Color(0xFF00F0FF)
val IceWhite = Color(0xFFE0FBFC)
val SilverSoft = Color(0xFFA4C2CB)

/** The drop-shadow every critical headline/rank title carries over video. */
val VideoTextShadow = Shadow(color = Color.Black, offset = Offset(2f, 2f), blurRadius = 8f)

/** Semi-transparent dark plate + cyan hairline — the video-panel surface. */
fun Modifier.videoPanel(): Modifier = this
    .background(PanelInk.copy(alpha = 0.78f), RoundedCornerShape(10.dp))
    .border(1.dp, CyanEdge.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
