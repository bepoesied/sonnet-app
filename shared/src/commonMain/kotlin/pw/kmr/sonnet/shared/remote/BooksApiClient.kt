import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import okio.FileSystem
import okio.Path
import okio.buffer
import pw.kmr.sonnet.shared.library.FileDownloadResult

    suspend fun download(url: String, targetPath: Path, fileSystem: FileSystem): FileDownloadResult {
        val response = apiClient.httpClient.get(url)
        if (!response.status.isSuccess()) {
            throw ApiException(response.status.value, response.bodyAsText())
        }

        targetPath.parent?.let(fileSystem::createDirectories)
        val channel = response.bodyAsChannel()
        val sink = fileSystem.sink(targetPath).buffer()
        try {
            val buffer = ByteArray(8_192)
            while (true) {
                val read = channel.readAvailable(buffer)
                if (read <= 0) break
                sink.write(buffer, 0, read)
            }
        } finally {
            sink.close()
        }
        return FileDownloadResult(response.headers[HttpHeaders.ContentType])
    }
    suspend fun download(url: String, targetPath: Path, fileSystem: FileSystem): FileDownloadResult {
        return booksApiClient.download(url = url, targetPath = targetPath, fileSystem = fileSystem)
    }

) : ProgressSyncRemoteDataSource {
    override suspend fun progress(bookId: String): RemoteProgress = withAuth { session ->
    override suspend fun updateProgress(
    override suspend fun markComplete(bookId: String) = withAuth { session ->
    override suspend fun markIncomplete(bookId: String) = withAuth { session ->
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import pw.kmr.sonnet.shared.auth.AuthSessionManager
import pw.kmr.sonnet.shared.model.BookDetail
import pw.kmr.sonnet.shared.model.BookSummary
import pw.kmr.sonnet.shared.model.RemoteProgress
import kotlin.time.Instant

class BooksApiClient(
    private val apiClient: SonnetApiClient
) {
    suspend fun books(serverUrl: String, accessToken: String): List<BookSummary> = apiClient.httpClient
        .get(serverUrl.apiUrl("books")) {
            bearerAuth(accessToken)
        }
        .requireBody<List<BookSummaryDto>>()
        .map(BookSummaryDto::toModel)

    suspend fun book(serverUrl: String, accessToken: String, bookId: String): BookDetail = apiClient.httpClient
        .get(serverUrl.apiUrl("books/$bookId")) {
            bearerAuth(accessToken)
        }
        .requireBody<BookDetailDto>()
        .toModel()

    suspend fun progress(serverUrl: String, accessToken: String, bookId: String): RemoteProgress = apiClient.httpClient
        .get(serverUrl.apiUrl("books/$bookId/progress")) {
            bearerAuth(accessToken)
        }
        .requireBody<RemoteProgressDto>()
        .toModel()

    suspend fun updateProgress(
        serverUrl: String,
        accessToken: String,
        bookId: String,
        chapterId: String,
        offsetMs: Long,
        updatedAtEpochMillis: Long
    ) {
        apiClient.httpClient.put(serverUrl.apiUrl("books/$bookId/progress")) {
            bearerAuth(accessToken)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                ProgressUpdateRequestDto(
                    chapterId = chapterId,
                    offsetMillis = offsetMs.coerceAtLeast(0L),
                    updatedAt = Instant.fromEpochMilliseconds(updatedAtEpochMillis).toString()
                )
            )
        }.requireSuccess()
    }

    suspend fun markComplete(serverUrl: String, accessToken: String, bookId: String) {
        updateCompletion(serverUrl = serverUrl, accessToken = accessToken, bookId = bookId, complete = true)
    }

    suspend fun markIncomplete(serverUrl: String, accessToken: String, bookId: String) {
        updateCompletion(serverUrl = serverUrl, accessToken = accessToken, bookId = bookId, complete = false)
    }

    private suspend fun updateCompletion(
        serverUrl: String,
        accessToken: String,
        bookId: String,
        complete: Boolean
    ) {
        val path = if (complete) "books/$bookId/complete" else "books/$bookId/incomplete"
        apiClient.httpClient.put(serverUrl.apiUrl(path)) {
            bearerAuth(accessToken)
        }.requireSuccess()
    }
}

class AuthenticatedBooksApiClient(
    private val booksApiClient: BooksApiClient,
    private val authSessionManager: AuthSessionManager
) {
    suspend fun books(): List<BookSummary> = withAuth { session ->
        booksApiClient.books(session.serverUrl, session.accessToken)
    }

    suspend fun book(bookId: String): BookDetail = withAuth { session ->
        booksApiClient.book(session.serverUrl, session.accessToken, bookId)
    }

    suspend fun progress(bookId: String): RemoteProgress = withAuth { session ->
        booksApiClient.progress(session.serverUrl, session.accessToken, bookId)
    }

    suspend fun updateProgress(
        bookId: String,
        chapterId: String,
        offsetMs: Long,
        updatedAtEpochMillis: Long
    ) = withAuth { session ->
        booksApiClient.updateProgress(
            serverUrl = session.serverUrl,
            accessToken = session.accessToken,
            bookId = bookId,
            chapterId = chapterId,
            offsetMs = offsetMs,
            updatedAtEpochMillis = updatedAtEpochMillis
        )
    }

    suspend fun markComplete(bookId: String) = withAuth { session ->
        booksApiClient.markComplete(session.serverUrl, session.accessToken, bookId)
    }

    suspend fun markIncomplete(bookId: String) = withAuth { session ->
        booksApiClient.markIncomplete(session.serverUrl, session.accessToken, bookId)
    }

    private suspend fun <T> withAuth(request: suspend (AuthenticatedSession) -> T): T =
        authSessionManager.withAuthRetry(AUTH_ACTION_MESSAGE) { session ->
            request(AuthenticatedSession(session.serverUrl, session.accessToken))
        }

    private data class AuthenticatedSession(
        val serverUrl: String,
        val accessToken: String
    )

    private companion object {
        const val AUTH_ACTION_MESSAGE = "syncing or downloading books"
    }
}

private suspend inline fun HttpResponse.requireSuccess() {
    if (!status.isSuccess()) {
        throw ApiException(status.value, bodyAsText())
    }
}