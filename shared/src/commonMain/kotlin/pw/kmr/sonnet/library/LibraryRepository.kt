package pw.kmr.sonnet.library

import kotlinx.coroutines.CancellationException
import pw.kmr.sonnet.shared.data.local.dao.LibraryDao
import pw.kmr.sonnet.shared.data.local.entity.DownloadEntity
import pw.kmr.sonnet.shared.model.BookDetail
import pw.kmr.sonnet.shared.model.LibraryBook
import pw.kmr.sonnet.shared.library.DownloadFileSystem
import pw.kmr.sonnet.shared.library.FileDownloader
import pw.kmr.sonnet.shared.library.LibraryViewModelRepository
import pw.kmr.sonnet.shared.library.SharedLibraryRepository
import pw.kmr.sonnet.shared.library.toDownloadedBookEntity
import pw.kmr.sonnet.shared.library.toDownloadedChapterEntity
import pw.kmr.sonnet.shared.library.toLibraryItemEntity
import pw.kmr.sonnet.shared.remote.AuthenticatedBooksApiClient

class LibraryRepository(
    private val booksApiClient: AuthenticatedBooksApiClient,
    private val fileDownloader: FileDownloader,
    private val fileSystem: DownloadFileSystem,
    private val libraryDao: LibraryDao
) : LibraryViewModelRepository {
    private val sharedRepository = SharedLibraryRepository(
        booksApiClient = booksApiClient,
        libraryDao = libraryDao
    )

    override val libraryItems = sharedRepository.libraryItems

    override suspend fun refresh() {
        sharedRepository.refresh()
    }

    override suspend fun interruptedDownloadBookIds(): List<String> = libraryDao.downloadingBookIds()

    override suspend fun downloadBook(bookId: String, restartInProgress: Boolean) {
        val existing = libraryDao.download(bookId)
        if (existing?.state == "complete") return
        if (existing?.state == "downloading" && !restartInProgress) return

        val book = booksApiClient.book(bookId)
        require(book.chapters.isNotEmpty()) { "Book has no chapters to download." }

        val bookDirectoryPath = fileSystem.bookDirectoryPath(book.id)
        val sortedChapters = book.chapters.sortedBy { it.position }
        val resumableChapterCount = if (restartInProgress) {
            sortedChapters
                .take(existing?.downloadedBytes?.toInt() ?: 0)
                .takeWhile { chapter -> fileSystem.fileExists(fileSystem.chapterFilePath(book.id, chapter.id, chapter.audioUrl)) }
                .size
        } else {
            0
        }
        libraryDao.upsertDownload(
            DownloadEntity(
                libraryItemId = book.id,
                localFilePath = bookDirectoryPath,
                downloadedBytes = resumableChapterCount.toLong(),
                totalBytes = sortedChapters.size.toLong(),
                state = "downloading"
            )
        )
        try {
            if (!restartInProgress) {
                fileSystem.resetBookDirectory(book.id)
            } else {
                fileSystem.ensureChapterDirectory(book.id)
            }
            libraryDao.upsertLibraryItem(book.toLibraryItemEntity())

            val coverPath = book.coverUrl?.let { coverUrl ->
                val targetPath = fileSystem.coverFilePath(book.id, coverUrl)
                if (!fileSystem.fileExists(targetPath)) {
                    fileDownloader.download(coverUrl, targetPath)
                }
                targetPath
            }

            var completedChapters = resumableChapterCount.toLong()
            val totalChapters = sortedChapters.size.toLong()
            val downloadedChapters = sortedChapters.take(resumableChapterCount).map { chapter ->
                chapter.toDownloadedChapterEntity(
                    book.id,
                    fileSystem.chapterFilePath(book.id, chapter.id, chapter.audioUrl),
                    contentType = null
                )
            } + sortedChapters.drop(resumableChapterCount).map { chapter ->
                val targetPath = fileSystem.chapterFilePath(book.id, chapter.id, chapter.audioUrl)
                val result = fileDownloader.download(chapter.audioUrl, targetPath)
                completedChapters++
                libraryDao.upsertDownload(
                    DownloadEntity(
                        libraryItemId = book.id,
                        localFilePath = bookDirectoryPath,
                        downloadedBytes = completedChapters,
                        totalBytes = totalChapters,
                        state = "downloading"
                    )
                )
                chapter.toDownloadedChapterEntity(book.id, targetPath, result.contentType)
            }

            libraryDao.replaceDownloadedBook(
                book = book.toDownloadedBookEntity(coverPath),
                chapters = downloadedChapters
            )
            libraryDao.upsertDownload(
                DownloadEntity(
                    libraryItemId = book.id,
                    localFilePath = bookDirectoryPath,
                    downloadedBytes = totalChapters,
                    totalBytes = totalChapters,
                    state = "complete"
                )
            )
        } catch (cancellation: CancellationException) {
            deleteDownload(book.id)
            throw cancellation
        } catch (throwable: Throwable) {
            fileSystem.deleteBookDirectory(book.id)
            libraryDao.upsertDownload(
                DownloadEntity(
                    libraryItemId = book.id,
                    downloadedBytes = 0L,
                    totalBytes = null,
                    state = "failed",
                    errorMessage = throwable.message ?: "Download failed."
                )
            )
            throw throwable
        }
    }

    override suspend fun deleteDownload(bookId: String) {
        fileSystem.deleteBookDirectory(bookId)
        libraryDao.deleteDownloadedBookMetadata(bookId)
    }

    override suspend fun setCompletion(book: LibraryBook, isCompleted: Boolean) {
        sharedRepository.setCompletion(book, isCompleted)
    }

    suspend fun prepareBookForPlayback(bookId: String) {
        sharedRepository.prepareBookForPlayback(bookId)
    }

    suspend fun downloadedBook(bookId: String) = sharedRepository.downloadedBook(bookId)
}