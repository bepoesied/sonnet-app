import pw.kmr.sonnet.shared.sync.ProgressSyncer
import pw.kmr.sonnet.shared.playback.chapterOffsetAt
import pw.kmr.sonnet.shared.playback.chapterStartPositionMs
import pw.kmr.sonnet.shared.playback.chapterTitleAt
import pw.kmr.sonnet.shared.playback.positionFor
import pw.kmr.sonnet.shared.playback.progressAt
import pw.kmr.sonnet.shared.playback.projectChapters
import pw.kmr.sonnet.shared.playback.seekTargetFor
import pw.kmr.sonnet.shared.playback.totalDurationMs
            val targetPosition = book.chapterStartPositionMs(index)
                val currentChapterIndex = (controller?.currentMediaItemIndex ?: 0).coerceAtLeast(0)
                val currentChapterEnd = book.chapterStartPositionMs(currentChapterIndex + 1)
        val currentChapterStartPosition = book?.chapterStartPositionMs(currentChapterIndex) ?: 0L
        return book.bookPositionFor(
            chapterIndex = player.currentMediaItemIndex,
            chapterPositionMs = max(player.currentPosition, 0L)
        )
    return projectChapters().map { chapter ->
            startPositionMs = chapter.startPositionMs,
        )
                    remoteUpdatedAt = remoteUpdatedAt
                )
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

    val isCheckingRemoteState: Boolean = false,
    val remoteChapterTitle: String,
    val remoteChapterOffsetMs: Long,
private fun DownloadedBook.chapterTitleAt(bookPositionMs: Long): String {
    var remaining = bookPositionMs.coerceAtLeast(0L)
    chapters.forEach { chapter ->
        val duration = chapter.durationMs ?: 0L
        if (duration <= 0L || remaining < duration) return chapter.title
        remaining -= duration
    }
    return chapters.last().title
}

private fun DownloadedBook.chapterOffsetAt(bookPositionMs: Long): Long = progressAt(bookPositionMs).chapterOffsetMs

