@file:OptIn(kotlin.time.ExperimentalTime::class)

package pw.kmr.sonnet.shared.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import pw.kmr.sonnet.shared.data.local.dao.LibraryDao
import pw.kmr.sonnet.shared.data.local.entity.DownloadEntity
import pw.kmr.sonnet.shared.data.local.entity.DownloadedBookEntity
import pw.kmr.sonnet.shared.data.local.entity.DownloadedChapterEntity
import pw.kmr.sonnet.shared.data.local.entity.LibraryItemEntity
import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity
import pw.kmr.sonnet.shared.model.BookChapter
import pw.kmr.sonnet.shared.model.BookDetail
import pw.kmr.sonnet.shared.model.BookSummary
import pw.kmr.sonnet.shared.model.DownloadStatus
import pw.kmr.sonnet.shared.model.DownloadedBook
import pw.kmr.sonnet.shared.model.DownloadedChapter
import pw.kmr.sonnet.shared.model.LibraryBook
import pw.kmr.sonnet.shared.remote.AuthenticatedBooksApiClient
import kotlin.time.Clock

class SharedLibraryRepository(
    private val booksApiClient: AuthenticatedBooksApiClient,
    private val libraryDao: LibraryDao
) {
    val libraryItems: Flow<List<LibraryBook>> = combine(
        libraryDao.observeLibraryItems(),
        libraryDao.observeDownloads(),
        libraryDao.observeDownloadedBooks(),
        libraryDao.observePlaybackProgress()
    ) { items, downloads, downloadedBooks, progress ->
        LibraryProjector.project(items, downloads, downloadedBooks, progress)
    }

    suspend fun refresh() {
        val remoteBooks = booksApiClient.books()
        libraryDao.upsertLibraryItems(remoteBooks.map { it.toEntity() })
    }

    suspend fun setCompletion(book: LibraryBook, isCompleted: Boolean) {
        val now = currentTimeMillis()
        if (isCompleted) {
            libraryDao.deletePlaybackProgress(book.id)
        }
        libraryDao.updateLibraryItemCompletion(book.id, isCompleted, now)
        libraryDao.updateDownloadedBookCompletion(book.id, isCompleted)
        libraryDao.upsertPlaybackProgress(
            PlaybackProgressEntity(
                libraryItemId = book.id,
                positionMillis = if (isCompleted) book.totalDurationMillis ?: 0L else 0L,
                durationMillis = book.totalDurationMillis,
                updatedAtEpochMillis = now,
                isCompleted = isCompleted,
                pendingSync = true
            )
        )

        if (isCompleted) {
            booksApiClient.markComplete(book.id)
        } else {
            booksApiClient.markIncomplete(book.id)
        }
        libraryDao.markPlaybackProgressSynced(book.id, now)
    }

    suspend fun prepareBookForPlayback(bookId: String) {
        val libraryItem = libraryDao.libraryItem(bookId)
        val localProgress = libraryDao.playbackProgress(bookId)
        if (libraryItem?.isCompleted == true && libraryItem.updatedAtEpochMillis >= (localProgress?.updatedAtEpochMillis ?: 0L)) {
            libraryDao.deletePlaybackProgress(bookId)
            libraryDao.updateDownloadedBookCompletion(bookId, false)
        }
    }

    suspend fun downloadedBook(bookId: String): DownloadedBook? {
        val book = libraryDao.downloadedBook(bookId) ?: return null
        return book.toModel(libraryDao.downloadedChapters(bookId))
    }

    private fun BookSummary.toEntity(): LibraryItemEntity = LibraryItemEntity(
        id = id,
        title = title,
        author = author,
        coverImageUrl = coverUrl,
        isCompleted = isCompleted,
        updatedAtEpochMillis = currentTimeMillis()
    )

    private fun DownloadedBookEntity.toModel(chapters: List<DownloadedChapterEntity>): DownloadedBook = DownloadedBook(
        id = id,
        title = title,
        author = author,
        coverFilePath = coverFilePath,
        chapters = chapters.map { it.toModel() }
    )

    private fun DownloadedChapterEntity.toModel(): DownloadedChapter = DownloadedChapter(
        id = id,
        title = title,
        position = position,
        audioFilePath = audioFilePath,
        durationMs = durationMs
    )
}

