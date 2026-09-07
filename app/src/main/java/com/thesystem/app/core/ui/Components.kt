package com.thesystem.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.*

fun rankColor(rank: SystemMath.HunterRank): Color = when (rank) {
    SystemMath.HunterRank.LOSER, SystemMath.HunterRank.GARBAGE -> CrimsonRed
    SystemMath.HunterRank.AVERAGE -> TextMuted
    SystemMath.HunterRank.ELITE -> ElectricBlue
    SystemMath.HunterRank.S_RANK -> NeonPurple
    SystemMath.HunterRank.MASTERPIECE -> HunterGold
}

// ── CONTRAST-SAFE FLOATING CONTAINERS (spec §2) ═════════════════════════════
// Rule: never float a bare vector icon over art or map tiles — always this shell.

/** 44dp touch target, 80% navy + 10% hairline: visible over ANY background. */
@Composable
fun FloatingIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = TextPrimary,
    size: Dp = 44.dp,
    onClick: () -> Unit,   // trailing-lambda convention: callbacks ride LAST
) {
    Box(
        modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(FloatingSurface)
            .border(1.dp, Hairline, androidx.compose.foundation.shape.CircleShape)
            .pressScale(0.9f)
            .clickableNoIndication(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.45f))
    }
}

private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
        indication = null, // press-scale is the feedback here; spec asks ripple on BUTTONS (NeonButton has it natively)
        onClick = onClick,
    )

/** Scannable tag chip — replaces noisy sentence-badges ("+30 XP Complete Bonus" → "+30 XP"). */
@Composable
fun SystemChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text.uppercase(), color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Sticky bottom execution area — anchors the primary action above the Tab Bar.
 *  Place inside a Box; renders in the floating contrast-safe shell. */
@Composable
fun BoxScope.StickyActionBar(
    status: String,
    statusColor: Color,
    actionText: String,
    actionColor: Color = ElectricBlue,
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(start = Grid.Margin, end = Grid.Margin, bottom = Grid.S12)
            .clip(RoundedCornerShape(16.dp))
            .background(FloatingSurface)
            .border(1.dp, Hairline, RoundedCornerShape(16.dp))
            .padding(horizontal = Grid.S16, vertical = Grid.S8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            status,
            style = MaterialTheme.typography.labelLarge,
            color = statusColor,
            modifier = Modifier.weight(1f),
        )
        NeonButton(actionText, onAction, color = actionColor, enabled = enabled)
    }
}

/** Screen scaffold: dark base + optional dynamic anime wallpaper + scrim + vignette + living ambient motes.
 *  Aura is HARD-CLAMPED to 0.12–0.16 plus a black vignette — foreground text readability is non-negotiable. */
@Composable
fun SystemBackground(wallpaperUrl: String? = null, wallpaperAlpha: Float = 0.14f, content: @Composable BoxScope.() -> Unit) {
    val aura = wallpaperAlpha.coerceIn(0.12f, 0.16f)
    Box(Modifier.fillMaxSize().background(VoidBlack)) {
        if (wallpaperUrl != null) {
            AsyncImage(
                model = wallpaperUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = aura,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0xCC0B0E14), Color(0x880B0E14), Color(0xF20B0E14))
                )
            )
        )
        // Director's vignette: edges swallow the art so eyes land on content
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(Color.Transparent, Color(0x99000000), Color(0xF2030508)))
            )
        )
        AmbientMotes(seed = wallpaperUrl?.hashCode() ?: 7)
        content()
    }
}

/**
 * Neon-bordered card — THE SYSTEM's core container.
 * [pulse] = slow breathing border glow for anything the user should feel is "alive"
 * (streak card, active battle, claimable reward).
 */
@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    glow: Color = ElectricBlue,
    corner: Dp = 14.dp,
    pulse: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    val borderGlow by if (pulse) {
        val inf = rememberInfiniteTransition(label = "cardPulse")
        inf.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "cardPulseA")
    } else remember { mutableStateOf(0.65f) }
    Column(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(SurfaceDark.copy(alpha = 0.92f), SurfaceHigh.copy(alpha = 0.85f))))
            .border(1.dp, Brush.linearGradient(listOf(glow.copy(alpha = borderGlow), glow.copy(alpha = 0.08f))), shape)
            .padding(Grid.CardPadding),
        content = content,
    )
}

@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = ElectricBlue,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.pressScale(if (enabled) 0.90f else 1f),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.16f),
            contentColor = color,
            disabledContainerColor = SurfaceHigh,
            disabledContentColor = TextMuted,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, if (enabled) color.copy(alpha = 0.8f) else Color(0x33FFFFFF)
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else TextMuted)
    }
}

@Composable
fun SectionTitle(text: String, color: Color = ElectricBlue) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
fun RankBadge(rank: SystemMath.HunterRank, modifier: Modifier = Modifier) {
    val c = rankColor(rank)
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.copy(alpha = 0.14f))
            .border(1.dp, c.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(rank.title, color = c, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
    }
}

// ── ANIMATED NUMBER — stats count up like a slot machine ════════════════════
// This single component is a huge addiction lever: numbers that ROLL feel earned.

@Composable
fun AnimatedCounter(
    target: Long,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    fontSize: androidx.compose.ui.unit.TextUnit = 22.sp,
    fontWeight: FontWeight = FontWeight.Black,
    format: (Long) -> String = { SystemMath.formatXp(it) },
) {
    val anim = remember { Animatable(target.toFloat()) }
    LaunchedEffect(target) {
        anim.animateTo(target.toFloat(), tween(650, easing = SystemMotion.Emphasize))
    }
    Text(format(anim.value.toLong()), modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight)
}

