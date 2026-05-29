package pw.kmr.sonnet.shared.auth

import io.ktor.http.Url
import pw.kmr.sonnet.shared.core.AppViewModelRepository
import pw.kmr.sonnet.shared.data.preferences.AppSettingsRepository
import pw.kmr.sonnet.shared.library.LocalLibraryCleaner
import pw.kmr.sonnet.shared.model.AuthSession
import pw.kmr.sonnet.shared.remote.AuthApiClient

class LoginRepository(
    private val authApiClient: AuthApiClient,
    private val authSessionManager: AuthSessionManager,
    private val settingsRepository: AppSettingsRepository,
    private val localLibraryCleaner: LocalLibraryCleaner,
    private val enforcesHttps: Boolean
) : AppViewModelRepository {

    suspend fun fetchMobileConfig(serverUrlInput: String): PendingLogin {
        val serverUrl = normalizeServerUrl(serverUrlInput)
        require(isAllowedUrl(serverUrl)) {
            "Enter a valid ${if (enforcesHttps) "HTTPS" else "HTTP or HTTPS"} Sonnet server URL."
        }

        val config = authApiClient.mobileConfig(serverUrl)
        require(config.responseType == RESPONSE_TYPE_CODE) { "Server returned unsupported OIDC response type." }
        require("S256" in config.codeChallengeMethodsSupported) { "OIDC provider does not support PKCE S256." }

        return PendingLogin(
            serverUrl = serverUrl,
            clientId = config.clientId,
            scopes = config.scopes,
            authorizationEndpoint = config.authorizationEndpoint,
            tokenEndpoint = config.tokenEndpoint,
            endSessionEndpoint = config.endSessionEndpoint,
            responseType = config.responseType,
            mobileConfig = config
        )
    }

    suspend fun completeLogin(serverUrl: String, idToken: String) {
        val login = authApiClient.oidcLogin(serverUrl, idToken)
        settingsRepository.setServerUrl(login.serverUrl)
        authSessionManager.saveSession(
            AuthSession(
                serverUrl = login.serverUrl,
                accessToken = login.accessToken,
                refreshToken = login.refreshToken,
                user = login.user
            )
        )
    }

    override suspend fun bootstrapSession() {
        authSessionManager.bootstrapSession()
    }

    override suspend fun logout() {
        val session = authSessionManager.storedSession()
        if (session != null) {
            runCatching {
                authApiClient.logout(session.serverUrl, session.accessToken, session.refreshToken)
            }
        }
        localLibraryCleaner.clearLocalLibrary()
        authSessionManager.clearSession(retainServerUrl = true)
    }

    fun savedServerUrl(): String? = authSessionManager.savedServerUrl()

    internal fun normalizeServerUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }

    private fun isAllowedUrl(serverUrl: String): Boolean {
        return try {
            val url = Url(serverUrl)
            val scheme = url.protocol.name
            val host = url.host
            host.isNotEmpty() &&
                scheme in setOf("http", "https") &&
                (!enforcesHttps || scheme == "https")
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val RESPONSE_TYPE_CODE = "code"
    }
}
