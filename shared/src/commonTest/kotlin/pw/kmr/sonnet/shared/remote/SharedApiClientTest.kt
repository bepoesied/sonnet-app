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
    fun `mobile config parses through shared ktor client`() = runTest {
        val authApiClient = AuthApiClient(
            SonnetApiClient(
                MockEngine {
                    assertEquals("https://example.test/api/mobile-config", it.url.toString())
                    respond(
                        content = """
                            {
                              "issuer": "https://issuer.example",
                              "client_id": "sonnet-mobile",
                              "authorization_endpoint": "https://issuer.example/authorize",
                              "token_endpoint": "https://issuer.example/token",
                              "end_session_endpoint": "https://issuer.example/logout",
                              "scopes": ["openid", "profile"],
                              "response_type": "code",
                              "code_challenge_methods_supported": ["S256"],
                              "ignored": true
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        )

        val config = authApiClient.mobileConfig("https://example.test")

        assertEquals("sonnet-mobile", config.clientId)
        assertEquals(listOf("openid", "profile"), config.scopes)
        assertEquals(listOf("S256"), config.codeChallengeMethodsSupported)
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
    fun `unauthorized responses become shared api exceptions`() = runTest {
        val authApiClient = AuthApiClient(
            SonnetApiClient(
                MockEngine {
                    respond(
                        content = "{\"error\":\"Unauthorized\"}",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        )

        val exception = assertFailsWith<ApiException> {
            authApiClient.me("https://example.test", "expired")
        }

        assertEquals(401, exception.statusCode)
        assertEquals("{\"error\":\"Unauthorized\"}", exception.message)
    }

    @Test
    fun `error status codes map to api exception across auth and books clients`() = runTest {
        val statusCodes = listOf(
            HttpStatusCode.Unauthorized to 401,
            HttpStatusCode.Forbidden to 403,
            HttpStatusCode.UnprocessableEntity to 422,
            HttpStatusCode.InternalServerError to 500
        )

        for ((httpStatus, expectedCode) in statusCodes) {
            val body = "{\"status\":$expectedCode}"
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

            val refreshException = assertFailsWith<ApiException> {
                authApiClient.refresh("https://example.test", "refresh-token")
            }
            assertEquals(expectedCode, refreshException.statusCode)
            assertEquals(body, refreshException.message)

            val booksException = assertFailsWith<ApiException> {
                booksApiClient.books("https://example.test", "access-token")
            }
            assertEquals(expectedCode, booksException.statusCode)
            assertEquals(body, booksException.message)

            val updateProgressException = assertFailsWith<ApiException> {
                booksApiClient.updateProgress(
                    serverUrl = "https://example.test",
                    accessToken = "access-token",
                    bookId = "1",
                    chapterId = "2",
                    offsetMs = 500L,
                    updatedAtEpochMillis = 1_000L
                )
            }
            assertEquals(expectedCode, updateProgressException.statusCode)
            assertEquals(body, updateProgressException.message)

            val completeException = assertFailsWith<ApiException> {
                booksApiClient.markComplete("https://example.test", "access-token", "1")
            }
            assertEquals(expectedCode, completeException.statusCode)
            assertEquals(body, completeException.message)
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