package pw.kmr.sonnet.shared.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import pw.kmr.sonnet.shared.data.local.entity.DownloadedBookEntity
import pw.kmr.sonnet.shared.data.local.entity.DownloadedChapterEntity
import pw.kmr.sonnet.shared.data.local.entity.LibraryItemEntity
import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity
import pw.kmr.sonnet.shared.model.BookDetail
import pw.kmr.sonnet.shared.model.BookSummary
import pw.kmr.sonnet.shared.model.DownloadStatus
import pw.kmr.sonnet.shared.model.LibraryBook
import pw.kmr.sonnet.shared.model.RemoteProgress
import pw.kmr.sonnet.shared.remote.ApiException
import pw.kmr.sonnet.shared.remote.BooksRemoteDataSource
import pw.kmr.sonnet.shared.sync.BackgroundSyncScheduler
import pw.kmr.sonnet.shared.sync.ProgressSyncer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedLibraryRepositoryTest {

    @Test
    fun `refresh syncs pending progress first and does not overwrite unsynced completion`() = runTest {
        val dao = FakeLibraryDao()
        dao.upsertLibraryItem(
            LibraryItemEntity(id = "book-1", title = "Dune", isCompleted = false, updatedAtEpochMillis = 100L)
        )
        dao.upsertPlaybackProgress(
            PlaybackProgressEntity(
                libraryItemId = "book-1",
                isCompleted = true,
                positionMillis = 10_000L,
                durationMillis = 10_000L,
                updatedAtEpochMillis = 200L,
                pendingSync = true
            )
        )

        val events = mutableListOf<String>()
        val remote = FakeBooksRemoteDataSource(
            onMarkComplete = { bookId ->
                events += "sync:$bookId"
                // Simulate transient sync failure so pendingSync stays true when books() returns stale state.
                throw ApiException(503, "temporarily unavailable")
            },
            onBooks = {
                events += "books"
                listOf(BookSummary(id = "book-1", title = "Dune", author = "Herbert", coverUrl = null, isCompleted = false))
            }
        )
        val syncer = ProgressSyncer(remote, dao)
        val repository = SharedLibraryRepository(remote, dao, syncer, nowMillis = { 500L })

        repository.refresh()

        assertEquals(listOf("sync:book-1", "books"), events)
        val projected = repository.libraryItems.first().single()
        assertTrue(projected.isCompleted, "Unsynced local completion must not be overwritten by remote refresh")
        assertTrue(dao.playbackProgress("book-1")?.pendingSync == true)
    }

    @Test
    fun `prepareBookForPlayback does not delete unsynced local progress`() = runTest {
        val dao = FakeLibraryDao()
        dao.upsertLibraryItem(
            LibraryItemEntity(id = "book-1", title = "Dune", isCompleted = true, updatedAtEpochMillis = 500L)
        )
        val unsyncedProgress = PlaybackProgressEntity(
            libraryItemId = "book-1",
            chapterId = "ch-2",
            chapterOffsetMillis = 12_000L,
            positionMillis = 72_000L,
            durationMillis = 120_000L,
            updatedAtEpochMillis = 200L,
            isCompleted = false,
            pendingSync = true
        )
        dao.upsertPlaybackProgress(unsyncedProgress)

        val remote = FakeBooksRemoteDataSource()
        val repository = SharedLibraryRepository(remote, dao)

        repository.prepareBookForPlayback("book-1")

        assertEquals(unsyncedProgress, dao.playbackProgress("book-1"))
    }

    @Test
    fun `refresh prunes removed non-downloaded remote items while preserving downloaded books`() = runTest {
        val dao = FakeLibraryDao()
        dao.upsertLibraryItems(
            listOf(
                LibraryItemEntity(id = "keep-remote", title = "Still On Server", updatedAtEpochMillis = 10L),
                LibraryItemEntity(id = "removed-remote", title = "Deleted From Server", updatedAtEpochMillis = 10L),
                LibraryItemEntity(id = "removed-but-downloaded", title = "Offline Copy", updatedAtEpochMillis = 10L)
            )
        )
        dao.upsertDownloadedBook(
            DownloadedBookEntity(id = "removed-but-downloaded", title = "Offline Copy", coverFilePath = "/cover.jpg")
        )

        val remote = FakeBooksRemoteDataSource(
            onBooks = {
                listOf(
                    BookSummary(id = "keep-remote", title = "Still On Server", author = null, coverUrl = null, isCompleted = false),
                    BookSummary(id = "new-remote", title = "New Book", author = null, coverUrl = null, isCompleted = false)
                )
            }
        )
        val repository = SharedLibraryRepository(remote, dao, nowMillis = { 200L })

        repository.refresh()

        val remainingIds = dao.allLibraryItemIds().toSet()
        assertEquals(setOf("keep-remote", "new-remote", "removed-but-downloaded"), remainingIds)
        assertNotNull(dao.downloadedBook("removed-but-downloaded"))
    }

    @Test
    fun `offline setCompletion succeeds silently, preserves duration, and schedules background sync`() = runTest {
        val dao = FakeLibraryDao()
        dao.upsertLibraryItem(
            LibraryItemEntity(id = "book-1", title = "Dune", isCompleted = false, updatedAtEpochMillis = 10L)
        )
        dao.upsertDownloadedChapters(
            listOf(
                DownloadedChapterEntity(
                    bookId = "book-1",
                    id = "ch-1",
                    title = "Ch 1",
                    position = 1,
                    durationMs = 60_000L,
                    mediaAssetId = "m1",
                    audioFilePath = "/ch1.mp3"
                ),
                DownloadedChapterEntity(
                    bookId = "book-1",
                    id = "ch-2",
                    title = "Ch 2",
                    position = 2,
                    durationMs = 90_000L,
                    mediaAssetId = "m2",
                    audioFilePath = "/ch2.mp3"
                )
            )
        )

        var backgroundSyncRequests = 0
        val remote = FakeBooksRemoteDataSource(
            onMarkComplete = { throw IllegalStateException("offline") }
        )
        val syncer = ProgressSyncer(remote, dao, BackgroundSyncScheduler { backgroundSyncRequests++ })
        val repository = SharedLibraryRepository(remote, dao, syncer, nowMillis = { 300L })

        val book = repository.libraryItems.first().single()
        assertEquals(150_000L, book.totalDurationMillis)

        // Must not throw when offline!
        repository.setCompletion(book, isCompleted = true)

        val savedProgress = dao.playbackProgress("book-1")
        assertNotNull(savedProgress)
        assertTrue(savedProgress.isCompleted)
        assertTrue(savedProgress.pendingSync)
        assertEquals(150_000L, savedProgress.durationMillis)
        assertEquals(150_000L, savedProgress.positionMillis)
        assertTrue(backgroundSyncRequests >= 1)

        val updatedBook = repository.libraryItems.first().single()
        assertTrue(updatedBook.isCompleted)
    }

    @Test
    fun `setCompletion marks incomplete offline without throwing`() = runTest {
        val dao = FakeLibraryDao()
        dao.upsertLibraryItem(
            LibraryItemEntity(id = "book-1", title = "Dune", isCompleted = true, updatedAtEpochMillis = 10L)
        )
        val remote = FakeBooksRemoteDataSource(
            onMarkIncomplete = { throw IllegalStateException("offline") }
        )
        var backgroundSyncRequests = 0
        val syncer = ProgressSyncer(remote, dao, BackgroundSyncScheduler { backgroundSyncRequests++ })
        val repository = SharedLibraryRepository(remote, dao, syncer, nowMillis = { 400L })

        val book = LibraryBook(
            id = "book-1",
            title = "Dune",
            author = null,
            isCompleted = true,
            isDownloaded = false,
            downloadStatus = DownloadStatus.NotDownloaded,
            downloadedChapters = 0L,
            totalChapters = null,
            localCoverUri = null,
            remoteCoverUrl = null,
            progressPercent = 1f,
            totalDurationMillis = 120_000L
        )

        repository.setCompletion(book, isCompleted = false)

        val saved = dao.playbackProgress("book-1")
        assertNotNull(saved)
        assertFalse(saved.isCompleted)
        assertTrue(saved.pendingSync)
        assertEquals(0L, saved.positionMillis)
        assertEquals(120_000L, saved.durationMillis)
        assertTrue(backgroundSyncRequests >= 1)
    }
}

