package pw.kmr.sonnet.auth

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import pw.kmr.sonnet.shared.auth.LoginRepository
import pw.kmr.sonnet.shared.auth.PendingLogin
import pw.kmr.sonnet.shared.auth.PlatformAuthProvider

fun loginViewModelFactory(
    loginRepository: LoginRepository,
    platformAuthProvider: PlatformAuthProvider
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        LoginViewModel(loginRepository, platformAuthProvider, createSavedStateHandle())
    }
}

class LoginViewModel(
    private val loginRepository: LoginRepository,
    private val platformAuthProvider: PlatformAuthProvider,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val pendingLogin: PendingLogin?
        get() = savedStateHandle.get<String>(PENDING_LOGIN)?.let(PendingLogin.Companion::fromJson)

    private val _uiState = MutableStateFlow(
        LoginUiState(serverUrl = loginRepository.savedServerUrl().orEmpty())
    )
    val uiState: StateFlow<LoginUiState> = _uiState

    private val effects = Channel<LoginEffect>()
    val loginEffects = effects.receiveAsFlow()

    fun onServerUrlChange(serverUrl: String) {
        _uiState.update { it.copy(serverUrl = serverUrl, errorMessage = null) }
    }

    fun startLogin() {
        val serverUrl = _uiState.value.serverUrl
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { loginRepository.fetchMobileConfig(serverUrl) }
                .onSuccess { pending ->
                    savedStateHandle[PENDING_LOGIN] = pending.toJson()
                    _uiState.update { it.copy(isLoading = false) }
                    val launchData = platformAuthProvider.startAuthorization(pending)
                    effects.send(LoginEffect.OpenAuth(launchData as Intent))
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = throwable.userMessage()) }
                }
        }
    }

    fun completeLogin(resultIntent: Intent?) {
        val pending = pendingLogin
        if (pending == null || resultIntent == null) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "Sign in was cancelled.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val idToken = platformAuthProvider.completeAuthorization(pending, resultIntent)
                loginRepository.completeLogin(pending.serverUrl, idToken)
            }
                .onSuccess {
                    savedStateHandle.remove<String>(PENDING_LOGIN)
                    _uiState.update { it.copy(isLoading = false) }
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = throwable.userMessage()) }
                }
        }
    }

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() }
        ?: "Sign in failed. Check the server URL and try again."

    private companion object {
        const val PENDING_LOGIN = "pending_login"
    }
}

data class LoginUiState(
    val serverUrl: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

sealed interface LoginEffect {
    data class OpenAuth(val intent: Intent) : LoginEffect
}

private fun PendingLogin.toJson(): String = JSONObject()
    .put("server_url", serverUrl)
    .put("client_id", clientId)
    .put("authorization_endpoint", authorizationEndpoint)
    .put("token_endpoint", tokenEndpoint)
    .put("end_session_endpoint", endSessionEndpoint ?: JSONObject.NULL)
    .put("response_type", responseType)
    .toString()

private fun PendingLogin.Companion.fromJson(value: String): PendingLogin {
    val json = JSONObject(value)
    return PendingLogin(
        serverUrl = json.getString("server_url"),
        clientId = json.getString("client_id"),
        scopes = emptyList(),
        authorizationEndpoint = json.getString("authorization_endpoint"),
        tokenEndpoint = json.getString("token_endpoint"),
        endSessionEndpoint = json.optString("end_session_endpoint").takeIf { it.isNotEmpty() },
        responseType = json.getString("response_type"),
        mobileConfig = pw.kmr.sonnet.shared.model.MobileConfig(
            issuer = "",
            clientId = json.getString("client_id"),
            authorizationEndpoint = json.getString("authorization_endpoint"),
            tokenEndpoint = json.getString("token_endpoint"),
            endSessionEndpoint = json.optString("end_session_endpoint").takeIf { it.isNotEmpty() },
            scopes = emptyList(),
            responseType = json.getString("response_type"),
            codeChallengeMethodsSupported = listOf("S256")
        )
    )
}
