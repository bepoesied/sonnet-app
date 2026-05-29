package pw.kmr.sonnet.shared.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import pw.kmr.sonnet.shared.data.local.dao.LibraryDao
import pw.kmr.sonnet.shared.data.local.entity.DownloadEntity
import pw.kmr.sonnet.shared.model.BookDetail
import pw.kmr.sonnet.shared.model.LibraryBook
import pw.kmr.sonnet.shared.remote.AuthenticatedBooksApiClient

class LibraryRepository(
    private val booksApiClient: AuthenticatedBooksApiClient,
    private val libraryDao: LibraryDao,
    private val booksDirectory: Path,
    private val fileSystem: FileSystem
) : LibraryViewModelRepository, LocalLibraryCleaner {

    init {
        require(booksDirectory.toString().let { !it.contains("..") && !it.contains("~") }) {
            "booksDirectory must not contain path traversal sequences"
        }
    }
    private val sharedRepository = SharedLibraryRepository(
        booksApiClient = booksApiClient,
        libraryDao = libraryDao
    )

    override val libraryItems: Flow<List<LibraryBook>> = sharedRepository.libraryItems

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

        val bookDirectory = bookDirectory(book.id)
        val sortedChapters = book.chapters.sortedBy { it.position }
        val resumableChapterCount = if (restartInProgress) {
            sortedChapters
                .take(existing?.downloadedBytes?.toInt() ?: 0)
                .takeWhile { chapter -> fileSystem.exists(chapterFile(book.id, chapter.id, chapter.audioUrl)) }
                .size
        } else {
            0
        }

        libraryDao.upsertDownload(
            DownloadEntity(
                libraryItemId = book.id,
                localFilePath = bookDirectory.toString(),
                downloadedBytes = resumableChapterCount.toLong(),
                totalBytes = sortedChapters.size.toLong(),
                state = "downloading"
            )
        )

        try {
            if (!restartInProgress) {
                fileSystem.deleteRecursively(bookDirectory, mustExist = false)
            }
            fileSystem.createDirectories(chapterDirectory(book.id))

            libraryDao.upsertLibraryItem(book.toLibraryItemEntity())

            val coverPath = book.coverUrl?.let { coverUrl ->
                val targetPath = coverFile(book.id, coverUrl)
                if (!fileSystem.exists(targetPath)) {
                    booksApiClient.download(coverUrl, targetPath, fileSystem)
                }
                targetPath.toString()
            }

            var completedChapters = resumableChapterCount.toLong()
            val totalChapters = sortedChapters.size.toLong()
            val downloadedChapters = sortedChapters.take(resumableChapterCount).map { chapter ->
                chapter.toDownloadedChapterEntity(
                    book.id,
                    chapterFile(book.id, chapter.id, chapter.audioUrl).toString(),
                    contentType = null
                )
            } + sortedChapters.drop(resumableChapterCount).map { chapter ->
                val targetPath = chapterFile(book.id, chapter.id, chapter.audioUrl)
                val result = booksApiClient.download(chapter.audioUrl, targetPath, fileSystem)
                completedChapters++
                libraryDao.upsertDownload(
                    DownloadEntity(
                        libraryItemId = book.id,
                        localFilePath = bookDirectory.toString(),
                        downloadedBytes = completedChapters,
                        totalBytes = totalChapters,
                        state = "downloading"
                    )
                )
                chapter.toDownloadedChapterEntity(book.id, targetPath.toString(), result.contentType)
            }

            libraryDao.replaceDownloadedBook(
                book = book.toDownloadedBookEntity(coverPath),
                chapters = downloadedChapters
            )
            libraryDao.upsertDownload(
                DownloadEntity(
                    libraryItemId = book.id,
                    localFilePath = bookDirectory.toString(),
                    downloadedBytes = totalChapters,
                    totalBytes = totalChapters,
                    state = "complete"
                )
            )
        } catch (cancellation: CancellationException) {
            deleteDownload(book.id)
            throw cancellation
        } catch (throwable: Throwable) {
            fileSystem.deleteRecursively(bookDirectory, mustExist = false)
            libraryDao.upsertDownload(
                DownloadEntity(
                    libraryItemId = book.id,
                    localFilePath = existing?.localFilePath,
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
        fileSystem.deleteRecursively(bookDirectory(bookId), mustExist = false)
        libraryDao.deleteDownloadedBookMetadata(bookId)
    }

    override suspend fun setCompletion(book: LibraryBook, isCompleted: Boolean) {
        sharedRepository.setCompletion(book, isCompleted)
    }

    suspend fun prepareBookForPlayback(bookId: String) {
        sharedRepository.prepareBookForPlayback(bookId)
    }

    suspend fun downloadedBook(bookId: String) = sharedRepository.downloadedBook(bookId)

    override suspend fun clearLocalLibrary() {
        fileSystem.deleteRecursively(booksDirectory, mustExist = false)
        libraryDao.clearLibraryData()
    }

    private fun bookDirectory(bookId: String): Path {
        validateId(bookId)
        return (booksDirectory.toString() + "/" + bookId).toPath()
    }

    private fun chapterDirectory(bookId: String): Path = (bookDirectory(bookId).toString() + "/chapters").toPath()

    private fun chapterFile(bookId: String, chapterId: String, sourceUrl: String): Path {
        validateId(chapterId)
        return (chapterDirectory(bookId).toString() + "/$chapterId.${extensionFromUrl(sourceUrl, "audio")}").toPath()
    }

    private fun coverFile(bookId: String, sourceUrl: String): Path =
        (bookDirectory(bookId).toString() + "/cover.${extensionFromUrl(sourceUrl, "img")}").toPath()

    private fun validateId(id: String) {
        require(id.isNotBlank()) { "ID must not be blank" }
        require(ID_REGEX.matches(id)) { "ID contains invalid characters: $id" }
    }

    private companion object {
        val ID_REGEX = Regex("^[a-zA-Z0-9_-]+$")
    }

    private fun extensionFromUrl(url: String, fallback: String): String {
        val lastSegment = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val extension = lastSegment.substringAfterLast('.', missingDelimiterValue = "")
            .takeWhile { it.isLetterOrDigit() }
            .lowercase()
        return extension.takeIf { it.isNotBlank() && it.length <= 5 } ?: fallback
    }
}
