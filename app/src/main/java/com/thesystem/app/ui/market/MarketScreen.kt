package com.thesystem.app.ui.market

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.thesystem.app.Routes
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.CourseDto
import com.thesystem.app.data.model.ProductDto
import com.thesystem.app.data.model.UserCourseDto
import com.thesystem.app.data.repo.TrainingRepository
import com.thesystem.app.data.repo.CommerceRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MARKET — one counter for everything the System sells:
 *   VC top-up · affiliate products · training courses · Black Room · digital goods.
 * Existing economy untouched: VC stays server-authoritative, every price is
 * admin/server-controlled, and settlement is the manual UPI rail (UTR + proof
 * → admin approval). No payment SDK, no in-app purchase.
 */
data class MarketState(
    val loading: Boolean = true,
    val vc: Long = 0,
    val products: List<ProductDto> = emptyList(),
    val courses: List<CourseDto> = emptyList(),
    val myCourses: List<UserCourseDto> = emptyList(),
    // ── AI ENGINE (spec §9): daily nutrition plan, REAL products only
    val nutritionPlan: com.thesystem.app.ai.NutritionPlan? = null,
    val nutritionBrain: String = "",
    val nutritionBusy: Boolean = false,
    val nutritionNote: String? = null,
    val error: String? = null,
) {
    fun enrollmentOf(c: CourseDto): UserCourseDto? = myCourses.firstOrNull { it.courseId == c.id }
    val digital: List<ProductDto> get() = products.filter { it.category == "DIGITAL" }
    val affiliate: List<ProductDto> get() = products.filter { it.category != "DIGITAL" }
    val blackRoom: CourseDto? get() = courses.firstOrNull { it.type == "FORBIDDEN" }
}

@HiltViewModel
class MarketViewModel @Inject constructor(
    private val system: SystemRepository,
    private val commerce: CommerceRepository,
    private val training: TrainingRepository,
    private val orchestrator: com.thesystem.app.ai.AIOrchestrator,
    private val contextEngine: com.thesystem.app.ai.ContextEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(MarketState())
    val state: StateFlow<MarketState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        val me = system.profile()
        val products = commerce.products()
        _state.value = _state.value.copy(
            loading = false,
            vc = me?.vcBalance ?: 0,
            products = products,
            courses = training.courses("MUSCLE") + training.courses("SPECIAL") + training.courses("FORBIDDEN"),
            myCourses = training.myCourses(),
        )
    }

    // ── AI ENGINE (spec §9): plan → validate vs REAL inventory → hunter reviews ──
    // NEVER purchases: confirmation is an acknowledgement, settlement stays manual UPI.
    fun planNutrition(budgetInr: Double) = viewModelScope.launch {
        if (_state.value.nutritionBusy) return@launch
        _state.value = _state.value.copy(nutritionBusy = true, nutritionNote = null)
        val snap = contextEngine.snapshot(
            setOf(
                com.thesystem.app.ai.ContextEngine.Need.PROFILE,
                com.thesystem.app.ai.ContextEngine.Need.QUESTS,
                com.thesystem.app.ai.ContextEngine.Need.MARKET,
            ),
        )
        val out = orchestrator.planNutrition(snap, budgetInr)
        _state.value = _state.value.copy(
            nutritionBusy = false,
            nutritionPlan = out.value,
            nutritionBrain = when (out.brain) {
                com.thesystem.app.ai.AIOrchestrator.Brain.LOCAL_LLM -> "LOCAL LLM"
                com.thesystem.app.ai.AIOrchestrator.Brain.DETERMINISTIC -> "RULES"
                com.thesystem.app.ai.AIOrchestrator.Brain.DISABLED -> "OFF"
            },
            nutritionNote = if (out.value == null) "NO PLAN — ${out.note.ifBlank { "off-grid" }}" else null,
        )
    }

    fun confirmNutrition() {
        _state.value = _state.value.copy(
            nutritionNote = "PLAN CONFIRMED — add-ons stay manual; nothing was purchased.",
        )
    }

    fun dismissNutrition() {
        _state.value = _state.value.copy(nutritionPlan = null, nutritionNote = null)
    }
}

