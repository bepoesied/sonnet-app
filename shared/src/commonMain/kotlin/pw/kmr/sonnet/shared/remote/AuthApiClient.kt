package pw.kmr.sonnet.shared.remote

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import pw.kmr.sonnet.shared.auth.AuthRemoteDataSource
import pw.kmr.sonnet.shared.model.LoginResponse
import pw.kmr.sonnet.shared.model.MobileConfig
import pw.kmr.sonnet.shared.model.SonnetUser
import pw.kmr.sonnet.shared.model.TokenRefreshResponse

class AuthApiClient(
    private val apiClient: SonnetApiClient
) : AuthRemoteDataSource {
    suspend fun mobileConfig(serverUrl: String): MobileConfig = apiClient.httpClient
        .get(serverUrl.apiUrl("mobile-config"))
        .requireBody<MobileConfigDto>()
        .toModel()

    suspend fun oidcLogin(serverUrl: String, idToken: String): LoginResponse = apiClient.httpClient
        .post(serverUrl.apiUrl("auth/oidc-login")) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(OidcLoginRequestDto(idToken = idToken))
        }
        .requireBody<LoginResponseDto>()
        .toModel(serverUrl)

    override suspend fun me(serverUrl: String, accessToken: String): SonnetUser = apiClient.httpClient
        .get(serverUrl.apiUrl("me")) {
            bearerAuth(accessToken)
        }
        .requireBody<SonnetUserDto>()
        .toModel()

    override suspend fun refresh(serverUrl: String, refreshToken: String): TokenRefreshResponse = apiClient.httpClient
        .post(serverUrl.apiUrl("auth/token-refresh")) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TokenRefreshRequestDto(refreshToken = refreshToken))
        }
        .requireBody<TokenRefreshResponseDto>()
        .toModel()

    suspend fun logout(serverUrl: String, accessToken: String, refreshToken: String) {
        val response = apiClient.httpClient.post(serverUrl.apiUrl("auth/logout")) {
            bearerAuth(accessToken)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(LogoutRequestDto(refreshToken = refreshToken))
        }

        if (!response.status.isSuccess() && response.status.value != 401) {
            throw ApiException(response.status.value, response.bodyAsText())
        }
    }

    override fun isUnauthorizedError(exception: Exception): Boolean =
        exception is ApiException && exception.statusCode == 401

    override fun isAuthFailureError(exception: Exception): Boolean =
        exception is ApiException && exception.statusCode in setOf(400, 401, 403, 422)
}

internal suspend inline fun <reified T> HttpResponse.requireBody(): T {
    if (!status.isSuccess()) {
        throw ApiException(status.value, bodyAsText())
    }
    return body()
}

internal fun String.apiUrl(path: String): String =
    "${trimEnd('/')}/api/${path.trimStart('/')}"