package com.thesystem.app.ui.social

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.thesystem.app.Routes
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.HunterPostDto
import java.time.Duration
import java.time.OffsetDateTime

/** Monochrome contract: even posted media renders in grayscale. */
private val feedGrayFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** Tab 2 — THE HUNTER FEED: dark-mode X for hunters.
 *  Global broadcasts / guild-only / rivalry lenses, mana flares, re-dispatch, 1v1 summons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HunterFeedScreen(nav: NavHostController, vm: HunterFeedViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val haptics = rememberSystemHaptics()
    LaunchedEffect(s.notice, s.error) {
        if (s.error != null) haptics.error()
        (s.notice ?: s.error)?.let { snack.showSnackbar(it); vm.consumeNotice() }
    }

    SystemBackground {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(horizontal = Grid.Margin)) {
                Spacer(Modifier.height(Grid.S8))
                Text("HUNTER FEED", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                Spacer(Modifier.height(Grid.S4))
                Text("Dispatches from the field. Arise what deserves mana.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Grid.S12))

                SystemTabBar(
                    tabs = listOf("GLOBAL", "GUILD", "RIVALRY"),
                    selected = s.scope.ordinal,
                    onSelect = { i -> haptics.tick(); vm.setScope(FeedScope.entries[i]) },
                )
                Spacer(Modifier.height(Grid.S12))

                PullToRefreshBox(
                    isRefreshing = s.loading,
                    onRefresh = { haptics.tick(); vm.refresh() },
                    modifier = Modifier.weight(1f),
                ) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 132.dp),
                    ) {
                        if (!s.loading && s.timeline.isEmpty()) {
                            item {
                                EmptyState(
                                    when (s.scope) {
                                        FeedScope.GLOBAL -> "The wire is silent. Be the first hunter to broadcast."
                                        FeedScope.GUILD -> if (s.myClanId == null) "You walk without a guild. Claim one in Map → Guilds." else "Your guild has not spoken yet."
                                        FeedScope.RIVALRY -> "No rival hunters on the wire. Yet."
                                    }
                                )
                            }
                        }
                        items(s.timeline, key = { it.id }) { post ->
                            PostRow(
                                post = post,
                                manaActive = post.id in s.myMana,
                                transmitted = post.id in s.myTransmits,
                                mine = post.authorId == s.myId,
                                onReply = { haptics.tick(); vm.openReply(post) },
                                onMana = { haptics.success(); vm.toggleMana(post) },
                                onTransmit = { haptics.tick(); vm.openQuote(post) },
                                onChallenge = {
                                    haptics.slam()
                                    vm.challenge(post) { battleId -> nav.navigate(Routes.battle(battleId)) }
                                },
                                onDelete = { vm.deletePost(post) },
                            )
                        }
                    }
                }
            }

            // Awaken Broadcast — the launcher
            FloatingIconButton(
                icon = Icons.Default.Add,
                contentDescription = "Awaken Broadcast",
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = Grid.Margin, bottom = Grid.S12),
                tint = ElectricBlue,
                size = 56.dp,
            ) { haptics.select(); vm.openComposer() }

            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (s.composerOpen) {
        ComposerDialog(
            quote = s.quoteTarget,
            busy = s.busy,
            onDispatch = { text, media -> vm.dispatch(text, media) },
            onDismiss = vm::closeComposer,
        )
    }
    s.replyTarget?.let { target ->
        ThreadDialog(
            target = target,
            replies = s.threadOf(target),
            busy = s.busy,
            onSend = vm::sendReply,
            onDismiss = vm::closeReply,
        )
    }
}

// (the three lenses now render through SystemTabBar — single line, hairline indicator)

// ── POST ROW — Twitter architecture: avatar rail + content column ════════════

