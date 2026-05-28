package pw.kmr.sonnet.shared.sync

import kotlinx.coroutines.sync.Mutex
import pw.kmr.sonnet.shared.data.local.dao.PlaybackProgressDao
import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity
import pw.kmr.sonnet.shared.model.RemoteProgress
import pw.kmr.sonnet.shared.remote.ProgressSyncRemoteDataSource

class ProgressSyncer(
    private val remoteDataSource: ProgressSyncRemoteDataSource,
    private val playbackProgressDao: PlaybackProgressDao
) {
    private val syncMutex = Mutex()

    suspend fun syncPending() {
        if (!syncMutex.tryLock()) return
        try {
            playbackProgressDao.pendingPlaybackProgress().forEach { progress ->
                if (!syncProgress(progress)) return
            }
        } finally {
            syncMutex.unlock()
        }
    }

    suspend fun syncBook(bookId: String) {
        if (!syncMutex.tryLock()) return
        try {
            val progress = playbackProgressDao.playbackProgress(bookId)?.takeIf { it.pendingSync } ?: return
            syncProgress(progress)
        } finally {
            syncMutex.unlock()
        }
    }

    suspend fun remoteProgress(bookId: String): RemoteProgress? {
        return try {
            remoteDataSource.progress(bookId)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun syncProgress(progress: PlaybackProgressEntity): Boolean {
        return try {
            if (progress.isCompleted) {
                remoteDataSource.markComplete(progress.libraryItemId)
            } else {
                val chapterId = progress.chapterId
                if (chapterId == null) {
                    remoteDataSource.markIncomplete(progress.libraryItemId)
                } else {
                    remoteDataSource.updateProgress(
                        bookId = progress.libraryItemId,
                        chapterId = chapterId,
                        offsetMs = progress.chapterOffsetMillis,
                        updatedAtEpochMillis = progress.updatedAtEpochMillis
                    )
                }
            }
            playbackProgressDao.markPlaybackProgressSynced(progress.libraryItemId, progress.updatedAtEpochMillis)
            true
        } catch (_: Exception) {
            false
        }
    }
}