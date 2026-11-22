package pw.kmr.sonnet.shared.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import pw.kmr.sonnet.shared.data.local.dao.LibraryDao
import pw.kmr.sonnet.shared.data.local.entity.DownloadEntity
import pw.kmr.sonnet.shared.data.local.entity.DownloadedChapterEntity
import pw.kmr.sonnet.shared.ioDispatcher as defaultIoDispatcher
import pw.kmr.sonnet.shared.model.LibraryBook
import pw.kmr.sonnet.shared.playback.PlaybackBookRepository
import pw.kmr.sonnet.shared.remote.ApiException
import pw.kmr.sonnet.shared.remote.BooksRemoteDataSource
import pw.kmr.sonnet.shared.sync.ProgressSyncer

class LibraryRepository(
    private val booksApiClient: BooksRemoteDataSource,
    private val libraryDao: LibraryDao,
    private val booksDirectory: Path,
    private val fileSystem: FileSystem,
    private val progressSyncer: ProgressSyncer = ProgressSyncer(booksApiClient, libraryDao),
    private val ioDispatcher: CoroutineDispatcher = defaultIoDispatcher,
    private val onBeforeDeleteBook: suspend (String) -> Unit = {},
    private val onBeforeClearLibrary: suspend () -> Unit = {}
) : LibraryViewModelRepository, LocalLibraryCleaner, PlaybackBookRepository {

    init {
        require(booksDirectory.toString().let { !it.contains("..") && !it.contains("~") }) {
            "booksDirectory must not contain path traversal sequences"
        }
    }

    private val sharedRepository = SharedLibraryRepository(
        booksApiClient = booksApiClient,
        libraryDao = libraryDao,
        progressSyncer = progressSyncer
    )

    override val libraryItems: Flow<List<LibraryBook>> = sharedRepository.libraryItems

    /**
     * A refresh is a best-effort cache update. Keep its entire network-to-database path off the
     * main dispatcher so a slow or stalled connection cannot prevent Room-backed UI state from
     * being rendered.
     */
    override suspend fun refresh() = withContext(ioDispatcher) {
        sharedRepository.refresh()
    }

    override suspend fun interruptedDownloadBookIds(): List<String> = withContext(ioDispatcher) {
        libraryDao.downloadingBookIds()
    }

    override suspend fun markDownloadQueued(bookId: String) = withContext(ioDispatcher) {
        val existing = libraryDao.download(bookId)
        if (existing?.state == "complete" || existing?.state == "downloading") return@withContext
        libraryDao.upsertDownload(
            DownloadEntity(
                libraryItemId = bookId,
                localFilePath = existing?.localFilePath,
                downloadedBytes = existing?.downloadedBytes ?: 0L,
                totalBytes = existing?.totalBytes,
                state = "queued",
                errorMessage = null
            )
        )
    }

    override suspend fun downloadBook(bookId: String, restartInProgress: Boolean) = withContext(ioDispatcher) {
        validateId(bookId)
        val existing = libraryDao.download(bookId)
        if (existing?.state == "complete" && libraryDao.downloadedBook(bookId) != null) return@withContext
        if (existing?.state == "downloading" && !restartInProgress) return@withContext

        val bookDirectory = bookDirectory(bookId)
        var completedChapters = existing?.downloadedBytes ?: 0L
        var totalChapters: Long? = existing?.totalBytes

        try {
            var book = booksApiClient.book(bookId)
            require(book.chapters.isNotEmpty()) { "Book has no chapters to download." }

            var sortedChapters = book.chapters.sortedBy { it.position }
            totalChapters = sortedChapters.size.toLong()

            fileSystem.createDirectories(chapterDirectory(book.id))
            cleanupPartialFiles(bookDirectory)
            cleanupPartialFiles(chapterDirectory(book.id))

            completedChapters = sortedChapters.count { chapter ->
                isValidFile(chapterFile(book.id, chapter.id, chapter.audioUrl))
            }.toLong()

            libraryDao.upsertDownload(
                DownloadEntity(
                    libraryItemId = book.id,
                    localFilePath = bookDirectory.toString(),
                    downloadedBytes = completedChapters,
                    totalBytes = totalChapters,
                    state = "downloading",
                    errorMessage = null
                )
            )
            libraryDao.upsertLibraryItem(book.toLibraryItemEntity())

            val coverPath = book.coverUrl?.let { initialCoverUrl ->
                var coverUrl = initialCoverUrl
                var coverTarget = coverFile(book.id, coverUrl)
                if (!isValidFile(coverTarget)) {
                    try {
                        booksApiClient.download(coverUrl, coverTarget, fileSystem)
                    } catch (apiException: ApiException) {
                        if (apiException.statusCode in EXPIRED_URL_STATUS_CODES) {
                            val refreshedBook = booksApiClient.book(book.id)
                            require(refreshedBook.chapters.isNotEmpty()) { "Book has no chapters to download." }
                            book = refreshedBook
                            sortedChapters = refreshedBook.chapters.sortedBy { it.position }
                            totalChapters = sortedChapters.size.toLong()
                            coverUrl = refreshedBook.coverUrl ?: throw apiException
                            coverTarget = coverFile(book.id, coverUrl)
                            booksApiClient.download(coverUrl, coverTarget, fileSystem)
                        } else {
                            throw apiException
                        }
                    }
                }
                coverTarget.toString()
            }

            val downloadedChapters = mutableListOf<DownloadedChapterEntity>()
            var index = 0
            var urlRefreshedForCurrentChapter = false
            while (index < sortedChapters.size) {
                val chapter = sortedChapters[index]
                val targetPath = chapterFile(book.id, chapter.id, chapter.audioUrl)
                if (isValidFile(targetPath)) {
                    downloadedChapters += chapter.toDownloadedChapterEntity(
                        bookId = book.id,
                        audioPath = targetPath.toString(),
                        contentType = null
                    )
                    index++
                    urlRefreshedForCurrentChapter = false
                    continue
                }

                try {
                    val result = booksApiClient.download(chapter.audioUrl, targetPath, fileSystem)
                    completedChapters++
                    libraryDao.upsertDownload(
                        DownloadEntity(
                            libraryItemId = book.id,
                            localFilePath = bookDirectory.toString(),
                            downloadedBytes = completedChapters,
                            totalBytes = totalChapters,
                            state = "downloading",
                            errorMessage = null
                        )
                    )
                    downloadedChapters += chapter.toDownloadedChapterEntity(
                        bookId = book.id,
                        audioPath = targetPath.toString(),
                        contentType = result.contentType
                    )
                    index++
                    urlRefreshedForCurrentChapter = false
                } catch (apiException: ApiException) {
                    if (apiException.statusCode in EXPIRED_URL_STATUS_CODES && !urlRefreshedForCurrentChapter) {
                        val refreshedBook = booksApiClient.book(book.id)
                        require(refreshedBook.chapters.isNotEmpty()) { "Book has no chapters to download." }
                        book = refreshedBook
                        sortedChapters = refreshedBook.chapters.sortedBy { it.position }
                        totalChapters = sortedChapters.size.toLong()
                        val refreshedIndex = sortedChapters.indexOfFirst { it.id == chapter.id }
                        if (refreshedIndex < 0) throw apiException
                        index = refreshedIndex
                        urlRefreshedForCurrentChapter = true
                    } else {
                        throw apiException
                    }
                }
            }

            libraryDao.replaceDownloadedBook(
                book = book.toDownloadedBookEntity(coverPath),
                chapters = downloadedChapters
            )
            val finalTotalChapters = sortedChapters.size.toLong()
            libraryDao.upsertDownload(
                DownloadEntity(
                    libraryItemId = book.id,
                    localFilePath = bookDirectory.toString(),
                    downloadedBytes = finalTotalChapters,
                    totalBytes = finalTotalChapters,
                    state = "complete",
                    errorMessage = null
                )
            )
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                deleteDownload(bookId)
            }
            throw cancellation
        } catch (throwable: Throwable) {
            // Preserve already-downloaded chapter files & progress so retry/resume skips completed chapters.
            libraryDao.upsertDownload(
                DownloadEntity(
                    libraryItemId = bookId,
                    localFilePath = bookDirectory.toString(),
                    downloadedBytes = completedChapters,
                    totalBytes = totalChapters,
                    state = "failed",
                    errorMessage = throwable.message ?: "Download failed."
                )
            )
            throw throwable
        }
    }

    override suspend fun deleteDownload(bookId: String) {
        onBeforeDeleteBook(bookId)
        withContext(ioDispatcher) {
            fileSystem.deleteRecursively(bookDirectory(bookId), mustExist = false)
            libraryDao.deleteDownloadedBookMetadata(bookId)
        }
    }

    override suspend fun setCompletion(book: LibraryBook, isCompleted: Boolean) = withContext(ioDispatcher) {
        sharedRepository.setCompletion(book, isCompleted)
    }

    override suspend fun prepareBookForPlayback(bookId: String) = withContext(ioDispatcher) {
        sharedRepository.prepareBookForPlayback(bookId)
    }

    override suspend fun downloadedBook(bookId: String) = withContext(ioDispatcher) {
        sharedRepository.downloadedBook(bookId)
    }

    override suspend fun clearLocalLibrary() {
        onBeforeClearLibrary()
        withContext(ioDispatcher) {
            fileSystem.deleteRecursively(booksDirectory, mustExist = false)
            libraryDao.clearLibraryData()
        }
    }

    private fun isValidFile(path: Path): Boolean {
        val metadata = fileSystem.metadataOrNull(path) ?: return false
        return metadata.isRegularFile && (metadata.size ?: 0L) > 0L
    }

    private fun cleanupPartialFiles(directory: Path) {
        if (!fileSystem.exists(directory)) return
        fileSystem.list(directory)
            .filter { it.name.endsWith(".part") }
            .forEach { fileSystem.delete(it, mustExist = false) }
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

    private fun extensionFromUrl(url: String, fallback: String): String {
        val lastSegment = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val extension = lastSegment.substringAfterLast('.', missingDelimiterValue = "")
            .takeWhile { it.isLetterOrDigit() }
            .lowercase()
        return extension.takeIf { it.isNotBlank() && it.length <= 5 } ?: fallback
    }

    private companion object {
        val ID_REGEX = Regex("^[a-zA-Z0-9_-]+$")
        val EXPIRED_URL_STATUS_CODES = setOf(403, 410)
    }
}
