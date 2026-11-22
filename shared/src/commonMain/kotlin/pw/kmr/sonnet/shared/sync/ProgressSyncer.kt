package pw.kmr.sonnet.shared.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pw.kmr.sonnet.shared.auth.AuthRequiredException
import pw.kmr.sonnet.shared.data.local.dao.PlaybackProgressDao
import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity
import pw.kmr.sonnet.shared.model.RemoteProgress
import pw.kmr.sonnet.shared.remote.ApiException
import pw.kmr.sonnet.shared.remote.ProgressSyncRemoteDataSource

data class ProgressSyncResult(
    val syncedCount: Int = 0,
    val hasTransientFailures: Boolean = false,
    val authFailed: Boolean = false
)

class ProgressSyncer(
    private val remoteDataSource: ProgressSyncRemoteDataSource,
    private val playbackProgressDao: PlaybackProgressDao,
    private val backgroundSyncScheduler: BackgroundSyncScheduler = BackgroundSyncScheduler.NoOp
) {
    private val syncMutex = Mutex()
    private val requestMutex = Mutex()

    private var syncAllRequested = false
    private val requestedBookIds = linkedSetOf<String>()
    private var lastDrainResult = ProgressSyncResult()

    fun notifyPendingProgressWritten() {
        backgroundSyncScheduler.scheduleProgressSync()
    }

    suspend fun syncPending(): ProgressSyncResult {
        requestMutex.withLock {
            syncAllRequested = true
        }
        return drainRequests()
    }

    suspend fun syncBook(bookId: String): ProgressSyncResult {
        requestMutex.withLock {
            if (!syncAllRequested) {
                requestedBookIds += bookId
            }
        }
        return drainRequests()
    }

    suspend fun remoteProgress(bookId: String): RemoteProgress? {
        return try {
            remoteDataSource.progress(bookId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun drainRequests(): ProgressSyncResult = syncMutex.withLock {
        var ranAnyPass = false
        var totalSynced = 0
        var hadTransientFailures = false
        var authFailed = false

        while (true) {
            val request = requestMutex.withLock {
                if (!syncAllRequested && requestedBookIds.isEmpty()) {
                    null
                } else {
                    val runAll = syncAllRequested
                    val bookIds = requestedBookIds.toList()
                    syncAllRequested = false
                    requestedBookIds.clear()
                    SyncPassRequest(runAll = runAll, bookIds = bookIds)
                }
            } ?: break

            ranAnyPass = true
            val passResult = if (request.runAll) {
                syncItems(playbackProgressDao.pendingPlaybackProgress())
            } else {
                val pendingBooks = request.bookIds.mapNotNull { bookId ->
                    playbackProgressDao.playbackProgress(bookId)?.takeIf { it.pendingSync }
                }
                syncItems(pendingBooks)
            }

            totalSynced += passResult.syncedCount
            hadTransientFailures = hadTransientFailures || passResult.hasTransientFailures
            if (passResult.authFailed) {
                authFailed = true
                // Clear queued requests since nothing else can succeed until the user re-authenticates.
                requestMutex.withLock {
                    syncAllRequested = false
                    requestedBookIds.clear()
                }
                break
            }
        }

        if (!ranAnyPass) {
            return@withLock lastDrainResult
        }

        val result = ProgressSyncResult(
            syncedCount = totalSynced,
            hasTransientFailures = hadTransientFailures,
            authFailed = authFailed
        )
        lastDrainResult = result
        if (hadTransientFailures && !authFailed) {
            backgroundSyncScheduler.scheduleProgressSync()
        }
        result
    }

    private suspend fun syncItems(items: List<PlaybackProgressEntity>): ProgressSyncResult {
        var syncedCount = 0
        var hasTransientFailures = false

        for (progress in items) {
            when (syncProgress(progress)) {
                SyncItemOutcome.Synced -> syncedCount++
                SyncItemOutcome.PermanentErrorCleared -> Unit
                SyncItemOutcome.TransientFailure -> hasTransientFailures = true
                SyncItemOutcome.AuthFailure -> {
                    return ProgressSyncResult(
                        syncedCount = syncedCount,
                        hasTransientFailures = hasTransientFailures,
                        authFailed = true
                    )
                }
            }
        }

        return ProgressSyncResult(
            syncedCount = syncedCount,
            hasTransientFailures = hasTransientFailures,
            authFailed = false
        )
    }

    private suspend fun syncProgress(progress: PlaybackProgressEntity): SyncItemOutcome {
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
            playbackProgressDao.markPlaybackProgressSynced(
                progress.libraryItemId,
                progress.updatedAtEpochMillis
            )
            SyncItemOutcome.Synced
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            when {
                isAuthFailure(exception) -> SyncItemOutcome.AuthFailure
                isPermanentClientError(exception) -> {
                    // Clear pendingSync only for this exact timestamp so a poisoned item cannot
                    // head-of-line block other books forever while preserving newer concurrent writes.
                    playbackProgressDao.markPlaybackProgressSynced(
                        progress.libraryItemId,
                        progress.updatedAtEpochMillis
                    )
                    SyncItemOutcome.PermanentErrorCleared
                }
                else -> SyncItemOutcome.TransientFailure
            }
        }
    }

    private fun isAuthFailure(exception: Exception): Boolean =
        exception is AuthRequiredException ||
            (exception is ApiException && exception.statusCode in AUTH_ERROR_STATUS_CODES)

    private fun isPermanentClientError(exception: Exception): Boolean =
        exception is ApiException && exception.statusCode in PERMANENT_CLIENT_ERROR_STATUS_CODES

    private data class SyncPassRequest(
        val runAll: Boolean,
        val bookIds: List<String>
    )

    private enum class SyncItemOutcome {
        Synced,
        PermanentErrorCleared,
        TransientFailure,
        AuthFailure
    }

    private companion object {
        val AUTH_ERROR_STATUS_CODES = setOf(401, 403)
        val PERMANENT_CLIENT_ERROR_STATUS_CODES = setOf(400, 404, 409, 410, 422)
    }
}
