package pw.kmr.sonnet.shared.auth

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.parseUrl
import kotlinx.coroutines.CancellationException
import pw.kmr.sonnet.shared.core.AppViewModelRepository
import pw.kmr.sonnet.shared.library.LocalLibraryCleaner
import pw.kmr.sonnet.shared.model.AuthSession
import pw.kmr.sonnet.shared.remote.AuthApiClient

class LoginRepository(
    private val authApiClient: AuthApiClient,
    private val authSessionManager: AuthSessionManager,
    private val localLibraryCleaner: LocalLibraryCleaner,
    private val enforcesHttps: Boolean,
    private val challengeGenerator: AuthChallengeGenerator = DefaultAuthChallengeGenerator,
    private val redirectUri: String = DEFAULT_REDIRECT_URI
) : AppViewModelRepository {

    fun prepareLogin(serverUrlInput: String): PreparedLogin {
        val serverUrl = normalizeServerUrl(serverUrlInput)
        require(isAllowedUrl(serverUrl)) {
            "Enter a valid ${if (enforcesHttps) "HTTPS" else "HTTP or HTTPS"} Sonnet server URL."
        }

        val challenge = challengeGenerator.generate()
        val authorizeUrl = URLBuilder("$serverUrl/auth/mobile/authorize").apply {
            parameters.append("redirect_uri", redirectUri)
            parameters.append("code_challenge", challenge.codeChallenge)
            parameters.append("code_challenge_method", PKCE_CHALLENGE_METHOD_S256)
            parameters.append("state", challenge.state)
        }.buildString()

        return PreparedLogin(
            pendingLogin = PendingLogin(
                serverUrl = serverUrl,
                redirectUri = redirectUri,
                codeVerifier = challenge.codeVerifier,
                state = challenge.state
            ),
            authorizeUrl = authorizeUrl
        )
    }

    suspend fun completeLogin(pendingLogin: PendingLogin, callbackUri: String) {
        val callback = runCatching { Url(callbackUri) }.getOrNull()
            ?: throw AuthCallbackException(AUTH_FAILED_MESSAGE)
        val expectedRedirect = runCatching { Url(pendingLogin.redirectUri) }.getOrNull()
            ?: throw AuthCallbackException(AUTH_FAILED_MESSAGE)

        if (!callback.matchesRedirectUri(expectedRedirect)) {
            throw AuthCallbackException(AUTH_FAILED_MESSAGE)
        }

        val returnedState = callback.parameters["state"]
        if (returnedState.isNullOrBlank() || !constantTimeEquals(pendingLogin.state, returnedState)) {
            throw AuthCallbackException(AUTH_FAILED_MESSAGE)
        }

        val error = callback.parameters["error"]
        if (!error.isNullOrBlank()) {
            throw AuthCallbackException(AUTH_FAILED_MESSAGE)
        }

        val code = callback.parameters["code"]?.takeIf { it.isNotBlank() }
            ?: throw AuthCallbackException(AUTH_FAILED_MESSAGE)

        val login = authApiClient.exchangeMobileCode(
            serverUrl = pendingLogin.serverUrl,
            code = code,
            codeVerifier = pendingLogin.codeVerifier,
            redirectUri = pendingLogin.redirectUri
        )
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
            try {
                authApiClient.logout(session.serverUrl, session.accessToken, session.refreshToken)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Best-effort remote logout; always clear local state afterward.
            }
        }
        localLibraryCleaner.clearLocalLibrary()
        authSessionManager.clearSession(retainServerUrl = true)
    }

    suspend fun savedServerUrl(): String? = authSessionManager.savedServerUrl()

    suspend fun savePendingLogin(pendingLogin: PendingLogin) {
        authSessionManager.savePendingLogin(pendingLogin)
    }

    suspend fun loadPendingLogin(): PendingLogin? = authSessionManager.loadPendingLogin()

    suspend fun clearPendingLogin() {
        authSessionManager.clearPendingLogin()
    }

    internal fun normalizeServerUrl(serverUrl: String): String =
        serverUrl.trim().trimEnd('/')

    private fun isAllowedUrl(serverUrl: String): Boolean {
        val url = parseUrl(serverUrl) ?: return false
        val scheme = url.protocol.name
        return url.host.isNotEmpty() &&
            scheme in setOf("http", "https") &&
            (!enforcesHttps || scheme == "https") &&
            url.parameters.isEmpty() &&
            url.fragment.isEmpty()
    }

    private fun Url.matchesRedirectUri(expected: Url): Boolean =
        protocol.name.equals(expected.protocol.name, ignoreCase = true) &&
            host.equals(expected.host, ignoreCase = true) &&
            port == expected.port &&
            encodedPath == expected.encodedPath

    companion object {
        const val DEFAULT_REDIRECT_URI = "sonnet://auth/callback"
        private const val PKCE_CHALLENGE_METHOD_S256 = "S256"
        private const val AUTH_FAILED_MESSAGE = "Authentication failed. Please sign in again."
    }
}
