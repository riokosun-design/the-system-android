package com.thesystem.app.ui.squad

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.thesystem.app.Routes
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.ClanDto
import com.thesystem.app.data.model.UserDto
import com.thesystem.app.data.repo.SocialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// SQUAD CHATS — the messenger rail (WhatsApp dark pattern, System skin).
//
//   · header "SQUAD CHATS" + search / menu
//   · filter chips: ALL · GUILDS / PACKS · DIRECT MESSAGES · UNREAD
//   · guilds chip → persistent YOUR GUILD banner (or CREATE / BROWSE doors)
//   · WhatsApp row: 50dp avatar · title + timestamp · preview + unread badge
//   · FAB (cyan, 20dp above the rail) → START DIRECT CHAT / CREATE GUILD 500 VC
//
// DATA LAW: every row is a REAL thread — clan room history + DM streams from
// messages_with_sender. Unread counts are measured against locally stored
// per-thread seen marks (no server read-receipts exist; nothing is invented).
// Creating a guild / joining / DMing all hit the existing server RPCs.
// ─────────────────────────────────────────────────────────────────────────────

private val DividerSoft = Color(0xFF1A1A1A)
private val PreviewGray = Color(0xFF9CA3AF)
private val TimeGray = Color(0xFF888888)
private val ChipIdle = Color(0xFF262626)

enum class SquadFilter(val label: String) { ALL("ALL"), GUILDS("GUILDS / PACKS"), DIRECT("DIRECT MESSAGES"), UNREAD("UNREAD") }

data class SquadThreadRow(
    val id: String,          // "clan:<id>" | "dm:<userId>"
    val guild: Boolean,
    val title: String,
    val refName: String,     // username for DM routing, tag for guilds
    val preview: String,
    val lastAt: String?,
    val unread: Int,
    val refId: String,
)

