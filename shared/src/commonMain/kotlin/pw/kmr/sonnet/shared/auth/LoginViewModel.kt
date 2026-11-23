package pw.kmr.sonnet.shared.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pw.kmr.sonnet.shared.remote.ApiException

class LoginViewModel(
    private val loginRepository: LoginRepository
) : ViewModel() {
    private var pendingLogin: PendingLogin? = null
    private var hasUserEditedServerUrl: Boolean = false

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    private val effects = Channel<LoginEffect>(Channel.BUFFERED)
    val loginEffects = effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            val savedUrl = loginRepository.savedServerUrl()
            if (!savedUrl.isNullOrBlank() && !hasUserEditedServerUrl) {
                _uiState.update { state ->
                    if (state.serverUrl.isEmpty()) state.copy(serverUrl = savedUrl) else state
                }
            }
        }
    }

    fun onServerUrlChange(serverUrl: String) {
        hasUserEditedServerUrl = true
        _uiState.update { it.copy(serverUrl = serverUrl, errorMessage = null) }
    }

    fun startLogin() {
        if (_uiState.value.isLoading) return
        val serverUrl = _uiState.value.serverUrl
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val prepared = loginRepository.prepareLogin(serverUrl)
                pendingLogin = prepared.pendingLogin
                loginRepository.savePendingLogin(prepared.pendingLogin)
                _uiState.update { it.copy(isLoading = false) }
                effects.send(
                    LoginEffect.OpenAuthBrowser(
                        authorizeUrl = prepared.authorizeUrl,
                        redirectUri = prepared.pendingLogin.redirectUri
                    )
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                clearPendingLoginBestEffort()
                _uiState.update { it.copy(isLoading = false, errorMessage = throwable.toUserMessage()) }
            }
        }
    }

    fun completeLogin(result: AuthCallbackResult) {
        viewModelScope.launch {
            when (result) {
                AuthCallbackResult.Cancelled -> {
                    clearPendingLoginBestEffort()
                    _uiState.update { it.copy(isLoading = false, errorMessage = CANCELLED_MESSAGE) }
                }

                is AuthCallbackResult.Failed -> {
                    clearPendingLoginBestEffort()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message.takeIf { message -> message.isNotBlank() }
                                ?: DEFAULT_ERROR_MESSAGE
                        )
                    }
                }

                is AuthCallbackResult.Callback -> {
                    _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                    val pending = pendingLogin ?: loginRepository.loadPendingLogin()
                    if (pending == null) {
                        clearPendingLoginBestEffort()
                        _uiState.update { it.copy(isLoading = false, errorMessage = AUTH_FAILED_MESSAGE) }
                        return@launch
                    }

                    // Handoff codes are single-use and short-lived; clear pending secrets immediately.
                    clearPendingLoginBestEffort()

                    try {
                        loginRepository.completeLogin(pending, result.uri)
                        _uiState.update { it.copy(isLoading = false) }
                        effects.send(LoginEffect.LoginCompleted)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        _uiState.update { it.copy(isLoading = false, errorMessage = throwable.toUserMessage()) }
                    }
                }
            }
        }
    }

    private suspend fun clearPendingLoginBestEffort() {
        pendingLogin = null
        try {
            loginRepository.clearPendingLogin()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Best-effort cleanup of pending login secrets.
        }
    }

    private fun Throwable.toUserMessage(): String {
        if (this is AuthCallbackException) {
            return message?.takeIf { it.isNotBlank() } ?: AUTH_FAILED_MESSAGE
        }
        if (this is IllegalArgumentException) {
            return message?.takeIf { it.isNotBlank() } ?: DEFAULT_ERROR_MESSAGE
        }
        if (this is ApiException) {
            return when (statusCode) {
                401, 403 -> AUTH_FAILED_MESSAGE
                404 -> "Sonnet server endpoint not found. Check the server URL and try again."
                in 500..599 -> "The server encountered an error. Please try again later."
                else -> "Server rejected the request (HTTP $statusCode). Check the server URL and try again."
            }
        }

        val rawMessage = message.orEmpty()
        return when {
            rawMessage.contains("cancelled", ignoreCase = true) ||
                rawMessage.contains("canceled", ignoreCase = true) -> CANCELLED_MESSAGE

            rawMessage.contains("unable to resolve host", ignoreCase = true) ||
                rawMessage.contains("unknownhost", ignoreCase = true) ||
                rawMessage.contains("no address associated", ignoreCase = true) ||
                rawMessage.contains("failed to connect", ignoreCase = true) ||
                rawMessage.contains("connection refused", ignoreCase = true) ||
                rawMessage.contains("network is unreachable", ignoreCase = true) ->
                "Cannot reach the server. Check the URL and your internet connection."

            rawMessage.contains("timeout", ignoreCase = true) ||
                rawMessage.contains("timed out", ignoreCase = true) ->
                "Server took too long to respond. Please try again."

            rawMessage.contains("ssl", ignoreCase = true) ||
                rawMessage.contains("tls", ignoreCase = true) ||
                rawMessage.contains("certificate", ignoreCase = true) ||
                rawMessage.contains("handshake", ignoreCase = true) ->
                "Secure connection failed. Check the server URL."

            else -> DEFAULT_ERROR_MESSAGE
        }
    }

    private companion object {
        const val CANCELLED_MESSAGE = "Sign in was cancelled."
        const val AUTH_FAILED_MESSAGE = "Authentication failed. Please sign in again."
        const val DEFAULT_ERROR_MESSAGE = "Sign in failed. Check the server URL and try again."
    }
}

fun loginViewModelFactory(
    loginRepository: LoginRepository
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        LoginViewModel(loginRepository)
    }
}
