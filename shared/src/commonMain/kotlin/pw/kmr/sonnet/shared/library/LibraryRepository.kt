) : LibraryViewModelRepository, LocalLibraryCleaner {
    override suspend fun clearLocalLibrary() {
        fileSystem.deleteRecursively(booksDirectory, mustExist = false)
        libraryDao.clearLibraryData()
    }


interface LocalLibraryCleaner {
    suspend fun clearLocalLibrary()
}
import okio.Path
import okio.Path.Companion.toPath
    private val libraryDao: LibraryDao,
    private val booksDirectory: Path,
    private val fileSystem: FileSystem
        val bookDirectory = bookDirectory(book.id)
                .takeWhile { chapter -> fileSystem.exists(chapterFile(book.id, chapter.id, chapter.audioUrl)) }
                localFilePath = bookDirectory.toString(),
            if (!restartInProgress) fileSystem.deleteRecursively(bookDirectory, mustExist = false)
            fileSystem.createDirectories(chapterDirectory(book.id))
                val targetPath = coverFile(book.id, coverUrl)
                if (!fileSystem.exists(targetPath)) {
                    booksApiClient.download(coverUrl, targetPath, fileSystem)
                targetPath.toString()
                    chapterFile(book.id, chapter.id, chapter.audioUrl).toString(),
                val targetPath = chapterFile(book.id, chapter.id, chapter.audioUrl)
                val result = booksApiClient.download(chapter.audioUrl, targetPath, fileSystem)
                        localFilePath = bookDirectory.toString(),
                chapter.toDownloadedChapterEntity(book.id, targetPath.toString(), result.contentType)
                    localFilePath = bookDirectory.toString(),
            fileSystem.deleteRecursively(bookDirectory, mustExist = false)
        fileSystem.deleteRecursively(bookDirectory(bookId), mustExist = false)

    private fun bookDirectory(bookId: String): Path = (booksDirectory.toString() + "/" + bookId).toPath()

    private fun chapterDirectory(bookId: String): Path = (bookDirectory(bookId).toString() + "/chapters").toPath()

    private fun chapterFile(bookId: String, chapterId: String, sourceUrl: String): Path =
        (chapterDirectory(bookId).toString() + "/$chapterId.${extensionFromUrl(sourceUrl, "audio")}").toPath()

    private fun coverFile(bookId: String, sourceUrl: String): Path =
        (bookDirectory(bookId).toString() + "/cover.${extensionFromUrl(sourceUrl, "img")}").toPath()

    private fun extensionFromUrl(url: String, fallback: String): String {
        val lastSegment = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val extension = lastSegment.substringAfterLast('.', missingDelimiterValue = "")
            .takeWhile { it.isLetterOrDigit() }
            .lowercase()
        return extension.takeIf { it.isNotBlank() && it.length <= 5 } ?: fallback
    }