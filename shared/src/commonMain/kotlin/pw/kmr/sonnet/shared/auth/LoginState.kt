package pw.kmr.sonnet.shared.auth

import pw.kmr.sonnet.shared.model.MobileConfig

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
