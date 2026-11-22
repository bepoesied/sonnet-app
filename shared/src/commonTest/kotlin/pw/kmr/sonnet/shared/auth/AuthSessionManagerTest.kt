package pw.kmr.sonnet.shared.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import pw.kmr.sonnet.shared.model.AuthSession
import pw.kmr.sonnet.shared.model.MobileConfig
import pw.kmr.sonnet.shared.model.SonnetUser
import pw.kmr.sonnet.shared.model.TokenRefreshResponse
import pw.kmr.sonnet.shared.remote.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthSessionManagerTest {

    @Test
    fun `401 in withAuthRetry refreshes tokens and retries with new access token`() = runTest {
        val initialSession = testSession(accessToken = "old-access", refreshToken = "old-refresh")
        val store = FakeSessionStore(initialSession)
        val remote = FakeAuthRemoteDataSource(
            onRefresh = { _, refreshToken ->
                assertEquals("old-refresh", refreshToken)
                TokenRefreshResponse(accessToken = "new-access", refreshToken = "new-refresh")
            }
        )
        val manager = AuthSessionManager(store, remote)
        val attemptedTokens = mutableListOf<String>()

        val result = manager.withAuthRetry("fetching books") { session ->
            attemptedTokens += session.accessToken
            if (session.accessToken == "old-access") {
                throw ApiException(401, "expired")
            }
            "ok:${session.accessToken}"
        }

        assertEquals("ok:new-access", result)
        assertEquals(listOf("old-access", "new-access"), attemptedTokens)
        assertEquals(1, remote.refreshCalls)
        assertEquals("new-access", store.storedSession()?.accessToken)
        assertEquals("new-refresh", store.storedSession()?.refreshToken)
        assertFalse(manager.requiresLogin.value)
    }

    @Test
    fun `rotated refresh token is persisted before me even when me fails`() = runTest {
        val initialSession = testSession(accessToken = "expired-access", refreshToken = "refresh-1")
        val store = FakeSessionStore(initialSession)
        val events = mutableListOf<String>()
        var meAttempts = 0
        val remote = FakeAuthRemoteDataSource(
            onMe = { _, accessToken ->
                meAttempts++
                events += "me:$accessToken"
                if (meAttempts == 1) {
                    throw ApiException(401, "access expired")
                }
                throw ApiException(500, "temporary backend outage")
            },
            onRefresh = { _, refreshToken ->
                events += "refresh:$refreshToken"
                TokenRefreshResponse(accessToken = "access-2", refreshToken = "refresh-2")
            }
        )
        store.onSave = { saved -> events += "save:${saved.accessToken}:${saved.refreshToken}" }

        val manager = AuthSessionManager(store, remote)
        manager.refreshStoredSession()

        assertEquals(
            listOf(
                "me:expired-access",
                "refresh:refresh-1",
                "save:access-2:refresh-2",
                "me:access-2"
            ),
            events
        )
        assertEquals("access-2", store.storedSession()?.accessToken)
        assertEquals("refresh-2", store.storedSession()?.refreshToken)
        assertEquals(initialSession.user, store.storedSession()?.user)
        assertFalse(manager.requiresLogin.value)
    }

    @Test
    fun `rotated refresh token stays persisted when me throws cancellation exception`() = runTest {
        val initialSession = testSession(accessToken = "expired-access", refreshToken = "refresh-1")
        val store = FakeSessionStore(initialSession)
        var meAttempts = 0
        val remote = FakeAuthRemoteDataSource(
            onMe = { _, _ ->
                meAttempts++
                if (meAttempts == 1) {
                    throw ApiException(401, "access expired")
                }
                throw CancellationException("job cancelled during me")
            },
            onRefresh = { _, _ ->
                TokenRefreshResponse(accessToken = "access-2", refreshToken = "refresh-2")
            }
        )

        val manager = AuthSessionManager(store, remote)
        assertFailsWith<CancellationException> {
            manager.refreshStoredSession()
        }

        assertEquals("access-2", store.storedSession()?.accessToken)
        assertEquals("refresh-2", store.storedSession()?.refreshToken)
    }

    @Test
    fun `offline refreshStoredSession does not call refresh on transient errors`() = runTest {
        val initialSession = testSession(accessToken = "valid-access", refreshToken = "valid-refresh")
        val store = FakeSessionStore(initialSession)
        val remote = FakeAuthRemoteDataSource(
            onMe = { _, _ -> throw IllegalStateException("Unable to resolve host") },
            onRefresh = { _, _ -> error("refresh must not be called on offline errors") }
        )

        val manager = AuthSessionManager(store, remote)
        manager.refreshStoredSession()

        assertEquals(1, remote.meCalls)
        assertEquals(0, remote.refreshCalls)
        assertEquals(initialSession, store.storedSession())
        assertFalse(manager.requiresLogin.value)
    }

    @Test
    fun `refreshStoredSession does not swallow cancellation exception from me`() = runTest {
        val store = FakeSessionStore(testSession())
        val remote = FakeAuthRemoteDataSource(
            onMe = { _, _ -> throw CancellationException("cancelled") }
        )

        val manager = AuthSessionManager(store, remote)
        assertFailsWith<CancellationException> {
            manager.refreshStoredSession()
        }
        assertEquals(0, remote.refreshCalls)
    }

    @Test
    fun `422 and 401 on refresh set requiresLogin to true`() = runTest {
        for (statusCode in listOf(422, 401)) {
            val initialSession = testSession(accessToken = "expired-access", refreshToken = "revoked-refresh")
            val store = FakeSessionStore(initialSession)
            val remote = FakeAuthRemoteDataSource(
                onRefresh = { _, _ -> throw ApiException(statusCode, "invalid refresh token") }
            )
            val manager = AuthSessionManager(store, remote)

            val error = assertFailsWith<IllegalStateException> {
                manager.withAuthRetry("syncing books") {
                    throw ApiException(401, "expired")
                }
            }

            assertTrue(error.message.orEmpty().contains("Sign in again"))
            assertTrue(manager.requiresLogin.value)

            // Subsequent calls fail fast without hitting the network.
            var requestInvoked = false
            assertFailsWith<IllegalStateException> {
                manager.withAuthRetry("syncing books") {
                    requestInvoked = true
                    "never"
                }
            }
            assertFalse(requestInvoked)

            // Saving a fresh session clears requiresLogin.
            manager.saveSession(testSession(accessToken = "fresh-access", refreshToken = "fresh-refresh"))
            assertFalse(manager.requiresLogin.value)
        }
    }

    @Test
    fun `concurrent 401 retries share a single refresh request`() = runTest {
        val initialSession = testSession(accessToken = "old-access", refreshToken = "old-refresh")
        val store = FakeSessionStore(initialSession)
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val remote = FakeAuthRemoteDataSource(
            onRefresh = { _, _ ->
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                TokenRefreshResponse(accessToken = "new-access", refreshToken = "new-refresh")
            }
        )
        val manager = AuthSessionManager(store, remote)

        val first = async {
            manager.withAuthRetry("request 1") { session ->
                if (session.accessToken == "old-access") throw ApiException(401, "expired")
                session.accessToken
            }
        }
        refreshStarted.await()
        val second = async {
            manager.withAuthRetry("request 2") { session ->
                if (session.accessToken == "old-access") throw ApiException(401, "expired")
                session.accessToken
            }
        }
        releaseRefresh.complete(Unit)

        assertEquals(listOf("new-access", "new-access"), awaitAll(first, second))
        assertEquals(1, remote.refreshCalls)
    }

    @Test
    fun `pending login round trips through session store`() = runTest {
        val store = FakeSessionStore(null)
        val manager = AuthSessionManager(store, FakeAuthRemoteDataSource())
        val pending = PendingLogin(
            serverUrl = "https://sonnet.example",
            clientId = "sonnet-mobile",
            scopes = listOf("openid", "profile"),
            authorizationEndpoint = "https://auth.example/authorize",
            tokenEndpoint = "https://auth.example/token",
            endSessionEndpoint = null,
            responseType = "code",
            mobileConfig = MobileConfig(
                issuer = "https://auth.example",
                clientId = "sonnet-mobile",
                authorizationEndpoint = "https://auth.example/authorize",
                tokenEndpoint = "https://auth.example/token",
                scopes = listOf("openid", "profile"),
                responseType = "code",
                codeChallengeMethodsSupported = listOf("S256")
            )
        )

        assertNull(manager.loadPendingLogin())
        manager.savePendingLogin(pending)
        assertEquals(pending, manager.loadPendingLogin())
        manager.clearPendingLogin()
        assertNull(manager.loadPendingLogin())
    }

    private fun testSession(
        accessToken: String = "access-1",
        refreshToken: String = "refresh-1"
    ) = AuthSession(
        serverUrl = "https://sonnet.example",
        accessToken = accessToken,
        refreshToken = refreshToken,
        user = SonnetUser(id = 1L, name = "Ada", avatarUrl = null)
    )
}

