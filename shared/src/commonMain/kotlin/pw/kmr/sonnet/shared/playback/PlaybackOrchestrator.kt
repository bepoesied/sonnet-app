package pw.kmr.sonnet.shared.playback

import kotlin.math.abs
import kotlin.math.max
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pw.kmr.sonnet.shared.data.local.dao.PlaybackProgressDao
import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity
import pw.kmr.sonnet.shared.ioDispatcher
import pw.kmr.sonnet.shared.model.DownloadedBook
import pw.kmr.sonnet.shared.sync.ProgressSyncer

internal expect fun currentTimeMillis(): Long

private const val SAVE_DEBOUNCE_MS = 300L
private const val PROGRESS_TICK_MS = 1_000L
private const val PERIODIC_SAVE_MS = 5_000L
private const val PERIODIC_SYNC_MS = 60_000L
private const val REMOTE_CHECK_TIMEOUT_MS = 2_000L
private const val REMOTE_RESUME_THRESHOLD_MS = 10_000L
const val SEEK_INCREMENT_MS = 10_000L

interface PlaybackBookRepository {
    suspend fun prepareBookForPlayback(bookId: String)

    suspend fun downloadedBook(bookId: String): DownloadedBook?
}

class PlaybackOrchestrator(
    private val engine: PlaybackEngine,
    private val libraryRepository: PlaybackBookRepository,
    private val libraryDao: PlaybackProgressDao,
    private val progressSyncer: ProgressSyncer,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val workDispatcher: CoroutineDispatcher = ioDispatcher,
    private val nowMillis: () -> Long = ::currentTimeMillis,
    private val monotonicMillis: () -> Long = defaultMonotonicClock(),
    private val remoteCheckTimeoutMs: Long = REMOTE_CHECK_TIMEOUT_MS
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val progressWriteMutex = Mutex()
    private val _state = MutableStateFlow(PlayerUiState())
    private val _pendingPlayerRequest = MutableStateFlow<String?>(null)

    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    val pendingPlayerRequest: StateFlow<String?> = _pendingPlayerRequest.asStateFlow()

    private var loadedBook: DownloadedBook? = null
    private var cachedUiChapters: List<PlayerChapter> = emptyList()
    private var listenerAttached = false
    private var playbackStarted = false
    private var chapterEndSleepEnabled = false
    private var pendingPlayAfterResumeResolution = false
    private var suppressDiscontinuitySave = false
    private var completedBookId: String? = null
    private var sessionGeneration = 0L

    private var lastPeriodicProgressSaveMs = 0L
    private var lastPeriodicProgressSyncMs = 0L

    private var remoteCheckJob: Job? = null
    private var debouncedSaveJob: Job? = null
    private var pendingDebouncedForceSync = false
    private var progressTickerJob: Job? = null
    private var sleepTimerJob: Job? = null

    private val engineListener = object : PlaybackEngineListener {
        override fun onPlaybackStateChanged(isPlaying: Boolean, playbackState: Int) {
            if (isPlaying) {
                playbackStarted = true
                completedBookId = null
                val now = nowMillis()
                if (lastPeriodicProgressSaveMs == 0L) lastPeriodicProgressSaveMs = now
                if (lastPeriodicProgressSyncMs == 0L) lastPeriodicProgressSyncMs = now
                ensureTickersRunning()
            } else {
                stopTickers()
            }
            publishState()
            if (playbackState == PLAYBACK_STATE_ENDED) {
                markBookComplete()
            } else if (playbackStarted && _state.value.resumePrompt == null) {
                if (!isPlaying) {
                    flushProgress(forceSync = true)
                } else {
                    scheduleDebouncedProgressSave(forceSync = false)
                }
            }
        }

        override fun onPositionDiscontinuity() {
            publishState()
            if (suppressDiscontinuitySave) {
                suppressDiscontinuitySave = false
                return
            }
            if (playbackStarted && _state.value.resumePrompt == null) {
                flushProgress(forceSync = !engine.isPlaying())
            }
        }

        override fun onMediaItemTransition(index: Int, reason: Int) {
            if (chapterEndSleepEnabled && reason == MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                chapterEndSleepEnabled = false
                engine.pause()
                setSleepTimer(SleepTimerState.Off)
            }
            publishState()
        }

        override fun onPlayerError(message: String) {
            _state.update { it.copy(errorMessage = message) }
        }
    }

    suspend fun load(bookId: String) = withContext(mainDispatcher) {
        ensureConnected()
        if (loadedBook?.id == bookId && engine.mediaItemCount() > 0) {
            publishState()
            return@withContext
        }

        if (loadedBook != null && loadedBook?.id != bookId && playbackStarted) {
            flushProgressInternal(forceSync = true)
        }

        val generation = ++sessionGeneration
        remoteCheckJob?.cancel()
        remoteCheckJob = null
        debouncedSaveJob?.cancel()
        debouncedSaveJob = null
        pendingDebouncedForceSync = false
        stopTickers()

        playbackStarted = false
        pendingPlayAfterResumeResolution = false
        suppressDiscontinuitySave = false
        completedBookId = null
        lastPeriodicProgressSaveMs = 0L
        lastPeriodicProgressSyncMs = 0L
        cachedUiChapters = emptyList()
        _state.update {
            it.copy(
                bookMissing = false,
                errorMessage = null,
                resumePrompt = null,
                isCheckingRemoteState = false
            )
        }

        val book = withContext(workDispatcher) {
            libraryRepository.prepareBookForPlayback(bookId)
            libraryRepository.downloadedBook(bookId)
        }
        if (generation != sessionGeneration) return@withContext

        if (book == null) {
            loadedBook = null
            cachedUiChapters = emptyList()
            _state.update {
                it.copy(
                    bookId = null,
                    title = "",
                    author = null,
                    coverFilePath = null,
                    chapters = emptyList(),
                    currentChapterId = null,
                    currentChapterTitle = "",
                    currentChapterStartPositionMs = 0L,
                    currentChapterPositionMs = 0L,
                    currentChapterDurationMs = 0L,
                    isPlaying = false,
                    positionMs = 0L,
                    durationMs = 0L,
                    canPlay = false,
                    bookMissing = true,
                    errorMessage = "This book is unavailable. Try downloading it again."
                )
            }
            return@withContext
        }

        val startPosition = withContext(workDispatcher) {
            reconcileProgressOnOpen(book)
        }
        if (generation != sessionGeneration) return@withContext

        loadedBook = book
        cachedUiChapters = book.toUiChapters()

        if (book.chapters.isEmpty()) {
            publishState()
            _state.update {
                it.copy(
                    canPlay = false,
                    errorMessage = "This book has no playable chapters."
                )
            }
            return@withContext
        }

        loadMediaIntoEngine(book, startPosition)
        publishState()
        startRemoteProgressCheck(book, generation)
    }

    fun playPause() {
        val book = loadedBook ?: return
        if (engine.isPlaying()) {
            engine.pause()
            stopTickers()
            publishState()
            flushProgress(forceSync = true)
            return
        }

        if (_state.value.resumePrompt != null) {
            pendingPlayAfterResumeResolution = true
            return
        }

        completedBookId = null
        if (listenerAttached && engine.connectionState.value == PlaybackConnectionState.CONNECTED && engine.mediaItemCount() > 0) {
            playbackStarted = true
            engine.play()
            publishState()
            ensureTickersRunning()
            return
        }

        scope.launch(mainDispatcher) {
            val generation = sessionGeneration
            ensureConnected()
            if (generation != sessionGeneration || loadedBook?.id != book.id) return@launch
            if (engine.mediaItemCount() == 0 && book.chapters.isNotEmpty()) {
                val startPosition = withContext(workDispatcher) { reconcileProgressOnOpen(book) }
                if (generation != sessionGeneration || loadedBook?.id != book.id) return@launch
                loadMediaIntoEngine(book, startPosition)
            }
            if (engine.mediaItemCount() > 0) {
                playbackStarted = true
                engine.play()
                publishState()
                ensureTickersRunning()
            }
        }
    }

    fun seekBack() {
        if (loadedBook == null) return
        completedBookId = null
        suppressDiscontinuitySave = false
        playbackStarted = true
        engine.seekBack()
    }

    fun seekForward() {
        if (loadedBook == null) return
        completedBookId = null
        suppressDiscontinuitySave = false
        playbackStarted = true
        engine.seekForward()
    }

    fun seekToBookPosition(positionMs: Long) {
        val book = loadedBook ?: return
        completedBookId = null
        suppressDiscontinuitySave = false
        playbackStarted = true
        val target = book.seekTargetFor(positionMs)
        engine.seekToChapter(target.chapterIndex, target.chapterPositionMs)
        publishState()
    }

    fun jumpToChapter(chapterId: String) {
        val book = loadedBook ?: return
        val chapterIndex = book.chapters.indexOfFirst { it.id == chapterId }
        if (chapterIndex < 0) return
        completedBookId = null
        suppressDiscontinuitySave = false
        playbackStarted = true
        engine.seekToChapter(chapterIndex, 0L)
        publishState()
    }

    fun setSleepTimer(timer: SleepTimerState) {
        chapterEndSleepEnabled = timer is SleepTimerState.ChapterEnd
        _state.update { it.copy(sleepTimer = timer) }
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (timer is SleepTimerState.Countdown && engine.isPlaying()) {
            ensureSleepTimerTickerRunning()
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun openFullPlayer(bookId: String) {
        _pendingPlayerRequest.value = bookId
    }

    fun consumePendingPlayerRequest(): String? {
        val request = _pendingPlayerRequest.value
        _pendingPlayerRequest.value = null
        return request
    }

    fun flushProgress(forceSync: Boolean = true) {
        scope.launch(mainDispatcher) {
            flushProgressInternal(forceSync = forceSync)
        }
    }

    fun stopAndReset() {
        scope.launch(mainDispatcher) {
            stopAndResetInternal()
        }
    }

    suspend fun stopIfLoaded(bookId: String) = withContext(mainDispatcher) {
        if (loadedBook?.id == bookId || _state.value.bookId == bookId) {
            stopAndResetInternal()
        }
    }

    suspend fun stopAndResetAndWait() = withContext(mainDispatcher) {
        stopAndResetInternal()
    }

    fun shutdown() {
        stopAndResetInternal()
        scope.cancel()
    }

    fun useRemoteProgress() {
        val book = loadedBook ?: return
        val prompt = _state.value.resumePrompt ?: return
        val shouldResume = pendingPlayAfterResumeResolution && !prompt.remoteIsCompleted
        pendingPlayAfterResumeResolution = false
        debouncedSaveJob?.cancel()
        debouncedSaveJob = null
        pendingDebouncedForceSync = false
        _state.update { it.copy(resumePrompt = null) }

        val targetPositionMs = if (prompt.remoteIsCompleted) book.totalDurationMs() else prompt.remotePositionMs
        val target = book.seekTargetFor(targetPositionMs)
        suppressDiscontinuitySave = true
        engine.seekToChapter(target.chapterIndex, target.chapterPositionMs)

        val chapterProgress = book.progressAt(targetPositionMs)
        val durationMs = book.totalDurationMs()
        val generation = sessionGeneration
        if (prompt.remoteIsCompleted) {
            completedBookId = book.id
            playbackStarted = false
        } else {
            completedBookId = null
        }

        scope.launch(workDispatcher) {
            progressWriteMutex.withLock {
                if (generation != sessionGeneration) return@withLock
                if (prompt.remoteIsCompleted) {
                    libraryDao.markBookCompletion(
                        bookId = book.id,
                        isCompleted = true,
                        positionMillis = durationMs,
                        durationMillis = durationMs,
                        updatedAtEpochMillis = prompt.remoteUpdatedAtEpochMillis,
                        pendingSync = false
                    )
                } else {
                    libraryDao.upsertPlaybackProgress(
                        PlaybackProgressEntity(
                            libraryItemId = book.id,
                            chapterId = chapterProgress?.chapterId,
                            chapterOffsetMillis = chapterProgress?.chapterOffsetMs ?: 0L,
                            positionMillis = prompt.remotePositionMs,
                            durationMillis = durationMs,
                            updatedAtEpochMillis = prompt.remoteUpdatedAtEpochMillis,
                            isCompleted = false,
                            pendingSync = false
                        )
                    )
                }
            }
        }

        if (shouldResume) {
            playbackStarted = true
            engine.play()
            ensureTickersRunning()
        }
        publishState()
    }

    fun keepLocalProgress() {
        if (_state.value.resumePrompt == null) return
        val shouldResume = pendingPlayAfterResumeResolution
        pendingPlayAfterResumeResolution = false
        _state.update { it.copy(resumePrompt = null) }
        if (loadedBook != null) {
            playbackStarted = true
            flushProgress(forceSync = true)
            if (shouldResume) {
                engine.play()
                ensureTickersRunning()
            }
        }
        publishState()
    }

    private suspend fun ensureConnected() {
        if (!listenerAttached) {
            engine.addListener(engineListener)
            listenerAttached = true
        }
        if (engine.connectionState.value != PlaybackConnectionState.CONNECTED) {
            engine.connect()
        }
    }

    private fun loadMediaIntoEngine(book: DownloadedBook, startPositionMs: Long) {
        val target = book.seekTargetFor(startPositionMs)
        engine.loadMedia(
            items = book.chapters.map { chapter ->
                MediaItemDescriptor(
                    uri = chapter.audioFilePath,
                    title = chapter.title,
                    albumTitle = book.title,
                    artist = book.author,
                    artworkUri = book.coverFilePath
                )
            },
            startIndex = target.chapterIndex,
            startPositionMs = target.chapterPositionMs
        )
        engine.prepare()
    }

    private suspend fun reconcileProgressOnOpen(book: DownloadedBook): Long {
        return libraryDao.playbackProgress(book.id)?.positionMillis ?: 0L
    }

    private fun startRemoteProgressCheck(book: DownloadedBook, generation: Long) {
        remoteCheckJob?.cancel()
        val bookId = book.id
        remoteCheckJob = scope.launch(mainDispatcher) {
            _state.update { it.copy(isCheckingRemoteState = true) }
            try {
                val localProgress = withContext(workDispatcher) {
                    libraryDao.playbackProgress(bookId)
                }
                val remoteProgress = withContext(workDispatcher) {
                    withTimeoutOrNull(remoteCheckTimeoutMs) {
                        progressSyncer.remoteProgress(bookId)
                    }
                }
                if (generation != sessionGeneration || loadedBook?.id != bookId) return@launch
                if (remoteProgress == null) return@launch

                val remoteUpdatedAt = remoteProgress.updatedAtEpochMillis
                val localUpdatedAt = localProgress?.updatedAtEpochMillis ?: 0L
                if (remoteUpdatedAt <= localUpdatedAt) return@launch

                val remotePosition = if (remoteProgress.isCompleted) {
                    book.totalDurationMs()
                } else {
                    remoteProgress.chapterId?.let { chapterId ->
                        book.positionFor(chapterId, remoteProgress.offsetMillis)
                    }
                } ?: return@launch

                val currentLocalPosition = if (playbackStarted) {
                    currentBookPositionMs()
                } else {
                    localProgress?.positionMillis ?: 0L
                }
                val isMeaningfullyDifferent = (remoteProgress.isCompleted && localProgress?.isCompleted != true) ||
                    abs(remotePosition - currentLocalPosition) >= REMOTE_RESUME_THRESHOLD_MS

                if (isMeaningfullyDifferent) {
                    val wasPlaying = engine.isPlaying()
                    showRemoteProgressPrompt(
                        book = book,
                        localPosition = currentLocalPosition,
                        remotePosition = remotePosition,
                        remoteUpdatedAt = remoteUpdatedAt,
                        remoteIsCompleted = remoteProgress.isCompleted
                    )
                    if (wasPlaying) {
                        pendingPlayAfterResumeResolution = true
                        engine.pause()
                        stopTickers()
                        publishState()
                    }
                }
            } finally {
                if (generation == sessionGeneration) {
                    _state.update { it.copy(isCheckingRemoteState = false) }
                }
            }
        }
    }

    private fun showRemoteProgressPrompt(
        book: DownloadedBook,
        localPosition: Long,
        remotePosition: Long,
        remoteUpdatedAt: Long,
        remoteIsCompleted: Boolean
    ) {
        _state.update {
            it.copy(
                resumePrompt = ProgressResumePrompt(
                    localPositionMs = localPosition,
                    localChapterTitle = book.chapterTitleAt(localPosition),
                    localChapterOffsetMs = book.chapterOffsetAt(localPosition),
                    remotePositionMs = remotePosition,
                    remoteChapterTitle = book.chapterTitleAt(remotePosition),
                    remoteChapterOffsetMs = book.chapterOffsetAt(remotePosition),
                    remoteUpdatedAtEpochMillis = remoteUpdatedAt,
                    remoteIsCompleted = remoteIsCompleted
                )
            )
        }
    }

    private fun scheduleDebouncedProgressSave(forceSync: Boolean = false) {
        if (loadedBook == null) return
        if (forceSync) pendingDebouncedForceSync = true
        debouncedSaveJob?.cancel()
        val generation = sessionGeneration
        debouncedSaveJob = scope.launch(mainDispatcher) {
            delay(SAVE_DEBOUNCE_MS)
            if (generation != sessionGeneration) return@launch
            val shouldSync = pendingDebouncedForceSync
            pendingDebouncedForceSync = false
            val snapshot = captureProgressSnapshot() ?: return@launch
            persistProgressSnapshot(snapshot, generation = generation, forceSync = shouldSync)
        }
    }

    private suspend fun flushProgressInternal(forceSync: Boolean) {
        debouncedSaveJob?.cancel()
        debouncedSaveJob = null
        val shouldSync = forceSync || pendingDebouncedForceSync
        pendingDebouncedForceSync = false
        if (!playbackStarted) return
        val generation = sessionGeneration
        val snapshot = captureProgressSnapshot() ?: return
        persistProgressSnapshot(snapshot, generation = generation, forceSync = shouldSync)
    }

    private suspend fun persistProgressSnapshot(
        snapshot: ProgressSnapshot,
        generation: Long,
        forceSync: Boolean
    ) {
        withContext(workDispatcher) {
            var written = false
            progressWriteMutex.withLock {
                if (generation != sessionGeneration) return@withLock
                if (completedBookId == snapshot.bookId) return@withLock
                libraryDao.upsertPlaybackProgress(
                    PlaybackProgressEntity(
                        libraryItemId = snapshot.bookId,
                        chapterId = snapshot.chapterId,
                        chapterOffsetMillis = snapshot.chapterOffsetMs,
                        positionMillis = snapshot.positionMs,
                        durationMillis = snapshot.durationMs,
                        updatedAtEpochMillis = nowMillis(),
                        isCompleted = false,
                        pendingSync = true
                    )
                )
                written = true
            }
            if (written) {
                progressSyncer.notifyPendingProgressWritten()
                if (forceSync) {
                    progressSyncer.syncBook(snapshot.bookId)
                }
            }
        }
    }

    private fun markBookComplete() {
        val book = loadedBook ?: return
        debouncedSaveJob?.cancel()
        debouncedSaveJob = null
        pendingDebouncedForceSync = false
        completedBookId = book.id
        val durationMs = book.totalDurationMs()
        val generation = sessionGeneration
        scope.launch(workDispatcher) {
            var written = false
            progressWriteMutex.withLock {
                if (generation != sessionGeneration) return@withLock
                val now = nowMillis()
                libraryDao.markBookCompletion(
                    bookId = book.id,
                    isCompleted = true,
                    positionMillis = durationMs,
                    durationMillis = durationMs,
                    updatedAtEpochMillis = now,
                    pendingSync = true
                )
                written = true
            }
            if (written) {
                progressSyncer.notifyPendingProgressWritten()
                progressSyncer.syncBook(book.id)
            }
        }
    }

    private fun ensureTickersRunning() {
        if (!engine.isPlaying()) return
        if (progressTickerJob?.isActive != true) {
            progressTickerJob = scope.launch(mainDispatcher) {
                progressTicker()
            }
        }
        ensureSleepTimerTickerRunning()
    }

    private fun ensureSleepTimerTickerRunning() {
        if (!engine.isPlaying() || _state.value.sleepTimer !is SleepTimerState.Countdown) return
        if (sleepTimerJob?.isActive != true) {
            sleepTimerJob = scope.launch(mainDispatcher) {
                sleepTimerTicker()
            }
        }
    }

    private fun stopTickers() {
        progressTickerJob?.cancel()
        progressTickerJob = null
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    private suspend fun progressTicker() {
        while (engine.isPlaying()) {
            delay(PROGRESS_TICK_MS)
            if (!engine.isPlaying()) break
            publishState()
            if (_state.value.resumePrompt != null) continue
            val now = nowMillis()
            if (now - lastPeriodicProgressSaveMs >= PERIODIC_SAVE_MS) {
                lastPeriodicProgressSaveMs = now
                scheduleDebouncedProgressSave(forceSync = false)
            }
            if (now - lastPeriodicProgressSyncMs >= PERIODIC_SYNC_MS) {
                lastPeriodicProgressSyncMs = now
                scope.launch(workDispatcher) { progressSyncer.syncPending() }
            }
        }
    }

    private suspend fun sleepTimerTicker() {
        var lastTickMs = monotonicMillis()
        while (engine.isPlaying()) {
            val currentTimer = _state.value.sleepTimer
            if (currentTimer !is SleepTimerState.Countdown) break
            val stepMs = minOf(PROGRESS_TICK_MS, currentTimer.remainingMs.coerceAtLeast(1L))
            delay(stepMs)
            if (!engine.isPlaying()) break
            val now = monotonicMillis()
            val elapsedMs = (now - lastTickMs).coerceAtLeast(0L)
            lastTickMs = now
            val latestTimer = _state.value.sleepTimer
            if (latestTimer !is SleepTimerState.Countdown) break
            val remaining = latestTimer.remainingMs - elapsedMs
            if (remaining <= 0L) {
                engine.pause()
                setSleepTimer(SleepTimerState.Off)
                break
            } else {
                _state.update { it.copy(sleepTimer = latestTimer.copy(remainingMs = remaining)) }
            }
        }
    }

    private fun stopAndResetInternal() {
        val alreadyReset = loadedBook == null &&
            !listenerAttached &&
            remoteCheckJob == null &&
            debouncedSaveJob == null &&
            progressTickerJob == null &&
            sleepTimerJob == null &&
            engine.connectionState.value == PlaybackConnectionState.DISCONNECTED &&
            _state.value == PlayerUiState()
        if (alreadyReset) return

        sessionGeneration++
        remoteCheckJob?.cancel()
        remoteCheckJob = null
        debouncedSaveJob?.cancel()
        debouncedSaveJob = null
        pendingDebouncedForceSync = false
        stopTickers()

        if (listenerAttached) {
            engine.removeListener(engineListener)
            listenerAttached = false
        }
        engine.pause()
        engine.release()

        playbackStarted = false
        loadedBook = null
        cachedUiChapters = emptyList()
        chapterEndSleepEnabled = false
        pendingPlayAfterResumeResolution = false
        suppressDiscontinuitySave = false
        completedBookId = null
        lastPeriodicProgressSaveMs = 0L
        lastPeriodicProgressSyncMs = 0L
        _state.value = PlayerUiState()
    }

    private fun captureProgressSnapshot(): ProgressSnapshot? {
        val book = loadedBook ?: return null
        val chapterIndex = engine.currentMediaItemIndex().coerceAtLeast(0)
        val chapterPositionMs = max(engine.currentPositionMs(), 0L)
        val positionMs = book.bookPositionFor(chapterIndex, chapterPositionMs)
        val durationMs = book.totalDurationMs()
        val currentChapter = book.chapters.getOrNull(chapterIndex)
        val fallbackProgress = if (currentChapter == null) book.progressAt(positionMs) else null
        return ProgressSnapshot(
            bookId = book.id,
            chapterId = currentChapter?.id ?: fallbackProgress?.chapterId,
            chapterOffsetMs = if (currentChapter != null) chapterPositionMs else (fallbackProgress?.chapterOffsetMs ?: 0L),
            positionMs = positionMs,
            durationMs = durationMs
        )
    }

    private fun publishState() {
        val book = loadedBook
        val currentChapterIndex = engine.currentMediaItemIndex().coerceAtLeast(0)
        val currentChapter = book?.chapters?.getOrNull(currentChapterIndex)
        val playerPosition = max(engine.currentPositionMs(), 0L)
        val position = book?.bookPositionFor(currentChapterIndex, playerPosition) ?: playerPosition
        val duration = book?.totalDurationMs() ?: 0L
        val currentChapterStart = book?.chapterStartPositionMs(currentChapterIndex) ?: 0L
        val currentChapterDuration = (currentChapter?.durationMs ?: 0L).coerceAtLeast(0L)

        _state.update {
            it.copy(
                bookId = book?.id,
                title = book?.title.orEmpty(),
                author = book?.author,
                coverFilePath = book?.coverFilePath,
                chapters = cachedUiChapters,
                currentChapterId = currentChapter?.id,
                currentChapterTitle = currentChapter?.title.orEmpty(),
                currentChapterStartPositionMs = currentChapterStart,
                currentChapterPositionMs = playerPosition,
                currentChapterDurationMs = currentChapterDuration,
                isPlaying = engine.isPlaying(),
                positionMs = position,
                durationMs = duration,
                canPlay = book != null && engine.mediaItemCount() > 0,
                bookMissing = if (book != null) false else it.bookMissing
            )
        }
    }

    private fun currentBookPositionMs(): Long {
        val book = loadedBook ?: return max(engine.currentPositionMs(), 0L)
        return book.bookPositionFor(
            chapterIndex = engine.currentMediaItemIndex(),
            chapterPositionMs = max(engine.currentPositionMs(), 0L)
        )
    }

    private data class ProgressSnapshot(
        val bookId: String,
        val chapterId: String?,
        val chapterOffsetMs: Long,
        val positionMs: Long,
        val durationMs: Long
    )

    companion object {
        const val MEDIA_ITEM_TRANSITION_REASON_AUTO = 1
        private const val PLAYBACK_STATE_ENDED = 4
    }
}

private fun defaultMonotonicClock(): () -> Long {
    val startMark = TimeSource.Monotonic.markNow()
    return { startMark.elapsedNow().inWholeMilliseconds }
}
