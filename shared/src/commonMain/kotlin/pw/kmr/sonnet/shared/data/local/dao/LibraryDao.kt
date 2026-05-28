
    @Query("DELETE FROM playback_progress")
    suspend fun deleteAllPlaybackProgress()

    @Query("DELETE FROM downloaded_chapters")
    suspend fun deleteAllDownloadedChapters()

    @Query("DELETE FROM downloaded_books")
    suspend fun deleteAllDownloadedBooks()

    @Query("DELETE FROM downloads")
    suspend fun deleteAllDownloads()

    @Query("DELETE FROM library_items")
    suspend fun deleteAllLibraryItems()

    @Transaction
    suspend fun clearLibraryData() {
        deleteAllPlaybackProgress()
        deleteAllDownloadedChapters()
        deleteAllDownloadedBooks()
        deleteAllDownloads()
        deleteAllLibraryItems()
    }
interface LibraryDao : PlaybackProgressDao {
    override suspend fun playbackProgress(bookId: String): PlaybackProgressEntity?
    override suspend fun pendingPlaybackProgress(): List<PlaybackProgressEntity>
    override suspend fun markPlaybackProgressSynced(bookId: String, updatedAtEpochMillis: Long)