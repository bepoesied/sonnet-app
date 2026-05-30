package pw.kmr.sonnet.shared.playback

import pw.kmr.sonnet.shared.model.DownloadedBook

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

fun DownloadedBook.toUiChapters(): List<PlayerChapter> =
    projectChapters().map { chapter ->
        PlayerChapter(
            id = chapter.id,
            title = chapter.title,
            position = chapter.position,
            startPositionMs = chapter.startPositionMs,
            durationMs = chapter.durationMs
        )
    }
