package pw.kmr.sonnet.shared.data.local.dao

import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity

interface PlaybackProgressDao {
    suspend fun playbackProgress(bookId: String): PlaybackProgressEntity?

    suspend fun pendingPlaybackProgress(): List<PlaybackProgressEntity>

    suspend fun markPlaybackProgressSynced(bookId: String, updatedAtEpochMillis: Long)
}
