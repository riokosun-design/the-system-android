package com.thesystem.app.ui.protocol

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.core.SystemMath
import com.thesystem.app.data.model.*
import com.thesystem.app.data.repo.CommerceRepository
import com.thesystem.app.data.repo.SocialRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import javax.inject.Inject

data class ProtocolState(
    val loading: Boolean = true,
    val profile: UserDto? = null,
    // Black Room
    val blackRoomUnlocked: Boolean = false,
    val blackRoomPrice: Double = 499.0,
    val upiId: String = "thesystem@upi",
    val upiQrUrl: String? = null,
    val myPayments: List<PaymentDto> = emptyList(),
    // Arcs
    val arcs: List<ArcDto> = emptyList(),
    val arcProgress: Map<String, ArcProgressDto> = emptyMap(),
    // Guilds
    val clans: List<ClanDto> = emptyList(),
    val myMembership: ClanMemberDto? = null,
    val myClan: ClanDto? = null,
    val clanMembers: List<ClanMemberDto> = emptyList(),
    // UX
    val paying: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

@HiltViewModel
class ProtocolViewModel @Inject constructor(
    private val system: SystemRepository,
    private val social: SocialRepository,
    private val commerce: CommerceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProtocolState())
    val state: StateFlow<ProtocolState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val profile = system.profile()
        val upi = async { system.config("upi_id") }
        val price = async { system.config("black_room_price_inr") }
        val qr = async { system.config("upi_qr_url") }
        val arcs = async { system.arcs() }
        val progress = async { system.myArcProgress() }
        val pays = async { commerce.myPayments() }
        val clans = async { social.clans() }
        val member = async { social.myMembership() }
        val membership = member.await()
        val myClan = membership?.let { social.clan(it.clanId) }
        val members = membership?.let { social.clanMembers(it.clanId) } ?: emptyList()
        _state.value = ProtocolState(
            loading = false,
            profile = profile,
            blackRoomUnlocked = profile?.blackRoomUntil?.let { until ->
                runCatching { OffsetDateTime.parse(until).isAfter(OffsetDateTime.now()) }.getOrDefault(false)
            } == true,
            blackRoomPrice = price.await()?.toDoubleOrNull() ?: 499.0,
            upiId = upi.await() ?: "thesystem@upi",
            upiQrUrl = qr.await(),
            myPayments = pays.await(),
            arcs = arcs.await(),
            arcProgress = progress.await().associateBy { it.arcId },
            clans = clans.await(),
            myMembership = membership,
            myClan = myClan,
            clanMembers = members,
        )
    }

    // ── Black Room manual UPI purchase ───────────────────────────────────────
    fun submitBlackRoomPayment(utr: String, screenshotUri: Uri?, context: Context) = viewModelScope.launch {
        if (utr.isBlank()) { _state.value = _state.value.copy(error = "UTR / UPI reference ID is required."); return@launch }
        _state.value = _state.value.copy(paying = true)
        val path = screenshotUri?.let { uri ->
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            bytes?.let { commerce.uploadPaymentProof(it).getOrNull() }
        }
        commerce.submitManualPayment("BLACK_ROOM_PASS", null, _state.value.blackRoomPrice, utr, path)
            .onSuccess {
                _state.value = _state.value.copy(paying = false, notice = "Payment submitted for admin verification. The Black Room opens on approval.")
                refresh()
            }
            .onFailure { _state.value = _state.value.copy(paying = false, error = it.message) }
    }

    // ── Arcs (progression lock: finish base conditioning to unlock others) ───
    fun startArc(arc: ArcDto) = viewModelScope.launch {
        system.startArc(arc.id)
            .onSuccess { _state.value = _state.value.copy(notice = "${arc.hero} arc initiated. 4 months. No excuses."); refresh() }
            .onFailure { _state.value = _state.value.copy(error = friendlyArcError(it.message)) }
    }

    private fun friendlyArcError(msg: String?): String = when {
        msg == null -> "Unknown error"
        msg.contains("lock_base") -> "PROGRESSION LOCK — complete base conditioning first."
        msg.contains("level_req") -> "Your level is below this arc's gate."
        msg.contains("already_started") -> "This arc is already in progress."
        else -> msg.take(140)
    }

    // ── Guilds ───────────────────────────────────────────────────────────────
    fun createClan(name: String, tag: String) = viewModelScope.launch {
        social.createClan(name, tag)
            .onSuccess { _state.value = _state.value.copy(notice = "Shadow Guild founded. −${SystemMath.formatVc(SystemMath.CLAN_CREATE_COST_VC)}"); refresh() }
            .onFailure {
                _state.value = _state.value.copy(error = when {
                    it.message?.contains("level_req") == true -> "Guild creation requires level ${SystemMath.CLAN_MIN_LEVEL}+."
                    it.message?.contains("insufficient_vc") == true -> "Guild creation costs ${SystemMath.formatVc(SystemMath.CLAN_CREATE_COST_VC)}."
                    else -> it.message?.take(140)
                })
            }
    }

    fun joinClan(clan: ClanDto) = viewModelScope.launch {
        social.joinClan(clan.id)
            .onSuccess { _state.value = _state.value.copy(notice = "You joined [${clan.tag}] ${clan.name}."); refresh() }
            .onFailure { _state.value = _state.value.copy(error = it.message?.take(140)) }
    }

    fun leaveClan() = viewModelScope.launch {
        social.leaveClan().onSuccess { _state.value = _state.value.copy(notice = "You abandoned the guild."); refresh() }
    }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null, error = null) }
}
