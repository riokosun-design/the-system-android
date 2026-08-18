package com.thesystem.app.ui.onboarding

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.thesystem.app.BuildConfig
import com.thesystem.app.data.model.LegalDocDto
import com.thesystem.app.data.model.UserDto
import com.thesystem.app.data.repo.AuthRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject

data class OnboardingUiState(
    val page: Int = 0,
    val privacyDoc: LegalDocDto? = null,
    val termsDoc: LegalDocDto? = null,
    val privacyAccepted: Boolean = false,
    val termsAccepted: Boolean = false,
    val legalLoading: Boolean = true,
    val legalError: String? = null,
    // metrics (pages 4–7)
    val age: Int = 18,
    val heightCm: Float = 170f,
    val weightKg: Float = 65f,
    val goal: String = "SHRED",
    // auth
    val signingIn: Boolean = false,
    val signedInProfile: UserDto? = null,
    val authError: String? = null,
    // username gate
    val username: String = "",
    val usernameChecking: Boolean = false,
    val usernameAvailable: Boolean? = null,
    val usernameError: String? = null,
    // referral
    val referralCode: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val system: SystemRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(OnboardingUiState())
    val ui: StateFlow<OnboardingUiState> = _ui

    private var usernameJob: Job? = null

    init { loadLegal() }

    fun loadLegal() = viewModelScope.launch {
        _ui.value = _ui.value.copy(legalLoading = true, legalError = null)
        val privacy = system.legalDocument("PRIVACY_POLICY")
        val terms = system.legalDocument("TERMS_OF_SERVICE")
        _ui.value = _ui.value.copy(
            privacyDoc = privacy, termsDoc = terms, legalLoading = false,
            legalError = if (privacy == null || terms == null) "Could not reach THE SYSTEM servers. Check your connection and retry." else null,
        )
    }

    fun setPage(p: Int) { _ui.value = _ui.value.copy(page = p) }
    fun acceptPrivacy(v: Boolean) { _ui.value = _ui.value.copy(privacyAccepted = v) }
    fun acceptTerms(v: Boolean) { _ui.value = _ui.value.copy(termsAccepted = v) }
    fun setAge(v: Int) { _ui.value = _ui.value.copy(age = v) }
    fun setHeight(v: Float) { _ui.value = _ui.value.copy(heightCm = v) }
    fun setWeight(v: Float) { _ui.value = _ui.value.copy(weightKg = v) }
    fun setGoal(v: String) { _ui.value = _ui.value.copy(goal = v) }
    fun setReferral(v: String) { _ui.value = _ui.value.copy(referralCode = v) }

    // ── Google Sign-In via Credential Manager → Supabase IDToken exchange ────
    fun signInWithGoogle(context: Context) {
        if (_ui.value.signingIn) return
        _ui.value = _ui.value.copy(signingIn = true, authError = null)
        viewModelScope.launch {
            runCatching {
                val rawNonce = UUID.randomUUID().toString()
                val hashed = MessageDigest.getInstance("SHA-256")
                    .digest(rawNonce.toByteArray()).joinToString("") { "%02x".format(it) }

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(BuildConfig.GOOGLE_SERVER_CLIENT_ID)
                    .setNonce(hashed)
                    .build()
                val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
                val result = CredentialManager.create(context).getCredential(context, request)
                val google = GoogleIdTokenCredential.createFrom(result.credential.data)

                auth.signInWithGoogleIdToken(google.idToken, rawNonce)
                auth.myProfile()
            }.onSuccess { profile ->
                _ui.value = _ui.value.copy(signingIn = false, signedInProfile = profile, page = 8)
            }.onFailure { e ->
                _ui.value = _ui.value.copy(signingIn = false, authError = e.message ?: "Google sign-in failed")
            }
        }
    }

    // ── Unique @handle with 400ms debounce + live availability ──────────────
    fun onUsernameChanged(raw: String) {
        val clean = raw.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(20)
        _ui.value = _ui.value.copy(username = clean, usernameAvailable = null, usernameError = null)
        usernameJob?.cancel()
        if (clean.length < 3) return
        usernameJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(usernameChecking = true)
            delay(400)
            val free = auth.isUsernameAvailable(clean)
            val current = _ui.value
            if (current.username == clean) {
                _ui.value = current.copy(
                    usernameChecking = false,
                    usernameAvailable = free,
                    usernameError = if (free) null else "@$clean is already claimed by another hunter.",
                )
            }
        }
    }

    /** Finalize: claim handle → save metrics → mark complete → apply referral (optional). */
    fun finish(onDone: () -> Unit) {
        val s = _ui.value
        if (s.busy || s.usernameAvailable != true) return
        _ui.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            val claimed = auth.claimUsername(s.username)
            if (claimed.isFailure) {
                _ui.value = _ui.value.copy(busy = false, error = claimed.exceptionOrNull()?.message ?: "Username rejected")
                return@launch
            }
            auth.completeOnboarding(s.age, s.heightCm.toDouble(), s.weightKg.toDouble(), s.goal)
                .onFailure { _ui.value = _ui.value.copy(error = it.message) }
            if (s.referralCode.isNotBlank()) runCatching { auth.applyReferralCode(s.referralCode) }
            _ui.value = _ui.value.copy(busy = false)
            onDone()
        }
    }
}