data class SquadChatsState(
    val loading: Boolean = true,
    val threads: List<SquadThreadRow> = emptyList(),
    val inSquad: Boolean = false,
    val clanId: String? = null,
    val clanName: String = "",
    val clanTag: String = "",
    val clanLevel: Int = 1,
    val clanMembers: Int = 0,
    val discover: List<ClanDto> = emptyList(),
    val filter: SquadFilter = SquadFilter.ALL,
    val searchOpen: Boolean = false,
    val query: String = "",
    val fabOpen: Boolean = false,
    val browseOpen: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

internal fun isoMs(iso: String?): Long = runCatching {
    OffsetDateTime.parse(iso).toInstant().toEpochMilli()
}.getOrDefault(0L)

@HiltViewModel
class SquadChatsViewModel @Inject constructor(
    private val social: SocialRepository,
    @ApplicationContext app: Context,
) : ViewModel() {

    private val prefs = app.getSharedPreferences("squad_seen", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(SquadChatsState())
    val state: StateFlow<SquadChatsState> = _state

    init {
        // messenger cadence: 20s honest poll (realtime flows live inside the rooms)
        viewModelScope.launch { while (isActive) { refresh(); delay(20_000) } }
    }

    suspend fun refresh() {
        val me = social.myId()
        val status = runCatching { social.squadStatus() }.getOrNull()

        var clanRow: SquadThreadRow? = null
        var inSquad = false
        var clanId: String? = null
        var clanName = ""; var clanTag = ""; var clanLevel = 1; var clanMembers = 0

        if (status?.inSquad == true && status.clanId != null && me != null) {
            inSquad = true
            clanId = status.clanId
            val clan = runCatching { social.clan(status.clanId) }.getOrNull()
            clanName = clan?.name?.takeIf { it.isNotBlank() } ?: status.name
            clanTag = clan?.tag?.takeIf { it.isNotBlank() } ?: status.tag
            clanLevel = clan?.level ?: status.storedLevel
            clanMembers = clan?.memberCount ?: 0
            val msgs = social.clanMessagesRecent(status.clanId)
            val seen = prefs.getLong("seen:clan:${status.clanId}", 0L)
            val unread = msgs.count { isoMs(it.createdAt) > seen && it.senderId != me }
            val last = msgs.firstOrNull()
            clanRow = SquadThreadRow(
                id = "clan:${status.clanId}", guild = true,
                title = clanName, refName = clanTag,
                preview = last?.let { (if (it.senderId == me) "You: " else "@${it.senderUsername ?: "hunter"}: ") + it.body }
                    ?: "The hall is quiet. Fire the first shot.",
                lastAt = last?.createdAt, unread = unread, refId = status.clanId,
            )
        }

        val dmRows = mutableListOf<SquadThreadRow>()
        if (me != null) {
            val msgs = social.recentDmMessages()
            msgs.groupBy { if (it.senderId == me) it.recipientId ?: me else it.senderId }
                .entries.take(24).forEach { (pid, thread) ->
                    if (pid == me) return@forEach
                    val u = runCatching { social.userById(pid) }.getOrNull() ?: return@forEach
                    val seen = prefs.getLong("seen:dm:$pid", 0L)
                    val unread = thread.count { isoMs(it.createdAt) > seen && it.senderId != me }
                    val last = thread.first()
                    dmRows += SquadThreadRow(
                        id = "dm:$pid", guild = false,
                        title = u.displayName ?: u.username, refName = u.username,
                        preview = (if (last.senderId == me) "You: " else "") + last.body,
                        lastAt = last.createdAt, unread = unread, refId = pid,
                    )
                }
        }

        val discover = if (!inSquad) runCatching { social.clans().take(12) }.getOrDefault(emptyList()) else emptyList()

        _state.value = _state.value.copy(
            loading = false,
            threads = (listOfNotNull(clanRow) + dmRows.sortedByDescending { isoMs(it.lastAt) }),
            inSquad = inSquad, clanId = clanId, clanName = clanName, clanTag = clanTag,
            clanLevel = clanLevel, clanMembers = clanMembers, discover = discover,
        )
    }

    fun setFilter(f: SquadFilter) { _state.value = _state.value.copy(filter = f) }
    fun toggleSearch() { _state.value = _state.value.copy(searchOpen = !_state.value.searchOpen, query = "") }
    fun setQuery(q: String) { _state.value = _state.value.copy(query = q) }
    fun setFabOpen(v: Boolean) { _state.value = _state.value.copy(fabOpen = v) }
    fun toggleBrowse() { _state.value = _state.value.copy(browseOpen = !_state.value.browseOpen) }
    fun consumeNotice() { _state.value = _state.value.copy(notice = null, error = null) }

    fun markSeen(threadId: String) {
        prefs.edit().putLong("seen:$threadId", System.currentTimeMillis()).apply()
        _state.value = _state.value.copy(
            threads = _state.value.threads.map { if (it.id == threadId) it.copy(unread = 0) else it },
        )
    }

    fun joinGuild(clanId: String) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true)
        social.joinClan(clanId)
            .onSuccess { _state.value = _state.value.copy(busy = false, notice = "PACK JOINED — the hall opens for you") }
            .onFailure { _state.value = _state.value.copy(busy = false, error = it.message) }
        refresh()
    }

    fun createGuild(name: String, tag: String) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true)
        social.createClan(name.trim(), tag.trim().uppercase())
            .onSuccess {
                _state.value = _state.value.copy(busy = false, fabOpen = false, notice = "GUILD FORGED — your hall awaits in GUILDS / PACKS")
            }
            .onFailure { _state.value = _state.value.copy(busy = false, error = it.message) }
        refresh()
    }

    /** Passthrough for the FAB dialog's hunter search (debounced by the UI). */
    suspend fun searchHunters(q: String): List<UserDto> = runCatching { social.searchUsers(q).take(8) }.getOrDefault(emptyList())
}

// ══ SCREEN ═══════════════════════════════════════════════════════════════════

