package pw.kmr.sonnet.auth

import android.content.Context
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
import pw.kmr.sonnet.shared.auth.PendingLogin
import pw.kmr.sonnet.shared.auth.PlatformAuthProvider
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AppAuthPlatformProvider(context: Context) : PlatformAuthProvider {
    private val authService = AuthorizationService(context.applicationContext)

    override suspend fun startAuthorization(pendingLogin: PendingLogin): Intent {
        val config = AuthorizationServiceConfiguration(
            Uri.parse(pendingLogin.authorizationEndpoint),
            Uri.parse(pendingLogin.tokenEndpoint),
            null,
            pendingLogin.endSessionEndpoint?.let { Uri.parse(it) }
        )
        val request = AuthorizationRequest.Builder(
            config,
            pendingLogin.clientId,
            ResponseTypeValues.CODE,
            REDIRECT_URI
        )
            .setScope(pendingLogin.scopes.joinToString(" "))
            .setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
            .setNonce(CodeVerifierUtil.generateRandomCodeVerifier())
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    override suspend fun completeAuthorization(pendingLogin: PendingLogin, result: Any): String {
        val resultIntent = result as Intent
        val exception = AuthorizationException.fromIntent(resultIntent)
        if (exception != null) throw exception

        val response = AuthorizationResponse.fromIntent(resultIntent)
            ?: error("OIDC provider did not return an authorization response.")
        val tokenResponse = suspendCancellableCoroutine { continuation ->
            authService.performTokenRequest(response.createTokenExchangeRequest()) { exchanged, tokenException ->
                when {
                    exchanged != null -> continuation.resume(exchanged)
                    tokenException != null -> continuation.resumeWithException(tokenException)
                    else -> continuation.resumeWithException(IllegalStateException("OIDC token exchange failed."))
                }
            }
        }
        return tokenResponse.idToken ?: error("OIDC provider did not return an id_token.")
    }

    fun dispose() {
        authService.dispose()
    }

    private companion object {
        val REDIRECT_URI: Uri = Uri.parse("sonnet://auth/callback")
    }
}
