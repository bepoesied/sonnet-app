package pw.kmr.sonnet.shared.remote

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedApiClientTest {
    @Test
    fun `mobile exchange posts code verifier and parses session response`() = runTest {
        var request: HttpRequestData? = null
        val authApiClient = AuthApiClient(
            SonnetApiClient(
                MockEngine {
                    request = it
                    respond(
                        content = """
                            {
                              "access_token": "session-token",
                              "refresh_token": "refresh-token",
                              "user": {
                                "id": 123,
                                "name": "Ada Lovelace",
                                "avatar_url": "https://example.test/avatar.png"
                              },
                              "ignored": true
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        )

        val response = authApiClient.exchangeMobileCode(
            serverUrl = "https://example.test",
            code = "handoff-code-1",
            codeVerifier = "verifier-123",
            redirectUri = "sonnet://auth/callback"
        )

        val sentRequest = requireNotNull(request)
        assertEquals(HttpMethod.Post, sentRequest.method)
        assertEquals("https://example.test/api/auth/mobile/exchange", sentRequest.url.toString())
        val body = sentRequest.body as TextContent
        assertEquals(
            "{\"code\":\"handoff-code-1\",\"code_verifier\":\"verifier-123\",\"redirect_uri\":\"sonnet://auth/callback\"}",
            body.text
        )
        assertEquals("https://example.test", response.serverUrl)
        assertEquals("session-token", response.accessToken)
        assertEquals("refresh-token", response.refreshToken)
        assertEquals(123L, response.user.id)
        assertEquals("Ada Lovelace", response.user.name)
        assertEquals("https://example.test/avatar.png", response.user.avatarUrl)
    }

    @Test
    fun `books update progress sends bearer auth and iso timestamp`() = runTest {
        var request: HttpRequestData? = null
        val booksApiClient = BooksApiClient(
            SonnetApiClient(
                MockEngine {
                    request = it
                    respond(status = HttpStatusCode.NoContent, content = "")
                }
            )
        )

        booksApiClient.updateProgress(
            serverUrl = "https://example.test",
            accessToken = "token-123",
            bookId = "42",
            chapterId = "10",
            offsetMs = 42000L,
            updatedAtEpochMillis = 1_747_312_000_000L
        )

        val sentRequest = requireNotNull(request)
        assertEquals(HttpMethod.Put, sentRequest.method)
        assertEquals("https://example.test/api/books/42/progress", sentRequest.url.toString())
        assertEquals("Bearer token-123", sentRequest.headers[HttpHeaders.Authorization])
        val body = sentRequest.body as TextContent
        assertEquals(
            "{\"chapter_id\":10,\"offset_ms\":42000,\"updated_at\":\"2025-05-15T12:26:40Z\"}",
            body.text
        )
    }

    @Test
    fun `non-2xx responses become api exceptions with status and body`() = runTest {
        val cases = listOf(
            HttpStatusCode.BadRequest to "{\"error\":\"invalid_request\"}",
            HttpStatusCode.Unauthorized to "{\"error\":\"invalid_grant\"}",
            HttpStatusCode.UnprocessableEntity to "{\"error\":\"Invalid or expired refresh token\"}",
            HttpStatusCode.InternalServerError to "{\"error\":\"boom\"}"
        )

        for ((httpStatus, body) in cases) {
            val apiClient = SonnetApiClient(
                MockEngine {
                    respond(
                        content = body,
                        status = httpStatus,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
            val authApiClient = AuthApiClient(apiClient)
            val booksApiClient = BooksApiClient(apiClient)

            val bodyException = assertFailsWith<ApiException> {
                authApiClient.exchangeMobileCode(
                    serverUrl = "https://example.test",
                    code = "code",
                    codeVerifier = "verifier",
                    redirectUri = "sonnet://auth/callback"
                )
            }
            assertEquals(httpStatus.value, bodyException.statusCode)
            assertEquals(body, bodyException.message)

            val noContentException = assertFailsWith<ApiException> {
                booksApiClient.markComplete("https://example.test", "access-token", "1")
            }
            assertEquals(httpStatus.value, noContentException.statusCode)
            assertEquals(body, noContentException.message)
        }
    }

    @Test
    fun `logout tolerates 401 but throws api exception for server errors`() = runTest {
        val unauthorizedAuthClient = AuthApiClient(
            SonnetApiClient(
                MockEngine {
                    respond(
                        content = "{\"error\":\"token expired\"}",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        )

        // Should succeed without throwing when backend returns 401.
        unauthorizedAuthClient.logout("https://example.test", "expired-access", "expired-refresh")

        val serverErrorAuthClient = AuthApiClient(
            SonnetApiClient(
                MockEngine {
                    respond(
                        content = "{\"error\":\"boom\"}",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        )

        val exception = assertFailsWith<ApiException> {
            serverErrorAuthClient.logout("https://example.test", "access", "refresh")
        }
        assertEquals(500, exception.statusCode)
    }

    @Test
    fun `auth error classifiers distinguish unauthorized, auth failure, and transient errors`() {
        val authApiClient = AuthApiClient(
            SonnetApiClient(MockEngine { respond(status = HttpStatusCode.OK, content = "") })
        )

        assertTrue(authApiClient.isUnauthorizedError(ApiException(401, "unauthorized")))
        assertFalse(authApiClient.isUnauthorizedError(ApiException(403, "forbidden")))
        assertFalse(authApiClient.isUnauthorizedError(ApiException(422, "unprocessable")))
        assertFalse(authApiClient.isUnauthorizedError(ApiException(500, "server error")))
        assertFalse(authApiClient.isUnauthorizedError(IllegalStateException("network")))

        assertTrue(authApiClient.isAuthFailureError(ApiException(400, "bad request")))
        assertTrue(authApiClient.isAuthFailureError(ApiException(401, "unauthorized")))
        assertTrue(authApiClient.isAuthFailureError(ApiException(403, "forbidden")))
        assertTrue(authApiClient.isAuthFailureError(ApiException(422, "unprocessable")))
        assertFalse(authApiClient.isAuthFailureError(ApiException(404, "not found")))
        assertFalse(authApiClient.isAuthFailureError(ApiException(500, "server error")))
        assertFalse(authApiClient.isAuthFailureError(IllegalStateException("offline")))
    }
}