@Composable
fun MarketScreen(nav: NavHostController, vm: MarketViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()

    SystemBackground {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin),
            verticalArrangement = Arrangement.spacedBy(Grid.CardSpace),
            contentPadding = PaddingValues(vertical = Grid.S16),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SYSTEM", style = MonoLabel, color = SkyBlue)
                        Text("MARKET", color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 1.5.sp)
                    }
                    VcChip(s.vc)
                }
            }

            // ── VC / wallet (server balance, read-only here) ──────────────────
            item {
                GlowCard(modifier = Modifier.fillMaxWidth()) {
                    SectionTitle("VC WALLET", PaperWhite)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Virtual coins are earned from cleared protocol blocks, wars and challenges. " +
                            "They are spent inside the System — never withdrawn, never cashed out.",
                        style = MaterialTheme.typography.bodySmall, color = LabelGray,
                    )
                }
            }

            // ── DAILY FUEL — AI nutrition planner (§9/§10: real inventory only) ──
            item {
                NutritionPlannerCard(s, vm)
            }

            // ── courses for sale / enrollment ────────────────────────────────
            item {
                Column {
                    SectionTitle("TRAINING PROGRAMS", SkyBlue)
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Grid.S12)) {
                        items(s.courses, key = { it.id }) { c ->
                            MarketCourseRow(c, s.enrollmentOf(c)) {
                                haptics.select()
                                nav.navigate(Routes.TRAINING)
                            }
                        }
                    }
                }
            }

            // ── Black Room ───────────────────────────────────────────────────
            s.blackRoom?.let { br ->
                item {
                    GlowCard(modifier = Modifier.fillMaxWidth().clickable {
                        haptics.select(); nav.navigate(Routes.BLACK_ROOM)
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = br.cover, contentDescription = "Black Room",
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(Grid.S12))
                            Column(Modifier.weight(1f)) {
                                Text("BLACK ROOM", color = PaperWhite, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Two-year bespoke protocol · server-priced ₹/$ · admin-built",
                                    style = MaterialTheme.typography.bodySmall, color = LabelGray,
                                )
                            }
                            Text("OPEN", style = MonoLabel, color = SkyBlue)
                        }
                    }
                }
            }

            // ── digital products — server-configured rows only ───────────────
            item { SectionTitle("DIGITAL PRODUCTS") }
            if (s.digital.isNotEmpty()) {
                items(s.digital, key = { it.id }) { p -> ProductCard(p) }
            } else {
                item {
                    EmptyShelf(
                        "No digital goods are configured yet. The operator publishes them " +
                            "from the admin console — nothing appears here automatically."
                    )
                }
            }

            // ── affiliate shelves — configured offers only, never seeded noise ─
            item { SectionTitle("AFFILIATE SHELVES") }
            if (s.affiliate.isNotEmpty()) {
                items(s.affiliate, key = { it.id }) { p -> ProductCard(p) }
            } else {
                item { EmptyShelf("No offers available yet.") }
            }

            item {
                Text(
                    "All prices are set server-side by the operator. Purchases settle through the manual UPI rail " +
                        "(UTR + screenshot → admin approval).",
                    style = MaterialTheme.typography.bodySmall, color = FaintGray,
                )
            }
            item { Spacer(Modifier.height(60.dp)) }
        }
    }
}

