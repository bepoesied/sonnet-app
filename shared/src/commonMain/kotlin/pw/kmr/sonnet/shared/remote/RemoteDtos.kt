@file:OptIn(kotlin.time.ExperimentalTime::class)

package pw.kmr.sonnet.shared.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant
import pw.kmr.sonnet.shared.model.BookChapter
import pw.kmr.sonnet.shared.model.BookDetail
import pw.kmr.sonnet.shared.model.BookSummary
import pw.kmr.sonnet.shared.model.LoginResponse
import pw.kmr.sonnet.shared.model.MobileConfig
import pw.kmr.sonnet.shared.model.RemoteProgress
import pw.kmr.sonnet.shared.model.SonnetUser
import pw.kmr.sonnet.shared.model.TokenRefreshResponse

@Serializable
internal data class MobileConfigDto(
    val issuer: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("end_session_endpoint") val endSessionEndpoint: String? = null,
    val scopes: List<String>,
    @SerialName("response_type") val responseType: String,
    @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String>
)

@Serializable
internal data class OidcLoginRequestDto(@SerialName("id_token") val idToken: String)

@Serializable
internal data class TokenRefreshRequestDto(@SerialName("refresh_token") val refreshToken: String)

@Serializable
internal data class LogoutRequestDto(@SerialName("refresh_token") val refreshToken: String)

@Serializable
internal data class LoginResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val user: SonnetUserDto
)

@Serializable
internal data class TokenRefreshResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String
)

@Serializable
internal data class SonnetUserDto(
    val id: Long,
    val name: String,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

@Serializable
internal data class BookSummaryDto(
    @Serializable(with = StringIdSerializer::class)
    val id: String,
    val title: String,
    val author: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("is_completed") val isCompleted: Boolean = false
)

@Serializable
internal data class BookDetailDto(
    @Serializable(with = StringIdSerializer::class)
    val id: String,
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("is_completed") val isCompleted: Boolean = false,
    val chapters: List<BookChapterDto>
)

@Serializable
internal data class BookChapterDto(
    @Serializable(with = StringIdSerializer::class)
    val id: String,
    val title: String,
    val position: Int,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @Serializable(with = StringIdSerializer::class)
    @SerialName("media_asset_id") val mediaAssetId: String,
    @SerialName("audio_url") val audioUrl: String
)

@Serializable
internal data class RemoteProgressDto(
    @Serializable(with = NullableStringIdSerializer::class)
    @SerialName("chapter_id") val chapterId: String? = null,
    @SerialName("offset_ms") val offsetMillis: Long = 0L,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("is_completed") val isCompleted: Boolean = false
)

@Serializable
internal data class ProgressUpdateRequestDto(
    @Serializable(with = StringIdSerializer::class)
    @SerialName("chapter_id") val chapterId: String,
    @SerialName("offset_ms") val offsetMillis: Long,
    @SerialName("updated_at") val updatedAt: String
)

internal fun MobileConfigDto.toModel(): MobileConfig = MobileConfig(
    issuer = issuer,
    clientId = clientId,
    authorizationEndpoint = authorizationEndpoint,
    tokenEndpoint = tokenEndpoint,
    endSessionEndpoint = endSessionEndpoint.normalizedOptional(),
    scopes = scopes,
    responseType = responseType,
    codeChallengeMethodsSupported = codeChallengeMethodsSupported
)

internal fun LoginResponseDto.toModel(serverUrl: String): LoginResponse = LoginResponse(
    serverUrl = serverUrl,
    accessToken = accessToken,
    refreshToken = refreshToken,
    user = user.toModel()
)

internal fun TokenRefreshResponseDto.toModel(): TokenRefreshResponse = TokenRefreshResponse(
    accessToken = accessToken,
    refreshToken = refreshToken
)

internal fun SonnetUserDto.toModel(): SonnetUser = SonnetUser(
    id = id,
    name = name,
    avatarUrl = avatarUrl.normalizedOptional()
)

internal fun BookSummaryDto.toModel(): BookSummary = BookSummary(
    id = id,
    title = title,
    author = author.normalizedOptional(),
    coverUrl = coverUrl.normalizedOptional(),
    isCompleted = isCompleted
)

internal fun BookDetailDto.toModel(): BookDetail = BookDetail(
    id = id,
    title = title,
    author = author.normalizedOptional(),
    narrator = narrator.normalizedOptional(),
    description = description.normalizedOptional(),
    coverUrl = coverUrl.normalizedOptional(),
    isCompleted = isCompleted,
    chapters = chapters.map(BookChapterDto::toModel)
)

internal fun BookChapterDto.toModel(): BookChapter = BookChapter(
    id = id,
    title = title,
    position = position,
    startMs = startMs,
    endMs = endMs,
    durationMs = durationMs,
    mediaAssetId = mediaAssetId,
    audioUrl = audioUrl
)

internal fun RemoteProgressDto.toModel(): RemoteProgress = RemoteProgress(
    chapterId = chapterId,
    offsetMillis = offsetMillis,
    updatedAtEpochMillis = updatedAt
        .normalizedOptional()
        ?.let { value -> runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull() },
    isCompleted = isCompleted
)

private fun String?.normalizedOptional(): String? = this?.takeUnless { it.isBlank() || it == "null" }

internal object StringIdSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return jsonDecoder.decodeJsonElement().toIdString()
    }

    override fun serialize(encoder: Encoder, value: String) {
        val numericValue = value.toLongOrNull()
        if (numericValue != null) {
            encoder.encodeLong(numericValue)
        } else {
            encoder.encodeString(value)
        }
    }
}

internal object NullableStringIdSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("NullableStringId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return jsonDecoder.decodeJsonElement().let { element ->
            if (element is JsonPrimitive && element.content == "null") null else element.toIdStringOrNull()
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) {
            val jsonEncoder = encoder as? JsonEncoder
            checkNotNull(jsonEncoder) { "NullableStringIdSerializer only supports JSON encoding." }
            jsonEncoder.encodeJsonElement(JsonNull)
            return
        }
        StringIdSerializer.serialize(encoder, value)
    }
}

private fun JsonElement.toIdString(): String = (this as JsonPrimitive).content

private fun JsonElement.toIdStringOrNull(): String? = (this as? JsonPrimitive)?.content?.takeUnless { it == "null" }