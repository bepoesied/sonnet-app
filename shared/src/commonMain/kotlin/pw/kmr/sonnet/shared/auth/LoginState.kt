package pw.kmr.sonnet.shared.auth

import kotlinx.serialization.Serializable
import pw.kmr.sonnet.shared.model.MobileConfig

data class LoginUiState(
    val serverUrl: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

sealed interface LoginEffect {
    data class OpenAuthBrowser(val authData: Any) : LoginEffect
    data object LoginCompleted : LoginEffect
}

@Serializable
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
