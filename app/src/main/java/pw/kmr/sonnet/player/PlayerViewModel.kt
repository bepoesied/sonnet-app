package pw.kmr.sonnet.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pw.kmr.sonnet.shared.playback.PlaybackOrchestrator
import pw.kmr.sonnet.shared.playback.PlayerUiState
import pw.kmr.sonnet.shared.playback.SleepTimerState

class PlayerViewModel(
    private val orchestrator: PlaybackOrchestrator,
    private val bookId: String
) : ViewModel() {
    val uiState: StateFlow<PlayerUiState> = orchestrator.state

    private var loadJob: Job? = null

    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { orchestrator.load(bookId) }
    }

    fun playPause() = orchestrator.playPause()

    fun seekBack() = orchestrator.seekBack()

    fun seekForward() = orchestrator.seekForward()

    fun seekTo(positionMs: Long) = orchestrator.seekToBookPosition(positionMs)

    fun jumpToChapter(chapterId: String) = orchestrator.jumpToChapter(chapterId)

    fun setSleepTimer(timer: SleepTimerState) = orchestrator.setSleepTimer(timer)

    fun useRemoteProgress() = orchestrator.useRemoteProgress()

    fun keepLocalProgress() = orchestrator.keepLocalProgress()

    fun clearError() = orchestrator.clearError()

    class Factory(
        private val orchestrator: PlaybackOrchestrator,
        private val bookId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(orchestrator, bookId) as T
        }
    }
}
