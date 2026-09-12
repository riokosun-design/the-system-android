package com.thesystem.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.*

// ═══════════════════════════════════════════════════════════════════════════
// MONOCHROME COMPONENT LIBRARY 3.0
// Rules: black/white/gray only · flat fills OR hairlines, never both on one
// surface · spacing carries hierarchy · data reads in JetBrains Mono.
// ═══════════════════════════════════════════════════════════════════════════

/** Rank is expressed as a ramp of GRAY SHADES. Penalty ranks invert to solid. */
fun rankColor(rank: SystemMath.HunterRank): Color = when (rank) {
    SystemMath.HunterRank.LOSER, SystemMath.HunterRank.GARBAGE -> PaperWhite
    SystemMath.HunterRank.AVERAGE -> Color(0xFF8A8A8A)
    SystemMath.HunterRank.ELITE -> Color(0xFFB0B0B0)
    SystemMath.HunterRank.S_RANK -> Color(0xFFD2D2D2)
    SystemMath.HunterRank.MASTERPIECE -> PaperWhite
}

// ── CONTRAST-SAFE FLOATING SHELL (over black map / camera) ──────────────────

@Composable
fun FloatingIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = PaperWhite,
    size: Dp = 44.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(FloatingSurface)
            .border(1.dp, LineStrong, CircleShape)
            .pressScale(0.9f)
            .clickableNoIndication(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.42f))
    }
}

private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    )

/** Neutral mono tag — mono type, hairline border, zero hue. */
@Composable
fun SystemChip(text: String, color: Color = PaperWhite, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, LineStrong, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text.uppercase(), color = color, style = MonoLabel, maxLines = 1)
    }
}

/** Sticky bottom execution area — flat black strip with a hairline top. */
@Composable
fun BoxScope.StickyActionBar(
    status: String,
    statusColor: Color,
    actionText: String,
    actionColor: Color = PaperWhite,
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(InkBlack)
            .border(width = 1.dp, color = LineSoft)
            .padding(horizontal = Grid.Margin, vertical = Grid.S12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(status, style = MonoLabel, color = statusColor, modifier = Modifier.weight(1f))
        NeonButton(actionText, onAction, color = actionColor, enabled = enabled)
    }
}

/** Screen scaffold: pitch black + optional GRAYSCALE art plate, nothing else. */
@Composable
fun SystemBackground(wallpaperUrl: String? = null, wallpaperAlpha: Float = 0.14f, content: @Composable BoxScope.() -> Unit) {
    val grayFilter = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Box(Modifier.fillMaxSize().background(InkBlack)) {
        if (wallpaperUrl != null) {
            AsyncImage(
                model = wallpaperUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = grayFilter,
                alpha = wallpaperAlpha.coerceIn(0.05f, 0.22f),
            )
            // single flat scrim — no radial noise
            Box(Modifier.fillMaxSize().background(Color(0xE6000000)))
        }
        content()
    }
}

/**
 * PANEL — the one container. Flat #0F0F0F fill, 12dp corners, NO border
 * (fill OR border, never both — spec). [glow]/[pulse] kept for signature
 * compatibility but do nothing: panels never glow.
 */
@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    glow: Color = PaperWhite,
    corner: Dp = 12.dp,
    pulse: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(PanelGray)
            .padding(Grid.CardPadding),
        content = content,
    )
}

/** PRIMARY ACTION — solid white plate, black text. */
@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = PaperWhite,
    enabled: Boolean = true,
) {
    // an explicitly gray color request = the quiet GHOST treatment (side B,
    // secondary choices); white = solid execution; disabled = faint outline.
    val ghost = color == LabelGray || color == FaintGray || color == NeonPurple
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.pressScale(if (enabled) 0.94f else 1f),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = when {
            !enabled -> ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = FaintGray,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = FaintGray,
            )
            ghost -> ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = LabelGray,
            )
            else -> ButtonDefaults.buttonColors(
                containerColor = PaperWhite,
                contentColor = InkBlack,
            )
        },
        border = when {
            !enabled -> androidx.compose.foundation.BorderStroke(1.dp, LineSoft)
            ghost -> androidx.compose.foundation.BorderStroke(1.dp, LineStrong)
            else -> null
        },
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = if (enabled) (if (ghost) LabelGray else InkBlack) else FaintGray, maxLines = 1)
    }
}