// ── XP RING — spring-loaded gauge with rotating halo ═════════════════════════

@Composable
fun XpRing(xp: Long, level: Int, modifier: Modifier = Modifier, color: Color = ElectricBlue) {
    val target = SystemMath.progressInLevel(xp)
    val animated by animateFloatAsState(targetValue = target, animationSpec = SystemMotion.springSoft, label = "xpRing")
    val inf = rememberInfiniteTransition(label = "ringHalo")
    val halo by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "haloRot",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = size.minDimension * 0.07f)
            drawArc(Color(0x3300F0FF), startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke)
            drawArc(
                Brush.sweepGradient(listOf(color, NeonPurple, color)),
                startAngle = -90f, sweepAngle = 360f * animated, useCenter = false, style = stroke,
            )
            // rotating energy halo — makes the ring feel powered, not painted
            drawArc(
                color.copy(alpha = 0.35f),
                startAngle = halo, sweepAngle = 42f, useCenter = false,
                style = Stroke(width = size.minDimension * 0.028f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LV", fontSize = 10.sp, color = TextMuted, letterSpacing = 2.sp)
            Text("$level", fontSize = 30.sp, fontWeight = FontWeight.Black, color = color)
        }
    }
}

@Composable
fun StatTile(label: String, value: String, color: Color = ElectricBlue, modifier: Modifier = Modifier) {
    GlowCard(modifier = modifier, glow = color) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** StatTile whose number rolls up when it changes. The addictive one. */
@Composable
fun AnimatedStatTile(label: String, value: Long, color: Color = ElectricBlue, modifier: Modifier = Modifier) {
    GlowCard(modifier = modifier, glow = color) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        AnimatedCounter(target = value, color = color, fontSize = 18.sp)
    }
}

// ── STREAK TILE — flame + rolling count; fires the daily-login loop ══════════

@Composable
fun StreakTile(days: Int, modifier: Modifier = Modifier) {
    val color = when {
        days >= 30 -> HunterGold
        days >= 7  -> NeonPurple
        days >= 1  -> ElectricBlue
        else       -> TextMuted
    }
    GlowCard(modifier = modifier, glow = color, pulse = days > 0) {
        Text("STREAK", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StreakFlame(days = days, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            AnimatedCounter(target = days.toLong(), color = color, fontSize = 18.sp, format = { "$it days" })
        }
    }
}

@Composable
fun VcChip(amount: Long, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(HunterGold.copy(alpha = 0.10f))
            .border(1.dp, HunterGold.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("◈ ", color = HunterGold, fontSize = 12.sp)
        AnimatedCounter(target = amount, color = HunterGold, fontSize = 12.sp, fontWeight = FontWeight.Bold, format = { SystemMath.formatVc(it) })
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("∅", color = TextMuted, fontSize = 34.sp)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

// ── XP PROGRESS BAR — springy fill + traveling shine ═════════════════════════

@Composable
fun XpProgressBar(xp: Long, modifier: Modifier = Modifier, color: Color = ElectricBlue) {
    val p by animateFloatAsState(SystemMath.progressInLevel(xp), animationSpec = SystemMotion.springSoft, label = "xpBar")
    val inf = rememberInfiniteTransition(label = "barShine")
    val shine by inf.animateFloat(
        initialValue = -0.3f, targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "shineX",
    )
    Box(
        modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(SurfaceHigh)
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(color, NeonPurple)))
        ) {
            // shine sweep — the "gacha bar" glint; reads as energy flowing to the next level
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val sx = shine * size.width
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                        startX = sx - 40f, endX = sx + 40f,
                    )
                )
            }
        }
    }
}

// ── XP GAIN FLOATER — "+61 XP" rises and fades on every clear ════════════════

@Composable
fun BoxScope.XpGainFloater(signal: Int, text: String, modifier: Modifier = Modifier, color: Color = HunterGold) {
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
            color = color, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp,
        )
    }
}

// ── LEVEL-UP BANNER — the payday moment ═════════════════════════════════════

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
                    val s = 0.4f + 0.6f * v
                    scaleX = s; scaleY = s
                    alpha = v.coerceAtMost(1f)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("LEVEL UP", style = MaterialTheme.typography.displayMedium, color = ElectricBlue)
            Text("LV $level", color = HunterGold, fontSize = 42.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Text("THE SYSTEM ACKNOWLEDGES YOUR GROWTH", style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}

// ── SKELETON LIST — premium-looking loading placeholders ════════════════════

@Composable
fun SkeletonCards(count: Int = 3) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(count) { i ->
            ShimmerBox(Modifier.fillMaxWidth().height(if (i == 0) 120.dp else 84.dp).clip(RoundedCornerShape(14.dp)))
        }
    }
}

/** Divider with a neon center dash. */
@Composable
fun NeonDivider(color: Color = ElectricBlue) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = 0.5f), Color.Transparent)))
    )
}

/** Full-width scrim line with text centered, e.g. "RESTRICTED // LEVEL 30 REQUIRED". */
@Composable
fun RestrictionBanner(text: String, color: Color = CrimsonRed) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text.uppercase(), color = color, style = MaterialTheme.typography.labelLarge) }
}
