package pw.kmr.sonnet.shared.core

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import pw.kmr.sonnet.shared.auth.AuthRemoteDataSource
import pw.kmr.sonnet.shared.auth.AuthSessionManager
import pw.kmr.sonnet.shared.auth.PendingLogin
import pw.kmr.sonnet.shared.auth.SessionStore
import pw.kmr.sonnet.shared.model.AuthSession
import pw.kmr.sonnet.shared.model.SonnetUser
import pw.kmr.sonnet.shared.model.TokenRefreshResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    @Test
    fun `starts in Loading and transitions directly to Ready(session) with zero Ready(null) flash`() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val viewModelStore = ViewModelStore()
        try {
            val storedSession = testSession()
            val bootstrapGate = CompletableDeferred<Unit>()
            val sessionStore = FakeAppSessionStore(persistedSession = storedSession)
            val authSessionManager = AuthSessionManager(sessionStore, FakeAppAuthRemoteDataSource())
            val repository = FakeAppViewModelRepository(
                authSessionManager = authSessionManager,
                onBootstrap = { bootstrapGate.await() }
            )

            val viewModel = ViewModelProvider.create(
                store = viewModelStore,
                factory = appViewModelFactory(repository, authSessionManager)
            )[AppViewModel::class]

            val emissions = mutableListOf<AppUiState>()
            val collectorJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { emissions += it }
            }

            runCurrent()
            assertEquals(AppUiState.Loading, viewModel.uiState.value)
            assertEquals(listOf<AppUiState>(AppUiState.Loading), emissions)

            bootstrapGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    AppUiState.Loading,
                    AppUiState.Ready(session = storedSession, requiresReauth = false)
                ),
                emissions
            )
            assertFalse(
                emissions.any { it is AppUiState.Ready && it.session == null },
                "Must never emit an intermediate Ready(null) flash when a stored session exists."
            )
            collectorJob.cancel()
        } finally {
            viewModelStore.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `transitions directly from Loading to Ready(null) when no stored session exists`() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val viewModelStore = ViewModelStore()
        try {
            val bootstrapGate = CompletableDeferred<Unit>()
            val sessionStore = FakeAppSessionStore(persistedSession = null)
            val authSessionManager = AuthSessionManager(sessionStore, FakeAppAuthRemoteDataSource())
            val repository = FakeAppViewModelRepository(
                authSessionManager = authSessionManager,
                onBootstrap = { bootstrapGate.await() }
            )

            val viewModel = ViewModelProvider.create(
                store = viewModelStore,
                factory = appViewModelFactory(repository, authSessionManager)
            )[AppViewModel::class]

            val emissions = mutableListOf<AppUiState>()
            val collectorJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { emissions += it }
            }

            runCurrent()
            assertEquals(AppUiState.Loading, viewModel.uiState.value)

            bootstrapGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    AppUiState.Loading,
                    AppUiState.Ready(session = null, requiresReauth = false)
                ),
                emissions
            )
            collectorJob.cancel()
        } finally {
            viewModelStore.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `updates requiresReauth reactively when session requires re-authentication and recovers on saveSession`() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val viewModelStore = ViewModelStore()
        try {
            val storedSession = testSession()
            val sessionStore = FakeAppSessionStore(persistedSession = storedSession)
            val authSessionManager = AuthSessionManager(sessionStore, FakeAppAuthRemoteDataSource())
            val repository = FakeAppViewModelRepository(authSessionManager = authSessionManager)

            val viewModel = ViewModelProvider.create(
                store = viewModelStore,
                factory = appViewModelFactory(repository, authSessionManager)
            )[AppViewModel::class]

            advanceUntilIdle()
            val readyInitial = assertIs<AppUiState.Ready>(viewModel.uiState.value)
            assertEquals(storedSession, readyInitial.session)
            assertFalse(readyInitial.requiresReauth)

            sessionStore.markRequiresLogin()
            advanceUntilIdle()
            val readyNeedsReauth = assertIs<AppUiState.Ready>(viewModel.uiState.value)
            assertEquals(storedSession, readyNeedsReauth.session)
            assertTrue(readyNeedsReauth.requiresReauth)

            val refreshedSession = storedSession.copy(accessToken = "fresh-access", refreshToken = "fresh-refresh")
            authSessionManager.saveSession(refreshedSession)
            advanceUntilIdle()
            val readyRecovered = assertIs<AppUiState.Ready>(viewModel.uiState.value)
            assertEquals(refreshedSession, readyRecovered.session)
            assertFalse(readyRecovered.requiresReauth)
        } finally {
            viewModelStore.clear()
            Dispatchers.resetMain()
        }
    }

    private fun testSession() = AuthSession(
        serverUrl = "https://sonnet.example",
        accessToken = "access-1",
        refreshToken = "refresh-1",
        user = SonnetUser(id = 1L, name = "Ada", avatarUrl = null)
    )
}

private class FakeAppViewModelRepository(
    private val authSessionManager: AuthSessionManager,
    private val onBootstrap: suspend () -> Unit = {}
) : AppViewModelRepository {
    override suspend fun bootstrapSession() {
        onBootstrap()
        authSessionManager.bootstrapSession()
    }

    override suspend fun logout() {
        authSessionManager.clearSession(retainServerUrl = true)
    }
}

private class FakeAppSessionStore(
    private var persistedSession: AuthSession?,
    private var persistedRequiresLogin: Boolean = false
) : SessionStore {
    private val sessionState = MutableStateFlow<AuthSession?>(null)
    private val requiresLoginState = MutableStateFlow(false)
    private var pendingLogin: PendingLogin? = null

    override val currentSession: StateFlow<AuthSession?> = sessionState.asStateFlow()
    override val requiresLogin: StateFlow<Boolean> = requiresLoginState.asStateFlow()

    override suspend fun saveSession(session: AuthSession) {
        persistedSession = session
        persistedRequiresLogin = false
        requiresLoginState.value = false
        sessionState.value = session
    }

    override suspend fun markRequiresLogin() {
        persistedRequiresLogin = true
        requiresLoginState.value = true
    }

    override suspend fun clearSession(retainServerUrl: Boolean) {
        persistedSession = null
        persistedRequiresLogin = false
        requiresLoginState.value = false
        sessionState.value = null
    }

    override suspend fun savedServerUrl(): String? = persistedSession?.serverUrl

    override suspend fun storedSession(): AuthSession? = persistedSession

    override suspend fun loadStoredSession(): AuthSession? {
        requiresLoginState.value = persistedRequiresLogin
        sessionState.value = persistedSession
        return persistedSession
    }

    override suspend fun savePendingLogin(pendingLogin: PendingLogin) {
        this.pendingLogin = pendingLogin
    }

    override suspend fun loadPendingLogin(): PendingLogin? = pendingLogin

    override suspend fun clearPendingLogin() {
        pendingLogin = null
    }
}

private class FakeAppAuthRemoteDataSource : AuthRemoteDataSource {
    override suspend fun me(serverUrl: String, accessToken: String): SonnetUser =
        SonnetUser(id = 1L, name = "Ada", avatarUrl = null)

    override suspend fun refresh(serverUrl: String, refreshToken: String): TokenRefreshResponse =
        TokenRefreshResponse(accessToken = "refreshed-access", refreshToken = "refreshed-refresh")

    override fun isUnauthorizedError(exception: Exception): Boolean = false

    override fun isAuthFailureError(exception: Exception): Boolean = false
}
