package pw.kmr.sonnet.shared.auth

import pw.kmr.sonnet.shared.model.MobileConfig

data class LoginUiState(
    val serverUrl: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

sealed interface LoginEffect {
    data object OpenAuthBrowser : LoginEffect
    data object LoginCompleted : LoginEffect
}

data class PendingLogin(
    val serverUrl: String,
    val clientId: String,
    val scopes: List<String>,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val endSessionEndpoint: String?,
    val responseType: String,
    val mobileConfig: MobileConfig
) {
    companion object
}
