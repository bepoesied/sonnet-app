package pw.kmr.sonnet.shared.auth

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import pw.kmr.sonnet.shared.model.AuthSession
import pw.kmr.sonnet.shared.model.MobileConfig
import pw.kmr.sonnet.shared.model.SonnetUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyValueSessionStoreTest {

    @Test
    fun savesAndRestoresSessionAcrossInstances() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val secure = InMemoryKeyValueStore()
        val prefs = InMemoryKeyValueStore()
        val store = KeyValueSessionStore(secure, prefs, dispatcher)

        val session = AuthSession(
            serverUrl = "https://sonnet.example.com",
            accessToken = "access-123",
            refreshToken = "refresh-456",
            user = SonnetUser(id = 42L, name = "Ada", avatarUrl = "https://cdn.example/a.png")
        )
        store.saveSession(session)

        assertEquals(session, store.currentSession.value)
        assertFalse(store.requiresLogin.value)
        // Tokens must live only in secureStore, never in preferencesStore.
        assertEquals("access-123", secure.getString(KeyValueSessionStore.KEY_ACCESS_TOKEN))
        assertEquals("refresh-456", secure.getString(KeyValueSessionStore.KEY_REFRESH_TOKEN))
        assertNull(prefs.getString(KeyValueSessionStore.KEY_ACCESS_TOKEN))
        assertNull(prefs.getString(KeyValueSessionStore.KEY_REFRESH_TOKEN))

        val restoredStore = KeyValueSessionStore(secure, prefs, dispatcher)
        assertEquals(session, restoredStore.loadStoredSession())
        assertEquals(session, restoredStore.currentSession.value)
        assertFalse(restoredStore.requiresLogin.value)
    }

    @Test
    fun purgesStaleSecureTokensWhenSessionActiveFlagMissingAfterReinstall() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val secure = InMemoryKeyValueStore().apply {
            setString(KeyValueSessionStore.KEY_ACCESS_TOKEN, "stale-access")
            setString(KeyValueSessionStore.KEY_REFRESH_TOKEN, "stale-refresh")
        }
        val prefs = InMemoryKeyValueStore()
        val store = KeyValueSessionStore(secure, prefs, dispatcher)

        assertNull(store.loadStoredSession())
        assertNull(secure.getString(KeyValueSessionStore.KEY_ACCESS_TOKEN))
        assertNull(secure.getString(KeyValueSessionStore.KEY_REFRESH_TOKEN))
    }

    @Test
    fun markRequiresLoginPersistsFlagWhileKeepingSessionPlayableOffline() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val secure = InMemoryKeyValueStore()
        val prefs = InMemoryKeyValueStore()
        val store = KeyValueSessionStore(secure, prefs, dispatcher)
        val session = AuthSession(
            serverUrl = "https://sonnet.example.com",
            accessToken = "access-1",
            refreshToken = "refresh-1",
            user = SonnetUser(id = 7L, name = "Grace", avatarUrl = null)
        )
        store.saveSession(session)
        store.markRequiresLogin()

        assertTrue(store.requiresLogin.value)
        assertEquals(session, store.currentSession.value)

        val reloaded = KeyValueSessionStore(secure, prefs, dispatcher)
        assertEquals(session, reloaded.loadStoredSession())
        assertTrue(reloaded.requiresLogin.value)
    }

    @Test
    fun clearSessionCanRetainServerUrl() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val secure = InMemoryKeyValueStore()
        val prefs = InMemoryKeyValueStore()
        val store = KeyValueSessionStore(secure, prefs, dispatcher)
        store.saveSession(
            AuthSession(
                serverUrl = "https://sonnet.example.com",
                accessToken = "access-1",
                refreshToken = "refresh-1",
                user = SonnetUser(id = 1L, name = "Ada", avatarUrl = null)
            )
        )

        store.clearSession(retainServerUrl = true)

        assertNull(store.currentSession.value)
        assertNull(store.storedSession())
        assertEquals("https://sonnet.example.com", store.savedServerUrl())
        assertNull(secure.getString(KeyValueSessionStore.KEY_ACCESS_TOKEN))
        assertNull(secure.getString(KeyValueSessionStore.KEY_REFRESH_TOKEN))
    }

    @Test
    fun savesLoadsAndClearsPendingLogin() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = KeyValueSessionStore(InMemoryKeyValueStore(), InMemoryKeyValueStore(), dispatcher)
        val pending = PendingLogin(
            serverUrl = "https://sonnet.example.com",
            clientId = "sonnet-mobile",
            scopes = listOf("openid", "profile"),
            authorizationEndpoint = "https://idp.example/authorize",
            tokenEndpoint = "https://idp.example/token",
            endSessionEndpoint = "https://idp.example/logout",
            responseType = "code",
            mobileConfig = MobileConfig(
                issuer = "https://idp.example",
                clientId = "sonnet-mobile",
                authorizationEndpoint = "https://idp.example/authorize",
                tokenEndpoint = "https://idp.example/token",
                endSessionEndpoint = "https://idp.example/logout",
                scopes = listOf("openid", "profile"),
                responseType = "code",
                codeChallengeMethodsSupported = listOf("S256")
            )
        )

        store.savePendingLogin(pending)
        assertEquals(pending, store.loadPendingLogin())

        store.clearPendingLogin()
        assertNull(store.loadPendingLogin())
    }
}

private class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun setString(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}