@Composable
private fun PostRow(
    post: HunterPostDto,
    manaActive: Boolean,
    transmitted: Boolean,
    mine: Boolean,
    onReply: () -> Unit,
    onMana: () -> Unit,
    onTransmit: () -> Unit,
    onChallenge: () -> Unit,
    onDelete: () -> Unit,
) {
    val rank = SystemMath.rankFor(post.level, post.missedDays)
    val ring = rankRingColor(rank)
    Column {
        Spacer(Modifier.height(Grid.S12))
        Row(Modifier.fillMaxWidth()) {
            RankAvatar(post, ring)
            Spacer(Modifier.width(Grid.S12))
            Column(Modifier.weight(1f)) {
                // header line 1: name + timestamp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        post.displayName ?: "Hunter",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(Grid.S8))
                    Text("· ${relTime(post.createdAt)}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    if (mine) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            "✕",
                            color = TextMuted, fontSize = 12.sp,
                            modifier = Modifier.clickableNoIndicationExt(onDelete),
                        )
                    }
                }
                // header line 2: @handle + rank chip
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("@${post.username ?: "hunter"}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(Grid.S8))
                    SystemChip(rank.title, ring)
                }
                Spacer(Modifier.height(Grid.S8))
                // body — markdown-lite (**bold**, @handles, #hashtags)
                Text(dispatchBody(post.content), style = MaterialTheme.typography.bodyLarge)

                post.mediaUrl?.let { url ->
                    Spacer(Modifier.height(Grid.S8))
                    AsyncImage(
                        model = url, contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(12.dp)).border(1.dp, LineSoft, RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                        colorFilter = feedGrayFilter,
                    )
                }

                // embedded quote (re-dispatch)
                post.quotedPostId?.let {
                    if (post.quotedUsername != null) {
                        Spacer(Modifier.height(Grid.S8))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(SurfaceHigh).border(1.dp, GridLine, RoundedCornerShape(10.dp))
                                .padding(Grid.S12),
                        ) {
                            Text("@${post.quotedUsername}", style = MaterialTheme.typography.labelSmall, color = ElectricBlue)
                            Spacer(Modifier.height(Grid.S4))
                            Text(post.quotedExcerpt ?: "", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                post.questVerificationId?.let { qid ->
                    Spacer(Modifier.height(Grid.S8))
                    SystemChip("QUEST-VERIFIED #$qid", HunterGold)
                }

                Spacer(Modifier.height(Grid.S8))
                // action bar — four glyphs, counts ride along
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FeedAction("↩", post.replyCount, active = false, activeColor = PaperWhite, onClick = onReply)
                    ManaAction(post.manaCount, manaActive, onMana)
                    FeedAction("⇄", post.transmitCount, active = transmitted, activeColor = PaperWhite, onClick = onTransmit)
                    FeedAction("DUEL", null, active = false, activeColor = PaperWhite, onClick = onChallenge)
                }
            }
        }
        Spacer(Modifier.height(Grid.S12))
        Box(Modifier.fillMaxWidth().height(1.dp).background(GridLine))
    }
}

// ── ATOMS ════════════════════════════════════════════════════════════════════

/** Avatar with the rank halo: ELITE #38BDF8 · S-RANK #00F0FF · MASTERPIECE amber. */
@Composable
private fun RankAvatar(post: HunterPostDto, ring: Color) {
    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(ring.copy(alpha = 0.85f), style = Stroke(width = 2.dp.toPx()))
        }
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (post.avatarUrl != null) {
                AsyncImage(model = post.avatarUrl, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop, colorFilter = feedGrayFilter)
            } else {
                Text(
                    (post.displayName ?: post.username ?: "H").first().uppercase(),
                    color = ring, fontWeight = FontWeight.Black, fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun RowScope.FeedAction(
    glyph: String, count: Long?, active: Boolean, activeColor: Color, onClick: () -> Unit,
) {
    Row(
        Modifier.weight(1f).pressScale(0.9f).clickableNoIndicationExt(onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, color = if (active) activeColor else TextMuted, fontSize = 15.sp)
        if (count != null && count > 0) {
            Spacer(Modifier.width(5.dp))
            Text(shortCount(count), style = MaterialTheme.typography.labelSmall, color = if (active) activeColor else TextMuted)
        }
    }
}

/** Arise — the mana flare. Active state pulses like a living rune. */
@Composable
private fun RowScope.ManaAction(count: Long, active: Boolean, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "manaPulse")
    val pulse by inf.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "manaPulseA",
    )
    Row(
        Modifier.weight(1f).pressScale(0.9f).clickableNoIndicationExt(onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "✦",
            color = if (active) PaperWhite else LabelGray,
            fontSize = 15.sp,
            modifier = Modifier.graphicsLayer { alpha = if (active) pulse else 1f },
        )
        if (count > 0) {
            Spacer(Modifier.width(5.dp))
            Text(shortCount(count), style = MaterialTheme.typography.labelSmall, color = if (active) PaperWhite else LabelGray)
        }
    }
}

// ── DIALOGS ══════════════════════════════════════════════════════════════════

