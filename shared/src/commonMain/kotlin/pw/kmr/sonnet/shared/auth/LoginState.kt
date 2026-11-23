package pw.kmr.sonnet.shared.auth

import kotlinx.serialization.Serializable

data class LoginUiState(
    val serverUrl: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

sealed interface LoginEffect {
    data class OpenAuthBrowser(
        val authorizeUrl: String,
        val redirectUri: String
    ) : LoginEffect

    data object LoginCompleted : LoginEffect
}

sealed interface AuthCallbackResult {
    data class Callback(val uri: String) : AuthCallbackResult
    data object Cancelled : AuthCallbackResult
    data class Failed(val message: String) : AuthCallbackResult
}

@Serializable
data class PendingLogin(
    val serverUrl: String,
    val redirectUri: String,
    val codeVerifier: String,
    val state: String
)

data class PreparedLogin(
    val pendingLogin: PendingLogin,
    val authorizeUrl: String
)

class AuthCallbackException(message: String) : IllegalStateException(message)
