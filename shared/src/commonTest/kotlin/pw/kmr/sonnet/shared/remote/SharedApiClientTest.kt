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
    }
}