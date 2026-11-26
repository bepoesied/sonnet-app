package pw.kmr.sonnet.shared.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val orchestrator: PlaybackOrchestrator,
    private val bookId: String
) : ViewModel() {
    val uiState: StateFlow<PlayerUiState> = orchestrator.state

    private var loadJob: Job? = null

    fun load() {
        if (loadJob?.isActive == true) return
        val current = orchestrator.state.value
        if (current.bookId == bookId && current.canPlay) return
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
}

fun playerViewModelFactory(
    orchestrator: PlaybackOrchestrator,
    bookId: String
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        PlayerViewModel(orchestrator, bookId)
    }
}
