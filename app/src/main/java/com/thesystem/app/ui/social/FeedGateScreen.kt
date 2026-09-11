package com.thesystem.app.ui.social

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.core.theme.ElectricBlue
import com.thesystem.app.core.theme.Grid
import com.thesystem.app.core.theme.Hairline
import com.thesystem.app.core.theme.TextMuted
import com.thesystem.app.core.theme.TextPrimary
import com.thesystem.app.core.ui.SystemBackground
import com.thesystem.app.core.ui.SystemChip

// ═══════════════════════════════════════════════════════════════════════════
// HUNTER FEED — CHANNEL GATE (Coming Soon)
//
// The full X-style social layer (HunterFeedScreen + migration 008) is SHIPPED
// and dormant: backend tables idle at zero cost on the free tier, no realtime
// channel opens while this gate is up. Flip one line in MainTabs.kt to reopen:
//   SystemTab.FEED -> HunterFeedScreen(nav = nav)
// ═══════════════════════════════════════════════════════════════════════════

private fun gateSeg(clock: Float, at: Float, span: Float = 0.25f): Float =
    ((clock - at) / span).coerceIn(0f, 1f)

private fun Modifier.gateAppear(c: Float): Modifier = this.graphicsLayer {
    alpha = c
    translationY = (1f - c) * 22.dp.toPx()
}

@Composable
fun HunterFeedGateScreen() {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { clock.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }

    SystemBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = Grid.Margin),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))

            // ── Encrypted-signal sigil: three slow counter-rotating arcs ────
            val inf = rememberInfiniteTransition(label = "gateSigil")
            val sweepA by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "sweepA")
            val sweepB by inf.animateFloat(360f, 0f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "sweepB")
            val pulse by inf.animateFloat(0.45f, 1f, infiniteRepeatable(tween(2100), RepeatMode.Reverse), label = "gatePulse")
            Box(
                Modifier.gateAppear(gateSeg(clock.value, 0.05f)).size(148.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    // outer arc — accent, slow clockwise
                    drawArc(
                        color = ElectricBlue, startAngle = sweepA, sweepAngle = 216f, useCenter = false,
                        topLeft = Offset(c.x - r + 6f, c.y - r + 6f), size = androidx.compose.ui.geometry.Size((r - 6f) * 2, (r - 6f) * 2),
                        style = Stroke(width = 2.5f, cap = StrokeCap.Round), alpha = 0.85f,
                    )
                    // mid arc — muted, counter-rotating
                    drawArc(
                        color = TextMuted, startAngle = sweepB, sweepAngle = 132f, useCenter = false,
                        topLeft = Offset(c.x - r * 0.68f, c.y - r * 0.68f), size = androidx.compose.ui.geometry.Size(r * 1.36f, r * 1.36f),
                        style = Stroke(width = 2f, cap = StrokeCap.Round), alpha = 0.4f,
                    )
                    // core — breathing dot behind the lock glyph
                    drawCircle(ElectricBlue.copy(alpha = 0.16f * pulse), radius = r * 0.34f)
                    drawCircle(ElectricBlue.copy(alpha = 0.9f), radius = 3.5f)
                }
                Text("▣", color = ElectricBlue.copy(alpha = 0.85f * pulse), fontSize = 30.sp)
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "CHANNEL 09 — HUNTER FEED", fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                color = ElectricBlue, letterSpacing = 3.sp,
                modifier = Modifier.gateAppear(gateSeg(clock.value, 0.2f)),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "COMING SOON",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp),
                color = TextPrimary,
                modifier = Modifier.gateAppear(gateSeg(clock.value, 0.3f)),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "The global hunter network is still forming.\nThis channel opens when the System decrees it.",
                style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center,
                modifier = Modifier.gateAppear(gateSeg(clock.value, 0.4f)),
            )

            Spacer(Modifier.height(40.dp))

            // ── What waits behind the gate — rows over cards, all locked ────
            val locked = listOf(
                "GLOBAL DISPATCHES" to "voice of every hunter, one timeline",
                "MANA BOOSTS" to "amplify the grinds worth witnessing",
                "SUMMON THE RIVAL" to "call a 1v1 straight from a post",
            )
            Column(Modifier.gateAppear(gateSeg(clock.value, 0.5f)).fillMaxWidth()) {
                locked.forEachIndexed { i, (name, sub) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Grid.CardPadding * 0.75f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                        SystemChip(text = "LOCKED", color = TextMuted)
                    }
                    if (i < locked.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Text(
                "GRIND IN SILENCE. THE SIGNAL WILL FIND YOU.",
                fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted.copy(alpha = 0.7f), letterSpacing = 2.sp,
                modifier = Modifier.gateAppear(gateSeg(clock.value, 0.65f)).padding(bottom = 18.dp),
            )
        }
    }
}
