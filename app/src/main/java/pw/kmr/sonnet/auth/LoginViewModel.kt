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
import net.openid.appauth.AuthorizationService

fun loginViewModelFactory(authRepository: AuthRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        LoginViewModel(authRepository, createSavedStateHandle())
    }
}

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val pendingLogin: PendingLogin?
        get() = savedStateHandle.get<String>(PENDING_LOGIN)?.let(PendingLogin::fromJson)

    private val _uiState = MutableStateFlow(
        LoginUiState(serverUrl = authRepository.savedServerUrl().orEmpty())
    )
    val uiState: StateFlow<LoginUiState> = _uiState

    private val effects = Channel<LoginEffect>()
    val loginEffects = effects.receiveAsFlow()

    fun onServerUrlChange(serverUrl: String) {
        _uiState.update { it.copy(serverUrl = serverUrl, errorMessage = null) }
    }

    fun startLogin(authorizationService: AuthorizationService) {
        val serverUrl = _uiState.value.serverUrl
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { authRepository.createLoginRequest(serverUrl) }
                .onSuccess { pending ->
                    savedStateHandle[PENDING_LOGIN] = pending.toJson()
                    _uiState.update { it.copy(isLoading = false) }
                    effects.send(LoginEffect.OpenAuth(pending.toIntent(authorizationService)))
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = throwable.userMessage()) }
                }
        }
    }

    fun completeLogin(resultIntent: Intent?, authorizationService: AuthorizationService) {
        val pending = pendingLogin
        if (pending == null || resultIntent == null) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "Sign in was cancelled.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { authRepository.completeLogin(pending, resultIntent, authorizationService) }
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
