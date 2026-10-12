package app.trazo.data.repo

import app.trazo.config.SettingsRepository
import app.trazo.data.remote.Backend
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface AuthState {
    data object Loading : AuthState
    data object NoBackend : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String?) : AuthState
}

class AuthRepository(private val backend: Backend, settings: SettingsRepository) {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<AuthState> = combine(settings.settings.map { it.backendConfigured }.distinctUntilChanged(), backend.clientFlow) { ok, c -> ok to c }
        .flatMapLatest { (configured, client) ->
        if (!configured) flowOf<AuthState>(AuthState.NoBackend)
        else if (client == null) flow<AuthState> { emit(AuthState.Loading); runCatching { backend.client() } }
        else client.auth.sessionStatus.map<SessionStatus, AuthState> { s ->
            when (s) {
                is SessionStatus.Authenticated -> AuthState.SignedIn(s.session.user?.id ?: "", s.session.user?.email)
                is SessionStatus.NotAuthenticated -> AuthState.SignedOut
                else -> AuthState.Loading
            }
        }
    }

    /** Returns true when the account is ready, false when the email must be confirmed first. */
    suspend fun signUp(name: String, email: String, password: String): Boolean {
        val auth = backend.client().auth
        auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject { put("display_name", name.trim()) }
        }
        return auth.currentSessionOrNull() != null
    }

    suspend fun signIn(email: String, password: String) {
        backend.client().auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    /** Sends the recovery email; it contains a 6-digit code (see docs/BACKEND.md for the template). */
    suspend fun sendRecovery(email: String) {
        backend.client().auth.resetPasswordForEmail(email.trim())
    }

    suspend fun completeRecovery(email: String, code: String, newPassword: String) {
        val auth = backend.client().auth
        auth.verifyEmailOtp(type = OtpType.Email.RECOVERY, email = email.trim(), token = code.trim())
        auth.updateUser { password = newPassword }
    }

    suspend fun changePassword(newPassword: String) {
        backend.client().auth.updateUser { password = newPassword }
    }

    suspend fun signOut() {
        runCatching { backend.client().auth.signOut() }
        // If the server call failed (offline, or the account was just deleted) still forget the local session.
        runCatching { backend.client().auth.clearSession() }
    }
}
