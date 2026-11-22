package pw.kmr.sonnet.shared.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import pw.kmr.sonnet.shared.auth.AuthRequiredException
import pw.kmr.sonnet.shared.data.local.dao.PlaybackProgressDao
import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity
import pw.kmr.sonnet.shared.model.RemoteProgress
import pw.kmr.sonnet.shared.remote.ApiException
import pw.kmr.sonnet.shared.remote.ProgressSyncRemoteDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

        val result = syncer.syncPending()

        assertEquals(2, result.syncedCount)
        assertFalse(result.hasTransientFailures)
        assertFalse(result.authFailed)
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
    fun `permanent 404 and 422 errors clear pendingSync and do not block syncing remaining books`() = runTest {
        val deletedBook = progress(bookId = "book-404", chapterId = "ch-1", updatedAtEpochMillis = 100L)
        val invalidChapter = progress(bookId = "book-422", chapterId = "bad-ch", updatedAtEpochMillis = 200L)
        val healthyBook = progress(bookId = "book-ok", chapterId = "ch-2", updatedAtEpochMillis = 300L)
        val dao = FakePlaybackProgressDao(pending = mutableListOf(deletedBook, invalidChapter, healthyBook))
        val remote = FakeProgressSyncRemoteDataSource(
            errorsByBookId = mapOf(
                "book-404" to ApiException(404, "not found"),
                "book-422" to ApiException(422, "invalid chapter")
            )
        )
        var scheduledCount = 0
        val syncer = ProgressSyncer(remote, dao, BackgroundSyncScheduler { scheduledCount++ })

        val result = syncer.syncPending()

        assertEquals(1, result.syncedCount)
        assertFalse(result.hasTransientFailures)
        assertFalse(result.authFailed)
        assertEquals(0, scheduledCount)
        assertEquals(
            listOf(
                "update:book-404:ch-1:100",
                "update:book-422:bad-ch:200",
                "update:book-ok:ch-2:300"
            ),
            remote.operations
        )
        assertTrue(dao.pendingPlaybackProgress().isEmpty())
        assertEquals(
            listOf("book-404:100", "book-422:200", "book-ok:300"),
            dao.synced
        )
    }

    @Test
    fun `transient 500 or network error keeps pendingSync true, continues remaining books, and reports retry needed`() = runTest {
        val failingBook = progress(bookId = "book-1", chapterId = "ch-1", updatedAtEpochMillis = 100L)
        val healthyBook = progress(bookId = "book-2", chapterId = "ch-2", updatedAtEpochMillis = 200L)
        val dao = FakePlaybackProgressDao(pending = mutableListOf(failingBook, healthyBook))
        val remote = FakeProgressSyncRemoteDataSource(
            errorsByBookId = mapOf("book-1" to ApiException(500, "server error"))
        )
        var scheduledCount = 0
        val syncer = ProgressSyncer(remote, dao, BackgroundSyncScheduler { scheduledCount++ })

        val result = syncer.syncPending()

        assertEquals(1, result.syncedCount)
        assertTrue(result.hasTransientFailures)
        assertFalse(result.authFailed)
        assertEquals(1, scheduledCount)
        assertEquals(
            listOf("update:book-1:ch-1:100", "update:book-2:ch-2:200"),
            remote.operations
        )
        assertEquals(listOf("book-2:200"), dao.synced)
        assertEquals(listOf(failingBook), dao.pendingPlaybackProgress())
    }

    @Test
    fun `auth failures stop pass immediately and leave pending items untouched`() = runTest {
        val authErrors = listOf(
            ApiException(401, "unauthorized"),
            ApiException(403, "forbidden"),
            AuthRequiredException("Sign in again")
        )
        for (error in authErrors) {
            val first = progress(bookId = "book-1", chapterId = "ch-1", updatedAtEpochMillis = 100L)
            val second = progress(bookId = "book-2", chapterId = "ch-2", updatedAtEpochMillis = 200L)
            val dao = FakePlaybackProgressDao(pending = mutableListOf(first, second))
            val remote = FakeProgressSyncRemoteDataSource(errorsByBookId = mapOf("book-1" to error))
            var scheduledCount = 0
            val syncer = ProgressSyncer(remote, dao, BackgroundSyncScheduler { scheduledCount++ })

            val result = syncer.syncPending()

            assertTrue(result.authFailed)
            assertEquals(0, result.syncedCount)
            assertEquals(0, scheduledCount)
            assertEquals(listOf("update:book-1:ch-1:100"), remote.operations)
            assertTrue(dao.synced.isEmpty())
            assertEquals(listOf(first, second), dao.pendingPlaybackProgress())
        }
    }

    @Test
    fun `concurrent syncPending and syncBook does not drop progress written during in-flight sync`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val dao = FakePlaybackProgressDao(
            pending = mutableListOf(progress(bookId = "book-1", chapterId = "chapter-1", updatedAtEpochMillis = 100L))
        )
        val remote = FakeProgressSyncRemoteDataSource(
            onUpdateProgress = { _, _, _, updatedAt ->
                if (updatedAt == 100L) {
                    gate.await()
                }
            }
        )
        val syncer = ProgressSyncer(remote, dao)

        val first = async { syncer.syncPending() }
        remote.firstOperationStarted.await()

        // While book-1@100 is uploading, user advances to chapter-2@200 and requests syncBook.
        dao.upsertPlaybackProgress(
            progress(bookId = "book-1", chapterId = "chapter-2", updatedAtEpochMillis = 200L)
        )
        val second = async { syncer.syncBook("book-1") }
        gate.complete(Unit)
        awaitAll(first, second)

        assertEquals(
            listOf(
                "update:book-1:chapter-1:100",
                "update:book-1:chapter-2:200"
            ),
            remote.operations
        )
        assertTrue(dao.pendingPlaybackProgress().isEmpty())
        assertEquals(false, dao.playbackProgress("book-1")?.pendingSync)
    }

    @Test
    fun `cancellation exception propagates from syncPending and remoteProgress`() = runTest {
        val dao = FakePlaybackProgressDao(
            pending = mutableListOf(progress(bookId = "book-1", chapterId = "ch-1", updatedAtEpochMillis = 100L))
        )
        val remote = FakeProgressSyncRemoteDataSource(
            errorsByBookId = mapOf("book-1" to CancellationException("cancelled")),
            progressException = CancellationException("cancelled")
        )
        val syncer = ProgressSyncer(remote, dao)

        assertFailsWith<CancellationException> {
            syncer.syncPending()
        }
        assertFailsWith<CancellationException> {
            syncer.remoteProgress("book-1")
        }
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

        val result = syncer.syncBook("book-1")

        assertEquals(1, result.syncedCount)
        assertFalse(result.hasTransientFailures)
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

        val result = syncer.syncBook("book-1")

        assertEquals(1, result.syncedCount)
        assertFalse(result.hasTransientFailures)
        assertEquals(listOf("incomplete:book-1"), remote.operations)
        assertEquals(listOf("book-1:100"), dao.synced)
    }

    @Test
    fun `sync calls coalesce when no new progress was written`() = runTest {
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

    override suspend fun upsertPlaybackProgress(progress: PlaybackProgressEntity) {
        playbackProgressByBookId[progress.libraryItemId] = progress
        pending.removeAll { it.libraryItemId == progress.libraryItemId }
        if (progress.pendingSync) {
            pending += progress
        }
    }

    override suspend fun markPlaybackProgressSynced(bookId: String, updatedAtEpochMillis: Long) {
        synced += "$bookId:$updatedAtEpochMillis"
        pending.removeAll { it.libraryItemId == bookId && it.updatedAtEpochMillis == updatedAtEpochMillis }
        playbackProgressByBookId[bookId]?.let { existing ->
            if (existing.updatedAtEpochMillis == updatedAtEpochMillis) {
                playbackProgressByBookId[bookId] = existing.copy(pendingSync = false)
            }
        }
    }

    override suspend fun markBookCompletion(
        bookId: String,
        isCompleted: Boolean,
        positionMillis: Long,
        durationMillis: Long?,
        updatedAtEpochMillis: Long,
        pendingSync: Boolean
    ) {
        upsertPlaybackProgress(
            PlaybackProgressEntity(
                libraryItemId = bookId,
                positionMillis = positionMillis,
                durationMillis = durationMillis,
                updatedAtEpochMillis = updatedAtEpochMillis,
                isCompleted = isCompleted,
                pendingSync = pendingSync
            )
        )
    }
}

private class FakeProgressSyncRemoteDataSource(
    private val errorsByBookId: Map<String, Exception> = emptyMap(),
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
        errorsByBookId[bookId]?.let { throw it }
        onUpdateProgress(bookId, chapterId, offsetMs, updatedAtEpochMillis)
    }

    override suspend fun markComplete(bookId: String) {
        operations += "complete:$bookId"
        errorsByBookId[bookId]?.let { throw it }
    }

    override suspend fun markIncomplete(bookId: String) {
        operations += "incomplete:$bookId"
        errorsByBookId[bookId]?.let { throw it }
    }
}
