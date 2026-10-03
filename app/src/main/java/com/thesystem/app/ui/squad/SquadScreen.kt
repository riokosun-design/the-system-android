package com.thesystem.app.ui.squad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.thesystem.app.Routes
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.ClanDto
import com.thesystem.app.data.model.ClanMemberDto
import com.thesystem.app.data.model.SquadLeaderRowDto
import com.thesystem.app.data.model.SquadStatusDto
import com.thesystem.app.data.model.UserDto
import com.thesystem.app.data.repo.SocialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * SQUAD (STEPs 11–14) — the social spine. Two honest states:
 *
 *   NO PACK  → FORM PACK (real gate: Level 30 + 500 VC, enforced server-side),
 *              JOIN PACK (every pack that exists, real member counts),
 *              FIND HUNTERS (live username search → DM via the existing chat).
 *   IN PACK  → MASTER (name/tag/treasury/level), WAR SCORE with the three
 *              MEASURED components (41% work · 9% member level · 50% wars —
 *              contribution weights, never luck), WEEKLY TASK (rotates
 *              server-side every ISO week), WARS (sealed until the monthly
 *              window exists — no fake clashes), ROSTER (real members,
 *              DM doors), GLOBAL RANKINGS (server-computed, top 10).
 *
 * Every number comes from squad_status / squad_leaderboard RPCs (mig 026).
 */
data class SquadState(
    val loading: Boolean = true,
    val status: SquadStatusDto? = null,
    val members: List<ClanMemberDto> = emptyList(),
    val myId: String = "",
    val discover: List<ClanDto> = emptyList(),
    val leaders: List<SquadLeaderRowDto> = emptyList(),
    val query: String = "",
    val searchResults: List<UserDto> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
)

@HiltViewModel
class SquadViewModel @Inject constructor(private val social: SocialRepository) : ViewModel() {
    private val _state = MutableStateFlow(SquadState())
    val state = _state

    init {
        viewModelScope.launch {
            while (isActive) { refresh(); delay(45_000) }
        }
        // manual debounce: search 350ms after the last keystroke
        viewModelScope.launch {
            _state.map { it.query }.distinctUntilChanged().collectLatest { q ->
                delay(350)
                val results = if (q.trim().length >= 2) social.searchUsers(q.trim()).take(8) else emptyList()
                _state.value = _state.value.copy(searchResults = results)
            }
        }
    }

    suspend fun refresh() {
        val st = social.squadStatus()
        val inSquad = st?.inSquad == true
        val members = if (inSquad) social.clanMembers(st?.clanId ?: "") else emptyList()
        val myId = if (inSquad) social.myMembership()?.userId ?: "" else ""
        _state.value = _state.value.copy(
            loading = false,
            status = st,
            members = members,
            myId = myId,
            discover = if (inSquad) emptyList() else social.clans(),
            leaders = social.squadLeaderboard().take(10),
        )
    }

    fun setQuery(v: String) { _state.value = _state.value.copy(query = v) }

    fun create(name: String, tag: String) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        val r = social.createClan(name.trim(), tag.trim().uppercase())
        _state.value = _state.value.copy(
            busy = false,
            notice = if (r.isSuccess) "PACK FORMED. NOW WORK." else "DENIED: ${r.exceptionOrNull()?.message ?: "gate not met"}",
        )
        refresh()
    }

    fun join(clanId: String) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        val r = social.joinClan(clanId)
        _state.value = _state.value.copy(
            busy = false,
            notice = if (r.isSuccess) "YOU ARE IN. PULL YOUR WEIGHT." else "DENIED: ${r.exceptionOrNull()?.message ?: ""}",
        )
        refresh()
    }

    fun leave() = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        social.leaveClan()
        _state.value = _state.value.copy(busy = false, notice = "YOU LEFT THE PACK.")
        refresh()
    }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null) }
}

private fun num(v: Long): String = String.format(Locale.US, "%,d", v)

@Composable
fun SquadScreen(nav: NavHostController, vm: SquadViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(s.notice) {
        s.notice?.let { snack.showSnackbar(it); vm.consumeNotice() }
    }

    SystemBackground {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Grid.Margin).padding(top = Grid.S16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("SYSTEM", style = MonoLabel, color = LabelGray)
                        Text("SQUAD", color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("CONTRIBUTION WEIGHTS", style = MonoLabel, color = LabelGray)
                        Text("41 · 9 · 50", style = MonoData, color = PaperWhite)
                    }
                }
                Spacer(Modifier.height(Grid.S8))

                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = Grid.Margin),
                    verticalArrangement = Arrangement.spacedBy(Grid.CardSpace),
                    contentPadding = PaddingValues(vertical = Grid.S16),
                ) {
                    if (s.loading) {
                        item { SkeletonCards(3) }
                    } else if (s.status?.inSquad == true) {
                        val st = s.status!!
                        item { SectionTitle("YOUR PACK", PaperWhite) }
                        item { MasterCard(st, s.members.size) { haptics.error(); vm.leave() } }
                        item { WarScoreCard(st) }
                        item { WeeklyTaskCard(st) }
                        item { WarsCard() }
                        item {
                            RosterCard(s.members, s.myId) { m ->
                                haptics.tick()
                                nav.navigate(Routes.dm(m.userId, m.username ?: "hunter"))
                            }
                        }
                        item { LeaderboardCard(s.leaders, st.clanId) }
                    } else {
                        item { SectionTitle("NO PACK YET", PaperWhite) }
                        item { CreateCard(s.busy) { name, tag -> vm.create(name, tag) } }
                        item { JoinCard(s.discover, s.busy) { id -> haptics.select(); vm.join(id) } }
                        item {
                            FindHuntersCard(
                                query = s.query,
                                results = s.searchResults,
                                onQuery = vm::setQuery,
                                onDm = { u ->
                                    haptics.tick()
                                    nav.navigate(Routes.dm(u.id, u.username))
                                },
                            )
                        }
                        item { LeaderboardCard(s.leaders, null) }
                    }
                    item { Spacer(Modifier.height(Grid.S24)) }
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
        }
    }
}

