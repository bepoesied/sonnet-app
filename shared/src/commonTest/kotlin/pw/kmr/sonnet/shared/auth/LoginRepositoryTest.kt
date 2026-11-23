package pw.kmr.sonnet.shared.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import pw.kmr.sonnet.shared.library.LocalLibraryCleaner
import pw.kmr.sonnet.shared.model.AuthSession
import pw.kmr.sonnet.shared.remote.AuthApiClient
import pw.kmr.sonnet.shared.remote.SonnetApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LoginRepositoryTest {
    private val fixedChallenge = AuthChallenge(
        codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
        codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
        state = "fixed-state-1234567890"
    )

    @Test
    fun `prepareLogin normalizes url and builds mobile authorize request`() {
        val repository = createRepository(enforcesHttps = true)

        val prepared = repository.prepareLogin("  sonnet.example.com/  ")

        assertEquals("https://sonnet.example.com", prepared.pendingLogin.serverUrl)
        assertEquals(LoginRepository.DEFAULT_REDIRECT_URI, prepared.pendingLogin.redirectUri)
        assertEquals(fixedChallenge.codeVerifier, prepared.pendingLogin.codeVerifier)
        assertEquals(fixedChallenge.state, prepared.pendingLogin.state)

        val authorizeUrl = Url(prepared.authorizeUrl)
        assertEquals("https", authorizeUrl.protocol.name)
        assertEquals("sonnet.example.com", authorizeUrl.host)
        assertEquals("/auth/mobile/authorize", authorizeUrl.encodedPath)
        assertEquals("sonnet://auth/callback", authorizeUrl.parameters["redirect_uri"])
        assertEquals(fixedChallenge.codeChallenge, authorizeUrl.parameters["code_challenge"])
        assertEquals("S256", authorizeUrl.parameters["code_challenge_method"])
        assertEquals(fixedChallenge.state, authorizeUrl.parameters["state"])
    }

    @Test
    fun `prepareLogin enforces https policy and rejects invalid urls`() {
        val httpsOnly = createRepository(enforcesHttps = true)
        assertFailsWith<IllegalArgumentException> { httpsOnly.prepareLogin("http://sonnet.example.com") }
        assertFailsWith<IllegalArgumentException> { httpsOnly.prepareLogin("ftp://sonnet.example.com") }
        assertFailsWith<IllegalArgumentException> { httpsOnly.prepareLogin("https://sonnet.example.com?x=1") }
        assertFailsWith<IllegalArgumentException> { httpsOnly.prepareLogin("https://sonnet.example.com#frag") }
        assertFailsWith<IllegalArgumentException> { httpsOnly.prepareLogin("   ") }

        val allowHttp = createRepository(enforcesHttps = false)
        val prepared = allowHttp.prepareLogin("http://10.0.2.2:4000/")
        assertEquals("http://10.0.2.2:4000", prepared.pendingLogin.serverUrl)
    }

    @Test
    fun `completeLogin verifies callback exchanges code and persists session`() = runTest {
        var exchangeRequest: HttpRequestData? = null
        val store = InMemorySessionStore()
        val repository = createRepository(
            sessionStore = store,
            engine = MockEngine { request ->
                exchangeRequest = request
                respond(
                    content = """
                        {
                          "access_token": "access-abc",
                          "refresh_token": "refresh-xyz",
                          "user": {
                            "id": 7,
                            "name": "Ada Lovelace",
                            "avatar_url": null
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        )
        val pending = repository.prepareLogin("https://sonnet.example.com").pendingLogin

        repository.completeLogin(
            pendingLogin = pending,
            callbackUri = "sonnet://auth/callback?code=handoff-99&state=${fixedChallenge.state}"
        )

        val sent = requireNotNull(exchangeRequest)
        assertEquals("https://sonnet.example.com/api/auth/mobile/exchange", sent.url.toString())
        assertEquals(
            "{\"code\":\"handoff-99\",\"code_verifier\":\"${fixedChallenge.codeVerifier}\",\"redirect_uri\":\"sonnet://auth/callback\"}",
            (sent.body as TextContent).text
        )
        val saved = requireNotNull(store.storedSession())
        assertEquals("https://sonnet.example.com", saved.serverUrl)
        assertEquals("access-abc", saved.accessToken)
        assertEquals("refresh-xyz", saved.refreshToken)
        assertEquals(7L, saved.user.id)
        assertEquals("Ada Lovelace", saved.user.name)
    }

    @Test
    fun `completeLogin rejects bad redirect uri state error and missing code without calling exchange`() = runTest {
        var exchangeCalls = 0
        val store = InMemorySessionStore()
        val repository = createRepository(
            sessionStore = store,
            engine = MockEngine {
                exchangeCalls++
                respond(status = HttpStatusCode.OK, content = "{}")
            }
        )
        val pending = repository.prepareLogin("https://sonnet.example.com").pendingLogin

        val invalidCallbacks = listOf(
            "other://auth/callback?code=ok&state=${fixedChallenge.state}",
            "sonnet://wrong/callback?code=ok&state=${fixedChallenge.state}",
            "sonnet://auth/other?code=ok&state=${fixedChallenge.state}",
            "sonnet://auth/callback?code=ok&state=wrong-state",
            "sonnet://auth/callback?code=ok",
            "sonnet://auth/callback?error=authentication_failed&state=${fixedChallenge.state}",
            "sonnet://auth/callback?state=${fixedChallenge.state}"
        )

        for (callback in invalidCallbacks) {
            assertFailsWith<AuthCallbackException> {
                repository.completeLogin(pending, callback)
            }
        }

        assertEquals(0, exchangeCalls)
        assertNull(store.storedSession())
    }

    private fun createRepository(
        enforcesHttps: Boolean = true,
        sessionStore: InMemorySessionStore = InMemorySessionStore(),
        engine: MockEngine = MockEngine { respond(status = HttpStatusCode.OK, content = "{}") }
    ): LoginRepository {
        val authApiClient = AuthApiClient(SonnetApiClient(engine))
        val sessionManager = AuthSessionManager(sessionStore, authApiClient)
        return LoginRepository(
            authApiClient = authApiClient,
            authSessionManager = sessionManager,
            localLibraryCleaner = LocalLibraryCleaner {},
            enforcesHttps = enforcesHttps,
            challengeGenerator = object : AuthChallengeGenerator {
                override fun generate(): AuthChallenge = fixedChallenge
            }
        )
    }
}

internal class InMemorySessionStore(
    initialSession: AuthSession? = null,
    private var savedUrl: String? = initialSession?.serverUrl
) : SessionStore {
    private val sessionState = MutableStateFlow(initialSession)
    private val requiresLoginState = MutableStateFlow(false)
    private var pendingLogin: PendingLogin? = null

    override val currentSession: StateFlow<AuthSession?> = sessionState.asStateFlow()
    override val requiresLogin: StateFlow<Boolean> = requiresLoginState.asStateFlow()

    override suspend fun saveSession(session: AuthSession) {
        savedUrl = session.serverUrl
        requiresLoginState.value = false
        sessionState.value = session
    }

    override suspend fun markRequiresLogin() {
        requiresLoginState.value = true
    }

    override suspend fun clearSession(retainServerUrl: Boolean) {
        if (!retainServerUrl) savedUrl = null
        requiresLoginState.value = false
        sessionState.value = null
    }

    override suspend fun savedServerUrl(): String? = savedUrl

    override suspend fun storedSession(): AuthSession? = sessionState.value

    override suspend fun loadStoredSession(): AuthSession? = sessionState.value

    override suspend fun savePendingLogin(pendingLogin: PendingLogin) {
        this.pendingLogin = pendingLogin
    }

    override suspend fun loadPendingLogin(): PendingLogin? = pendingLogin

    override suspend fun clearPendingLogin() {
        pendingLogin = null
    }
}
