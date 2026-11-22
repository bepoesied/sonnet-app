package pw.kmr.sonnet.shared.auth

import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pw.kmr.sonnet.shared.remote.ApiException
import pw.kmr.sonnet.shared.remote.SonnetApiClient

/**
 * Cryptographic primitives required for RFC 7636 PKCE S256 and OIDC state/nonce generation.
 */
interface OidcCryptoProvider {
    fun randomBytes(count: Int): ByteArray

    fun sha256(data: ByteArray): ByteArray
}

/**
 * Payload emitted in [LoginEffect.OpenAuthBrowser] for platforms using [OidcPkceAuthProvider].
 */
data class OidcAuthorizationRequest(
    val authorizationUrl: String,
    val callbackScheme: String,
    val state: String,
    val codeVerifier: String,
    val nonce: String
)

/**
 * Callback payload passed into [LoginViewModel.completeLogin] after the native browser auth session
 * redirects back to the registered custom scheme.
 */
data class OidcAuthorizationResult(
    val callbackUrl: String,
    val codeVerifier: String? = null,
    val expectedState: String? = null
)

/**
 * Cross-platform native OIDC Authorization Code + PKCE S256 implementation of [PlatformAuthProvider].
 *
 * Uses [OidcCryptoProvider] for CSPRNG & SHA-256 and [SonnetApiClient] (Ktor) for the direct
 * token exchange with the OIDC provider.
 */
class OidcPkceAuthProvider(
    private val apiClient: SonnetApiClient,
    private val cryptoProvider: OidcCryptoProvider,
    private val redirectUri: String = DEFAULT_REDIRECT_URI,
    private val callbackScheme: String = DEFAULT_CALLBACK_SCHEME
) : PlatformAuthProvider {
    private val attemptMutex = Mutex()
    private var activeAttempt: PkceAttempt? = null

    override suspend fun startAuthorization(pendingLogin: PendingLogin): Any {
        val codeVerifier = base64UrlEncode(cryptoProvider.randomBytes(VERIFIER_BYTE_COUNT))
        val codeChallenge = base64UrlEncode(cryptoProvider.sha256(codeVerifier.encodeToByteArray()))
        val state = base64UrlEncode(cryptoProvider.randomBytes(STATE_BYTE_COUNT))
        val nonce = base64UrlEncode(cryptoProvider.randomBytes(NONCE_BYTE_COUNT))

        val authUrl = URLBuilder(pendingLogin.authorizationEndpoint).apply {
            parameters.append("client_id", pendingLogin.clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_type", pendingLogin.responseType)
            parameters.append("scope", pendingLogin.scopes.joinToString(" "))
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", CODE_CHALLENGE_METHOD_S256)
            parameters.append("state", state)
            parameters.append("nonce", nonce)
        }.buildString()

        attemptMutex.withLock {
            activeAttempt = PkceAttempt(
                state = state,
                codeVerifier = codeVerifier,
                nonce = nonce
            )
        }

        return OidcAuthorizationRequest(
            authorizationUrl = authUrl,
            callbackScheme = callbackScheme,
            state = state,
            codeVerifier = codeVerifier,
            nonce = nonce
        )
    }

    override suspend fun completeAuthorization(pendingLogin: PendingLogin, result: Any): String {
        val authResult = when (result) {
            is OidcAuthorizationResult -> result
            is String -> OidcAuthorizationResult(callbackUrl = result)
            else -> error("Unsupported OIDC authorization result.")
        }

        val attempt = attemptMutex.withLock {
            val current = activeAttempt
            activeAttempt = null
            current
        }

        val expectedState = authResult.expectedState ?: attempt?.state
        val codeVerifier = authResult.codeVerifier ?: attempt?.codeVerifier
            ?: error("Missing PKCE verification state. Please try signing in again.")

        val callbackUrl = try {
            Url(authResult.callbackUrl)
        } catch (_: Exception) {
            error("OIDC provider returned an invalid callback URL.")
        }

        val errorCode = callbackUrl.parameters["error"]
        if (!errorCode.isNullOrBlank()) {
            val description = callbackUrl.parameters["error_description"]?.takeIf { it.isNotBlank() }
            if (errorCode == "access_denied") {
                error("Sign in was cancelled.")
            }
            error(description ?: "OIDC authorization failed ($errorCode).")
        }

        val returnedState = callbackUrl.parameters["state"]
        if (!expectedState.isNullOrBlank() && returnedState != expectedState) {
            error("OIDC state verification failed. Please try signing in again.")
        }

        val authorizationCode = callbackUrl.parameters["code"]?.takeIf { it.isNotBlank() }
            ?: error("OIDC provider did not return an authorization code.")

        val response = apiClient.httpClient.submitForm(
            url = pendingLogin.tokenEndpoint,
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("client_id", pendingLogin.clientId)
                append("code", authorizationCode)
                append("redirect_uri", redirectUri)
                append("code_verifier", codeVerifier)
            }
        )

        if (!response.status.isSuccess()) {
            throw ApiException(response.status.value, response.bodyAsText())
        }

        val tokenPayload = response.body<OidcTokenResponseDto>()
        return tokenPayload.idToken?.takeIf { it.isNotBlank() }
            ?: error("OIDC provider did not return an id_token.")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

    private data class PkceAttempt(
        val state: String,
        val codeVerifier: String,
        val nonce: String
    )

    @Serializable
    private data class OidcTokenResponseDto(
        @SerialName("id_token") val idToken: String? = null
    )

    companion object {
        const val DEFAULT_CALLBACK_SCHEME = "sonnet"
        const val DEFAULT_REDIRECT_URI = "sonnet://auth/callback"
        private const val CODE_CHALLENGE_METHOD_S256 = "S256"
        private const val VERIFIER_BYTE_COUNT = 32
        private const val STATE_BYTE_COUNT = 24
        private const val NONCE_BYTE_COUNT = 24
    }
}