@Composable
fun SquadChatsScreen(nav: NavHostController, vm: SquadChatsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(s.notice, s.error) {
        if (s.error != null) haptics.error()
        (s.notice ?: s.error)?.let { snack.showSnackbar(it); vm.consumeNotice() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {

            // ── top app bar ──────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SQUAD CHATS", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("MESSENGER RAIL", style = MonoLabel, color = FaintGray)
                }
                IconButton(onClick = { haptics.tick(); vm.toggleSearch() }) {
                    Icon(Icons.Default.Search, "search threads", tint = if (s.searchOpen) SkyBlue else Color.White)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "menu", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("GUILD COMMAND") },
                            onClick = { menuOpen = false; nav.navigate(Routes.SQUAD_COMMAND) },
                        )
                        DropdownMenuItem(
                            text = { Text("REFRESH") },
                            onClick = { menuOpen = false; scope.launch { vm.refresh() } },
                        )
                    }
                }
            }

            // ── inline thread search (filters what is already loaded — no fake magic) ──
            if (s.searchOpen) {
                OutlinedTextField(
                    value = s.query, onValueChange = vm::setQuery,
                    placeholder = { Text("Filter channels…", color = TimeGray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SkyBlue, unfocusedBorderColor = ChipIdle,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    ),
                )
                Spacer(Modifier.height(8.dp))
            }

            // ── filter chips (horizontally scrollable) ───────────────────
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SquadFilter.entries.forEach { f ->
                    val on = s.filter == f
                    Text(
                        f.label,
                        color = if (on) SkyBlue else PreviewGray,
                        fontSize = 11.sp, fontFamily = SystemMono,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, letterSpacing = 1.sp,
                        modifier = Modifier
                            .border(1.dp, if (on) SkyBlue else ChipIdle, RoundedCornerShape(16.dp))
                            .clickable { haptics.select(); vm.setFilter(f) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── GUILDS chip: persistent banner ───────────────────────────
            if (s.filter == SquadFilter.GUILDS) {
                if (s.inSquad) GuildBanner(s) { haptics.select(); nav.navigate(Routes.SQUAD_COMMAND) }
                else NoGuildBanner(
                    onCreate = { haptics.select(); vm.setFabOpen(true) },
                    onBrowse = { haptics.select(); vm.toggleBrowse() },
                )
            }

            val visible = s.threads
                .filter {
                    when (s.filter) {
                        SquadFilter.ALL -> true
                        SquadFilter.GUILDS -> it.guild
                        SquadFilter.DIRECT -> !it.guild
                        SquadFilter.UNREAD -> it.unread > 0
                    }
                }
                .filter {
                    s.query.isBlank() || it.title.contains(s.query, true) || it.preview.contains(s.query, true)
                }

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(visible, key = { it.id }) { row ->
                    ThreadRow(
                        row = row,
                        onOpen = {
                            haptics.tick()
                            vm.markSeen(row.id)
                            if (row.guild) nav.navigate(Routes.CHAT)
                            else nav.navigate(Routes.dm(row.refId, row.refName))
                        },
                    )
                    Box(Modifier.fillMaxWidth().padding(start = 74.dp).height(1.dp).background(DividerSoft))
                }

                // BROWSE GUILDS — every pack that exists, real member counts
                if (s.filter == SquadFilter.GUILDS && !s.inSquad && s.browseOpen) {
                    items(s.discover, key = { "guild-" + it.id }) { c ->
                        DiscoverRow(c, joining = s.busy) { vm.joinGuild(c.id) }
                        Box(Modifier.fillMaxWidth().padding(start = 74.dp).height(1.dp).background(DividerSoft))
                    }
                    if (s.discover.isEmpty()) item { QuietHint("No packs forged yet. Yours could be the first.") }
                }

                if (visible.isEmpty() && !(s.filter == SquadFilter.GUILDS && !s.inSquad)) {
                    item {
                        QuietHint(
                            when {
                                s.loading -> "TUNING CHANNELS…"
                                s.filter == SquadFilter.UNREAD -> "ALL CHANNELS CLEAR."
                                s.filter == SquadFilter.DIRECT -> "No direct channels. The beacon below finds hunters."
                                s.filter == SquadFilter.GUILDS -> "Your pack hall will surface here."
                                else -> "NO TRANSMISSIONS YET — the beacon below opens one."
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(90.dp)) } // FAB airspace
            }
        }

        // ── FAB — cyan beacon, 20dp above the rail ───────────────────────
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 20.dp)
                .size(56.dp)
                .clip(CircleShape)
                .background(SkyBlue)
                .clickable { haptics.select(); vm.setFabOpen(true) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Chat, "new chat", tint = Color.Black)
        }

        Box(Modifier.align(Alignment.BottomCenter)) { SnackbarHost(snack) }
    }

    if (s.fabOpen) FabDialog(s, vm, nav)
}

// ── rows ─────────────────────────────────────────────────────────────────────