/** SECONDARY ACTION — white hairline, white text (EDIT-style). */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.pressScale(if (enabled) 0.94f else 1f),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PaperWhite, disabledContentColor = FaintGray),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) PaperWhite else LineSoft),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, color: Color = PaperWhite) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** Rank tag: outlined by default; penalty ranks invert to solid white/black. */
@Composable
fun RankBadge(rank: SystemMath.HunterRank, modifier: Modifier = Modifier) {
    val penalty = rank.isPenaltyRank
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .then(if (penalty) Modifier.background(PaperWhite) else Modifier.border(1.dp, LineStrong, RoundedCornerShape(4.dp)))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            rank.title,
            color = if (penalty) InkBlack else PaperWhite,
            fontSize = 10.sp,
            fontFamily = SystemMono,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
    }
}

// ── ANIMATED NUMBER — readout in JetBrains Mono ─────────────────────────────

@Composable
fun AnimatedCounter(
    target: Long,
    modifier: Modifier = Modifier,
    color: Color = PaperWhite,
    fontSize: androidx.compose.ui.unit.TextUnit = 18.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    format: (Long) -> String = { SystemMath.formatXp(it) },
) {
    val anim = remember { Animatable(target.toFloat()) }
    LaunchedEffect(target) {
        anim.animateTo(target.toFloat(), tween(650, easing = SystemMotion.Emphasize))
    }
    Text(format(anim.value.toLong()), modifier = modifier, color = color, fontSize = fontSize,
        fontWeight = fontWeight, fontFamily = SystemMono)
}

// ── LEVEL DISC — one gray track + one white arc, nothing else ───────────────

@Composable
fun XpRing(xp: Long, level: Int, modifier: Modifier = Modifier, color: Color = PaperWhite) {
    val target = SystemMath.progressInLevel(xp)
    val animated by animateFloatAsState(targetValue = target, animationSpec = SystemMotion.springSoft, label = "xpRing")
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = size.minDimension * 0.085f, cap = StrokeCap.Round)
            drawArc(TrackGray, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke)
            drawArc(color, startAngle = -90f, sweepAngle = 360f * animated, useCenter = false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LV", fontSize = 9.sp, color = LabelGray, fontFamily = SystemMono, letterSpacing = 2.sp)
            Text("$level", fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono, color = PaperWhite)
        }
    }
}

@Composable
fun StatTile(label: String, value: String, color: Color = PaperWhite, modifier: Modifier = Modifier) {
    GlowCard(modifier = modifier) {
        Text(label.uppercase(), style = MonoLabel)
        Spacer(Modifier.height(6.dp))
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
    }
}

@Composable
fun AnimatedStatTile(label: String, value: Long, color: Color = PaperWhite, modifier: Modifier = Modifier) {
    GlowCard(modifier = modifier) {
        Text(label.uppercase(), style = MonoLabel)
        Spacer(Modifier.height(6.dp))
        AnimatedCounter(target = value, color = color, fontSize = 17.sp)
    }
}

// ── STREAK TILE ─────────────────────────────────────────────────────────────

@Composable
fun StreakTile(days: Int, modifier: Modifier = Modifier) {
    GlowCard(modifier = modifier) {
        Text("STREAK", style = MonoLabel)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StreakFlame(days = days, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            AnimatedCounter(target = days.toLong(), color = PaperWhite, fontSize = 17.sp, format = { "$it days" })
        }
    }
}