import pw.kmr.sonnet.sync.ProgressSyncer
    private val libraryDao: LibraryDao,
    private val progressSyncer: ProgressSyncer
    private var forceSyncAfterSave = false
    private var lastPeriodicProgressSyncMs = 0L
                saveProgressSoon(forceSync = !player.isPlaying)
        val savedPosition = reconcileProgressOnOpen(book)
            if (player?.isPlaying == true && SystemClock.elapsedRealtime() - lastPeriodicProgressSyncMs >= 60_000L) {
                lastPeriodicProgressSyncMs = SystemClock.elapsedRealtime()
                scope.launch(Dispatchers.IO) { progressSyncer.syncPending() }
            }
    private suspend fun reconcileProgressOnOpen(book: DownloadedBook): Long {
        val localProgress = libraryDao.playbackProgress(book.id)
        val remoteProgress = progressSyncer.remoteProgress(book.id)
        val remoteUpdatedAt = remoteProgress?.updatedAtEpochMillis
        val remoteChapterId = remoteProgress?.chapterId
        val remotePosition = if (remoteChapterId != null) {
            book.positionFor(remoteChapterId, remoteProgress.offsetMillis)
        } else {
            null
        }

        if (remoteUpdatedAt != null && remotePosition != null) {
            val localUpdatedAt = localProgress?.updatedAtEpochMillis ?: 0L
            val localPosition = localProgress?.positionMillis ?: 0L
            if (remoteUpdatedAt > localUpdatedAt && kotlin.math.abs(remotePosition - localPosition) >= 10_000L) {
                _state.update {
                    it.copy(
                        resumePrompt = ProgressResumePrompt(
                            localPositionMs = localPosition,
                            remotePositionMs = remotePosition,
                            remoteUpdatedAtEpochMillis = remoteUpdatedAt
                        )
                    )
                }
                return localPosition
            }
            if (remoteUpdatedAt > localUpdatedAt) {
                val chapterProgress = book.progressAt(remotePosition)
                libraryDao.upsertPlaybackProgress(
                    PlaybackProgressEntity(
                        libraryItemId = book.id,
                        chapterId = chapterProgress.chapterId,
                        chapterOffsetMillis = chapterProgress.chapterOffsetMs,
                        positionMillis = remotePosition,
                        durationMillis = book.totalDurationMs(),
                        updatedAtEpochMillis = remoteUpdatedAt,
                        pendingSync = false
                    )
                )
                return remotePosition
            }
            if (localProgress != null && localUpdatedAt > remoteUpdatedAt) {
                val chapterProgress = book.progressAt(localProgress.positionMillis)
                libraryDao.upsertPlaybackProgress(
                    localProgress.copy(
                        chapterId = chapterProgress.chapterId,
                        chapterOffsetMillis = chapterProgress.chapterOffsetMs,
                        durationMillis = book.totalDurationMs(),
                        pendingSync = true
                    )
                )
                scope.launch(Dispatchers.IO) { progressSyncer.syncBook(book.id) }
                return localProgress.positionMillis
            }
        }

        if (remoteProgress != null && remoteUpdatedAt == null && localProgress != null) {
            val chapterProgress = book.progressAt(localProgress.positionMillis)
            libraryDao.upsertPlaybackProgress(
                localProgress.copy(
                    chapterId = chapterProgress.chapterId,
                    chapterOffsetMillis = chapterProgress.chapterOffsetMs,
                    durationMillis = book.totalDurationMs(),
                    pendingSync = true
                )
            )
            scope.launch(Dispatchers.IO) { progressSyncer.syncBook(book.id) }
            return localProgress.positionMillis
        }

        if (localProgress?.pendingSync == true) {
            if (localProgress.chapterId == null) {
                val chapterProgress = book.progressAt(localProgress.positionMillis)
                libraryDao.upsertPlaybackProgress(
                    localProgress.copy(
                        chapterId = chapterProgress.chapterId,
                        chapterOffsetMillis = chapterProgress.chapterOffsetMs,
                        durationMillis = book.totalDurationMs()
                    )
                )
            }
            scope.launch(Dispatchers.IO) { progressSyncer.syncBook(book.id) }
        }
        return localProgress?.positionMillis ?: 0L
    }

    fun useRemoteProgress() {
        val prompt = state.value.resumePrompt ?: return
        val target = book.seekTargetFor(prompt.remotePositionMs)
        controller?.seekTo(target.chapterIndex, target.chapterPositionMs)
        val chapterProgress = book.progressAt(prompt.remotePositionMs)
        _state.update { it.copy(resumePrompt = null) }
        scope.launch(Dispatchers.IO) {
            libraryDao.upsertPlaybackProgress(
                PlaybackProgressEntity(
                    libraryItemId = book.id,
                    chapterId = chapterProgress.chapterId,
                    chapterOffsetMillis = chapterProgress.chapterOffsetMs,
                    positionMillis = prompt.remotePositionMs,
                    durationMillis = book.totalDurationMs(),
                    updatedAtEpochMillis = prompt.remoteUpdatedAtEpochMillis,
                    pendingSync = false
                )
            )
        }
    }

    fun keepLocalProgress() {
        _state.update { it.copy(resumePrompt = null) }
        saveProgressSoon(forceSync = true)
    }

    private fun saveProgressSoon(forceSync: Boolean = false) {
        if (saveInFlight) {
            if (forceSync) forceSyncAfterSave = true
            return
        }
        val book = loadedBook ?: return
        val chapterProgress = book.progressAt(positionMs)
                    chapterId = chapterProgress.chapterId,
                    chapterOffsetMillis = chapterProgress.chapterOffsetMs,
            val shouldSync = forceSync || forceSyncAfterSave
            forceSyncAfterSave = false
            if (shouldSync) progressSyncer.syncBook(book.id)
    val resumePrompt: ProgressResumePrompt? = null,
data class ProgressResumePrompt(
    val localPositionMs: Long,
    val remotePositionMs: Long,
    val remoteUpdatedAtEpochMillis: Long
)

private data class ChapterProgress(
    val chapterId: String,
    val chapterOffsetMs: Long
)

private fun DownloadedBook.positionFor(chapterId: String, chapterOffsetMs: Long): Long? {
    val chapterIndex = chapters.indexOfFirst { it.id == chapterId }
    if (chapterIndex < 0) return null
    val beforeChapter = chapters.take(chapterIndex).sumOf { it.durationMs ?: 0L }
    val chapterDuration = chapters[chapterIndex].durationMs
    val offset = if (chapterDuration == null || chapterDuration <= 0L) {
        chapterOffsetMs.coerceAtLeast(0L)
    } else {
        chapterOffsetMs.coerceIn(0L, chapterDuration)
    }
    return beforeChapter + offset
}

private fun DownloadedBook.progressAt(bookPositionMs: Long): ChapterProgress {
    var remaining = bookPositionMs.coerceAtLeast(0L)
    chapters.forEach { chapter ->
        val duration = chapter.durationMs ?: 0L
        if (duration <= 0L || remaining < duration) return ChapterProgress(chapter.id, remaining)
        remaining -= duration
    }
    val last = chapters.last()
    return ChapterProgress(last.id, (last.durationMs ?: 0L).coerceAtLeast(0L))
}

            is SleepTimerState.Countdown -> {
                val remaining = timer.remainingMs - (toPositionMs - fromPositionMs)
                if (remaining <= 0L) {
                    controller?.pause()
                    clearSleepTimer()
                } else {
                    _state.update { it.copy(sleepTimer = timer.copy(remainingMs = remaining)) }
                }
            }
                if (toPositionMs >= currentChapterEnd) clearSleepTimer()

    private fun clearSleepTimerIfSeekPassesTimer(fromPositionMs: Long, toPositionMs: Long) {
        val timer = state.value.sleepTimer
        if (toPositionMs <= fromPositionMs) return
        val shouldClear = when (timer) {
            SleepTimerState.Off -> false
            is SleepTimerState.Countdown -> toPositionMs >= fromPositionMs + timer.remainingMs
            SleepTimerState.ChapterEnd -> {
                val book = loadedBook ?: return
                val currentChapterEnd = book.chapters
                    .take((controller?.currentMediaItemIndex ?: 0).coerceAtLeast(0) + 1)
                    .sumOf { it.durationMs ?: 0L }
                toPositionMs >= currentChapterEnd
            }
        }
        if (shouldClear) clearSleepTimer()
    }

const val SEEK_INCREMENT_MS = 10_000L
        val currentChapter = book?.chapters?.getOrNull(currentChapterIndex)
        val currentChapterStartPosition = book?.chapters
            ?.take(currentChapterIndex.coerceAtLeast(0))
            ?.sumOf { it.durationMs ?: 0L }
            ?: 0L
        val currentChapterPosition = max(player?.currentPosition ?: 0L, 0L)
        val currentChapterDuration = currentChapter?.durationMs ?: player?.duration?.takeIf { it > 0 } ?: 0L
                currentChapterStartPositionMs = currentChapterStartPosition,
                currentChapterPositionMs = currentChapterPosition,
                currentChapterDurationMs = currentChapterDuration,
    val currentChapterStartPositionMs: Long = 0L,
    val currentChapterPositionMs: Long = 0L,
    val currentChapterDurationMs: Long = 0L,
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
    private var controller: MediaController? = null
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishState()
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                saveProgressSoon()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (chapterEndSleepEnabled && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                chapterEndSleepEnabled = false
                controller?.pause()
                _state.update { it.copy(sleepTimer = SleepTimerState.Off) }
        }
        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(errorMessage = error.message ?: "Playback failed.") }
        }
    }

    init {
        val player = ensureController()
        val player = controller ?: return
        controller?.seekBack()
        controller?.seekForward()
        val player = controller ?: return
        val player = controller ?: return
        controller?.release()
        controller = null
        scope.cancel()
    private suspend fun ensureController(): MediaController {
        controller?.let { return it }
        ContextCompat.startForegroundService(
            applicationContext,
            Intent(applicationContext, SonnetMediaSessionService::class.java)
        )
        val sessionToken = SessionToken(
            applicationContext,
            ComponentName(applicationContext, SonnetMediaSessionService::class.java)
        )
        val future = MediaController.Builder(applicationContext, sessionToken).buildAsync()
        return suspendCancellableCoroutine { continuation ->
            future.addListener(
                {
                    val mediaController = future.get()
                    mediaController.addListener(playerListener)
                    controller = mediaController
                    continuation.resume(mediaController)
                },
                MoreExecutors.directExecutor()
            )
            continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
        }
    }

            val player = controller
            if (player?.isPlaying == true && SystemClock.elapsedRealtime() - lastPeriodicProgressSaveMs >= 5_000L) {
            val player = controller
            if (timer is SleepTimerState.Countdown && player?.isPlaying == true) {
        val player = controller
        val duration = book?.totalDurationMs() ?: player?.duration?.takeIf { it > 0 } ?: 0L
        val currentChapter = book?.chapters?.getOrNull(player?.currentMediaItemIndex ?: 0)
                isPlaying = player?.isPlaying == true,
                isBuffering = player?.playbackState == Player.STATE_BUFFERING,
                canPlay = book != null && (player?.mediaItemCount ?: 0) > 0
        val player = controller ?: return 0L

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
        }

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                publishState()
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                    events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                ) {
                    saveProgressSoon()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (chapterEndSleepEnabled && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    chapterEndSleepEnabled = false
                    player.pause()
                    _state.update { it.copy(sleepTimer = SleepTimerState.Off) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(errorMessage = error.message ?: "Playback failed.") }
            }
        })
        scope.launch { progressTicker() }
        scope.launch { sleepTimerTicker() }
    }

    suspend fun load(bookId: String) {
        val book = libraryRepository.downloadedBook(bookId)
        if (book == null || book.chapters.isEmpty()) {
            loadedBook = null
            player.clearMediaItems()
            _state.value = PlayerUiState(errorMessage = "Download this book before playback.")
            return
        }
        if (loadedBook?.id == bookId && player.mediaItemCount == book.chapters.size) {
            publishState()
            return
        }

        loadedBook = book
        val mediaItems = book.chapters.map { chapter ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(java.io.File(chapter.audioFilePath)))
                .setMediaId(chapter.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(chapter.title)
                        .setAlbumTitle(book.title)
                        .build()
                )
                .build()
        }
        val savedPosition = libraryDao.playbackProgress(bookId)?.positionMillis ?: 0L
        val seekTarget = book.seekTargetFor(savedPosition)
        player.setMediaItems(mediaItems, seekTarget.chapterIndex, seekTarget.chapterPositionMs)
        player.prepare()
        publishState()
    }

    fun playPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            applicationContext.startService(Intent(applicationContext, SonnetMediaSessionService::class.java))
            player.play()
        }
    }

    fun seekBack() {
        player.seekBack()
        saveProgressSoon()
    }

    fun seekForward() {
        player.seekForward()
        saveProgressSoon()
    }

    fun seekToBookPosition(positionMs: Long) {
        val book = loadedBook ?: return
        val target = book.seekTargetFor(positionMs)
        player.seekTo(target.chapterIndex, target.chapterPositionMs)
        saveProgressSoon()
    }

    fun jumpToChapter(chapterId: String) {
        val book = loadedBook ?: return
        val index = book.chapters.indexOfFirst { it.id == chapterId }
        if (index >= 0) {
            player.seekTo(index, 0L)
            saveProgressSoon()
        }
    }

    fun setSleepTimer(timer: SleepTimerState) {
        chapterEndSleepEnabled = timer is SleepTimerState.ChapterEnd
        lastSleepPositionMs = null
        _state.update { it.copy(sleepTimer = timer) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun release() {
        saveProgressSoon()
        player.release()
    }

    private suspend fun progressTicker() {
        while (true) {
            publishState()
            if (player.isPlaying && SystemClock.elapsedRealtime() - lastPeriodicProgressSaveMs >= 5_000L) {
                lastPeriodicProgressSaveMs = SystemClock.elapsedRealtime()
                saveProgressSoon()
            }
            delay(1_000)
        }
    }

    private suspend fun sleepTimerTicker() {
        while (true) {
            val timer = state.value.sleepTimer
            if (timer is SleepTimerState.Countdown && player.isPlaying) {
                val position = currentBookPositionMs()
                val previous = lastSleepPositionMs
                lastSleepPositionMs = position
                if (previous != null) {
                    val playedDelta = (position - previous).coerceIn(0L, 1_500L)
                    val remaining = timer.remainingMs - playedDelta
                    if (remaining <= 0L) {
                        player.pause()
                        setSleepTimer(SleepTimerState.Off)
                    } else {
                        _state.update { it.copy(sleepTimer = timer.copy(remainingMs = remaining)) }
                    }
                }
            } else {
                lastSleepPositionMs = null
            }
            delay(1_000)
        }
    }

    private fun publishState() {
        val book = loadedBook
        val position = currentBookPositionMs()
        val duration = book?.totalDurationMs() ?: player.duration.takeIf { it > 0 } ?: 0L
        val currentChapter = book?.chapters?.getOrNull(player.currentMediaItemIndex)
        _state.update {
            it.copy(
                bookId = book?.id,
                title = book?.title.orEmpty(),
                chapters = book?.toUiChapters().orEmpty(),
                currentChapterId = currentChapter?.id,
                currentChapterTitle = currentChapter?.title.orEmpty(),
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = position,
                durationMs = duration,
                canPlay = book != null && player.mediaItemCount > 0
            )
        }
    }

    private fun saveProgressSoon() {
        if (saveInFlight) return
        val book = loadedBook ?: return
        val positionMs = currentBookPositionMs()
        val durationMs = book.totalDurationMs()
        saveInFlight = true
        scope.launch(Dispatchers.IO) {
            delay(300)
            libraryDao.upsertPlaybackProgress(
                PlaybackProgressEntity(
                    libraryItemId = book.id,
                    positionMillis = positionMs,
                    durationMillis = durationMs,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    pendingSync = true
                )
            )
            saveInFlight = false
        }
    }

    private fun currentBookPositionMs(): Long {
        val book = loadedBook ?: return max(player.currentPosition, 0L)
        val beforeCurrent = book.chapters
            .take(player.currentMediaItemIndex.coerceAtLeast(0))
            .sumOf { it.durationMs ?: 0L }
        return beforeCurrent + max(player.currentPosition, 0L)
    }
}

data class PlayerUiState(
    val bookId: String? = null,
    val title: String = "",
    val chapters: List<PlayerChapter> = emptyList(),
    val currentChapterId: String? = null,
    val currentChapterTitle: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val canPlay: Boolean = false,
    val sleepTimer: SleepTimerState = SleepTimerState.Off,
    val errorMessage: String? = null
)

data class PlayerChapter(
    val id: String,
    val title: String,
    val position: Int,
    val startPositionMs: Long,
    val durationMs: Long?
)

sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data class Countdown(val remainingMs: Long) : SleepTimerState
    data object ChapterEnd : SleepTimerState
}

private data class SeekTarget(
    val chapterIndex: Int,
    val chapterPositionMs: Long
)

private fun DownloadedBook.seekTargetFor(bookPositionMs: Long): SeekTarget {
    var remaining = bookPositionMs.coerceAtLeast(0L)
    chapters.forEachIndexed { index, chapter ->
        val duration = chapter.durationMs ?: 0L
        if (duration <= 0L || remaining < duration) return SeekTarget(index, remaining)
        remaining -= duration
    }
    val lastIndex = chapters.lastIndex.coerceAtLeast(0)
    val lastDuration = chapters.getOrNull(lastIndex)?.durationMs ?: 0L
    return SeekTarget(lastIndex, lastDuration)
}

private fun DownloadedBook.totalDurationMs(): Long = chapters.sumOf { it.durationMs ?: 0L }

private fun DownloadedBook.toUiChapters(): List<PlayerChapter> {
    var startPosition = 0L
    return chapters.map { chapter ->
        PlayerChapter(
            id = chapter.id,
            title = chapter.title,
            position = chapter.position,
            startPositionMs = startPosition,
            durationMs = chapter.durationMs
        ).also { startPosition += chapter.durationMs ?: 0L }
    }
}

const val SEEK_INCREMENT_MS = 15_000L