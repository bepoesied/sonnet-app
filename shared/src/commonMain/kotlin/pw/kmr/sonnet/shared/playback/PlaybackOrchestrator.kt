package pw.kmr.sonnet.shared.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import pw.kmr.sonnet.shared.data.local.dao.LibraryDao
import pw.kmr.sonnet.shared.data.local.entity.PlaybackProgressEntity
import pw.kmr.sonnet.shared.ioDispatcher
import pw.kmr.sonnet.shared.library.LibraryRepository
import pw.kmr.sonnet.shared.model.DownloadedBook
import pw.kmr.sonnet.shared.sync.ProgressSyncer

internal expect fun currentTimeMillis(): Long

private const val SAVE_DEBOUNCE_MS = 300L
private const val PROGRESS_TICK_MS = 1_000L
private const val PERIODIC_SAVE_MS = 5_000L
private const val PERIODIC_SYNC_MS = 60_000L
const val SEEK_INCREMENT_MS = 10_000L

class PlaybackOrchestrator(
    private val engine: PlaybackEngine,
    private val libraryRepository: LibraryRepository,
    private val libraryDao: LibraryDao,
    private val progressSyncer: ProgressSyncer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    private val _pendingPlayerRequest = MutableStateFlow<String?>(null)

    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    val pendingPlayerRequest: StateFlow<String?> = _pendingPlayerRequest.asStateFlow()

    private var loadedBook: DownloadedBook? = null
    private var saveInFlight = false
    private var forceSyncAfterSave = false
    private var resumeCheckInFlight = false
    private var playbackStarted = false
    private var lastPeriodicProgressSaveMs = 0L
    private var lastPeriodicProgressSyncMs = 0L
    private var chapterEndSleepEnabled = false
    private var lastSleepPositionMs: Long? = null

    private val engineListener = object : PlaybackEngineListener {
        override fun onPlaybackStateChanged(isPlaying: Boolean, playbackState: Int) {
            publishState()
            if (playbackStarted) saveProgressSoon(forceSync = !isPlaying)
            if (playbackState == PLAYBACK_STATE_ENDED) {
                markBookComplete()
            }
        }

        override fun onPositionDiscontinuity() {
            publishState()
            if (playbackStarted) saveProgressSoon(forceSync = !engine.isPlaying())
        }

        override fun onMediaItemTransition(index: Int, reason: Int) {
            if (chapterEndSleepEnabled && reason == MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                chapterEndSleepEnabled = false
                engine.pause()
                setSleepTimer(SleepTimerState.Off)
            }
        }

        override fun onPlayerError(message: String) {
            _state.update { it.copy(errorMessage = message) }
        }
    }

    init {
        engine.addListener(engineListener)
        scope.launch {
            engine.connect()
            progressTicker()
        }
        scope.launch { sleepTimerTicker() }
    }

    suspend fun load(bookId: String) {
        if (loadedBook?.id == bookId && engine.mediaItemCount() > 0) {
            publishState()
            return
        }
        playbackStarted = false
        libraryRepository.prepareBookForPlayback(bookId)
        val book = libraryRepository.downloadedBook(bookId) ?: return
        val startPosition = reconcileProgressOnOpen(book)
        val target = book.seekTargetFor(startPosition)

        loadedBook = book
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
        publishState()
    }

    fun playPause() {
        val book = loadedBook ?: return
        if (engine.isPlaying()) {
            engine.pause()
            saveProgressSoon(forceSync = true)
            return
        }

        if (resumeCheckInFlight || state.value.resumePrompt != null) return

        scope.launch {
            if (reconcileProgressBeforePlay(book)) {
                playbackStarted = true
                engine.play()
            }
        }
    }

    fun seekBack() {
        val from = currentBookPositionMs()
        engine.seekBack()
        val to = currentBookPositionMs()
        consumeSleepTimerForSeek(from, to)
        saveProgressSoon(forceSync = !engine.isPlaying())
        publishState()
    }

    fun seekForward() {
        val from = currentBookPositionMs()
        engine.seekForward()
        val to = currentBookPositionMs()
        consumeSleepTimerForSeek(from, to)
        saveProgressSoon(forceSync = !engine.isPlaying())
        publishState()
    }

    fun seekToBookPosition(positionMs: Long) {
        val book = loadedBook ?: return
        val from = currentBookPositionMs()
        val target = book.seekTargetFor(positionMs)
        engine.seekToChapter(target.chapterIndex, target.chapterPositionMs)
        consumeSleepTimerForSeek(from, positionMs)
        saveProgressSoon(forceSync = !engine.isPlaying())
        publishState()
    }

    fun jumpToChapter(chapterId: String) {
        val book = loadedBook ?: return
        val target = book.positionFor(chapterId, 0L) ?: return
        seekToBookPosition(target)
    }

    fun setSleepTimer(timer: SleepTimerState) {
        chapterEndSleepEnabled = timer is SleepTimerState.ChapterEnd
        lastSleepPositionMs = null
        _state.update { it.copy(sleepTimer = timer) }
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

    fun shutdown() {
        engine.pause()
        if (playbackStarted) saveProgressSoon(forceSync = true)
        playbackStarted = false
        loadedBook = null
        engine.removeListener(engineListener)
        engine.release()
        _state.value = PlayerUiState()
    }

    fun useRemoteProgress() {
        val book = loadedBook ?: return
        val prompt = state.value.resumePrompt ?: return
        val target = book.seekTargetFor(prompt.remotePositionMs)
        engine.seekToChapter(target.chapterIndex, target.chapterPositionMs)
        val chapterProgress = book.progressAt(prompt.remotePositionMs)
        _state.update { it.copy(resumePrompt = null) }
        scope.launch(ioDispatcher) {
            libraryDao.upsertPlaybackProgress(
                PlaybackProgressEntity(
                    libraryItemId = book.id,
                    chapterId = chapterProgress.chapterId,
                    chapterOffsetMillis = chapterProgress.chapterOffsetMs,
                    positionMillis = prompt.remotePositionMs,
                    durationMillis = book.totalDurationMs(),
                    updatedAtEpochMillis = prompt.remoteUpdatedAtEpochMillis,
                    isCompleted = false,
                    pendingSync = false
                )
            )
        }
        publishState()
    }

    fun keepLocalProgress() {
        _state.update { it.copy(resumePrompt = null) }
        saveProgressSoon(forceSync = true)
    }

    private suspend fun reconcileProgressOnOpen(book: DownloadedBook): Long {
        return libraryDao.playbackProgress(book.id)?.positionMillis ?: 0L
    }

    private suspend fun reconcileProgressBeforePlay(book: DownloadedBook): Boolean {
        resumeCheckInFlight = true
        _state.update { it.copy(isCheckingRemoteState = true) }
        return try {
            val localProgress = libraryDao.playbackProgress(book.id)
            val remoteProgress = progressSyncer.remoteProgress(book.id)
            val remoteUpdatedAt = remoteProgress?.updatedAtEpochMillis
            val remotePosition = remoteProgress?.chapterId?.let { chapterId ->
                book.positionFor(chapterId, remoteProgress.offsetMillis)
            }

            if (remoteUpdatedAt != null && remotePosition != null) {
                val localUpdatedAt = localProgress?.updatedAtEpochMillis ?: 0L
                val localPosition = localProgress?.positionMillis ?: 0L
                if (remoteUpdatedAt > localUpdatedAt && abs(remotePosition - localPosition) >= 10_000L) {
                    showRemoteProgressPrompt(book, localPosition, remotePosition, remoteUpdatedAt)
                    false
                } else {
                    true
                }
            } else {
                true
            }
        } finally {
            resumeCheckInFlight = false
            _state.update { it.copy(isCheckingRemoteState = false) }
        }
    }

    private fun showRemoteProgressPrompt(
        book: DownloadedBook,
        localPosition: Long,
        remotePosition: Long,
        remoteUpdatedAt: Long
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
                    remoteUpdatedAtEpochMillis = remoteUpdatedAt
                )
            )
        }
    }

    private fun saveProgressSoon(forceSync: Boolean = false) {
        if (saveInFlight) {
            if (forceSync) forceSyncAfterSave = true
            return
        }

        val book = loadedBook ?: return
        val positionMs = currentBookPositionMs()
        val durationMs = book.totalDurationMs()
        val chapterProgress = book.progressAt(positionMs)

        saveInFlight = true
        scope.launch(ioDispatcher) {
            delay(SAVE_DEBOUNCE_MS)
            libraryDao.upsertPlaybackProgress(
                PlaybackProgressEntity(
                    libraryItemId = book.id,
                    chapterId = chapterProgress.chapterId,
                    chapterOffsetMillis = chapterProgress.chapterOffsetMs,
                    positionMillis = positionMs,
                    durationMillis = durationMs,
                    updatedAtEpochMillis = currentTimeMillis(),
                    isCompleted = false,
                    pendingSync = true
                )
            )
            saveInFlight = false
            val shouldSync = forceSync || forceSyncAfterSave
            forceSyncAfterSave = false
            if (shouldSync) {
                progressSyncer.syncBook(book.id)
            }
        }
    }

    private fun markBookComplete() {
        val book = loadedBook ?: return
        scope.launch(ioDispatcher) {
            val now = currentTimeMillis()
            libraryDao.deletePlaybackProgress(book.id)
            libraryDao.updateLibraryItemCompletion(book.id, true, now)
            libraryDao.updateDownloadedBookCompletion(book.id, true)
            libraryDao.upsertPlaybackProgress(
                PlaybackProgressEntity(
                    libraryItemId = book.id,
                    positionMillis = book.totalDurationMs(),
                    durationMillis = book.totalDurationMs(),
                    updatedAtEpochMillis = now,
                    isCompleted = true,
                    pendingSync = true
                )
            )
            progressSyncer.syncBook(book.id)
        }
    }

    private suspend fun progressTicker() {
        while (true) {
            publishState()
            val now = currentTimeMillis()
            if (engine.isPlaying() && now - lastPeriodicProgressSaveMs >= PERIODIC_SAVE_MS) {
                lastPeriodicProgressSaveMs = now
                saveProgressSoon(forceSync = false)
            }
            if (engine.isPlaying() && now - lastPeriodicProgressSyncMs >= PERIODIC_SYNC_MS) {
                lastPeriodicProgressSyncMs = now
                scope.launch(ioDispatcher) { progressSyncer.syncPending() }
            }
            delay(PROGRESS_TICK_MS)
        }
    }

    private suspend fun sleepTimerTicker() {
        while (true) {
            val timer = state.value.sleepTimer
            if (timer is SleepTimerState.Countdown && engine.isPlaying()) {
                val position = currentBookPositionMs()
                val last = lastSleepPositionMs
                if (last != null && position > last) {
                    val remaining = timer.remainingMs - (position - last)
                    if (remaining <= 0L) {
                        engine.pause()
                        setSleepTimer(SleepTimerState.Off)
                    } else {
                        _state.update { it.copy(sleepTimer = timer.copy(remainingMs = remaining)) }
                    }
                }
                lastSleepPositionMs = position
            } else {
                lastSleepPositionMs = null
            }
            delay(PROGRESS_TICK_MS)
        }
    }

    private fun consumeSleepTimerForSeek(fromPositionMs: Long, toPositionMs: Long) {
        if (toPositionMs <= fromPositionMs) return
        when (val timer = state.value.sleepTimer) {
            SleepTimerState.Off -> Unit
            is SleepTimerState.Countdown -> {
                val remaining = timer.remainingMs - (toPositionMs - fromPositionMs)
                if (remaining <= 0L) {
                    engine.pause()
                    setSleepTimer(SleepTimerState.Off)
                } else {
                    _state.update { it.copy(sleepTimer = timer.copy(remainingMs = remaining)) }
                }
            }
            SleepTimerState.ChapterEnd -> {
                val book = loadedBook ?: return
                val currentChapterEnd = book.chapterStartPositionMs(engine.currentMediaItemIndex() + 1)
                if (toPositionMs >= currentChapterEnd) {
                    setSleepTimer(SleepTimerState.Off)
                }
            }
        }
    }

    private fun publishState() {
        val book = loadedBook
        val currentChapterIndex = engine.currentMediaItemIndex().coerceAtLeast(0)
        val currentChapter = book?.chapters?.getOrNull(currentChapterIndex)
        val playerPosition = max(engine.currentPositionMs(), 0L)
        val position = book?.bookPositionFor(currentChapterIndex, playerPosition) ?: playerPosition
        val duration = book?.totalDurationMs() ?: 0L
        val currentChapterStart = book?.chapterStartPositionMs(currentChapterIndex) ?: 0L
        val currentChapterDuration = currentChapter?.durationMs ?: 0L

        _state.update {
            it.copy(
                bookId = book?.id,
                title = book?.title.orEmpty(),
                author = book?.author,
                coverFilePath = book?.coverFilePath,
                chapters = book?.toUiChapters().orEmpty(),
                currentChapterId = currentChapter?.id,
                currentChapterTitle = currentChapter?.title.orEmpty(),
                currentChapterStartPositionMs = currentChapterStart,
                currentChapterPositionMs = playerPosition,
                currentChapterDurationMs = currentChapterDuration,
                isPlaying = engine.isPlaying(),
                positionMs = position,
                durationMs = duration,
                canPlay = book != null && engine.mediaItemCount() > 0
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

    companion object {
        const val MEDIA_ITEM_TRANSITION_REASON_AUTO = 1
        private const val PLAYBACK_STATE_ENDED = 4
    }
}