/** Quiet shelf used instead of fake products: the truth, beautifully empty. */
@Composable
private fun EmptyShelf(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, LineSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = Grid.S16, vertical = Grid.S24),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = LabelGray,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun MarketCourseRow(c: CourseDto, enrollment: UserCourseDto?, onTap: () -> Unit) {
    // split layout like the training cards: art block on top, info block below
    Column(
        Modifier
            .width(164.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PanelGray)
            .border(1.dp, LineSoft, RoundedCornerShape(12.dp))
            .clickable { onTap() },
    ) {
        AsyncImage(
            model = c.cover, contentDescription = c.title,
            modifier = Modifier.fillMaxWidth().height(88.dp), contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
        Column(Modifier.fillMaxWidth().padding(Grid.S12)) {
            Text(
                c.title.uppercase(), color = PaperWhite, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(c.type, style = MonoLabel, color = SkyBlue)
            Spacer(Modifier.height(2.dp))
            Text(
                if (enrollment != null) "%.0f%% COMPLETE".format(enrollment.progressPercent) else "VIEW PROGRAM",
                style = MonoLabel, color = LabelGray,
            )
        }
    }
}

@Composable
private fun ProductCard(p: ProductDto) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = PaperWhite, style = MaterialTheme.typography.titleMedium)
                Text(
                    p.description ?: "",
                    style = MaterialTheme.typography.bodySmall, color = LabelGray,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text("RANK ${p.minRankRequired} · ${p.category}", style = MonoLabel, color = LabelGray)
            }
            Text("₹%.0f".format(p.priceInr), color = PaperWhite, fontFamily = SystemMono,
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

// ── DAILY FUEL — NutritionPlanner UI (spec §9): plan · review · confirm ──────
// Laws: products/prices are backend truth; no invented items; no auto-purchase;
// minors get conservative floors (enforced in AIValidator + DeterministicAI).

@Composable
private fun NutritionPlannerCard(s: MarketState, vm: MarketViewModel) {
    val haptics = rememberSystemHaptics()
    var budgetText by remember { mutableStateOf("500") }
    val plan = s.nutritionPlan

    GlowCard(modifier = Modifier.fillMaxWidth()) {
        SectionTitle("DAILY FUEL — NUTRITION PLANNER", PaperWhite)
        Spacer(Modifier.height(4.dp))
        Text(
            "A day plan built from YOUR goal and training. Market add-ons come from the real shelves only — you review, you confirm, nothing is ever auto-purchased.",
            style = MaterialTheme.typography.bodySmall, color = LabelGray,
        )
        Spacer(Modifier.height(Grid.S8))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = budgetText,
                onValueChange = { budgetText = it.filter { c -> c.isDigit() }.take(5) },
                modifier = Modifier.weight(1f),
                label = { Text("DAILY BUDGET ₹", color = FaintGray, fontSize = 9.sp, fontFamily = SystemMono) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PaperWhite, unfocusedBorderColor = LineSoft,
                    focusedTextColor = PaperWhite, unfocusedTextColor = PaperWhite,
                    cursorColor = PaperWhite,
                    focusedContainerColor = PanelGray, unfocusedContainerColor = PanelGray,
                ),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.width(8.dp))
            NeonButton(
                if (s.nutritionBusy) "PLANNING…" else "PLAN",
                onClick = {
                    haptics.select()
                    vm.planNutrition(budgetText.toDoubleOrNull() ?: 500.0)
                },
                enabled = !s.nutritionBusy,
            )
        }

        s.nutritionNote?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MonoLabel, color = LabelGray)
        }

        plan?.let { p ->
            Spacer(Modifier.height(Grid.S12))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${p.calorieTarget} KCAL TARGET", style = MonoData, color = PaperWhite)
                Spacer(Modifier.weight(1f))
                Text("BY ${s.nutritionBrain}", style = MonoLabel, color = FaintGray)
            }
            Spacer(Modifier.height(8.dp))
            p.slots.forEach { slot ->
                Text("▸ $slot", style = MonoLabel, color = LabelGray, modifier = Modifier.padding(vertical = 2.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("MARKET MATCHES", style = MonoLabel, color = FaintGray)
            Spacer(Modifier.height(4.dp))
            if (p.marketRefs.isEmpty()) {
                Text(
                    "No real shelf products matched today's plan — clean empty state, nothing invented.",
                    style = MonoLabel, color = FaintGray,
                )
            } else {
                p.marketRefs.forEach { ref ->
                    val prod = s.products.firstOrNull { it.id == ref }
                    if (prod != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(prod.name, color = PaperWhite, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(prod.category, style = MonoLabel, color = FaintGray)
                            }
                            Text("₹${prod.priceInr.toInt()}", style = MonoData, color = PaperWhite)
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "ADD-ON TOTAL ₹${p.budgetInr.toInt()} — purchase remains YOUR action on the shelves.",
                    style = MonoLabel, color = LabelGray,
                )
            }
            if (p.reason.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(p.reason, style = MonoLabel, color = FaintGray)
            }
            Spacer(Modifier.height(Grid.S8))
            Row(horizontalArrangement = Arrangement.spacedBy(Grid.S8)) {
                GhostButton("DISMISS", { vm.dismissNutrition() }, Modifier.weight(1f))
                NeonButton("CONFIRM PLAN", { haptics.success(); vm.confirmNutrition() }, Modifier.weight(1.3f))
            }
        }
    }
}