object LibraryProjector {
    fun project(
        items: List<LibraryItemEntity>,
        downloads: List<DownloadEntity>,
        downloadedBooks: List<DownloadedBookEntity>,
        progress: List<PlaybackProgressEntity>
    ): List<LibraryBook> {
        val downloadsByBook = downloads.associateBy { it.libraryItemId }
        val downloadedByBook = downloadedBooks.associateBy { it.id }
        val progressByBook = progress.associateBy { it.libraryItemId }

        val remoteBooks = items.map { item ->
            val download = downloadsByBook[item.id]
            val downloadedBook = downloadedByBook[item.id]
            val bookProgress = progressByBook[item.id]
            val downloadStatus = download?.state.toDownloadStatus()

            LibraryBook(
                id = item.id,
                title = item.title,
                author = item.author,
                isCompleted = item.completionState(bookProgress),
                isDownloaded = downloadStatus == DownloadStatus.Downloaded && downloadedBook != null,
                downloadStatus = downloadStatus,
                downloadedChapters = download?.downloadedBytes ?: 0L,
                totalChapters = download?.totalBytes,
                localCoverUri = downloadedBook?.coverFilePath,
                remoteCoverUrl = item.coverImageUrl,
                progressPercent = bookProgress?.percentComplete()
            )
        }
        val remoteIds = items.mapTo(mutableSetOf()) { it.id }
        val localOnlyBooks = downloadedBooks
            .filterNot { it.id in remoteIds }
            .map { book ->
                val download = downloadsByBook[book.id]
                val bookProgress = progressByBook[book.id]
                LibraryBook(
                    id = book.id,
                    title = book.title,
                    author = book.author,
                    isCompleted = bookProgress?.isCompleted ?: book.isCompleted,
                    isDownloaded = true,
                    downloadStatus = download?.state.toDownloadStatus().takeIf { it != DownloadStatus.NotDownloaded }
                        ?: DownloadStatus.Downloaded,
                    downloadedChapters = download?.downloadedBytes ?: 0L,
                    totalChapters = download?.totalBytes,
                    localCoverUri = book.coverFilePath,
                    remoteCoverUrl = null,
                    progressPercent = bookProgress?.percentComplete()
                )
            }

        return (remoteBooks + localOnlyBooks).sortedBy { it.title.lowercase() }
    }
}

fun BookDetail.toLibraryItemEntity(updatedAtEpochMillis: Long = currentTimeMillis()): LibraryItemEntity = LibraryItemEntity(
    id = id,
    title = title,
    author = author,
    coverImageUrl = coverUrl,
    isCompleted = isCompleted,
    updatedAtEpochMillis = updatedAtEpochMillis
)

fun BookDetail.toDownloadedBookEntity(
    coverPath: String?,
    downloadedAtEpochMillis: Long = currentTimeMillis()
): DownloadedBookEntity = DownloadedBookEntity(
    id = id,
    title = title,
    author = author,
    narrator = narrator,
    description = description,
    coverFilePath = coverPath,
    isCompleted = isCompleted,
    downloadedAtEpochMillis = downloadedAtEpochMillis
)

fun BookChapter.toDownloadedChapterEntity(
    bookId: String,
    audioPath: String,
    contentType: String?
): DownloadedChapterEntity = DownloadedChapterEntity(
    bookId = bookId,
    id = id,
    title = title,
    position = position,
    startMs = startMs,
    endMs = endMs,
    durationMs = durationMs,
    mediaAssetId = mediaAssetId,
    audioFilePath = audioPath,
    contentType = contentType
)

private fun String?.toDownloadStatus(): DownloadStatus = when (this?.lowercase()) {
    "complete", "completed", "downloaded" -> DownloadStatus.Downloaded
    "downloading", "running", "in_progress" -> DownloadStatus.Downloading
    "failed", "failure", "error" -> DownloadStatus.Failed
    "queued" -> DownloadStatus.Queued
    else -> DownloadStatus.NotDownloaded
}

private fun PlaybackProgressEntity.percentComplete(): Float? {
    val duration = durationMillis ?: return null
    if (duration <= 0L) return null
    return (positionMillis.toFloat() / duration).coerceIn(0f, 1f)
}

private fun LibraryItemEntity.completionState(progress: PlaybackProgressEntity?): Boolean {
    if (progress == null) return isCompleted
    return if (updatedAtEpochMillis >= progress.updatedAtEpochMillis) isCompleted else progress.isCompleted
}

private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()