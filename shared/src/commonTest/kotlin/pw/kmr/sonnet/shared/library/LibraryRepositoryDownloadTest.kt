package pw.kmr.sonnet.shared.library

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import pw.kmr.sonnet.shared.model.BookChapter
import pw.kmr.sonnet.shared.model.BookDetail
import pw.kmr.sonnet.shared.remote.ApiException
import pw.kmr.sonnet.shared.remote.BooksApiClient
import pw.kmr.sonnet.shared.remote.SonnetApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRepositoryDownloadTest {

    @Test
    fun `cancelling a download cleans up both files and DB metadata via NonCancellable`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fileSystem = FakeFileSystem()
        val dao = FakeLibraryDao()
        val chapter2Started = CompletableDeferred<Unit>()
        val chapter2Gate = CompletableDeferred<Unit>()

        val remote = FakeBooksRemoteDataSource(
            onBook = { sampleBookDetail("book-1") },
            onDownload = { url, targetPath, fs ->
                if (url.contains("ch-2")) {
                    chapter2Started.complete(Unit)
                    chapter2Gate.await()
                }
                targetPath.parent?.let(fs::createDirectories)
                fs.write(targetPath) { writeUtf8("audio:$url") }
                FileDownloadResult("audio/mpeg")
            }
        )
        val repository = LibraryRepository(
            booksApiClient = remote,
            libraryDao = dao,
            booksDirectory = "/books".toPath(),
            fileSystem = fileSystem,
            ioDispatcher = dispatcher
        )

        val job = launch {
            repository.downloadBook("book-1")
        }
        chapter2Started.await()

        // Confirm chapter 1 was written and DB row is downloading before cancel.
        assertEquals("downloading", dao.download("book-1")?.state)
        assertTrue(fileSystem.exists("/books/book-1/chapters/ch-1.mp3".toPath()))

        job.cancel()
        job.join()

        // Cancellation cleanup must remove BOTH the files AND DB metadata (not left stuck in "downloading").
        assertFalse(fileSystem.exists("/books/book-1".toPath()))
        assertNull(dao.download("book-1"))
        assertNull(dao.downloadedBook("book-1"))
        assertTrue(dao.downloadedChapters("book-1").isEmpty())
    }

    @Test
    fun `transient download error preserves already-completed chapter files for resume`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fileSystem = FakeFileSystem()
        val dao = FakeLibraryDao()
        var failChapter2 = true
        val downloadedUrls = mutableListOf<String>()

        val remote = FakeBooksRemoteDataSource(
            onBook = { sampleBookDetail("book-1") },
            onDownload = { url, targetPath, fs ->
                downloadedUrls += url
                if (url.contains("ch-2") && failChapter2) {
                    throw ApiException(503, "network drop")
                }
                targetPath.parent?.let(fs::createDirectories)
                fs.write(targetPath) { writeUtf8("audio:$url") }
                FileDownloadResult("audio/mpeg")
            }
        )
        val repository = LibraryRepository(
            booksApiClient = remote,
            libraryDao = dao,
            booksDirectory = "/books".toPath(),
            fileSystem = fileSystem,
            ioDispatcher = dispatcher
        )

        // First attempt downloads cover + ch-1, then fails on ch-2.
        assertFailsWith<ApiException> {
            repository.downloadBook("book-1")
        }

        assertTrue(fileSystem.exists("/books/book-1/chapters/ch-1.mp3".toPath()))
        assertFalse(fileSystem.exists("/books/book-1/chapters/ch-2.mp3".toPath()))
        val failedRow = dao.download("book-1")
        assertNotNull(failedRow)
        assertEquals("failed", failedRow.state)
        assertEquals(1L, failedRow.downloadedBytes)
        assertEquals(3L, failedRow.totalBytes)

        // Second attempt resumes without re-downloading cover or ch-1!
        downloadedUrls.clear()
        failChapter2 = false
        repository.downloadBook("book-1")

        assertEquals(
            listOf(
                "https://cdn.example/ch-2.mp3?sig=v1",
                "https://cdn.example/ch-3.mp3?sig=v1"
            ),
            downloadedUrls
        )
        assertEquals("complete", dao.download("book-1")?.state)
        assertEquals(3L, dao.download("book-1")?.downloadedBytes)
        assertEquals(3, dao.downloadedChapters("book-1").size)
    }

    @Test
    fun `403 and 410 during chapter download refresh presigned URLs once and complete`() = runTest {
        for (expiredStatus in listOf(403, 410)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val fileSystem = FakeFileSystem()
            val dao = FakeLibraryDao()
            var bookFetchCount = 0

            val remote = FakeBooksRemoteDataSource(
                onBook = {
                    bookFetchCount++
                    val sig = if (bookFetchCount == 1) "expired" else "fresh"
                    sampleBookDetail("book-1", signature = sig)
                },
                onDownload = { url, targetPath, fs ->
                    if (url.contains("ch-2") && url.contains("sig=expired")) {
                        throw ApiException(expiredStatus, "presigned url expired")
                    }
                    targetPath.parent?.let(fs::createDirectories)
                    fs.write(targetPath) { writeUtf8("audio:$url") }
                    FileDownloadResult("audio/mpeg")
                }
            )
            val repository = LibraryRepository(
                booksApiClient = remote,
                libraryDao = dao,
                booksDirectory = "/books".toPath(),
                fileSystem = fileSystem,
                ioDispatcher = dispatcher
            )

            repository.downloadBook("book-1")

            assertEquals(2, bookFetchCount)
            assertEquals("complete", dao.download("book-1")?.state)
            assertTrue(fileSystem.exists("/books/book-1/chapters/ch-2.mp3".toPath()))
            assertEquals(
                "audio:https://cdn.example/ch-2.mp3?sig=fresh",
                fileSystem.read("/books/book-1/chapters/ch-2.mp3".toPath()) { readUtf8() }
            )
        }
    }

    @Test
    fun `atomic download writes via part file and never leaves corrupt file at targetPath on failure`() = runTest {
        val fileSystem = FakeFileSystem()
        val targetPath = "/books/book-1/chapters/ch-1.mp3".toPath()
        val partPath = "/books/book-1/chapters/ch-1.mp3.part".toPath()

        val failingClient = BooksApiClient(
            SonnetApiClient(
                MockEngine {
                    val channel = ByteChannel(autoFlush = true)
                    channel.writeFully("partial-bytes".encodeToByteArray(), 0, 13)
                    channel.cancel(IllegalStateException("connection reset mid-stream"))
                    respond(
                        content = channel,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Audio.MPEG.toString())
                    )
                }
            )
        )

        assertFailsWith<Exception> {
            failingClient.download("https://cdn.example/ch-1.mp3", targetPath, fileSystem)
        }

        assertFalse(fileSystem.exists(targetPath), "Target path must not exist after failed stream")
        assertFalse(fileSystem.exists(partPath), "Temporary .part file must be cleaned up on failure")

        val succeedingClient = BooksApiClient(
            SonnetApiClient(
                MockEngine {
                    respond(
                        content = "complete-audio-payload",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Audio.MPEG.toString())
                    )
                }
            )
        )

        val result = succeedingClient.download("https://cdn.example/ch-1.mp3", targetPath, fileSystem)

        assertEquals(ContentType.Audio.MPEG.toString(), result.contentType)
        assertTrue(fileSystem.exists(targetPath))
        assertFalse(fileSystem.exists(partPath))
        assertEquals("complete-audio-payload", fileSystem.read(targetPath) { readUtf8() })
    }

    @Test
    fun `deleteDownload invokes active playback guard before deleting files`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fileSystem = FakeFileSystem()
        val dao = FakeLibraryDao()
        val chapterPath = "/books/book-1/chapters/ch-1.mp3".toPath()
        fileSystem.createDirectories(chapterPath.parent!!)
        fileSystem.write(chapterPath) { writeUtf8("audio") }

        var fileExistedWhenGuardRan = false
        var guardedBookId: String? = null
        val repository = LibraryRepository(
            booksApiClient = FakeBooksRemoteDataSource(),
            libraryDao = dao,
            booksDirectory = "/books".toPath(),
            fileSystem = fileSystem,
            ioDispatcher = dispatcher,
            onBeforeDeleteBook = { bookId ->
                guardedBookId = bookId
                fileExistedWhenGuardRan = fileSystem.exists(chapterPath)
            }
        )

        repository.deleteDownload("book-1")

        assertEquals("book-1", guardedBookId)
        assertTrue(fileExistedWhenGuardRan, "Playback guard must run before files are deleted")
        assertFalse(fileSystem.exists("/books/book-1".toPath()))
    }

    private fun sampleBookDetail(bookId: String, signature: String = "v1") = BookDetail(
        id = bookId,
        title = "The Odyssey",
        author = "Homer",
        narrator = null,
        description = null,
        coverUrl = "https://cdn.example/cover.jpg?sig=$signature",
        isCompleted = false,
        chapters = listOf(
            BookChapter("ch-1", "Chapter 1", 1, 0L, 10_000L, 10_000L, "m1", "https://cdn.example/ch-1.mp3?sig=$signature"),
            BookChapter("ch-2", "Chapter 2", 2, 10_000L, 20_000L, 10_000L, "m2", "https://cdn.example/ch-2.mp3?sig=$signature"),
            BookChapter("ch-3", "Chapter 3", 3, 20_000L, 30_000L, 10_000L, "m3", "https://cdn.example/ch-3.mp3?sig=$signature")
        )
    )
}
