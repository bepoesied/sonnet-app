package pw.kmr.sonnet.shared.auth

import kotlinx.coroutines.flow.StateFlow
import pw.kmr.sonnet.shared.model.AuthSession

interface SessionStore {
    val currentSession: StateFlow<AuthSession?>
    val requiresLogin: StateFlow<Boolean>

    suspend fun saveSession(session: AuthSession)

    suspend fun markRequiresLogin()

    suspend fun clearSession(retainServerUrl: Boolean = false)

    suspend fun savedServerUrl(): String?

    suspend fun storedSession(): AuthSession?

    suspend fun loadStoredSession(): AuthSession?

    suspend fun savePendingLogin(pendingLogin: PendingLogin)

    suspend fun loadPendingLogin(): PendingLogin?

    suspend fun clearPendingLogin()
}
