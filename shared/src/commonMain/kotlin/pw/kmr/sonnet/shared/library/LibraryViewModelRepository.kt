package pw.kmr.sonnet.shared.library

import kotlinx.coroutines.flow.Flow
import pw.kmr.sonnet.shared.model.LibraryBook

interface LibraryViewModelRepository {
    val libraryItems: Flow<List<LibraryBook>>

    suspend fun refresh()

    suspend fun interruptedDownloadBookIds(): List<String>

    suspend fun downloadBook(bookId: String, restartInProgress: Boolean = false)

    suspend fun deleteDownload(bookId: String)

    suspend fun setCompletion(book: LibraryBook, isCompleted: Boolean)
}