private class FakeSessionStore(
    initialSession: AuthSession? = null,
    initialRequiresLogin: Boolean = false
) : SessionStore {
    private val sessionState = MutableStateFlow(initialSession)
    private val requiresLoginState = MutableStateFlow(initialRequiresLogin)
    private var serverUrl: String? = initialSession?.serverUrl
    private var pendingLogin: PendingLogin? = null

    var onSave: (AuthSession) -> Unit = {}

    override val currentSession: StateFlow<AuthSession?> = sessionState.asStateFlow()
    override val requiresLogin: StateFlow<Boolean> = requiresLoginState.asStateFlow()

    override suspend fun saveSession(session: AuthSession) {
        onSave(session)
        serverUrl = session.serverUrl
        requiresLoginState.value = false
        sessionState.value = session
    }

    override suspend fun markRequiresLogin() {
        requiresLoginState.value = true
    }

    override suspend fun clearSession(retainServerUrl: Boolean) {
        if (!retainServerUrl) serverUrl = null
        requiresLoginState.value = false
        sessionState.value = null
    }

    override suspend fun savedServerUrl(): String? = serverUrl

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

private class FakeAuthRemoteDataSource(
    private val onMe: suspend (String, String) -> SonnetUser = { _, _ ->
        SonnetUser(id = 1L, name = "Ada", avatarUrl = null)
    },
    private val onRefresh: suspend (String, String) -> TokenRefreshResponse = { _, _ ->
        TokenRefreshResponse(accessToken = "refreshed-access", refreshToken = "refreshed-refresh")
    }
) : AuthRemoteDataSource {
    var meCalls: Int = 0
        private set
    var refreshCalls: Int = 0
        private set

    override suspend fun me(serverUrl: String, accessToken: String): SonnetUser {
        meCalls++
        return onMe(serverUrl, accessToken)
    }

    override suspend fun refresh(serverUrl: String, refreshToken: String): TokenRefreshResponse {
        refreshCalls++
        return onRefresh(serverUrl, refreshToken)
    }

    override fun isUnauthorizedError(exception: Exception): Boolean =
        exception is ApiException && exception.statusCode == 401

    override fun isAuthFailureError(exception: Exception): Boolean =
        exception is ApiException && exception.statusCode in setOf(400, 401, 403, 422)
}
