package pw.kmr.sonnet.shared.model

data class BookSummary(
    val id: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val isCompleted: Boolean
)

data class BookDetail(
    val id: String,
    val title: String,
    val author: String?,
    val narrator: String?,
    val description: String?,
    val coverUrl: String?,
    val isCompleted: Boolean,
    val chapters: List<BookChapter>
)

data class BookChapter(
    val id: String,
    val title: String,
    val position: Int,
    val startMs: Long?,
    val endMs: Long?,
    val durationMs: Long?,
    val mediaAssetId: String,
    val audioUrl: String
)

data class RemoteProgress(
    val chapterId: String?,
    val offsetMillis: Long,
    val updatedAtEpochMillis: Long = 0L,
    val isCompleted: Boolean
)

data class MobileConfig(
    val issuer: String,
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val endSessionEndpoint: String?,
    val scopes: List<String>,
    val responseType: String,
    val codeChallengeMethodsSupported: List<String>
)

data class LoginResponse(
    val serverUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val user: SonnetUser
)

data class TokenRefreshResponse(
    val accessToken: String,
    val refreshToken: String
)