internal class FakeBooksRemoteDataSource(
    private val onBooks: suspend () -> List<BookSummary> = { emptyList() },
    private val onBook: suspend (String) -> BookDetail = { error("Unexpected book($it)") },
    private val onDownload: suspend (String, Path, FileSystem) -> FileDownloadResult = { _, _, _ ->
        error("Unexpected download")
    },
    private val onProgress: suspend (String) -> RemoteProgress = { error("Unexpected progress($it)") },
    private val onUpdateProgress: suspend (String, String, Long, Long) -> Unit = { _, _, _, _ -> },
    private val onMarkComplete: suspend (String) -> Unit = {},
    private val onMarkIncomplete: suspend (String) -> Unit = {}
) : BooksRemoteDataSource {
    override suspend fun books(): List<BookSummary> = onBooks()

    override suspend fun book(bookId: String): BookDetail = onBook(bookId)

    override suspend fun download(url: String, targetPath: Path, fileSystem: FileSystem): FileDownloadResult =
        onDownload(url, targetPath, fileSystem)

    override suspend fun progress(bookId: String): RemoteProgress = onProgress(bookId)

    override suspend fun updateProgress(bookId: String, chapterId: String, offsetMs: Long, updatedAtEpochMillis: Long) =
        onUpdateProgress(bookId, chapterId, offsetMs, updatedAtEpochMillis)

    override suspend fun markComplete(bookId: String) = onMarkComplete(bookId)

    override suspend fun markIncomplete(bookId: String) = onMarkIncomplete(bookId)
}
