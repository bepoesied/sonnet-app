package pw.kmr.sonnet.shared.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import pw.kmr.sonnet.shared.model.MobileConfig
import pw.kmr.sonnet.shared.remote.ApiException
import pw.kmr.sonnet.shared.remote.SonnetApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OidcPkceAuthProviderTest {

    private val pendingLogin = PendingLogin(
        serverUrl = "https://sonnet.example.com",
        clientId = "sonnet-mobile",
        scopes = listOf("openid", "profile"),
        authorizationEndpoint = "https://idp.example.com/oauth/authorize",
        tokenEndpoint = "https://idp.example.com/oauth/token",
        endSessionEndpoint = "https://idp.example.com/oauth/logout",
        responseType = "code",
        mobileConfig = MobileConfig(
            issuer = "https://idp.example.com",
            clientId = "sonnet-mobile",
            authorizationEndpoint = "https://idp.example.com/oauth/authorize",
            tokenEndpoint = "https://idp.example.com/oauth/token",
            endSessionEndpoint = "https://idp.example.com/oauth/logout",
            scopes = listOf("openid", "profile"),
            responseType = "code",
            codeChallengeMethodsSupported = listOf("S256")
        )
    )

    @Test
    fun buildsAuthorizationUrlAndExchangesCodeForIdToken() = runTest {
        var capturedForm: Map<String, String> = emptyMap()
        val mockEngine = MockEngine { request ->
            assertEquals("https://idp.example.com/oauth/token", request.url.toString())
            val formContent = request.body as FormDataContent
            capturedForm = formContent.formData.entries().associate { (k, v) -> k to v.first() }
            respond(
                content = """{"id_token":"jwt.id.token","access_token":"idp-access"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val apiClient = SonnetApiClient(mockEngine)
        val provider = OidcPkceAuthProvider(apiClient, FakeOidcCryptoProvider())

        val authRequest = provider.startAuthorization(pendingLogin) as OidcAuthorizationRequest
        val parsed = Url(authRequest.authorizationUrl)

        assertEquals("sonnet", authRequest.callbackScheme)
        assertEquals("sonnet-mobile", parsed.parameters["client_id"])
        assertEquals("sonnet://auth/callback", parsed.parameters["redirect_uri"])
        assertEquals("code", parsed.parameters["response_type"])
        assertEquals("openid profile", parsed.parameters["scope"])
        assertEquals("S256", parsed.parameters["code_challenge_method"])
        assertEquals(authRequest.state, parsed.parameters["state"])
        assertEquals(authRequest.nonce, parsed.parameters["nonce"])
        assertNotEquals(authRequest.codeVerifier, parsed.parameters["code_challenge"])
        assertTrue(authRequest.codeVerifier.length >= 43)

        val callback = "sonnet://auth/callback?code=auth-code-99&state=${authRequest.state}"
        val idToken = provider.completeAuthorization(
            pendingLogin = pendingLogin,
            result = OidcAuthorizationResult(callbackUrl = callback)
        )

        assertEquals("jwt.id.token", idToken)
        assertEquals("authorization_code", capturedForm["grant_type"])
        assertEquals("sonnet-mobile", capturedForm["client_id"])
        assertEquals("auth-code-99", capturedForm["code"])
        assertEquals("sonnet://auth/callback", capturedForm["redirect_uri"])
        assertEquals(authRequest.codeVerifier, capturedForm["code_verifier"])
        apiClient.close()
    }

    @Test
    fun rejectsMismatchedState() = runTest {
        val apiClient = SonnetApiClient(MockEngine { error("Token endpoint should not be called") })
        val provider = OidcPkceAuthProvider(apiClient, FakeOidcCryptoProvider())

        provider.startAuthorization(pendingLogin)
        val error = assertFailsWith<IllegalStateException> {
            provider.completeAuthorization(
                pendingLogin = pendingLogin,
                result = OidcAuthorizationResult("sonnet://auth/callback?code=abc&state=wrong-state")
            )
        }
        assertTrue(error.message.orEmpty().contains("state", ignoreCase = true))
        apiClient.close()
    }

    @Test
    fun mapsAccessDeniedToCancelledError() = runTest {
        val apiClient = SonnetApiClient(MockEngine { error("Token endpoint should not be called") })
        val provider = OidcPkceAuthProvider(apiClient, FakeOidcCryptoProvider())

        val authRequest = provider.startAuthorization(pendingLogin) as OidcAuthorizationRequest
        val error = assertFailsWith<IllegalStateException> {
            provider.completeAuthorization(
                pendingLogin = pendingLogin,
                result = OidcAuthorizationResult(
                    "sonnet://auth/callback?error=access_denied&state=${authRequest.state}"
                )
            )
        }
        assertTrue(error.message.orEmpty().contains("cancelled", ignoreCase = true))
        apiClient.close()
    }

    @Test
    fun throwsApiExceptionOnTokenEndpointFailure() = runTest {
        val apiClient = SonnetApiClient(
            MockEngine {
                respond(
                    content = """{"error":"invalid_grant"}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        )
        val provider = OidcPkceAuthProvider(apiClient, FakeOidcCryptoProvider())

        val authRequest = provider.startAuthorization(pendingLogin) as OidcAuthorizationRequest
        val exception = assertFailsWith<ApiException> {
            provider.completeAuthorization(
                pendingLogin = pendingLogin,
                result = OidcAuthorizationResult("sonnet://auth/callback?code=bad&state=${authRequest.state}")
            )
        }
        assertEquals(400, exception.statusCode)
        apiClient.close()
    }
}

private class FakeOidcCryptoProvider : OidcCryptoProvider {
    private var counter = 1

    override fun randomBytes(count: Int): ByteArray =
        ByteArray(count) { index -> ((index + counter++) and 0xFF).toByte() }

    override fun sha256(data: ByteArray): ByteArray =
        ByteArray(32) { index -> (data[index % data.size].toInt() xor (index * 31)).toByte() }
}
