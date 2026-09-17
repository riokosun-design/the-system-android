package com.thesystem.app.ui.admin

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.data.model.*
import com.thesystem.app.data.repo.AdminRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

data class AdminState(
    val loading: Boolean = true,
    val overview: JsonObject? = null,
    val privacy: LegalDocDto? = null,
    val terms: LegalDocDto? = null,
    val products: List<ProductDto> = emptyList(),
    val tournaments: List<TournamentDto> = emptyList(),
    val payments: List<PaymentDto> = emptyList(),
    val assets: List<AssetDto> = emptyList(),
    val pools: List<PoolDto> = emptyList(),
    // v0.4.1 training CMS
    val courses: List<CourseDto> = emptyList(),
    val courseQuests: List<CourseQuestDto> = emptyList(),
    val questTemplates: List<QuestTemplateDto> = emptyList(),
    val blackRooms: List<BlackRoomApplicationDto> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val admin: AdminRepository,
    private val system: SystemRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AdminState())
    val state: StateFlow<AdminState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        val o = async { admin.overview() }
        val pv = async { system.legalDocument("PRIVACY_POLICY") }
        val ts = async { system.legalDocument("TERMS_OF_SERVICE") }
        val pr = async { admin.allProducts() }
        val tr = async { admin.allTournaments() }
        val pay = async { admin.pendingPayments() }
        val asd = async { admin.allAssets() }
        val pools = async { admin.unsettledPools() }
        val coursesD = async { admin.allCourses() }
        val templatesD = async { admin.questTemplates() }
        val blackD = async { admin.pendingBlackRooms() }
        _state.value = _state.value.copy(
            loading = false,
            overview = o.await(), privacy = pv.await(), terms = ts.await(),
            products = pr.await(), tournaments = tr.await(), payments = pay.await(),
            assets = asd.await(), pools = pools.await(),
            courses = coursesD.await(), questTemplates = templatesD.await(), blackRooms = blackD.await(),
        )
    }

    private fun run(action: suspend () -> Result<Unit>, okMsg: String) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        action()
            .onSuccess { _state.value = _state.value.copy(busy = false, notice = okMsg); refresh() }
            .onFailure { _state.value = _state.value.copy(busy = false, error = it.message?.take(160)) }
    }

    fun publishPrivacy(markdown: String, title: String) = run({ admin.publishLegal("PRIVACY_POLICY", title, markdown) }, "Privacy Policy republished live.")
    fun publishTerms(markdown: String, title: String) = run({ admin.publishLegal("TERMS_OF_SERVICE", title, markdown) }, "Terms of Service republished live.")

    fun upsertProduct(p: ProductDto) = run({ admin.upsertProduct(p) }, "Product saved.")
    fun deleteProduct(id: String) = run({ admin.deleteProduct(id) }, "Product deleted.")
    fun uploadProductImage(uri: Uri, context: Context, onDone: (String?) -> Unit) = viewModelScope.launch {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        onDone(bytes?.let { admin.uploadProductImage(it).getOrNull() })
    }

    fun createTournament(title: String, type: String, fee: Long) =
        run({ admin.createTournament(title, type, fee, null, null) }, "Tournament opened with $fee VC entry.")

    fun setTournamentStatus(id: String, status: String) = run({ admin.setTournamentStatus(id, status) }, "Tournament → $status.")

    fun generateBracket(id: String) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        admin.generateBracket(id)
            .onSuccess { _state.value = _state.value.copy(busy = false, notice = "BRACKET SEEDED — $it battles live. Odd hunter out advances on a BYE."); refresh() }
            .onFailure { _state.value = _state.value.copy(busy = false, error = it.message?.take(160)) }
    }

    fun advanceBracket(id: String) = viewModelScope.launch {
        _state.value = _state.value.copy(busy = true)
        admin.advanceBracket(id)
            .onSuccess { created ->
                val msg = if (created > 0) "ROUND ADVANCED — $created new battles." else "CHAMPION CROWNED — PRIZE POOL PAID."
                _state.value = _state.value.copy(busy = false, notice = msg); refresh()
            }
            .onFailure { _state.value = _state.value.copy(busy = false, error = it.message?.take(160)) }
    }

    fun settlePool(poolId: String, side: String) = run({ admin.settlePool(poolId, side) }, "Pool settled. Winners paid, house kept 15%.")

    fun reviewPayment(id: String, approve: Boolean) =
        run({ admin.reviewPayment(id, approve, "") }, if (approve) "Payment approved — effect applied." else "Payment rejected.")

    fun uploadAsset(key: String, type: String, uri: Uri, context: Context, fade: Double) = viewModelScope.launch {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
        admin.uploadAsset(key, type, bytes, fade)
            .onSuccess { _state.value = _state.value.copy(notice = "Asset live — every client re-themes on next fetch."); refresh() }
            .onFailure { _state.value = _state.value.copy(error = it.message?.take(160)) }
    }

    fun toggleAsset(id: String, enabled: Boolean) = run({ admin.setAssetEnabled(id, enabled) }, if (enabled) "Asset enabled." else "Asset disabled.")

    fun setConfig(key: String, value: String) = run({ admin.setSystemConfig(key, value) }, "Config '$key' updated.")

    fun adjustVc(userId: String, amount: Long, note: String) = run({ admin.adjustVc(userId, amount, note) }, "VC adjusted.")

    fun proofUrl(path: String?) = path?.let { admin.paymentProofUrl(it) }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null, error = null) }

    // ── v0.4.1 TRAINING CMS ─────────────────────────────────────────────────
    fun upsertCourse(c: CourseDto, scheduleJson: String) =
        run({ admin.upsertCourse(c, scheduleJson) }, "Course published — hunters get it on next sync.")

    fun upsertCourseQuest(q: CourseQuestDto, courseSlug: String) =
        run({ admin.upsertCourseQuest(q, courseSlug) }, "Course quest published.")

    fun upsertQuestTemplate(t: QuestTemplateDto) =
        run({ admin.upsertQuestTemplate(t) }, "Daily protocol block published.")

    fun reviewBlackRoom(id: String, approve: Boolean, regiment: String = "") =
        run({ admin.reviewBlackRoom(id, approve, regiment) },
            if (approve) "Application approved — 180 days granted." else "Application rejected.")

    fun saveBlackRoomProgram(
        applicationId: String, week: Int, day: Int, title: String, exercise: String,
        sets: Int, reps: String, durationSec: Int, restSec: Int, notes: String,
    ) = run(
        { admin.saveBlackRoomProgram(applicationId, week, day, title, exercise, sets, reps, durationSec, restSec, notes) },
        "Program row saved for that hunter.",
    )

    fun loadCourseQuests(slug: String) = viewModelScope.launch {
        val course = _state.value.courses.firstOrNull { it.slug == slug } ?: return@launch
        _state.value = _state.value.copy(courseQuests = admin.allCourseQuests(course.id))
    }
}