@Composable
private fun ComposerDialog(
    quote: HunterPostDto?,
    busy: Boolean,
    onDispatch: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var media by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceHigh,
        title = {
            Text(if (quote != null) "RE-DISPATCH" else "AWAKEN BROADCAST", color = ElectricBlue, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column {
                if (quote != null) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SurfaceDark)
                            .border(1.dp, GridLine, RoundedCornerShape(10.dp)).padding(Grid.S12),
                    ) {
                        Text("@${quote.username}", style = MaterialTheme.typography.labelSmall, color = ElectricBlue)
                        Spacer(Modifier.height(Grid.S4))
                        Text(quote.content.take(136), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(Grid.S8))
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text(if (quote != null) "Add your voice…" else "Report from the field…", color = TextMuted) },
                    minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (quote == null) {
                    Spacer(Modifier.height(Grid.S8))
                    OutlinedTextField(
                        value = media, onValueChange = { media = it },
                        placeholder = { Text("Media URL (optional)", color = TextMuted) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            NeonButton(if (busy) "…" else "DISPATCH", onClick = { onDispatch(text, media) }, color = ElectricBlue, enabled = !busy && text.isNotBlank())
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) } },
    )
}

@Composable
private fun ThreadDialog(
    target: HunterPostDto,
    replies: List<HunterPostDto>,
    busy: Boolean,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceHigh,
        title = { Text("THREAD — @${target.username}", color = ElectricBlue, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                Text(target.content, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Grid.S8))
                Box(Modifier.fillMaxWidth().height(1.dp).background(GridLine))
                Spacer(Modifier.height(Grid.S8))
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(Grid.S8)) {
                    if (replies.isEmpty()) item { Text("No echoes yet. Answer first.", style = MaterialTheme.typography.bodyMedium) }
                    items(replies, key = { it.id }) { r ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SurfaceDark).padding(Grid.S8)) {
                            Text("@${r.username ?: "hunter"} · ${relTime(r.createdAt)}", style = MaterialTheme.typography.labelSmall, color = ElectricBlue)
                            Spacer(Modifier.height(Grid.S4))
                            Text(dispatchBody(r.content), style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        }
                    }
                }
                Spacer(Modifier.height(Grid.S8))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("Reply…", color = TextMuted) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Grid.S8))
                    FloatingIconButton(Icons.AutoMirrored.Filled.Send, "Send reply", tint = ElectricBlue) { if (!busy) onSend(text).also { text = "" } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = TextMuted, modifier = Modifier.size(18.dp)) }
        },
    )
}

// ── TEXT / TIME UTILITIES ════════════════════════════════════════════════════

/** Rank halo palette — the spec's ring colors, mapped through the System rank engine. */
private fun rankRingColor(rank: SystemMath.HunterRank): Color = when (rank) {
    SystemMath.HunterRank.LOSER, SystemMath.HunterRank.GARBAGE -> PaperWhite
    SystemMath.HunterRank.AVERAGE -> Color(0xFF8A8A8A)
    SystemMath.HunterRank.ELITE -> Color(0xFFB0B0B0)
    SystemMath.HunterRank.S_RANK -> Color(0xFFD2D2D2)
    SystemMath.HunterRank.MASTERPIECE -> PaperWhite
}

/** Markdown-lite: **bold**, @handles, #hashtags — one AnnotatedString pass. */
private fun dispatchBody(text: String) = buildAnnotatedString {
    val regex = Regex("(\\*\\*[^*]+\\*\\*)|(@[A-Za-z0-9_]+)|(#[A-Za-z0-9_]+)")
    var last = 0
    for (m in regex.findAll(text)) {
        if (m.range.first > last) append(text.substring(last, m.range.first))
        when {
            m.value.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) {
                append(m.value.removeSurrounding("**"))
            }
            else -> withStyle(SpanStyle(color = ElectricBlue)) { append(m.value) }
        }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

/** Glyph-button click helper (no ripple — press-scale is the feedback). */
@Composable
private fun Modifier.clickableNoIndicationExt(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = src, indication = null, onClick = onClick)
}

private fun relTime(iso: String?): String {
    if (iso == null) return ""
    return try {
        // PostgREST flavors: "...T05:24:35.77+00:00", "... +00", "...Z" — normalize all.
        var v = iso.trim().replace(' ', 'T')
        if (Regex("[+-]\\d{2}$").containsMatchIn(v)) v += ":00"
        val t = OffsetDateTime.parse(v)
        val d = Duration.between(t, OffsetDateTime.now()).let { if (it.isNegative) it.negated() else it }
        when {
            d.toMinutes() < 1 -> "now"
            d.toHours() < 1 -> "${d.toMinutes()}m"
            d.toDays() < 1 -> "${d.toHours()}h"
            d.toDays() < 7 -> "${d.toDays()}d"
            else -> t.toLocalDate().toString()
        }
    } catch (e: Exception) { "" }
}

private fun shortCount(v: Long): String = when {
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000f)
    v >= 1_000 -> "%.1fK".format(v / 1_000f)
    else -> "$v"
}