// ── IN-PACK CARDS ────────────────────────────────────────────────────────────

@Composable
private fun MasterCard(st: SquadStatusDto, memberCount: Int, onLeave: () -> Unit) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PACK", style = MonoLabel, color = LabelGray)
            Spacer(Modifier.weight(1f))
            Text("$memberCount MEMBERS", style = MonoLabel, color = LabelGray)
        }
        Spacer(Modifier.height(Grid.S8))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(st.name.uppercase(), style = MaterialTheme.typography.headlineMedium, color = PaperWhite)
            Spacer(Modifier.width(Grid.S8))
            Text("[${st.tag}]", style = MonoTitle, color = PaperWhite)
        }
        Spacer(Modifier.height(Grid.S8))
        Row(horizontalArrangement = Arrangement.spacedBy(Grid.S16)) {
            Text("LEVEL ${st.storedLevel}", style = MonoData, color = LabelGray)
            Text("TREASURY ${num(st.treasuryVc)} VC", style = MonoData, color = LabelGray)
        }
        Spacer(Modifier.height(Grid.S12))
        GhostButton("LEAVE PACK", onClick = onLeave)
    }
}

@Composable
private fun WarScoreCard(st: SquadStatusDto) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("WAR SCORE", style = MonoLabel, color = LabelGray)
            Spacer(Modifier.weight(1f))
            Text("${st.score}", style = MonoDisplay, color = PaperWhite)
        }
        Spacer(Modifier.height(Grid.S12))
        ComponentBar("SQUAD WORK", st.workComponent, 41, "weekly task progress")
        Spacer(Modifier.height(Grid.S8))
        ComponentBar("MEMBER LEVEL", st.levelComponent, 9, "average level vs 50")
        Spacer(Modifier.height(Grid.S8))
        ComponentBar("SQUAD WARS", st.warsComponent, 50, "war points vs 100")
        Spacer(Modifier.height(Grid.S12))
        Text("MEASURED. NO LUCK. NO RNG.", style = MonoLabel, color = FaintGray)
    }
}

@Composable
private fun ComponentBar(label: String, frac: Double, weight: Int, sub: String) {
    val f = frac.coerceIn(0.0, 1.0).toFloat()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = PaperWhite)
            Spacer(Modifier.width(Grid.S8))
            Text("$weight%", style = MonoData, color = PaperWhite)
            Spacer(Modifier.weight(1f))
            Text("${(f * 100).toInt()}% · $sub", style = MonoLabel, color = FaintGray)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(TrackGray)) {
            Box(
                Modifier.fillMaxWidth(f).height(5.dp)
                    .clip(RoundedCornerShape(3.dp)).background(PaperWhite)
            )
        }
    }
}

@Composable
private fun WeeklyTaskCard(st: SquadStatusDto) {
    val target = st.taskTarget.coerceAtLeast(1).toLong()
    val progress = st.taskProgress.coerceAtLeast(0)
    val frac = (progress.toFloat() / target).coerceIn(0f, 1f)
    val done = progress >= target
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("WEEKLY TASK · ROTATES EVERY WEEK", style = MonoLabel, color = LabelGray)
            Spacer(Modifier.weight(1f))
            if (done) SystemChip("CLEARED", PaperWhite)
        }
        Spacer(Modifier.height(Grid.S8))
        Text(st.taskTitle, style = MaterialTheme.typography.titleMedium, color = PaperWhite)
        Spacer(Modifier.height(Grid.S8))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(TrackGray)) {
            Box(
                Modifier.fillMaxWidth(frac).height(6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(PaperWhite)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("${num(progress)} / ${num(target)} ${st.taskUnit}", style = MonoData, color = LabelGray)
    }
}

@Composable
private fun WarsCard() {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Text("SQUAD WARS · MONTHLY", style = MonoLabel, color = LabelGray)
        Spacer(Modifier.height(Grid.S8))
        Text(
            "SEALED — FIRST WINDOW OPENS WITH THE FIRST MONTHLY CYCLE",
            style = MaterialTheme.typography.labelLarge, color = PaperWhite,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Wars are scheduled monthly. When a window opens, packs clash and war points move the 50% component. Nothing is simulated before then.",
            style = MaterialTheme.typography.bodySmall, color = FaintGray,
        )
    }
}

