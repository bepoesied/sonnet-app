package pw.kmr.sonnet.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import pw.kmr.sonnet.data.local.entity.DownloadEntity
import pw.kmr.sonnet.data.local.entity.DownloadedBookEntity
import pw.kmr.sonnet.data.local.entity.DownloadedChapterEntity
import pw.kmr.sonnet.data.local.entity.LibraryItemEntity
import pw.kmr.sonnet.data.local.entity.PlaybackProgressEntity

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_items ORDER BY title COLLATE NOCASE")
    fun observeLibraryItems(): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE id = :bookId")
    suspend fun libraryItem(bookId: String): LibraryItemEntity?

    @Query("SELECT * FROM downloads")
    fun observeDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloaded_books")
    fun observeDownloadedBooks(): Flow<List<DownloadedBookEntity>>

    @Query("SELECT * FROM playback_progress")
    fun observePlaybackProgress(): Flow<List<PlaybackProgressEntity>>

    @Query("SELECT * FROM playback_progress WHERE libraryItemId = :bookId")
    suspend fun playbackProgress(bookId: String): PlaybackProgressEntity?

    @Query("SELECT * FROM playback_progress WHERE pendingSync = 1 ORDER BY updatedAtEpochMillis")
    suspend fun pendingPlaybackProgress(): List<PlaybackProgressEntity>

    @Upsert
    suspend fun upsertPlaybackProgress(progress: PlaybackProgressEntity)

    @Query("UPDATE playback_progress SET pendingSync = 0 WHERE libraryItemId = :bookId AND updatedAtEpochMillis = :updatedAtEpochMillis")
    suspend fun markPlaybackProgressSynced(bookId: String, updatedAtEpochMillis: Long)

    @Query("DELETE FROM playback_progress WHERE libraryItemId = :bookId")
    suspend fun deletePlaybackProgress(bookId: String)

    @Query("UPDATE library_items SET isCompleted = :isCompleted, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :bookId")
    suspend fun updateLibraryItemCompletion(bookId: String, isCompleted: Boolean, updatedAtEpochMillis: Long)

    @Query("UPDATE downloaded_books SET isCompleted = :isCompleted WHERE id = :bookId")
    suspend fun updateDownloadedBookCompletion(bookId: String, isCompleted: Boolean)

    @Upsert
    suspend fun upsertLibraryItems(items: List<LibraryItemEntity>)

    @Upsert
    suspend fun upsertLibraryItem(item: LibraryItemEntity)

    @Upsert
    suspend fun upsertDownload(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE libraryItemId = :bookId")
    suspend fun download(bookId: String): DownloadEntity?

    @Query("SELECT libraryItemId FROM downloads WHERE state = 'downloading'")
    suspend fun downloadingBookIds(): List<String>

    @Upsert
    suspend fun upsertDownloadedBook(book: DownloadedBookEntity)

    @Upsert
    suspend fun upsertDownloadedChapters(chapters: List<DownloadedChapterEntity>)

    @Query("DELETE FROM downloaded_books WHERE id = :bookId")
    suspend fun deleteDownloadedBook(bookId: String)

    @Query("DELETE FROM downloaded_chapters WHERE bookId = :bookId")
    suspend fun deleteDownloadedChapters(bookId: String)

    @Query("DELETE FROM downloads WHERE libraryItemId = :bookId")
    suspend fun deleteDownload(bookId: String)

    @Query("SELECT * FROM downloaded_books WHERE id = :bookId")
    suspend fun downloadedBook(bookId: String): DownloadedBookEntity?

    @Query("SELECT * FROM downloaded_chapters WHERE bookId = :bookId ORDER BY position")
    suspend fun downloadedChapters(bookId: String): List<DownloadedChapterEntity>

    @Transaction
    suspend fun replaceDownloadedBook(book: DownloadedBookEntity, chapters: List<DownloadedChapterEntity>) {
        deleteDownloadedChapters(book.id)
        upsertDownloadedBook(book)
        upsertDownloadedChapters(chapters)
    }

    @Transaction
    suspend fun deleteDownloadedBookMetadata(bookId: String) {
        deleteDownloadedChapters(bookId)
        deleteDownloadedBook(bookId)
        deleteDownload(bookId)
    }
}