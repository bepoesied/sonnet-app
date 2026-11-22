package pw.kmr.sonnet.shared.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import pw.kmr.sonnet.shared.model.AuthSession
import pw.kmr.sonnet.shared.model.SonnetUser

/**
 * Minimal synchronous key-value storage primitive backed by platform storage
 * (e.g., iOS Keychain for secrets and NSUserDefaults for non-secret metadata).
 */
interface KeyValueStore {
    fun getString(key: String): String?

    fun setString(key: String, value: String)

    fun remove(key: String)
}

/**
 * Cross-platform [SessionStore] that keeps bearer/refresh tokens in [secureStore]
 * (e.g. iOS Keychain) and non-secret session metadata in [preferencesStore]
 * (e.g. NSUserDefaults).
 *
 * If [preferencesStore] does not record an active session marker (for example after an
 * iOS app reinstall where Keychain items survived), stale Keychain tokens are purged so
 * the app starts cleanly logged out.
 */
class KeyValueSessionStore(
    private val secureStore: KeyValueStore,
    private val preferencesStore: KeyValueStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SessionStore {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val sessionState = MutableStateFlow<AuthSession?>(null)
    private val requiresLoginState = MutableStateFlow(false)

    override val currentSession: StateFlow<AuthSession?> = sessionState.asStateFlow()
    override val requiresLogin: StateFlow<Boolean> = requiresLoginState.asStateFlow()

    override suspend fun saveSession(session: AuthSession) {
        withContext(ioDispatcher) {
            mutex.withLock {
                secureStore.setString(KEY_ACCESS_TOKEN, session.accessToken)
                secureStore.setString(KEY_REFRESH_TOKEN, session.refreshToken)
                preferencesStore.setString(KEY_SERVER_URL, session.serverUrl)
                preferencesStore.setString(KEY_USER_ID, session.user.id.toString())
                preferencesStore.setString(KEY_USER_NAME, session.user.name)
                if (session.user.avatarUrl != null) {
                    preferencesStore.setString(KEY_USER_AVATAR_URL, session.user.avatarUrl)
                } else {
                    preferencesStore.remove(KEY_USER_AVATAR_URL)
                }
                preferencesStore.setString(KEY_REQUIRES_LOGIN, "false")
                preferencesStore.setString(KEY_SESSION_ACTIVE, "true")
            }
        }
        requiresLoginState.value = false
        sessionState.value = session
    }

    override suspend fun markRequiresLogin() {
        val session = withContext(ioDispatcher) {
            mutex.withLock {
                preferencesStore.setString(KEY_REQUIRES_LOGIN, "true")
                readSessionLocked()
            }
        }
        requiresLoginState.value = true
        sessionState.value = session
    }

    override suspend fun clearSession(retainServerUrl: Boolean) {
        withContext(ioDispatcher) {
            mutex.withLock {
                val existingServerUrl = preferencesStore.getString(KEY_SERVER_URL)
                clearTokensLocked()
                preferencesStore.remove(KEY_SESSION_ACTIVE)
                preferencesStore.remove(KEY_USER_ID)
                preferencesStore.remove(KEY_USER_NAME)
                preferencesStore.remove(KEY_USER_AVATAR_URL)
                preferencesStore.remove(KEY_REQUIRES_LOGIN)
                preferencesStore.remove(KEY_PENDING_LOGIN)
                if (retainServerUrl && !existingServerUrl.isNullOrBlank()) {
                    preferencesStore.setString(KEY_SERVER_URL, existingServerUrl)
                } else {
                    preferencesStore.remove(KEY_SERVER_URL)
                }
            }
        }
        requiresLoginState.value = false
        sessionState.value = null
    }

    override suspend fun savedServerUrl(): String? = withContext(ioDispatcher) {
        mutex.withLock {
            preferencesStore.getString(KEY_SERVER_URL)?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun storedSession(): AuthSession? = withContext(ioDispatcher) {
        mutex.withLock {
            readSessionLocked()
        }
    }

    override suspend fun loadStoredSession(): AuthSession? {
        val (session, needsLogin) = withContext(ioDispatcher) {
            mutex.withLock {
                val restored = readSessionLocked()
                val requiresReauth = restored != null &&
                    preferencesStore.getString(KEY_REQUIRES_LOGIN) == "true"
                restored to requiresReauth
            }
        }
        requiresLoginState.value = needsLogin
        sessionState.value = session
        return session
    }

    override suspend fun savePendingLogin(pendingLogin: PendingLogin) {
        withContext(ioDispatcher) {
            mutex.withLock {
                preferencesStore.setString(KEY_PENDING_LOGIN, json.encodeToString(pendingLogin))
            }
        }
    }

    override suspend fun loadPendingLogin(): PendingLogin? = withContext(ioDispatcher) {
        mutex.withLock {
            val encoded = preferencesStore.getString(KEY_PENDING_LOGIN) ?: return@withLock null
            runCatching { json.decodeFromString<PendingLogin>(encoded) }.getOrNull()
        }
    }

    override suspend fun clearPendingLogin() {
        withContext(ioDispatcher) {
            mutex.withLock {
                preferencesStore.remove(KEY_PENDING_LOGIN)
            }
        }
    }

    private fun readSessionLocked(): AuthSession? {
        if (preferencesStore.getString(KEY_SESSION_ACTIVE) != "true") {
            clearTokensLocked()
            return null
        }

        val serverUrl = preferencesStore.getString(KEY_SERVER_URL)
        val accessToken = secureStore.getString(KEY_ACCESS_TOKEN)
        val refreshToken = secureStore.getString(KEY_REFRESH_TOKEN)
        val userName = preferencesStore.getString(KEY_USER_NAME)
        val userId = preferencesStore.getString(KEY_USER_ID)?.toLongOrNull() ?: 0L

        if (serverUrl.isNullOrBlank() ||
            accessToken.isNullOrBlank() ||
            refreshToken.isNullOrBlank() ||
            userName.isNullOrBlank() ||
            userId <= 0L
        ) {
            return null
        }

        return AuthSession(
            serverUrl = serverUrl,
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = SonnetUser(
                id = userId,
                name = userName,
                avatarUrl = preferencesStore.getString(KEY_USER_AVATAR_URL)
            )
        )
    }

    private fun clearTokensLocked() {
        secureStore.remove(KEY_ACCESS_TOKEN)
        secureStore.remove(KEY_REFRESH_TOKEN)
    }

    companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_AVATAR_URL = "user_avatar_url"
        const val KEY_REQUIRES_LOGIN = "requires_login"
        const val KEY_PENDING_LOGIN = "pending_login"
        const val KEY_SESSION_ACTIVE = "session_active"
    }
}
