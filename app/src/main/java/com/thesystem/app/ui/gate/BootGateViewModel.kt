package com.thesystem.app.ui.gate

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.thesystem.app.BuildConfig
import com.thesystem.app.data.repo.AuthRepository
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject

/** The four acts of the Access Gate ritual. Plays on EVERY cold open (Section 2-A). */
enum class SplashPhase { TEXT_FADE, FINGERPRINT_HOLD, WELCOME, AUTH_SYNC }

data class BootGateState(
    val phase: SplashPhase = SplashPhase.TEXT_FADE,
    val authResolved: Boolean = false,
    val authenticated: Boolean = false,
    val syncing: Boolean = false,
    val authError: String? = null,
    val gateArtUrl: String? = null,      // dynamic "GATE_HERO" asset from system_assets (admin-swappable)
    val gateArtAlpha: Float = 0.30f,
)

@HiltViewModel
class BootGateViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val system: SystemRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BootGateState())
    val state: StateFlow<BootGateState> = _state

    init {
        viewModelScope.launch {
            auth.sessionStatus.collect { st ->
                when (st) {
                    is SessionStatus.Authenticated ->
                        _state.value = _state.value.copy(authResolved = true, authenticated = true, authError = null)
                    is SessionStatus.NotAuthenticated ->
                        _state.value = _state.value.copy(authResolved = true, authenticated = false)
                    else -> Unit // auth layer still initializing — gate keeps playing
                }
            }
        }
        // The monarch art is remotely swappable: Admin → ASSETS uploads hit the next cold start
        viewModelScope.launch {
            system.wallpaperFor("GATE_HERO")?.let { (url, alpha) ->
                _state.value = _state.value.copy(gateArtUrl = url, gateArtAlpha = alpha.coerceIn(0.15f, 0.55f))
            }
        }
    }

    fun setPhase(phase: SplashPhase) { _state.value = _state.value.copy(phase = phase) }

    /** Phase 4 path A: session already alive → short sync ritual, then straight through. */
    fun syncThrough(onFinished: () -> Unit) = viewModelScope.launch {
        if (_state.value.syncing) return@launch
        _state.value = _state.value.copy(syncing = true)
        delay(1500) // let the hologram read as a REAL sync, not a flicker
        onFinished()
    }

    /** Phase 4 path B: Credential Manager → Google ID token → Supabase exchange. */
    fun signInWithGoogle(context: Context, onFinished: () -> Unit) = viewModelScope.launch {
        if (_state.value.syncing) return@launch
        _state.value = _state.value.copy(syncing = true, authError = null)
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
        }.onSuccess {
            delay(900) // syncing modal completes its beat
            onFinished()
        }.onFailure { e ->
            _state.value = _state.value.copy(syncing = false, authError = e.message?.take(140) ?: "Google sign-in failed")
        }
    }

    fun clearError() { _state.value = _state.value.copy(authError = null) }
}
