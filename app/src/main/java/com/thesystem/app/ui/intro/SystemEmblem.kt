package com.thesystem.app.ui.intro

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.SkyBlue
import kotlin.math.min

/**
 * THE SYSTEM EMBLEM — the "S" that owns the launch frame.
 *
 * Sharp angular ribbon-S built from hard 90°/diagonal joints. No circle, no
 * clock, no spinner frame — the mark stands alone on black: white primary
 * form with a faint SYSTEM-blue energy bleed. Pure vector; one path, built
 * once, reused everywhere (intro, boot sync, hub watermarks).
 */
object SystemEmblem {

    // ribbon centerline through a 100 × 160 design space
    private val RIBBON = Path().apply {
        moveTo(88f, 14f)
        lineTo(26f, 14f)
        lineTo(26f, 64f)
        lineTo(74f, 88f)
        lineTo(74f, 146f)
        lineTo(12f, 146f)
    }

    private const val DESIGN_W = 100f
    private const val DESIGN_H = 160f
    private const val RIBBON_W = 21f

    /** Draws the emblem into [boxW]×[boxH] px. Glitch 0..1 = slice displacement energy. */
    fun DrawScope.draw(
        left: Float,
        top: Float,
        boxW: Float,
        boxH: Float,
        glitch: Float = 0f,
        energy: Float = 0.55f,
        body: Color = PaperWhite,
        accent: Color = SkyBlue,
    ) {
        val s = min(boxW / DESIGN_W, boxH / DESIGN_H)
        val ox = left + (boxW - DESIGN_W * s) / 2f
        val oy = top + (boxH - DESIGN_H * s) / 2f
        withTransform({
            translate(ox, oy)
            scale(s, s, Offset.Zero)
        }) {
            // blue energy bleed — two widening spectral passes behind the white form
            drawPath(
                RIBBON, accent.copy(alpha = 0.10f * energy),
                style = Stroke(RIBBON_W + 22f, cap = StrokeCap.Butt, join = StrokeJoin.Miter),
            )
            drawPath(
                RIBBON, accent.copy(alpha = 0.22f * energy),
                style = Stroke(RIBBON_W + 9f, cap = StrokeCap.Butt, join = StrokeJoin.Miter),
            )
            // white primary form
            drawPath(
                RIBBON, body,
                style = Stroke(RIBBON_W, cap = StrokeCap.Butt, join = StrokeJoin.Miter),
            )
            // glitch slices — thin displaced slivers, deterministic per energy level
            if (glitch > 0.01f) {
                val k = (glitch * 997f).toInt()
                repeat(3) { i ->
                    val y = ((k * (i + 3) * 37) % 150).toFloat() + 4f
                    val dx = (((k + i * 61) % 33) - 16) * 0.55f * glitch
                    val sliceTop = y - 3.5f
                    val sliceBottom = y + 3.5f
                    // only slices crossing the ribbon bands read as faults
                    drawRect(
                        accent.copy(alpha = 0.55f * glitch),
                        topLeft = Offset(dx + 2f, sliceTop - DESIGN_H * 0.0f - 10f),
                        size = androidx.compose.ui.geometry.Size(DESIGN_W + 20f, 2.6f + (i % 2)),
                    )
                }
                // core displaced echo of the white form itself
                drawPath(
                    RIBBON, body.copy(alpha = 0.85f),
                    style = Stroke(RIBBON_W * 0.42f, cap = StrokeCap.Butt, join = StrokeJoin.Miter),
                )
            }
        }
    }
}

/** Ready-made emblem composable — black-ready, centered by the caller. */
@Composable
fun SystemEmblemMark(
    modifier: Modifier = Modifier,
    glitch: Float = 0f,
    energy: Float = 0.55f,
) {
    Canvas(modifier) {
        with(SystemEmblem) {
            draw(0f, 0f, size.width, size.height, glitch, energy)
        }
    }
}
