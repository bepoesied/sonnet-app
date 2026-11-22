package pw.kmr.sonnet.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pw.kmr.sonnet.shared.data.local.dao.PlaybackProgressDao
import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity
import pw.kmr.sonnet.shared.model.AppSettings
import pw.kmr.sonnet.shared.model.DownloadedBook
import pw.kmr.sonnet.shared.model.DownloadedChapter
import pw.kmr.sonnet.shared.model.RemoteProgress
import pw.kmr.sonnet.shared.remote.ProgressSyncRemoteDataSource
import pw.kmr.sonnet.shared.sync.BackgroundSyncScheduler
import pw.kmr.sonnet.shared.sync.ProgressSyncer

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackOrchestratorTest {

    private val sampleBook = DownloadedBook(
        id = "book-1",
        title = "The Odyssey",
        author = "Homer",
        coverFilePath = "/tmp/cover.jpg",
        chapters = listOf(
            DownloadedChapter("ch-1", "Chapter 1", 1, "/tmp/ch1.mp3", durationMs = 60_000L),
            DownloadedChapter("ch-2", "Chapter 2", 2, "/tmp/ch2.mp3", durationMs = 60_000L)
        )
    )

    @Test
    fun `does not connect eagerly in init and reconnects cleanly after stopAndReset`() = runTest {
        val fixture = createFixture(books = mapOf(sampleBook.id to sampleBook))

        // Lazy connect: engine must NOT connect during init.
        assertEquals(0, fixture.engine.connectCalls)
        assertEquals(PlaybackConnectionState.DISCONNECTED, fixture.engine.connectionState.value)

        // First session: load, play, then stopAndReset (simulating logout).
        fixture.orchestrator.load(sampleBook.id)
        runCurrent()
        assertEquals(1, fixture.engine.connectCalls)
        assertEquals(1, fixture.engine.listenerCount)

        fixture.orchestrator.playPause()
        runCurrent()
        assertTrue(fixture.engine.isPlaying())

        fixture.orchestrator.stopAndReset()
        runCurrent()
        assertFalse(fixture.engine.isPlaying())
        assertEquals(1, fixture.engine.releaseCalls)
        assertEquals(0, fixture.engine.listenerCount)
        assertEquals(PlayerUiState(), fixture.orchestrator.state.value)

        // Idempotent second stopAndReset must not double-release.
        fixture.orchestrator.stopAndReset()
        runCurrent()
        assertEquals(1, fixture.engine.releaseCalls)

        // Re-login: load + playPause must reconnect, re-attach listener, and play cleanly.
        fixture.orchestrator.load(sampleBook.id)
        runCurrent()
        assertEquals(2, fixture.engine.connectCalls)
        assertEquals(1, fixture.engine.listenerCount)
        assertTrue(fixture.orchestrator.state.value.canPlay)

        fixture.orchestrator.playPause()
        runCurrent()
        assertTrue(fixture.engine.isPlaying())
        assertTrue(fixture.orchestrator.state.value.isPlaying)

        fixture.orchestrator.stopAndReset()
        runCurrent()
    }

    @Test
    fun `playPause starts playback immediately without waiting for slow or failing remoteProgress`() = runTest {
        val remoteGate = CompletableDeferred<RemoteProgress?>()
        val fixture = createFixture(
            books = mapOf(sampleBook.id to sampleBook),
            onRemoteProgress = { remoteGate.await() }
        )

        fixture.orchestrator.load(sampleBook.id)
        // Do NOT complete remoteGate — simulate a 60s stalled server while offline.
        fixture.orchestrator.playPause()

        assertTrue(fixture.engine.isPlaying())
        assertEquals(1, fixture.engine.playCalls)
        assertTrue(fixture.orchestrator.state.value.isPlaying)

        // Remote check times out cleanly after remoteCheckTimeoutMs without interrupting playback.
        advanceTimeBy(2_100L)
        runCurrent()
        assertTrue(fixture.engine.isPlaying())
        assertFalse(fixture.orchestrator.state.value.isCheckingRemoteState)
        assertNull(fixture.orchestrator.state.value.resumePrompt)

        fixture.orchestrator.stopAndReset()
        runCurrent()
    }

    @Test
    fun `resolving resume prompt resumes playback automatically when user was trying to play`() = runTest {
        val remoteGate = CompletableDeferred<RemoteProgress?>()
        val fixture = createFixture(
            books = mapOf(sampleBook.id to sampleBook),
            initialProgress = mapOf(
                sampleBook.id to PlaybackProgressEntity(
                    libraryItemId = sampleBook.id,
                    chapterId = "ch-1",
                    chapterOffsetMillis = 5_000L,
                    positionMillis = 5_000L,
                    durationMillis = 120_000L,
                    updatedAtEpochMillis = 1_000L,
                    isCompleted = false,
                    pendingSync = false
                )
            ),
            onRemoteProgress = { remoteGate.await() }
        )

        fixture.orchestrator.load(sampleBook.id)
        fixture.orchestrator.playPause()
        runCurrent()
        assertTrue(fixture.engine.isPlaying())

        // Remote check returns newer progress 45s into Chapter 1.
        remoteGate.complete(
            RemoteProgress(
                chapterId = "ch-1",
                offsetMillis = 45_000L,
                updatedAtEpochMillis = 5_000L,
                isCompleted = false
            )
        )
        runCurrent()

        val prompt = fixture.orchestrator.state.value.resumePrompt
        assertNotNull(prompt)
        assertEquals(45_000L, prompt.remotePositionMs)
        assertFalse(fixture.engine.isPlaying())
        // Pausing to show prompt must NOT overwrite server progress with local 5s position.
        assertTrue(fixture.remote.updateCalls.isEmpty())

        // User chooses remote progress -> seeks and immediately resumes playback.
        fixture.orchestrator.useRemoteProgress()
        runCurrent()

        assertNull(fixture.orchestrator.state.value.resumePrompt)
        assertEquals(45_000L, fixture.engine.currentPositionMs())
        assertTrue(fixture.engine.isPlaying())
        val saved = fixture.dao.playbackProgress(sampleBook.id)
        assertNotNull(saved)
        assertEquals(45_000L, saved.positionMillis)
        assertFalse(saved.pendingSync)

        fixture.orchestrator.stopAndReset()
        runCurrent()
    }

    @Test
    fun `remote completed progress surfaces completion prompt and marks complete locally`() = runTest {
        val fixture = createFixture(
            books = mapOf(sampleBook.id to sampleBook),
            onRemoteProgress = {
                RemoteProgress(
                    chapterId = null,
                    offsetMillis = 0L,
                    updatedAtEpochMillis = 9_000L,
                    isCompleted = true
                )
            }
        )

        fixture.orchestrator.load(sampleBook.id)
        runCurrent()

        val prompt = fixture.orchestrator.state.value.resumePrompt
        assertNotNull(prompt)
        assertTrue(prompt.remoteIsCompleted)

        fixture.orchestrator.useRemoteProgress()
        runCurrent()

        assertNull(fixture.orchestrator.state.value.resumePrompt)
        assertFalse(fixture.engine.isPlaying())
        val saved = fixture.dao.playbackProgress(sampleBook.id)
        assertNotNull(saved)
        assertTrue(saved.isCompleted)
        assertFalse(saved.pendingSync)
        assertEquals(1, fixture.dao.completionTransactionCalls)

        fixture.orchestrator.stopAndReset()
        runCurrent()
    }

    @Test
    fun `external playback start and pause via engine listener persists progress`() = runTest {
        val fixture = createFixture(books = mapOf(sampleBook.id to sampleBook))

        fixture.orchestrator.load(sampleBook.id)
        runCurrent()

        // Simulate lock-screen / Bluetooth headset starting playback directly on the engine.
        fixture.engine.simulateExternalPlay(positionMs = 12_000L)
        advanceTimeBy(400L)
        runCurrent()

        val playingProgress = fixture.dao.playbackProgress(sampleBook.id)
        assertNotNull(playingProgress)
        assertEquals(12_000L, playingProgress.positionMillis)
        assertEquals("ch-1", playingProgress.chapterId)

        // Advance and pause externally via headset.
        fixture.engine.simulateExternalPause(positionMs = 27_000L)
        runCurrent()

        val pausedProgress = fixture.dao.playbackProgress(sampleBook.id)
        assertNotNull(pausedProgress)
        assertEquals(27_000L, pausedProgress.positionMillis)
        assertEquals(listOf("update:book-1:ch-1:27000"), fixture.remote.updateCalls)

        fixture.orchestrator.stopAndReset()
        runCurrent()
    }

    @Test
    fun `debounced save reads latest position and cannot overwrite markBookComplete`() = runTest {
        val fixture = createFixture(books = mapOf(sampleBook.id to sampleBook))

        fixture.orchestrator.load(sampleBook.id)
        runCurrent()

        // Start playback at 10s, then quickly advance position to 25s before 300ms debounce expires.
        fixture.engine.simulateExternalPlay(positionMs = 10_000L)
        advanceTimeBy(100L)
        fixture.engine.setPosition(chapterIndex = 0, positionMs = 25_000L)
        advanceTimeBy(250L)
        runCurrent()

        val debounced = fixture.dao.playbackProgress(sampleBook.id)
        assertNotNull(debounced)
        assertEquals(25_000L, debounced.positionMillis)
        assertFalse(debounced.isCompleted)

        // Trigger another debounced save, then immediately finish the book before debounce fires.
        fixture.engine.simulateExternalPlay(positionMs = 59_000L)
        advanceTimeBy(50L)
        fixture.engine.simulatePlaybackEnded()
        advanceTimeBy(500L)
        runCurrent()

        val completed = fixture.dao.playbackProgress(sampleBook.id)
        assertNotNull(completed)
        assertTrue(completed.isCompleted)
        assertEquals(120_000L, completed.positionMillis)
        assertEquals(1, fixture.dao.completionTransactionCalls)
        assertEquals(listOf("complete:book-1"), fixture.remote.completeCalls)

        fixture.orchestrator.stopAndReset()
        runCurrent()
    }

    @Test
    fun `async seekForward persists post-seek position on discontinuity rather than pre-seek position`() = runTest {
        val fixture = createFixture(books = mapOf(sampleBook.id to sampleBook))

        fixture.orchestrator.load(sampleBook.id)
        runCurrent()
        fixture.engine.setPosition(0, 15_000L)
        fixture.engine.deferSeek = true

        fixture.orchestrator.seekForward()
        advanceTimeBy(400L)
        runCurrent()

        // Pre-seek position must NOT have been persisted before discontinuity fires.
        assertNull(fixture.dao.playbackProgress(sampleBook.id))

        // Engine finishes async seek to 25s and reports position discontinuity.
        fixture.engine.completeDeferredSeek(chapterIndex = 0, positionMs = 25_000L)
        runCurrent()

        val saved = fixture.dao.playbackProgress(sampleBook.id)
        assertNotNull(saved)
        assertEquals(25_000L, saved.positionMillis)

        fixture.orchestrator.stopAndReset()
        runCurrent()
    }

    @Test
    fun `load with missing book exposes bookMissing and errorMessage instead of hanging`() = runTest {
        val fixture = createFixture(books = emptyMap())

        fixture.orchestrator.load("missing-book")
        runCurrent()

        val state = fixture.orchestrator.state.value
        assertTrue(state.bookMissing)
        assertFalse(state.canPlay)
        assertNull(state.bookId)
        assertNotNull(state.errorMessage)

        fixture.orchestrator.stopAndReset()
        runCurrent()
    }

    @Test
    fun `sleep timer counts down using monotonic elapsed playback time and pauses on expiry`() = runTest {
        val fixture = createFixture(books = mapOf(sampleBook.id to sampleBook))

        fixture.orchestrator.load(sampleBook.id)
        runCurrent()

        // Set 3s countdown while paused -> must NOT count down while paused.
        fixture.orchestrator.setSleepTimer(SleepTimerState.Countdown(3_000L))
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(SleepTimerState.Countdown(3_000L), fixture.orchestrator.state.value.sleepTimer)

        // Start playback. Keep book position completely static (e.g. simulating inaccurate durations
        // or different playback speed) to prove countdown uses monotonic elapsed time, not position deltas.
        fixture.orchestrator.playPause()
        runCurrent()
        assertTrue(fixture.engine.isPlaying())

        val initialChaptersRef = fixture.orchestrator.state.value.chapters

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(SleepTimerState.Countdown(2_000L), fixture.orchestrator.state.value.sleepTimer)
        // Chapters list must be cached and not re-allocated on ticks.
        assertSame(initialChaptersRef, fixture.orchestrator.state.value.chapters)

        // Pause for 10s -> remaining time stays at 2s.
        fixture.orchestrator.playPause()
        runCurrent()
        assertFalse(fixture.engine.isPlaying())
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(SleepTimerState.Countdown(2_000L), fixture.orchestrator.state.value.sleepTimer)

        // Resume and let remaining 2s elapse -> engine pauses and timer turns off.
        fixture.orchestrator.playPause()
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()

        assertFalse(fixture.engine.isPlaying())
        assertEquals(SleepTimerState.Off, fixture.orchestrator.state.value.sleepTimer)

        fixture.orchestrator.stopAndReset()
        runCurrent()
    }

    @Test
    fun `stopIfLoaded stops playback only for matching book and progress writes schedule background sync`() = runTest {
        var syncScheduledCount = 0
        val fixture = createFixture(
            books = mapOf(sampleBook.id to sampleBook),
            backgroundSyncScheduler = BackgroundSyncScheduler { syncScheduledCount++ }
        )

        fixture.orchestrator.load(sampleBook.id)
        runCurrent()
        fixture.engine.simulateExternalPlay(positionMs = 15_000L)
        advanceTimeBy(400L)
        runCurrent()

        assertTrue(syncScheduledCount >= 1)
        assertTrue(fixture.engine.isPlaying())

        // Unrelated book ID must not interrupt active playback.
        fixture.orchestrator.stopIfLoaded("other-book")
        assertTrue(fixture.engine.isPlaying())
        assertEquals(sampleBook.id, fixture.orchestrator.state.value.bookId)

        // Matching book ID stops and releases immediately before file deletion.
        fixture.orchestrator.stopIfLoaded(sampleBook.id)
        assertFalse(fixture.engine.isPlaying())
        assertNull(fixture.orchestrator.state.value.bookId)
        assertEquals(1, fixture.engine.releaseCalls)
    }

    @Test
    fun `periodic progress sync honors playbackSyncCadenceSeconds from settings`() = runTest {
        val settingsFlow = MutableStateFlow(AppSettings(playbackSyncCadenceSeconds = 10L))
        val fixture = createFixture(
            books = mapOf(sampleBook.id to sampleBook),
            settings = settingsFlow
        )

        fixture.orchestrator.load(sampleBook.id)
        runCurrent()
        fixture.orchestrator.playPause()
        runCurrent()

        // After 6s: periodic local save at 5s (+300ms debounce) has written pending progress,
        // but the 10s sync cadence has not elapsed yet.
        advanceTimeBy(6_000L)
        runCurrent()
        assertNotNull(fixture.dao.playbackProgress(sampleBook.id))
        assertTrue(fixture.remote.updateCalls.isEmpty())

        // Advancing past 10s triggers periodic sync using the configured cadence.
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(1, fixture.remote.updateCalls.size)

        fixture.orchestrator.stopAndReset()
        runCurrent()
    }

    private fun TestScope.createFixture(
        books: Map<String, DownloadedBook>,
        initialProgress: Map<String, PlaybackProgressEntity> = emptyMap(),
        onRemoteProgress: suspend (String) -> RemoteProgress? = { null },
        backgroundSyncScheduler: BackgroundSyncScheduler = BackgroundSyncScheduler.NoOp,
        settings: Flow<AppSettings> = flowOf(AppSettings())
    ): TestFixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakePlaybackEngine()
        val repo = FakePlaybackBookRepository(books)
        val dao = FakeOrchestratorProgressDao(initialProgress.toMutableMap())
        val remote = FakeOrchestratorRemoteDataSource(onRemoteProgress)
        val syncer = ProgressSyncer(remote, dao, backgroundSyncScheduler)
        val orchestrator = PlaybackOrchestrator(
            engine = engine,
            libraryRepository = repo,
            libraryDao = dao,
            progressSyncer = syncer,
            settings = settings,
            mainDispatcher = dispatcher,
            workDispatcher = dispatcher,
            nowMillis = { 10_000L + testScheduler.currentTime },
            monotonicMillis = { testScheduler.currentTime }
        )
        return TestFixture(orchestrator, engine, dao, remote)
    }

    private data class TestFixture(
        val orchestrator: PlaybackOrchestrator,
        val engine: FakePlaybackEngine,
        val dao: FakeOrchestratorProgressDao,
        val remote: FakeOrchestratorRemoteDataSource
    )
}

