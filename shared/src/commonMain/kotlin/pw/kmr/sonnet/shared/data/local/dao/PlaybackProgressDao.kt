package pw.kmr.sonnet.shared.data.local.dao

import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity

interface PlaybackProgressDao {
    suspend fun playbackProgress(bookId: String): PlaybackProgressEntity?

    suspend fun pendingPlaybackProgress(): List<PlaybackProgressEntity>

    suspend fun upsertPlaybackProgress(progress: PlaybackProgressEntity)

    suspend fun markPlaybackProgressSynced(bookId: String, updatedAtEpochMillis: Long)

    suspend fun markBookCompletion(
        bookId: String,
        isCompleted: Boolean,
        positionMillis: Long,
        durationMillis: Long?,
        updatedAtEpochMillis: Long,
        pendingSync: Boolean
    )
}
