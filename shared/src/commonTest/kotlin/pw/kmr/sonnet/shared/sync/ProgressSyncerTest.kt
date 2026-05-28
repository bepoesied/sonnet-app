package pw.kmr.sonnet.shared.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import pw.kmr.sonnet.shared.data.local.dao.PlaybackProgressDao
import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity
import pw.kmr.sonnet.shared.model.RemoteProgress
import pw.kmr.sonnet.shared.remote.ProgressSyncRemoteDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressSyncerTest {

    @Test
    fun `sync pending marks uploaded progress as synced`() = runTest {
        val dao = FakePlaybackProgressDao(
            pending = mutableListOf(
                progress(bookId = "book-1", chapterId = "chapter-1", updatedAtEpochMillis = 100L),
                progress(bookId = "book-2", chapterId = "chapter-2", updatedAtEpochMillis = 200L)
            )
        )
        val remote = FakeProgressSyncRemoteDataSource()
        val syncer = ProgressSyncer(remote, dao)

        syncer.syncPending()

        assertEquals(
            listOf(
                "update:book-1:chapter-1:100",
                "update:book-2:chapter-2:200"
            ),
            remote.operations
        )
        assertEquals(
            listOf("book-1:100", "book-2:200"),
            dao.synced
        )
    }

    @Test
    fun `sync pending stops after first failure and leaves later progress pending`() = runTest {
        val first = progress(bookId = "book-1", chapterId = "chapter-1", updatedAtEpochMillis = 100L)
        val second = progress(bookId = "book-2", chapterId = "chapter-2", updatedAtEpochMillis = 200L)
        val dao = FakePlaybackProgressDao(pending = mutableListOf(first, second))
        val remote = FakeProgressSyncRemoteDataSource(failBookIds = setOf("book-1"))
        val syncer = ProgressSyncer(remote, dao)

        syncer.syncPending()

        assertEquals(listOf("update:book-1:chapter-1:100"), remote.operations)
        assertTrue(dao.synced.isEmpty())
        assertEquals(listOf(first, second), dao.pendingPlaybackProgress())
    }

    @Test
    fun `sync book marks completed progress complete`() = runTest {
        val dao = FakePlaybackProgressDao(
            playbackProgressByBookId = mutableMapOf(
                "book-1" to progress(bookId = "book-1", chapterId = "chapter-1", isCompleted = true, updatedAtEpochMillis = 100L)
            )
        )
        val remote = FakeProgressSyncRemoteDataSource()
        val syncer = ProgressSyncer(remote, dao)

        syncer.syncBook("book-1")

        assertEquals(listOf("complete:book-1"), remote.operations)
        assertEquals(listOf("book-1:100"), dao.synced)
    }

    @Test
    fun `sync book marks null chapter progress incomplete`() = runTest {
        val dao = FakePlaybackProgressDao(
            playbackProgressByBookId = mutableMapOf(
                "book-1" to progress(bookId = "book-1", chapterId = null, updatedAtEpochMillis = 100L)
            )
        )
        val remote = FakeProgressSyncRemoteDataSource()
        val syncer = ProgressSyncer(remote, dao)

        syncer.syncBook("book-1")

        assertEquals(listOf("incomplete:book-1"), remote.operations)
        assertEquals(listOf("book-1:100"), dao.synced)
    }

    @Test
    fun `sync calls are single flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val dao = FakePlaybackProgressDao(
            pending = mutableListOf(progress(bookId = "book-1", chapterId = "chapter-1", updatedAtEpochMillis = 100L))
        )
        val remote = FakeProgressSyncRemoteDataSource(onUpdateProgress = { _, _, _, _ -> gate.await() })
        val syncer = ProgressSyncer(remote, dao)

        val first = async { syncer.syncPending() }
        remote.firstOperationStarted.await()
        val second = async { syncer.syncPending() }
        val third = async { syncer.syncBook("book-1") }
        gate.complete(Unit)
        awaitAll(first, second, third)

        assertEquals(listOf("update:book-1:chapter-1:100"), remote.operations)
        assertEquals(listOf("book-1:100"), dao.synced)
    }

    @Test
    fun `remote progress returns null on failure`() = runTest {
        val remote = FakeProgressSyncRemoteDataSource(progressException = IllegalStateException("boom"))
        val syncer = ProgressSyncer(remote, FakePlaybackProgressDao())

        assertNull(syncer.remoteProgress("book-1"))
    }

    private fun progress(
        bookId: String,
        chapterId: String?,
        updatedAtEpochMillis: Long,
        isCompleted: Boolean = false,
        pendingSync: Boolean = true
    ) = PlaybackProgressEntity(
        libraryItemId = bookId,
        chapterId = chapterId,
        chapterOffsetMillis = 42L,
        positionMillis = 420L,
        durationMillis = 1_000L,
        updatedAtEpochMillis = updatedAtEpochMillis,
        isCompleted = isCompleted,
        pendingSync = pendingSync
    )
}

private class FakePlaybackProgressDao(
    private val pending: MutableList<PlaybackProgressEntity> = mutableListOf(),
    private val playbackProgressByBookId: MutableMap<String, PlaybackProgressEntity> = pending.associateByTo(mutableMapOf()) { it.libraryItemId }
) : PlaybackProgressDao {
    val synced = mutableListOf<String>()

    override suspend fun playbackProgress(bookId: String): PlaybackProgressEntity? = playbackProgressByBookId[bookId]

    override suspend fun pendingPlaybackProgress(): List<PlaybackProgressEntity> = pending.toList()

    override suspend fun markPlaybackProgressSynced(bookId: String, updatedAtEpochMillis: Long) {
        synced += "$bookId:$updatedAtEpochMillis"
        pending.removeAll { it.libraryItemId == bookId && it.updatedAtEpochMillis == updatedAtEpochMillis }
        playbackProgressByBookId[bookId]?.let { existing ->
            if (existing.updatedAtEpochMillis == updatedAtEpochMillis) {
                playbackProgressByBookId[bookId] = existing.copy(pendingSync = false)
            }
        }
    }
}

private class FakeProgressSyncRemoteDataSource(
    private val failBookIds: Set<String> = emptySet(),
    private val progressException: Exception? = null,
    private val onUpdateProgress: suspend (String, String, Long, Long) -> Unit = { _, _, _, _ -> }
) : ProgressSyncRemoteDataSource {
    val operations = mutableListOf<String>()
    val firstOperationStarted = CompletableDeferred<Unit>()

    override suspend fun progress(bookId: String): RemoteProgress {
        throw progressException ?: error("Unexpected progress lookup for $bookId")
    }

    override suspend fun updateProgress(bookId: String, chapterId: String, offsetMs: Long, updatedAtEpochMillis: Long) {
        firstOperationStarted.complete(Unit)
        operations += "update:$bookId:$chapterId:$updatedAtEpochMillis"
        if (bookId in failBookIds) throw IllegalStateException("sync failed")
        onUpdateProgress(bookId, chapterId, offsetMs, updatedAtEpochMillis)
    }

    override suspend fun markComplete(bookId: String) {
        operations += "complete:$bookId"
        if (bookId in failBookIds) throw IllegalStateException("sync failed")
    }

    override suspend fun markIncomplete(bookId: String) {
        operations += "incomplete:$bookId"
        if (bookId in failBookIds) throw IllegalStateException("sync failed")
    }
}