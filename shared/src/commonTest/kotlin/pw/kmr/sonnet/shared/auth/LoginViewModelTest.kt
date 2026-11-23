package pw.kmr.sonnet.shared.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import pw.kmr.sonnet.shared.library.LocalLibraryCleaner
import pw.kmr.sonnet.shared.remote.AuthApiClient
import pw.kmr.sonnet.shared.remote.SonnetApiClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val fixedChallenge = AuthChallenge(
        codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
        codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
        state = "viewmodel-state-1234567890"
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startLogin persists pending login and emits authorize url`() = runTest(testDispatcher) {
        val store = InMemorySessionStore()
        val viewModel = LoginViewModel(createRepository(store = store))
        viewModel.onServerUrlChange("https://sonnet.example.com")

        viewModel.startLogin()
        advanceUntilIdle()

        val effect = assertIs<LoginEffect.OpenAuthBrowser>(viewModel.loginEffects.first())
        assertEquals(LoginRepository.DEFAULT_REDIRECT_URI, effect.redirectUri)
        assertEquals(fixedChallenge.state, store.loadPendingLogin()?.state)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `completeLogin recovers pending login from store after process recreation`() = runTest(testDispatcher) {
        val store = InMemorySessionStore()
        val repository = createRepository(
            store = store,
            engine = createMockEngine {
                respond(
                    content = """
                        {
                          "access_token": "token-1",
                          "refresh_token": "refresh-1",
                          "user": { "id": 42, "name": "Ada" }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        )
        store.savePendingLogin(
            PendingLogin(
                serverUrl = "https://sonnet.example.com",
                redirectUri = LoginRepository.DEFAULT_REDIRECT_URI,
                codeVerifier = fixedChallenge.codeVerifier,
                state = fixedChallenge.state
            )
        )

        val recreatedViewModel = LoginViewModel(repository)
        recreatedViewModel.completeLogin(
            AuthCallbackResult.Callback(
                "sonnet://auth/callback?code=handoff-ok&state=${fixedChallenge.state}"
            )
        )
        advanceUntilIdle()

        assertIs<LoginEffect.LoginCompleted>(recreatedViewModel.loginEffects.first())
        assertEquals("token-1", store.storedSession()?.accessToken)
        assertNull(store.loadPendingLogin())
        assertFalse(recreatedViewModel.uiState.value.isLoading)
        assertNull(recreatedViewModel.uiState.value.errorMessage)
    }

    @Test
    fun `cancelled and failed callbacks clear pending login and surface message`() = runTest(testDispatcher) {
        val store = InMemorySessionStore()
        val repository = createRepository(store = store)
        val viewModel = LoginViewModel(repository)
        viewModel.onServerUrlChange("https://sonnet.example.com")

        viewModel.startLogin()
        advanceUntilIdle()
        viewModel.completeLogin(AuthCallbackResult.Cancelled)
        advanceUntilIdle()

        assertNull(store.loadPendingLogin())
        assertEquals("Sign in was cancelled.", viewModel.uiState.value.errorMessage)

        viewModel.startLogin()
        advanceUntilIdle()
        viewModel.completeLogin(AuthCallbackResult.Failed("No browser available."))
        advanceUntilIdle()

        assertNull(store.loadPendingLogin())
        assertEquals("No browser available.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `invalid grant during exchange clears pending login and shows auth failed message`() = runTest(testDispatcher) {
        val store = InMemorySessionStore()
        val repository = createRepository(
            store = store,
            engine = createMockEngine {
                respond(
                    content = "{\"error\":\"invalid_grant\"}",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        )
        val viewModel = LoginViewModel(repository)
        viewModel.onServerUrlChange("https://sonnet.example.com")

        viewModel.startLogin()
        advanceUntilIdle()
        viewModel.completeLogin(
            AuthCallbackResult.Callback(
                "sonnet://auth/callback?code=expired&state=${fixedChallenge.state}"
            )
        )
        advanceUntilIdle()

        assertNull(store.loadPendingLogin())
        assertNull(store.storedSession())
        assertEquals("Authentication failed. Please sign in again.", viewModel.uiState.value.errorMessage)
    }

    private fun createMockEngine(handler: MockRequestHandler): MockEngine = MockEngine(
        MockEngineConfig().apply {
            dispatcher = testDispatcher
            addHandler(handler)
        }
    )

    private fun createRepository(
        store: InMemorySessionStore,
        engine: MockEngine = createMockEngine { respond(status = HttpStatusCode.OK, content = "{}") }
    ): LoginRepository {
        val authApiClient = AuthApiClient(SonnetApiClient(engine))
        val sessionManager = AuthSessionManager(store, authApiClient)
        return LoginRepository(
            authApiClient = authApiClient,
            authSessionManager = sessionManager,
            localLibraryCleaner = LocalLibraryCleaner {},
            enforcesHttps = true,
            challengeGenerator = object : AuthChallengeGenerator {
                override fun generate(): AuthChallenge = fixedChallenge
            }
        )
    }
}