/** VC readout: white diamond + mono amount, quiet hairline chip. */
@Composable
fun VcChip(amount: Long, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, LineStrong, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Filled.Diamond, contentDescription = "VC", tint = PaperWhite, modifier = Modifier.size(10.dp))
        AnimatedCounter(target = amount, color = PaperWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            format = { SystemMath.formatVc(it) })
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("—", color = FaintGray, fontSize = 28.sp, fontFamily = SystemMono)
        Spacer(Modifier.height(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

// ── PROGRESS LINE — white hair over dark gray track ─────────────────────────

@Composable
fun XpProgressBar(xp: Long, modifier: Modifier = Modifier, color: Color = PaperWhite) {
    val p by animateFloatAsState(SystemMath.progressInLevel(xp), animationSpec = SystemMotion.springSoft, label = "xpBar")
    Box(
        modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(TrackGray)
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).clip(RoundedCornerShape(2.dp))
                .background(color)
        )
    }
}

// ── XP GAIN FLOATER ─────────────────────────────────────────────────────────

@Composable
fun BoxScope.XpGainFloater(signal: Int, text: String, modifier: Modifier = Modifier, color: Color = PaperWhite) {
    if (signal <= 0) return
    androidx.compose.runtime.key(signal) {
        val rise = remember { Animatable(0f) }
        LaunchedEffect(Unit) { rise.animateTo(1f, tween(1100, easing = SystemMotion.Emphasize)) }
        Text(
            text,
            modifier = modifier
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    translationY = -rise.value * 160f
                    alpha = (1f - rise.value).coerceAtLeast(0f)
                },
            color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono, letterSpacing = 1.sp,
        )
    }
}

// ── LEVEL-UP BANNER ─────────────────────────────────────────────────────────

@Composable
fun BoxScope.LevelUpBanner(signal: Int, level: Int, modifier: Modifier = Modifier) {
    if (signal <= 0) return
    androidx.compose.runtime.key(signal) {
        val a = remember { Animatable(0f) }
        LaunchedEffect(Unit) { a.animateTo(1f, SystemMotion.springPunch) }
        val v = a.value
        Column(
            modifier = modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    val s = 0.5f + 0.5f * v
                    scaleX = s; scaleY = s
                    alpha = v.coerceAtMost(1f)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("LEVEL UP", style = MaterialTheme.typography.displayMedium, color = PaperWhite)
            Text("LV $level", color = PaperWhite, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono, letterSpacing = 3.sp)
            Text("THE SYSTEM ACKNOWLEDGES YOUR GROWTH", style = MonoLabel)
        }
    }
}

// ── SKELETON LOADING ────────────────────────────────────────────────────────

@Composable
fun SkeletonCards(count: Int = 3) {
    Column(verticalArrangement = Arrangement.spacedBy(Grid.CardSpace)) {
        repeat(count) { i ->
            ShimmerBox(Modifier.fillMaxWidth().height(if (i == 0) 120.dp else 84.dp).clip(RoundedCornerShape(12.dp)))
        }
    }
}

/** Hairline divider, no glow. */
@Composable
fun NeonDivider(color: Color = LineSoft) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(color))
}

/** Restriction strip — outlined, type carries the warning (no hue available). */
@Composable
fun RestrictionBanner(text: String, color: Color = PaperWhite) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .border(1.dp, LineStrong, RoundedCornerShape(8.dp)).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text.uppercase(), color = color, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

// ═══════════════════════════════════════════════════════════════════════════
// SYSTEM TAB BAR — single-line sub-tab navigation with a hairline indicator.
// Replaces every stock/wrapping TabRow: labels NEVER truncate, active tab is
// marked only by a 2dp white underline.
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun SystemTabBar(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, label ->
                val active = i == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // single line always — bar is meant to be placed full-bleed so
                    // the longest label (TOURNAMENTS) never wraps to "TOURN AMENT S".
                    Text(
                        label,
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 11.sp,
                        letterSpacing = 0.8.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) PaperWhite else LabelGray,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .height(2.dp)
                            .width(28.dp)
                            .background(if (active) PaperWhite else Color.Transparent)
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
    }
}
