package pw.kmr.sonnet.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pw.kmr.sonnet.shared.model.AuthSession

class AuthSessionManager(
    private val sessionStore: SessionStore,
    private val authRemoteDataSource: AuthRemoteDataSource
) {
    private val refreshMutex = Mutex()

    val currentSession: Flow<AuthSession?> = sessionStore.currentSession

    suspend fun saveSession(session: AuthSession) {
        sessionStore.saveSession(session)
    }

    suspend fun clearSession(retainServerUrl: Boolean = false) {
        sessionStore.clearSession(retainServerUrl)
    }

    suspend fun storedSession(): AuthSession? = sessionStore.storedSession()

    fun savedServerUrl(): String? = sessionStore.savedServerUrl()

    fun requiresLogin(): Boolean = sessionStore.requiresLogin()

    suspend fun bootstrapSession() {
        val session = sessionStore.loadStoredSession() ?: return
        try {
            val user = authRemoteDataSource.me(session.serverUrl, session.accessToken)
            saveSession(session.copy(user = user))
        } catch (_: Exception) {
            runCatching { refreshSession(session, refreshUser = true) }
        }
    }

    suspend fun <T> withAuthRetry(
        notSignedInMessage: String,
        request: suspend (AuthSession) -> T
    ): T {
        val session = validSessionOrThrow(notSignedInMessage)
        return try {
            request(session)
        } catch (exception: Exception) {
            if (!authRemoteDataSource.isUnauthorizedError(exception)) throw exception
            request(refreshSession(session))
        }
    }

    private suspend fun validSessionOrThrow(message: String): AuthSession {
        if (requiresLogin()) error("Sign in again before $message.")
        return currentSession.firstOrNull() ?: error("Sign in before $message.")
    }

    private suspend fun refreshSession(session: AuthSession, refreshUser: Boolean = false): AuthSession = refreshMutex.withLock {
        val latestSession = storedSession() ?: session
        if (latestSession.accessToken != session.accessToken || latestSession.refreshToken != session.refreshToken) {
            return@withLock latestSession
        }

        try {
            val refreshed = authRemoteDataSource.refresh(session.serverUrl, session.refreshToken)
            val refreshedSession = session.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken
            ).let { updated ->
                if (refreshUser) updated.copy(user = authRemoteDataSource.me(updated.serverUrl, updated.accessToken)) else updated
            }
            saveSession(refreshedSession)
            refreshedSession
        } catch (exception: Exception) {
            if (authRemoteDataSource.isAuthFailureError(exception)) {
                sessionStore.markRequiresLogin()
                error("Sign in again before syncing or downloading books.")
            }
            throw exception
        }
    }
}