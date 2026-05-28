package pw.kmr.sonnet.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pw.kmr.sonnet.shared.data.local.entity.DownloadEntity
import pw.kmr.sonnet.shared.data.local.entity.DownloadedBookEntity
import pw.kmr.sonnet.shared.data.local.entity.LibraryItemEntity
import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity
import pw.kmr.sonnet.shared.model.DownloadStatus

class LibraryProjectorTest {
    @Test
    fun projectsRemoteOnlyBooks() {
        val books = LibraryProjector.project(
            items = listOf(
                LibraryItemEntity(
                    id = "remote",
                    title = "Remote Book",
                    author = "Author",
                    coverImageUrl = "https://example.com/cover.jpg",
                    isCompleted = false,
                    updatedAtEpochMillis = 10L
                )
            ),
            downloads = emptyList(),
            downloadedBooks = emptyList(),
            progress = emptyList()
        )

        assertEquals(1, books.size)
        assertEquals("remote", books.single().id)
        assertFalse(books.single().isDownloaded)
        assertEquals(DownloadStatus.NotDownloaded, books.single().downloadStatus)
        assertEquals("https://example.com/cover.jpg", books.single().remoteCoverUrl)
        assertNull(books.single().localCoverUri)
    }

    @Test
    fun projectsDownloadedBooksFromRemoteMetadata() {
        val books = LibraryProjector.project(
            items = listOf(
                LibraryItemEntity(id = "book", title = "Book", isCompleted = false, updatedAtEpochMillis = 10L)
            ),
            downloads = listOf(
                DownloadEntity(
                    libraryItemId = "book",
                    downloadedBytes = 5L,
                    totalBytes = 5L,
                    state = "complete"
                )
            ),
            downloadedBooks = listOf(
                DownloadedBookEntity(id = "book", title = "Book", coverFilePath = "/files/book/cover.jpg")
            ),
            progress = emptyList()
        )

        val book = books.single()
        assertTrue(book.isDownloaded)
        assertEquals(DownloadStatus.Downloaded, book.downloadStatus)
        assertEquals(5L, book.downloadedChapters)
        assertEquals(5L, book.totalChapters)
        assertEquals("/files/book/cover.jpg", book.localCoverUri)
    }

    @Test
    fun projectsLocalOnlyDownloadedBooks() {
        val books = LibraryProjector.project(
            items = emptyList(),
            downloads = emptyList(),
            downloadedBooks = listOf(
                DownloadedBookEntity(
                    id = "local",
                    title = "Local Book",
                    author = "Author",
                    coverFilePath = "/files/local/cover.jpg",
                    isCompleted = true
                )
            ),
            progress = emptyList()
        )

        val book = books.single()
        assertEquals("local", book.id)
        assertTrue(book.isDownloaded)
        assertEquals(DownloadStatus.Downloaded, book.downloadStatus)
        assertNull(book.remoteCoverUrl)
        assertEquals("/files/local/cover.jpg", book.localCoverUri)
        assertTrue(book.isCompleted)
    }

    @Test
    fun projectsFailedAndDownloadingStates() {
        val failed = LibraryProjector.project(
            items = listOf(LibraryItemEntity(id = "failed", title = "Failed", updatedAtEpochMillis = 1L)),
            downloads = listOf(DownloadEntity(libraryItemId = "failed", state = "failed")),
            downloadedBooks = emptyList(),
            progress = emptyList()
        ).single()
        val downloading = LibraryProjector.project(
            items = listOf(LibraryItemEntity(id = "downloading", title = "Downloading", updatedAtEpochMillis = 1L)),
            downloads = listOf(
                DownloadEntity(
                    libraryItemId = "downloading",
                    downloadedBytes = 2L,
                    totalBytes = 4L,
                    state = "downloading"
                )
            ),
            downloadedBooks = emptyList(),
            progress = emptyList()
        ).single()

        assertEquals(DownloadStatus.Failed, failed.downloadStatus)
        assertFalse(failed.isDownloaded)
        assertEquals(DownloadStatus.Downloading, downloading.downloadStatus)
        assertFalse(downloading.isDownloaded)
        assertEquals(2L, downloading.downloadedChapters)
        assertEquals(4L, downloading.totalChapters)
    }

    @Test
    fun projectsProgressPercentFromPlaybackProgress() {
        val book = LibraryProjector.project(
            items = listOf(LibraryItemEntity(id = "book", title = "Book", updatedAtEpochMillis = 1L)),
            downloads = emptyList(),
            downloadedBooks = emptyList(),
            progress = listOf(
                PlaybackProgressEntity(
                    libraryItemId = "book",
                    positionMillis = 50L,
                    durationMillis = 200L,
                    updatedAtEpochMillis = 2L
                )
            )
        ).single()

        assertEquals(0.25f, book.progressPercent)
    }

    @Test
    fun prefersMostRecentCompletionState() {
        val remoteWins = LibraryProjector.project(
            items = listOf(
                LibraryItemEntity(
                    id = "book",
                    title = "Book",
                    isCompleted = true,
                    updatedAtEpochMillis = 20L
                )
            ),
            downloads = emptyList(),
            downloadedBooks = emptyList(),
            progress = listOf(
                PlaybackProgressEntity(
                    libraryItemId = "book",
                    isCompleted = false,
                    updatedAtEpochMillis = 10L
                )
            )
        ).single()
        val localWins = LibraryProjector.project(
            items = listOf(
                LibraryItemEntity(
                    id = "book",
                    title = "Book",
                    isCompleted = false,
                    updatedAtEpochMillis = 10L
                )
            ),
            downloads = emptyList(),
            downloadedBooks = emptyList(),
            progress = listOf(
                PlaybackProgressEntity(
                    libraryItemId = "book",
                    isCompleted = true,
                    updatedAtEpochMillis = 20L
                )
            )
        ).single()

        assertTrue(remoteWins.isCompleted)
        assertTrue(localWins.isCompleted)
    }
}