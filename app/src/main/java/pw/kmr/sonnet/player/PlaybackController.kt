package pw.kmr.sonnet.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
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
import pw.kmr.sonnet.shared.library.LibraryRepository
import pw.kmr.sonnet.shared.model.DownloadedBook
import pw.kmr.sonnet.shared.playback.bookPositionFor
import pw.kmr.sonnet.shared.playback.chapterOffsetAt
import pw.kmr.sonnet.shared.playback.chapterStartPositionMs
import pw.kmr.sonnet.shared.playback.chapterTitleAt
import pw.kmr.sonnet.shared.playback.positionFor
import pw.kmr.sonnet.shared.playback.progressAt
import pw.kmr.sonnet.shared.playback.projectChapters
import pw.kmr.sonnet.shared.playback.seekTargetFor
import pw.kmr.sonnet.shared.playback.totalDurationMs
import pw.kmr.sonnet.shared.sync.ProgressSyncer

private const val SAVE_DEBOUNCE_MS = 300L
private const val PROGRESS_TICK_MS = 1_000L
private const val PERIODIC_SAVE_MS = 5_000L
private const val PERIODIC_SYNC_MS = 60_000L
const val SEEK_INCREMENT_MS = 10_000L

class PlaybackController(
    context: Context,
    private val libraryRepository: LibraryRepository,
    private val libraryDao: LibraryDao,
    private val progressSyncer: ProgressSyncer
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    private val _pendingPlayerRequest = MutableStateFlow<String?>(null)

    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    val pendingPlayerRequest: StateFlow<String?> = _pendingPlayerRequest.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    @Volatile private var loadedBook: DownloadedBook? = null
    @Volatile private var saveInFlight = false
    @Volatile private var forceSyncAfterSave = false
    @Volatile private var resumeCheckInFlight = false
    private var lastPeriodicProgressSaveMs = 0L
    private var lastPeriodicProgressSyncMs = 0L
    private var chapterEndSleepEnabled = false
    private var lastSleepPositionMs: Long? = null
    private var pendingPlayAfterConnect = false

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishState()
            if (
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                saveProgressSoon(forceSync = !player.isPlaying)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (chapterEndSleepEnabled && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                chapterEndSleepEnabled = false
                controller?.pause()
                _state.update { it.copy(sleepTimer = SleepTimerState.Off) }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _state.update { it.copy(errorMessage = error.message ?: "Playback failed.") }
        }
    }

    init {
        connectToService()
    }

    private fun connectToService() {
        val token = SessionToken(
            applicationContext,
            ComponentName(applicationContext, SonnetMediaSessionService::class.java)
        )
        val future = MediaController.Builder(applicationContext, token).buildAsync()
        controllerFuture = future
        Futures.addCallback(
            future,
            object : FutureCallback<MediaController> {
                override fun onSuccess(result: MediaController) {
                    controller = result
                    result.addListener(playerListener)
                    scope.launch { progressTicker() }
                    scope.launch { sleepTimerTicker() }
                    if (pendingPlayAfterConnect) {
                        pendingPlayAfterConnect = false
                        result.play()
                    }
                }

                override fun onFailure(t: Throwable) {
                    _state.update { it.copy(errorMessage = "Failed to connect to playback service.") }
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    suspend fun load(bookId: String) {
        val c = controller ?: return
        if (loadedBook?.id == bookId && c.mediaItemCount > 0) {
            publishState()
            return
        }
        libraryRepository.prepareBookForPlayback(bookId)
        val book = libraryRepository.downloadedBook(bookId) ?: return
        val startPosition = reconcileProgressOnOpen(book)
        val target = book.seekTargetFor(startPosition)
        val artworkUri = book.coverFilePath?.let { Uri.fromFile(java.io.File(it)) }

        loadedBook = book
        c.setMediaItems(
            book.chapters.map { chapter ->
                MediaItem.Builder()
                    .setUri(chapter.audioFilePath)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(chapter.title)
                            .setAlbumTitle(book.title)
                            .setArtist(book.author)
                            .setArtworkUri(artworkUri)
                            .build()
                    )
                    .build()
            },
            target.chapterIndex,
            target.chapterPositionMs
        )
        c.prepare()
        publishState()
    }

    fun playPause() {
        val book = loadedBook ?: return
        val c = controller
        if (c != null && c.isPlaying) {
            c.pause()
            saveProgressSoon(forceSync = true)
            return
        }

        if (resumeCheckInFlight || state.value.resumePrompt != null) return

        scope.launch {
            if (reconcileProgressBeforePlay(book)) {
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(applicationContext, SonnetMediaSessionService::class.java)
                )
                controller?.play() ?: run { pendingPlayAfterConnect = true }
            }
        }
    }

    fun seekBack() {
        val c = controller ?: return
        val from = currentBookPositionMs(c)
        c.seekBack()
        val to = currentBookPositionMs(c)
        consumeSleepTimerForSeek(from, to)
        saveProgressSoon(forceSync = !c.isPlaying)
        publishState()
    }

    fun seekForward() {
        val c = controller ?: return
        val from = currentBookPositionMs(c)
        c.seekForward()
        val to = currentBookPositionMs(c)
        consumeSleepTimerForSeek(from, to)
        saveProgressSoon(forceSync = !c.isPlaying)
        publishState()
    }

    fun seekToBookPosition(positionMs: Long) {
        val book = loadedBook ?: return
        val c = controller ?: return
        val from = currentBookPositionMs(c)
        val target = book.seekTargetFor(positionMs)
        c.seekTo(target.chapterIndex, target.chapterPositionMs)
        consumeSleepTimerForSeek(from, positionMs)
        saveProgressSoon(forceSync = !c.isPlaying)
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
        val c = controller ?: return
        c.pause()
        saveProgressSoon(forceSync = true)
        loadedBook = null
        c.removeListener(playerListener)
        c.release()
        controller = null
        controllerFuture?.cancel(true)
        controllerFuture = null
        _state.value = PlayerUiState()
    }

    fun useRemoteProgress() {
        val book = loadedBook ?: return
        val c = controller ?: return
        val prompt = state.value.resumePrompt ?: return
        val target = book.seekTargetFor(prompt.remotePositionMs)
        c.seekTo(target.chapterIndex, target.chapterPositionMs)
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
        val localProgress = libraryDao.playbackProgress(book.id)
        val remoteProgress = progressSyncer.remoteProgress(book.id)
        val remoteUpdatedAt = remoteProgress?.updatedAtEpochMillis
        val remotePosition = remoteProgress?.chapterId?.let { chapterId ->
            book.positionFor(chapterId, remoteProgress.offsetMillis)
        }

        if (remoteUpdatedAt != null && remotePosition != null) {
            val localUpdatedAt = localProgress?.updatedAtEpochMillis ?: 0L
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
                        isCompleted = remoteProgress.isCompleted,
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

        if (localProgress?.pendingSync == true) {
            scope.launch(Dispatchers.IO) { progressSyncer.syncBook(book.id) }
        }
        return localProgress?.positionMillis ?: 0L
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
        val positionMs = currentBookPositionMs(controller ?: return)
        val durationMs = book.totalDurationMs()
        val chapterProgress = book.progressAt(positionMs)

        saveInFlight = true
        scope.launch(Dispatchers.IO) {
            delay(SAVE_DEBOUNCE_MS)
            libraryDao.upsertPlaybackProgress(
                PlaybackProgressEntity(
                    libraryItemId = book.id,
                    chapterId = chapterProgress.chapterId,
                    chapterOffsetMillis = chapterProgress.chapterOffsetMs,
                    positionMillis = positionMs,
                    durationMillis = durationMs,
                    updatedAtEpochMillis = System.currentTimeMillis(),
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

    private suspend fun progressTicker() {
        while (true) {
            val c = controller
            if (c == null) {
                delay(PROGRESS_TICK_MS)
                continue
            }
            publishState()
            val now = System.currentTimeMillis()
            if (c.isPlaying && now - lastPeriodicProgressSaveMs >= PERIODIC_SAVE_MS) {
                lastPeriodicProgressSaveMs = now
                saveProgressSoon(forceSync = false)
            }
            if (c.isPlaying && now - lastPeriodicProgressSyncMs >= PERIODIC_SYNC_MS) {
                lastPeriodicProgressSyncMs = now
                scope.launch(Dispatchers.IO) { progressSyncer.syncPending() }
            }
            delay(PROGRESS_TICK_MS)
        }
    }

    private suspend fun sleepTimerTicker() {
        while (true) {
            val c = controller
            val timer = state.value.sleepTimer
            if (timer is SleepTimerState.Countdown && c != null && c.isPlaying) {
                val position = currentBookPositionMs(c)
                val last = lastSleepPositionMs
                if (last != null && position > last) {
                    val remaining = timer.remainingMs - (position - last)
                    if (remaining <= 0L) {
                        c.pause()
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
                    controller?.pause()
                    setSleepTimer(SleepTimerState.Off)
                } else {
                    _state.update { it.copy(sleepTimer = timer.copy(remainingMs = remaining)) }
                }
            }
            SleepTimerState.ChapterEnd -> {
                val book = loadedBook ?: return
                val c = controller ?: return
                val currentChapterEnd = book.chapterStartPositionMs(c.currentMediaItemIndex + 1)
                if (toPositionMs >= currentChapterEnd) {
                    setSleepTimer(SleepTimerState.Off)
                }
            }
        }
    }

    private fun publishState() {
        val book = loadedBook
        val c = controller
        val currentChapterIndex = c?.currentMediaItemIndex?.coerceAtLeast(0) ?: 0
        val currentChapter = book?.chapters?.getOrNull(currentChapterIndex)
        val playerPosition = max(c?.currentPosition ?: 0L, 0L)
        val position = book?.bookPositionFor(currentChapterIndex, playerPosition) ?: playerPosition
        val duration = book?.totalDurationMs() ?: c?.duration?.takeIf { it > 0L } ?: 0L
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
                isPlaying = c?.isPlaying == true,
                positionMs = position,
                durationMs = duration,
                canPlay = book != null && (c?.mediaItemCount ?: 0) > 0
            )
        }
    }

    private fun currentBookPositionMs(c: MediaController): Long {
        val book = loadedBook ?: return max(c.currentPosition, 0L)
        return book.bookPositionFor(
            chapterIndex = c.currentMediaItemIndex,
            chapterPositionMs = max(c.currentPosition, 0L)
        )
    }
}

data class PlayerUiState(
    val bookId: String? = null,
    val title: String = "",
    val author: String? = null,
    val coverFilePath: String? = null,
    val chapters: List<PlayerChapter> = emptyList(),
    val currentChapterId: String? = null,
    val currentChapterTitle: String = "",
    val currentChapterStartPositionMs: Long = 0L,
    val currentChapterPositionMs: Long = 0L,
    val currentChapterDurationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val canPlay: Boolean = false,
    val isCheckingRemoteState: Boolean = false,
    val sleepTimer: SleepTimerState = SleepTimerState.Off,
    val resumePrompt: ProgressResumePrompt? = null,
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

data class ProgressResumePrompt(
    val localPositionMs: Long,
    val localChapterTitle: String = "",
    val localChapterOffsetMs: Long = 0L,
    val remotePositionMs: Long,
    val remoteChapterTitle: String = "",
    val remoteChapterOffsetMs: Long = 0L,
    val remoteUpdatedAtEpochMillis: Long
)

private fun DownloadedBook.toUiChapters(): List<PlayerChapter> =
    projectChapters().map { chapter ->
        PlayerChapter(
            id = chapter.id,
            title = chapter.title,
            position = chapter.position,
            startPositionMs = chapter.startPositionMs,
            durationMs = chapter.durationMs
        )
    }
