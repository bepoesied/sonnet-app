package pw.kmr.sonnet.auth

import pw.kmr.sonnet.shared.model.SonnetUser
import pw.kmr.sonnet.shared.model.TokenRefreshResponse

interface AuthRemoteDataSource {
    suspend fun me(serverUrl: String, accessToken: String): SonnetUser

    suspend fun refresh(serverUrl: String, refreshToken: String): TokenRefreshResponse

    fun isUnauthorizedError(exception: Exception): Boolean

    fun isAuthFailureError(exception: Exception): Boolean
}