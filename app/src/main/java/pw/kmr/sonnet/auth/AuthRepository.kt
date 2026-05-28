package pw.kmr.sonnet.auth

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject
import pw.kmr.sonnet.data.remote.ServerUrlPolicy
import pw.kmr.sonnet.shared.auth.AuthSessionManager
import pw.kmr.sonnet.shared.core.AppViewModelRepository
import pw.kmr.sonnet.shared.data.preferences.AppSettingsRepository
import pw.kmr.sonnet.shared.library.LocalLibraryCleaner
import pw.kmr.sonnet.shared.model.AuthSession
import pw.kmr.sonnet.shared.model.MobileConfig
import pw.kmr.sonnet.shared.remote.AuthApiClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthRepository(
    private val authApiClient: AuthApiClient,
    private val authSessionManager: AuthSessionManager,
    private val settingsRepository: AppSettingsRepository,
    private val serverUrlPolicy: ServerUrlPolicy,
    private val localLibraryCleaner: LocalLibraryCleaner
) : AppViewModelRepository {
    suspend fun createLoginRequest(serverUrlInput: String): PendingLogin {
        val serverUrl = normalizeServerUrl(serverUrlInput)
        require(serverUrlPolicy.isAllowed(serverUrl)) {
            "Enter a valid ${if (serverUrlPolicy.enforcesHttps) "HTTPS" else "HTTP or HTTPS"} Sonnet server URL."
        }

        val config = authApiClient.mobileConfig(serverUrl)
        require(config.responseType == ResponseTypeValues.CODE) {
            "Server returned unsupported OIDC response type."
        }
        require("S256" in config.codeChallengeMethodsSupported) {
            "OIDC provider does not support PKCE S256."
        }

        return PendingLogin(
            serverUrl = serverUrl,
            mobileConfig = config,
            request = AuthorizationRequest.Builder(
                config.toServiceConfiguration(),
                config.clientId,
                ResponseTypeValues.CODE,
                REDIRECT_URI
            )
                .setScope(config.scopes.joinToString(" "))
                .setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
                .setNonce(CodeVerifierUtil.generateRandomCodeVerifier())
                .build()
        )
    }

    suspend fun completeLogin(
        pendingLogin: PendingLogin,
        resultIntent: Intent,
        authorizationService: AuthorizationService
    ) {
        val exception = AuthorizationException.fromIntent(resultIntent)
        if (exception != null) throw exception

        val response = AuthorizationResponse.fromIntent(resultIntent)
            ?: error("OIDC provider did not return an authorization response.")
        val tokenResponse = suspendCancellableCoroutine { continuation ->
            authorizationService.performTokenRequest(response.createTokenExchangeRequest()) { exchanged, tokenException ->
                when {
                    exchanged != null -> continuation.resume(exchanged)
                    tokenException != null -> continuation.resumeWithException(tokenException)
                    else -> continuation.resumeWithException(IllegalStateException("OIDC token exchange failed."))
                }
            }
        }
        val idToken = tokenResponse.idToken ?: error("OIDC provider did not return an id_token.")
        val login = authApiClient.oidcLogin(pendingLogin.serverUrl, idToken)

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

    private fun normalizeServerUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }

    private fun MobileConfig.toServiceConfiguration(): AuthorizationServiceConfiguration =
        AuthorizationServiceConfiguration(
            Uri.parse(authorizationEndpoint),
            Uri.parse(tokenEndpoint),
            null,
            endSessionEndpoint?.let(Uri::parse)
        )

    private companion object {
        val REDIRECT_URI: Uri = Uri.parse("sonnet://auth/callback")
    }
}

data class PendingLogin(
    val serverUrl: String,
    val mobileConfig: MobileConfig,
    val request: AuthorizationRequest
) {
    fun toIntent(authorizationService: AuthorizationService): Intent =
        authorizationService.getAuthorizationRequestIntent(request)

    fun toJson(): String = JSONObject()
        .put("server_url", serverUrl)
        .put("request", request.jsonSerialize())
        .toString()

    companion object {
        fun fromJson(value: String): PendingLogin {
            val json = JSONObject(value)
            val request = AuthorizationRequest.jsonDeserialize(json.getJSONObject("request"))
            return PendingLogin(
                serverUrl = json.getString("server_url"),
                mobileConfig = MobileConfig(
                    issuer = "",
                    clientId = request.clientId,
                    authorizationEndpoint = request.configuration.authorizationEndpoint.toString(),
                    tokenEndpoint = request.configuration.tokenEndpoint.toString(),
                    endSessionEndpoint = request.configuration.endSessionEndpoint?.toString(),
                    scopes = request.scope?.split(' ') ?: emptyList(),
                    responseType = request.responseType,
                    codeChallengeMethodsSupported = listOf("S256")
                ),
                request = request
            )
        }
    }
}