private class FakePlaybackBookRepository(
    private val books: Map<String, DownloadedBook>
) : PlaybackBookRepository {
    override suspend fun prepareBookForPlayback(bookId: String) = Unit

    override suspend fun downloadedBook(bookId: String): DownloadedBook? = books[bookId]
}

private class FakeOrchestratorProgressDao(
    private val store: MutableMap<String, PlaybackProgressEntity> = mutableMapOf()
) : PlaybackProgressDao {
    var completionTransactionCalls = 0

    override suspend fun playbackProgress(bookId: String): PlaybackProgressEntity? = store[bookId]

    override suspend fun pendingPlaybackProgress(): List<PlaybackProgressEntity> =
        store.values.filter { it.pendingSync }.sortedBy { it.updatedAtEpochMillis }

    override suspend fun upsertPlaybackProgress(progress: PlaybackProgressEntity) {
        store[progress.libraryItemId] = progress
    }

    override suspend fun markPlaybackProgressSynced(bookId: String, updatedAtEpochMillis: Long) {
        val current = store[bookId] ?: return
        if (current.updatedAtEpochMillis == updatedAtEpochMillis) {
            store[bookId] = current.copy(pendingSync = false)
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
        completionTransactionCalls++
        store[bookId] = PlaybackProgressEntity(
            libraryItemId = bookId,
            positionMillis = positionMillis,
            durationMillis = durationMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
            isCompleted = isCompleted,
            pendingSync = pendingSync
        )
    }
}

private class FakeOrchestratorRemoteDataSource(
    private val onProgress: suspend (String) -> RemoteProgress?
) : ProgressSyncRemoteDataSource {
    val updateCalls = mutableListOf<String>()
    val completeCalls = mutableListOf<String>()

    override suspend fun progress(bookId: String): RemoteProgress =
        onProgress(bookId) ?: RemoteProgress(
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
    ) {
        updateCalls += "update:$bookId:$chapterId:$offsetMs"
    }

    override suspend fun markComplete(bookId: String) {
        completeCalls += "complete:$bookId"
    }

    override suspend fun markIncomplete(bookId: String) = Unit
}

private class FakePlaybackEngine : PlaybackEngine {
    private val _connectionState = MutableStateFlow(PlaybackConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<PlaybackConnectionState> = _connectionState.asStateFlow()

    private val listeners = mutableListOf<PlaybackEngineListener>()
    private var mediaItems: List<MediaItemDescriptor> = emptyList()
    private var chapterIndex = 0
    private var positionMs = 0L
    private var playing = false

    var connectCalls = 0
    var releaseCalls = 0
    var playCalls = 0
    var deferSeek = false

    val listenerCount: Int
        get() = listeners.size

    override suspend fun connect() {
        connectCalls++
        _connectionState.value = PlaybackConnectionState.CONNECTED
    }

    override fun release() {
        releaseCalls++
        playing = false
        mediaItems = emptyList()
        chapterIndex = 0
        positionMs = 0L
        _connectionState.value = PlaybackConnectionState.DISCONNECTED
    }

    override fun loadMedia(items: List<MediaItemDescriptor>, startIndex: Int, startPositionMs: Long) {
        mediaItems = items
        chapterIndex = startIndex
        positionMs = startPositionMs
    }

    override fun prepare() = Unit

    override fun play() {
        playCalls++
        playing = true
        listeners.toList().forEach { it.onPlaybackStateChanged(isPlaying = true, playbackState = STATE_READY) }
    }

    override fun pause() {
        val wasPlaying = playing
        playing = false
        if (wasPlaying) {
            listeners.toList().forEach { it.onPlaybackStateChanged(isPlaying = false, playbackState = STATE_READY) }
        }
    }

    override fun seekBack() {
        if (!deferSeek) {
            positionMs = (positionMs - SEEK_INCREMENT_MS).coerceAtLeast(0L)
            listeners.toList().forEach { it.onPositionDiscontinuity() }
        }
    }

    override fun seekForward() {
        if (!deferSeek) {
            positionMs += SEEK_INCREMENT_MS
            listeners.toList().forEach { it.onPositionDiscontinuity() }
        }
    }

    override fun seekToChapter(chapterIndex: Int, positionMs: Long) {
        this.chapterIndex = chapterIndex
        this.positionMs = positionMs
        listeners.toList().forEach { it.onPositionDiscontinuity() }
    }

    override fun currentPositionMs(): Long = positionMs

    override fun currentMediaItemIndex(): Int = chapterIndex

    override fun isPlaying(): Boolean = playing

    override fun mediaItemCount(): Int = mediaItems.size

    override fun addListener(listener: PlaybackEngineListener) {
        if (listener !in listeners) listeners += listener
    }

    override fun removeListener(listener: PlaybackEngineListener) {
        listeners.remove(listener)
    }

    fun setPosition(chapterIndex: Int, positionMs: Long) {
        this.chapterIndex = chapterIndex
        this.positionMs = positionMs
    }

    fun simulateExternalPlay(positionMs: Long) {
        this.positionMs = positionMs
        playing = true
        listeners.toList().forEach { it.onPlaybackStateChanged(isPlaying = true, playbackState = STATE_READY) }
    }

    fun simulateExternalPause(positionMs: Long) {
        this.positionMs = positionMs
        playing = false
        listeners.toList().forEach { it.onPlaybackStateChanged(isPlaying = false, playbackState = STATE_READY) }
    }

    fun simulatePlaybackEnded() {
        playing = false
        listeners.toList().forEach { it.onPlaybackStateChanged(isPlaying = false, playbackState = STATE_ENDED) }
    }

    fun completeDeferredSeek(chapterIndex: Int, positionMs: Long) {
        this.chapterIndex = chapterIndex
        this.positionMs = positionMs
        listeners.toList().forEach { it.onPositionDiscontinuity() }
    }

    private companion object {
        const val STATE_READY = 3
        const val STATE_ENDED = 4
    }
}
