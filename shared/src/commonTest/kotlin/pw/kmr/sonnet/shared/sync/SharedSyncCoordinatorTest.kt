package pw.kmr.sonnet.shared.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import pw.kmr.sonnet.shared.data.local.dao.PlaybackProgressDao
import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity
import pw.kmr.sonnet.shared.model.RemoteProgress
import pw.kmr.sonnet.shared.remote.ProgressSyncRemoteDataSource
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SharedSyncCoordinatorTest {

    @Test
    fun `init does not register network callback or trigger sync before start`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val networkMonitor = FakeNetworkMonitor(initialOnline = true)
        val dao = RecordingProgressDao()
        val syncer = ProgressSyncer(NoOpProgressSyncRemoteDataSource, dao)

        val coordinator = SharedSyncCoordinator(networkMonitor, syncer, dispatcher)
        advanceUntilIdle()

        assertEquals(0, networkMonitor.registerCount)
        assertEquals(0, dao.pendingQueries)

        coordinator.dispose()
    }

    @Test
    fun `start registers network callback and triggers initial sync when already online`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val networkMonitor = FakeNetworkMonitor(initialOnline = true)
        val dao = RecordingProgressDao()
        val syncer = ProgressSyncer(NoOpProgressSyncRemoteDataSource, dao)
        val coordinator = SharedSyncCoordinator(networkMonitor, syncer, dispatcher)

        coordinator.start()
        // Idempotent second start must not re-register or duplicate sync.
        coordinator.start()
        advanceUntilIdle()

        assertEquals(1, networkMonitor.registerCount)
        assertEquals(1, dao.pendingQueries)

        networkMonitor.emitNetworkAvailable()
        advanceUntilIdle()
        assertEquals(2, dao.pendingQueries)

        coordinator.dispose()
    }

    @Test
    fun `start while offline defers sync until network becomes available and stop unregisters`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val networkMonitor = FakeNetworkMonitor(initialOnline = false)
        val dao = RecordingProgressDao()
        val syncer = ProgressSyncer(NoOpProgressSyncRemoteDataSource, dao)
        val coordinator = SharedSyncCoordinator(networkMonitor, syncer, dispatcher)

        coordinator.start()
        advanceUntilIdle()
        assertEquals(1, networkMonitor.registerCount)
        assertEquals(0, dao.pendingQueries)

        networkMonitor.emitNetworkAvailable()
        advanceUntilIdle()
        assertEquals(1, dao.pendingQueries)

        coordinator.stop()
        assertEquals(1, networkMonitor.disposeCount)
        networkMonitor.emitNetworkAvailable()
        advanceUntilIdle()
        assertEquals(1, dao.pendingQueries)

        // Can be started again after stop (e.g. re-authentication).
        coordinator.start()
        advanceUntilIdle()
        assertEquals(2, networkMonitor.registerCount)
        assertEquals(2, dao.pendingQueries)

        coordinator.dispose()
        coordinator.start()
        advanceUntilIdle()
        assertEquals(2, networkMonitor.registerCount)
    }
}

private class FakeNetworkMonitor(initialOnline: Boolean) : NetworkMonitor {
    private val _isOnline = MutableStateFlow(initialOnline)
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    var registerCount: Int = 0
        private set
    var disposeCount: Int = 0
        private set
    private var activeCallback: (() -> Unit)? = null

    override fun onNetworkAvailable(callback: () -> Unit) {
        registerCount++
        activeCallback = callback
    }

    override fun dispose() {
        disposeCount++
        activeCallback = null
    }

    fun emitNetworkAvailable() {
        _isOnline.value = true
        activeCallback?.invoke()
    }
}

private class RecordingProgressDao : PlaybackProgressDao {
    var pendingQueries: Int = 0
        private set

    override suspend fun playbackProgress(bookId: String): PlaybackProgressEntity? = null

    override suspend fun pendingPlaybackProgress(): List<PlaybackProgressEntity> {
        pendingQueries++
        return emptyList()
    }

    override suspend fun upsertPlaybackProgress(progress: PlaybackProgressEntity) = Unit

    override suspend fun markPlaybackProgressSynced(bookId: String, updatedAtEpochMillis: Long) = Unit

    override suspend fun markBookCompletion(
        bookId: String,
        isCompleted: Boolean,
        positionMillis: Long,
        durationMillis: Long?,
        updatedAtEpochMillis: Long,
        pendingSync: Boolean
    ) = Unit
}

private object NoOpProgressSyncRemoteDataSource : ProgressSyncRemoteDataSource {
    override suspend fun progress(bookId: String): RemoteProgress = RemoteProgress(
        chapterId = null,
        offsetMillis = 0L,
        updatedAtEpochMillis = 0L,
        isCompleted = false
    )

    override suspend fun updateProgress(
        bookId: String,
        chapterId: String,
        offsetMs: Long,
        updatedAtEpochMillis: Long
    ) = Unit

    override suspend fun markComplete(bookId: String) = Unit

    override suspend fun markIncomplete(bookId: String) = Unit
}
