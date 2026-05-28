package pw.kmr.sonnet.shared.remote

import pw.kmr.sonnet.shared.model.RemoteProgress

interface ProgressSyncRemoteDataSource {
    suspend fun progress(bookId: String): RemoteProgress

    suspend fun updateProgress(
        bookId: String,
        chapterId: String,
        offsetMs: Long,
        updatedAtEpochMillis: Long
    )

    suspend fun markComplete(bookId: String)

    suspend fun markIncomplete(bookId: String)
}