@Composable
private fun RosterCard(members: List<ClanMemberDto>, myId: String, onDm: (ClanMemberDto) -> Unit) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Text("ROSTER", style = MonoLabel, color = LabelGray)
        Spacer(Modifier.height(Grid.S8))
        if (members.isEmpty()) {
            Text("No roster loaded.", style = MaterialTheme.typography.bodySmall, color = FaintGray)
        }
        members.forEach { m ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "@${m.username ?: "hunter"}",
                            style = MonoData,
                            color = if (m.userId == myId) PaperWhite else LabelGray,
                        )
                        if (m.role != "MEMBER") {
                            Spacer(Modifier.width(Grid.S8))
                            SystemChip(if (m.role == "GUILD_MASTER") "GM" else m.role, PaperWhite)
                        }
                    }
                }
                if (m.userId != myId) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, "DM",
                        tint = LabelGray,
                        modifier = Modifier.size(18.dp).clickable { onDm(m) },
                    )
                } else {
                    Text("YOU", style = MonoLabel, color = FaintGray)
                }
            }
        }
    }
}

// ── NO-PACK CARDS ────────────────────────────────────────────────────────────

@Composable
private fun CreateCard(busy: Boolean, onCreate: (String, String) -> Unit) {
    val haptics = rememberSystemHaptics()
    var name by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Text("FORM A PACK", style = MonoLabel, color = LabelGray)
        Spacer(Modifier.height(4.dp))
        Text("GATE: LEVEL 30 · COSTS 500 VC", style = MonoLabel, color = PaperWhite)
        Spacer(Modifier.height(Grid.S8))
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("PACK NAME", style = MonoLabel) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Grid.S8))
        OutlinedTextField(
            value = tag, onValueChange = { tag = it.uppercase().take(6) },
            label = { Text("TAG (3–6 LETTERS)", style = MonoLabel) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Grid.S12))
        NeonButton(
            "CREATE PACK",
            onClick = { haptics.select(); onCreate(name, tag) },
            enabled = !busy && name.trim().length >= 3 && tag.trim().length >= 3,
        )
    }
}

@Composable
private fun JoinCard(discover: List<ClanDto>, busy: Boolean, onJoin: (String) -> Unit) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Text("JOIN A PACK", style = MonoLabel, color = LabelGray)
        Spacer(Modifier.height(Grid.S8))
        if (discover.isEmpty()) {
            Text("No packs founded yet. Be the first.", style = MaterialTheme.typography.bodySmall, color = FaintGray)
        }
        discover.forEach { c ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${c.name}  [${c.tag}]", style = MonoData, color = PaperWhite)
                    Text("${c.memberCount ?: 0} MEMBERS · LEVEL ${c.level}", style = MonoLabel, color = FaintGray)
                }
                GhostButton("JOIN", onClick = { if (!busy) onJoin(c.id) })
            }
        }
    }
}

@Composable
private fun FindHuntersCard(
    query: String,
    results: List<UserDto>,
    onQuery: (String) -> Unit,
    onDm: (UserDto) -> Unit,
) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Text("FIND HUNTERS", style = MonoLabel, color = LabelGray)
        Spacer(Modifier.height(Grid.S8))
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            label = { Text("USERNAME", style = MonoLabel) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Grid.S8))
        if (query.trim().length >= 2 && results.isEmpty()) {
            Text("No hunter under that handle.", style = MaterialTheme.typography.bodySmall, color = FaintGray)
        }
        results.forEach { u ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("@${u.username}", style = MonoData, color = PaperWhite)
                    Text("LEVEL ${u.level}", style = MonoLabel, color = FaintGray)
                }
                Icon(
                    Icons.AutoMirrored.Filled.Send, "DM",
                    tint = LabelGray,
                    modifier = Modifier.size(18.dp).clickable { onDm(u) },
                )
            }
        }
    }
}

@Composable
private fun LeaderboardCard(leaders: List<SquadLeaderRowDto>, myClanId: String?) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Text("GLOBAL SQUAD RANKINGS", style = MonoLabel, color = LabelGray)
        Spacer(Modifier.height(Grid.S8))
        if (leaders.isEmpty()) {
            Text("No packs ranked yet.", style = MaterialTheme.typography.bodySmall, color = FaintGray)
        }
        leaders.forEachIndexed { idx, row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#${idx + 1}", style = MonoData, color = if (idx == 0) PaperWhite else FaintGray)
                Spacer(Modifier.width(Grid.S12))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${row.name}  [${row.tag}]",
                        style = MonoData,
                        color = if (row.id == myClanId) PaperWhite else LabelGray,
                    )
                    Text("${row.members} MEMBERS", style = MonoLabel, color = FaintGray)
                }
                Text("${row.score}", style = MonoTitle, color = PaperWhite)
            }
        }
    }
}
