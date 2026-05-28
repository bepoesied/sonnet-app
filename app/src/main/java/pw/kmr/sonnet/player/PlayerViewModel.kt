package pw.kmr.sonnet.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val playbackController: PlaybackController,
    private val bookId: String
) : ViewModel() {
    val uiState: StateFlow<PlayerUiState> = playbackController.state

    private var loadJob: Job? = null

    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { playbackController.load(bookId) }
    }

    fun playPause() = playbackController.playPause()

    fun seekBack() = playbackController.seekBack()

    fun seekForward() = playbackController.seekForward()

    fun seekTo(positionMs: Long) = playbackController.seekToBookPosition(positionMs)

    fun jumpToChapter(chapterId: String) = playbackController.jumpToChapter(chapterId)

    fun setSleepTimer(timer: SleepTimerState) = playbackController.setSleepTimer(timer)

    fun useRemoteProgress() = playbackController.useRemoteProgress()

    fun keepLocalProgress() = playbackController.keepLocalProgress()

    fun clearError() = playbackController.clearError()

    class Factory(
        private val playbackController: PlaybackController,
        private val bookId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(playbackController, bookId) as T
        }
    }
}
