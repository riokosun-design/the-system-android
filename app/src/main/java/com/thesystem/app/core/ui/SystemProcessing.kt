package com.thesystem.app.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.core.theme.LabelGray
import com.thesystem.app.core.theme.LineSoft
import com.thesystem.app.core.theme.MonoLabel
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.SkyBlue

/**
 * SYSTEM-STYLE FUNCTIONAL LOADING — the honest replacement for every generic
 * spinner/progress ring. A mono status line plus one traveling blue hairline.
 * Deterministic, allocation-free per frame, trivially cheap on 2GB devices.
 *
 * Use ONLY for functional waits (network / compute). Never as decoration.
 */
@Composable
fun SystemProcessing(
    label: String = "SYSTEM PROCESSING",
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val inf = rememberInfiniteTransition(label = "sysproc")
    // traveling segment position 0..1 (left → right, wraps)
    val sweep by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "sysprocSweep",
    )
    // terminal dots: . .. ... cycling
    val beat by inf.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "sysprocDots",
    )
    val dots = ".".repeat(1 + (beat.toInt() % 3))

    if (compact) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text("$label$dots", style = MonoLabel, color = PaperWhite)
            Spacer(Modifier.width(10.dp))
            ProcessingHairline(sweep, Modifier.width(56.dp))
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$label$dots", style = MonoLabel, color = LabelGray, letterSpacing = 2.2.sp)
            Spacer(Modifier.height(10.dp))
            ProcessingHairline(sweep, Modifier.fillMaxWidth().padding(horizontal = 48.dp))
        }
    }
}

@Composable
private fun ProcessingHairline(sweep: Float, modifier: Modifier) {
    Canvas(modifier.height(2.dp)) {
        val w = size.width
        val h = size.height
        // dormant track
        drawLine(LineSoft, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = h)
        // traveling blue segment (wraps around both ends)
        val seg = w * 0.22f
        var head = sweep * (w + seg) - seg
        var tail = head + seg
        if (tail > head) {
            if (head < 0f) {
                drawLine(SkyBlue, Offset(0f, h / 2f), Offset(tail.coerceAtMost(w), h / 2f), strokeWidth = h)
                drawLine(SkyBlue, Offset(w + head, h / 2f), Offset(w, h / 2f), strokeWidth = h)
            } else if (tail > w) {
                drawLine(SkyBlue, Offset(head, h / 2f), Offset(w, h / 2f), strokeWidth = h)
                drawLine(SkyBlue, Offset(0f, h / 2f), Offset(tail - w, h / 2f), strokeWidth = h)
            } else {
                drawLine(SkyBlue, Offset(head, h / 2f), Offset(tail, h / 2f), strokeWidth = h)
            }
        }
    }
}

/** Static centered status pair used on boot/sync gates (no motion except the hairline). */
@Composable
fun SystemStatusLine(label: String, modifier: Modifier = Modifier, active: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Canvas(Modifier.height(6.dp).width(6.dp)) {
            drawRect(if (active) SkyBlue else LabelGray, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, size.height))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MonoLabel, color = if (active) PaperWhite else LabelGray, letterSpacing = 2.sp)
    }
}
