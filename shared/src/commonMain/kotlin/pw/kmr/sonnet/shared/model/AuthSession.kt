package pw.kmr.sonnet.shared.model

data class AuthSession(
    val serverUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val user: SonnetUser
)

data class SonnetUser(
    val id: Long,
    val name: String,
    val avatarUrl: String?
)