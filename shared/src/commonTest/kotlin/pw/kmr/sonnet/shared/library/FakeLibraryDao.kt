package pw.kmr.sonnet.shared.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import pw.kmr.sonnet.shared.data.local.dao.DownloadedBookDuration
import pw.kmr.sonnet.shared.data.local.dao.LibraryDao
import pw.kmr.sonnet.shared.data.local.entity.DownloadEntity
import pw.kmr.sonnet.shared.data.local.entity.DownloadedBookEntity
import pw.kmr.sonnet.shared.data.local.entity.DownloadedChapterEntity
import pw.kmr.sonnet.shared.data.local.entity.LibraryItemEntity
import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity

internal class FakeLibraryDao : LibraryDao {
    val libraryItemsFlow = MutableStateFlow<List<LibraryItemEntity>>(emptyList())
    val downloadsFlow = MutableStateFlow<List<DownloadEntity>>(emptyList())
    val downloadedBooksFlow = MutableStateFlow<List<DownloadedBookEntity>>(emptyList())
    val downloadedChaptersFlow = MutableStateFlow<List<DownloadedChapterEntity>>(emptyList())
    val playbackProgressFlow = MutableStateFlow<List<PlaybackProgressEntity>>(emptyList())

    override fun observeLibraryItems(): Flow<List<LibraryItemEntity>> = libraryItemsFlow

    override suspend fun libraryItem(bookId: String): LibraryItemEntity? =
        libraryItemsFlow.value.firstOrNull { it.id == bookId }

    override fun observeDownloads(): Flow<List<DownloadEntity>> = downloadsFlow

    override fun observeDownloadedBooks(): Flow<List<DownloadedBookEntity>> = downloadedBooksFlow

    override fun observePlaybackProgress(): Flow<List<PlaybackProgressEntity>> = playbackProgressFlow

    override fun observeDownloadedBookDurations(): Flow<List<DownloadedBookDuration>> =
        downloadedChaptersFlow.map { chapters ->
            chapters
                .filter { (it.durationMs ?: 0L) > 0L }
                .groupBy { it.bookId }
                .map { (bookId, bookChapters) ->
                    DownloadedBookDuration(
                        bookId = bookId,
                        totalDurationMillis = bookChapters.sumOf { it.durationMs ?: 0L }
                    )
                }
        }

    override suspend fun playbackProgress(bookId: String): PlaybackProgressEntity? =
        playbackProgressFlow.value.firstOrNull { it.libraryItemId == bookId }

    override suspend fun pendingPlaybackProgress(): List<PlaybackProgressEntity> =
        playbackProgressFlow.value
            .filter { it.pendingSync }
            .sortedBy { it.updatedAtEpochMillis }

    override suspend fun upsertPlaybackProgress(progress: PlaybackProgressEntity) {
        playbackProgressFlow.value =
            playbackProgressFlow.value.filterNot { it.libraryItemId == progress.libraryItemId } + progress
    }

    override suspend fun markPlaybackProgressSynced(bookId: String, updatedAtEpochMillis: Long) {
        playbackProgressFlow.value = playbackProgressFlow.value.map { current ->
            if (current.libraryItemId == bookId && current.updatedAtEpochMillis == updatedAtEpochMillis) {
                current.copy(pendingSync = false)
            } else {
                current
            }
        }
    }

    override suspend fun deletePlaybackProgress(bookId: String) {
        playbackProgressFlow.value = playbackProgressFlow.value.filterNot { it.libraryItemId == bookId }
    }

    override suspend fun updateLibraryItemCompletion(bookId: String, isCompleted: Boolean, updatedAtEpochMillis: Long) {
        libraryItemsFlow.value = libraryItemsFlow.value.map { current ->
            if (current.id == bookId) {
                current.copy(isCompleted = isCompleted, updatedAtEpochMillis = updatedAtEpochMillis)
            } else {
                current
            }
        }
    }

    override suspend fun updateDownloadedBookCompletion(bookId: String, isCompleted: Boolean) {
        downloadedBooksFlow.value = downloadedBooksFlow.value.map { current ->
            if (current.id == bookId) current.copy(isCompleted = isCompleted) else current
        }
    }

    override suspend fun upsertLibraryItems(items: List<LibraryItemEntity>) {
        val incomingIds = items.mapTo(mutableSetOf()) { it.id }
        libraryItemsFlow.value = libraryItemsFlow.value.filterNot { it.id in incomingIds } + items
    }

    override suspend fun upsertLibraryItem(item: LibraryItemEntity) {
        upsertLibraryItems(listOf(item))
    }

    override suspend fun allLibraryItemIds(): List<String> = libraryItemsFlow.value.map { it.id }

    override suspend fun allDownloadedBookIds(): List<String> = downloadedBooksFlow.value.map { it.id }

    override suspend fun deleteLibraryItem(bookId: String) {
        libraryItemsFlow.value = libraryItemsFlow.value.filterNot { it.id == bookId }
    }

    override suspend fun upsertDownload(download: DownloadEntity) {
        downloadsFlow.value = downloadsFlow.value.filterNot { it.libraryItemId == download.libraryItemId } + download
    }

    override suspend fun download(bookId: String): DownloadEntity? =
        downloadsFlow.value.firstOrNull { it.libraryItemId == bookId }

    override suspend fun downloadingBookIds(): List<String> =
        downloadsFlow.value.filter { it.state == "downloading" }.map { it.libraryItemId }

    override suspend fun upsertDownloadedBook(book: DownloadedBookEntity) {
        downloadedBooksFlow.value = downloadedBooksFlow.value.filterNot { it.id == book.id } + book
    }

    override suspend fun upsertDownloadedChapters(chapters: List<DownloadedChapterEntity>) {
        val keys = chapters.mapTo(mutableSetOf()) { it.bookId to it.id }
        downloadedChaptersFlow.value =
            downloadedChaptersFlow.value.filterNot { (it.bookId to it.id) in keys } + chapters
    }

    override suspend fun deleteDownloadedBook(bookId: String) {
        downloadedBooksFlow.value = downloadedBooksFlow.value.filterNot { it.id == bookId }
    }

    override suspend fun deleteDownloadedChapters(bookId: String) {
        downloadedChaptersFlow.value = downloadedChaptersFlow.value.filterNot { it.bookId == bookId }
    }

    override suspend fun deleteDownload(bookId: String) {
        downloadsFlow.value = downloadsFlow.value.filterNot { it.libraryItemId == bookId }
    }

    override suspend fun downloadedBook(bookId: String): DownloadedBookEntity? =
        downloadedBooksFlow.value.firstOrNull { it.id == bookId }

    override suspend fun downloadedChapters(bookId: String): List<DownloadedChapterEntity> =
        downloadedChaptersFlow.value.filter { it.bookId == bookId }.sortedBy { it.position }

    override suspend fun deleteAllPlaybackProgress() {
        playbackProgressFlow.value = emptyList()
    }

    override suspend fun deleteAllDownloadedChapters() {
        downloadedChaptersFlow.value = emptyList()
    }

    override suspend fun deleteAllDownloadedBooks() {
        downloadedBooksFlow.value = emptyList()
    }

    override suspend fun deleteAllDownloads() {
        downloadsFlow.value = emptyList()
    }

    override suspend fun deleteAllLibraryItems() {
        libraryItemsFlow.value = emptyList()
    }
}
