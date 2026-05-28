package pw.kmr.sonnet.auth

import kotlinx.coroutines.flow.Flow
import pw.kmr.sonnet.shared.model.AuthSession

interface SessionStore {
    val currentSession: Flow<AuthSession?>

    suspend fun saveSession(session: AuthSession)

    suspend fun markRequiresLogin()

    fun requiresLogin(): Boolean

    suspend fun clearSession(retainServerUrl: Boolean = false)

    fun savedServerUrl(): String?

    suspend fun storedSession(): AuthSession?

    suspend fun loadStoredSession(): AuthSession?
}