@Composable
private fun ThreadRow(row: SquadThreadRow, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 50dp circular avatar — guild emblem (tag initial) / hunter handle initial + badge dot
        Box(Modifier.size(50.dp)) {
            Box(
                Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0D0D0D))
                    .border(1.dp, if (row.guild) SkyBlue else ChipIdle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (if (row.guild) row.refName else row.title).take(1).uppercase(),
                    color = if (row.guild) SkyBlue else Color.White,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono,
                )
            }
            if (!row.guild) {
                Box(Modifier.align(Alignment.BottomEnd).size(10.dp).clip(CircleShape).background(SkyBlue))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(timeLabel(row.lastAt), color = TimeGray, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.preview, color = PreviewGray, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (row.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).background(SkyBlue),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (row.unread > 99) "99" else "${row.unread}",
                            color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverRow(c: ClanDto, joining: Boolean, onJoin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(50.dp).clip(CircleShape).background(Color(0xFF0D0D0D)).border(1.dp, ChipIdle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(c.tag.take(1), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "[${c.tag}] · ${c.memberCount ?: "—"} HUNTERS · LV ${c.level}",
                color = PreviewGray, fontSize = 12.sp, fontFamily = SystemMono,
            )
        }
        GhostButton("JOIN", onJoin, enabled = !joining)
    }
}

// ── banners ──────────────────────────────────────────────────────────────────

@Composable
private fun GuildBanner(s: SquadChatsState, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .border(1.dp, SkyBlue.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text("YOUR GUILD: ${s.clanName.uppercase()}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
        Text(
            "[${s.clanTag}] · ${if (s.clanMembers > 0) "${s.clanMembers} HUNTERS" else "HUNTERS —"} · LEVEL ${s.clanLevel} · TAP FOR COMMAND",
            style = MonoLabel, color = SkyBlue,
        )
    }
}

@Composable
private fun NoGuildBanner(onCreate: () -> Unit, onBrowse: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .border(1.dp, ChipIdle, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text("NO PACK YET", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono)
        Text("A guild multiplies discipline. Forge one or join an existing hall.", style = MonoLabel, color = PreviewGray)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("CREATE GUILD (500 VC)", onCreate, Modifier.weight(1f), color = SkyBlue)
            GhostButton("BROWSE GUILDS", onBrowse, Modifier.weight(1f))
        }
    }
}

// ── FAB dialog: START DIRECT CHAT / CREATE GUILD ─────────────────────────────

@Composable
private fun FabDialog(s: SquadChatsState, vm: SquadChatsViewModel, nav: NavHostController) {
    var mode by remember { mutableIntStateOf(0) } // 0 DM · 1 GUILD
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var gName by remember { mutableStateOf("") }
    var gTag by remember { mutableStateOf("") }
    LaunchedEffect(q) {
        if (q.trim().length >= 2) { delay(350); results = vm.searchHunters(q.trim()) } else results = emptyList()
    }

    Dialog(onDismissRequest = { vm.setFabOpen(false) }) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0A0A), RoundedCornerShape(14.dp))
                .border(1.dp, ChipIdle, RoundedCornerShape(14.dp))
                .padding(16.dp),
        ) {
            Text("NEW TRANSMISSION", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = SystemMono, letterSpacing = 1.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "START DIRECT CHAT",
                    color = if (mode == 0) SkyBlue else PreviewGray, fontSize = 11.sp, fontFamily = SystemMono,
                    fontWeight = if (mode == 0) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .border(1.dp, if (mode == 0) SkyBlue else ChipIdle, RoundedCornerShape(14.dp))
                        .clickable { mode = 0 }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Text(
                    "CREATE GUILD / PACK",
                    color = if (mode == 1) SkyBlue else PreviewGray, fontSize = 11.sp, fontFamily = SystemMono,
                    fontWeight = if (mode == 1) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .border(1.dp, if (mode == 1) SkyBlue else ChipIdle, RoundedCornerShape(14.dp))
                        .clickable { mode = 1 }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))

            if (mode == 0) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it },
                    placeholder = { Text("@handle of a hunter", color = TimeGray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SkyBlue, unfocusedBorderColor = ChipIdle,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                results.forEach { u ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setFabOpen(false); nav.navigate(Routes.dm(u.id, u.username)) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(u.displayName ?: u.username, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("@${u.username} · LV ${u.level}", color = PreviewGray, fontSize = 11.sp, fontFamily = SystemMono)
                        }
                        Text("DM ▸", color = SkyBlue, fontSize = 12.sp, fontFamily = SystemMono, fontWeight = FontWeight.Bold)
                    }
                }
                if (q.trim().length >= 2 && results.isEmpty()) {
                    Text("No hunter answers to that handle.", color = TimeGray, fontSize = 12.sp)
                }
            } else {
                Text("COST: 500 VC · LEVEL 30 — the System validates, never the button.", style = MonoLabel, color = TimeGray)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = gName, onValueChange = { gName = it.take(24) },
                    placeholder = { Text("Guild name", color = TimeGray) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SkyBlue, unfocusedBorderColor = ChipIdle, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = gTag, onValueChange = { gTag = it.take(5) },
                    placeholder = { Text("TAG (max 5)", color = TimeGray) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SkyBlue, unfocusedBorderColor = ChipIdle, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                )
                Spacer(Modifier.height(12.dp))
                NeonButton(
                    if (s.busy) "FORGING…" else "CREATE GUILD (500 VC)",
                    { if (gName.isNotBlank() && gTag.isNotBlank()) vm.createGuild(gName, gTag) },
                    Modifier.fillMaxWidth(), color = SkyBlue,
                    enabled = !s.busy && gName.isNotBlank() && gTag.isNotBlank(),
                )
            }
        }
    }
}

// ── small shared bits ────────────────────────────────────────────────────────

@Composable
private fun QuietHint(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = TimeGray, fontSize = 12.sp, fontFamily = SystemMono, letterSpacing = 0.5.sp)
    }
}

private fun timeLabel(iso: String?): String {
    if (iso == null) return ""
    return runCatching {
        val t = OffsetDateTime.parse(iso)
        val today = LocalDate.now(ZoneId.systemDefault())
        if (t.toLocalDate() == today) t.atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        else t.format(DateTimeFormatter.ofPattern("dd/MM"))
    }.getOrDefault("")
}
