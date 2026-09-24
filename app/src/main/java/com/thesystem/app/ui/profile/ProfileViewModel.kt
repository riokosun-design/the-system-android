package com.thesystem.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesystem.app.data.model.*
import com.thesystem.app.data.repo.CommerceRepository
import com.thesystem.app.data.repo.SocialRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileState(
    val loading: Boolean = true,
    val profile: UserDto? = null,
    val forms: List<FormDto> = emptyList(),
    val ledger: List<VcTxnDto> = emptyList(),
    val products: List<ProductDto> = emptyList(),
    val hall: List<ReferralHallDto> = emptyList(),
    val myReferrals: Long = 0,
    val offerwallUrl: String? = null,
    val bests: com.thesystem.app.data.model.VerifiedBestsDto? = null,
    val notice: String? = null,
    val error: String? = null,
    val deleting: Boolean = false,
) {
    val merch get() = products.filter { it.category == "MERCH" }
    val supplements get() = products.filter { it.category == "SUPPLEMENT" }
}

/** Luxury referral reward ladder (marketing copy; fulfillment is ops-side). */
val REFERRAL_TIERS = listOf(
    500L to "SKY VILLA — 'The Architect' penthouse tier",
    100L to "SUPERCAR — 'Monarch' grand tourer tier",
    25L to "CHRONOGRAPH — Swiss luxury watch tier",
    5L to "SHADOW MERCH — full S-Rank apparel set",
    1L to "ARISEN — 250 VC welcome blessing",
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val system: SystemRepository,
    private val commerce: CommerceRepository,
    private val social: SocialRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        val p = async { system.profile() }
        val forms = async { system.myForms() }
        val ledger = async { commerce.walletLedger() }
        val prods = async { commerce.products() }
        val hall = async { social.referralHallOfFame() }
        val refs = async { social.myReferralCount() }
        val offerwallBase = async { system.config("offerwall_url") }
        val bestsD = async { system.verifiedBests() }
        _state.value = _state.value.copy(
            loading = false,
            profile = p.await(), forms = forms.await(), ledger = ledger.await(),
            products = prods.await(), hall = hall.await(), myReferrals = refs.await(),
            offerwallUrl = offerwallBase.await()?.let { commerce.offerwallUrl(it) },
            bests = bestsD.await(),
        )
    }

    fun saveBodyStats(age: Int, heightCm: Double, weightKg: Double) = viewModelScope.launch {
        val me = _state.value.profile ?: return@launch
        runCatching { system.updateBodyStats(me.id, age, heightCm, weightKg) }
            .onSuccess { _state.value = _state.value.copy(notice = "Biometrics updated."); refresh() }
            .onFailure { _state.value = _state.value.copy(error = it.message) }
    }

    /** Play policy: in-app account deletion. onDeleted should re-route to onboarding. */
    fun deleteAccount(onDeleted: () -> Unit) = viewModelScope.launch {
        if (_state.value.deleting) return@launch
        _state.value = _state.value.copy(deleting = true)
        runCatching { system.deleteAccount() }
            .onSuccess { onDeleted() }
            .onFailure { _state.value = _state.value.copy(deleting = false, error = "Deletion failed: ${it.message}") }
    }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null, error = null) }
}
