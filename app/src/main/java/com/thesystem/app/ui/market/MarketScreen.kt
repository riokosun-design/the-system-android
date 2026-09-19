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
