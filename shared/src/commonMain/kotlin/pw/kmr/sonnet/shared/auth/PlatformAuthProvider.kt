package pw.kmr.sonnet.shared.auth

interface PlatformAuthProvider {
    suspend fun startAuthorization(pendingLogin: PendingLogin): Any

    suspend fun completeAuthorization(pendingLogin: PendingLogin, result: Any): String
}
