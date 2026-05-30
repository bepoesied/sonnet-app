package pw.kmr.sonnet.shared.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(
    private val loginRepository: LoginRepository,
    private val platformAuthProvider: PlatformAuthProvider
) : ViewModel() {
    private var pendingLogin: PendingLogin? = null

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
                    pendingLogin = pending
                    _uiState.update { it.copy(isLoading = false) }
                    val authData = platformAuthProvider.startAuthorization(pending)
                    effects.send(LoginEffect.OpenAuthBrowser(authData))
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = throwable.userMessage()) }
                }
        }
    }

    fun completeLogin(authResult: Any) {
        val pending = pendingLogin
        if (pending == null) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "Sign in was cancelled.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val idToken = platformAuthProvider.completeAuthorization(pending, authResult)
                loginRepository.completeLogin(pending.serverUrl, idToken)
            }
                .onSuccess {
                    pendingLogin = null
                    _uiState.update { it.copy(isLoading = false) }
                    effects.send(LoginEffect.LoginCompleted)
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = throwable.userMessage()) }
                }
        }
    }

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() }
        ?: "Sign in failed. Check the server URL and try again."
}

fun loginViewModelFactory(
    loginRepository: LoginRepository,
    platformAuthProvider: PlatformAuthProvider
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        LoginViewModel(loginRepository, platformAuthProvider)
    }
}
