import kotlinx.coroutines.Job
    private var loadJob: Job? = null
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { playbackController.load(bookId) }
    fun keepLocalProgress() = playbackController.keepLocalProgress()
package pw.kmr.sonnet.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val playbackController: PlaybackController,
    private val bookId: String
) : ViewModel() {
    val uiState: StateFlow<PlayerUiState> = playbackController.state

    init {
        viewModelScope.launch { playbackController.load(bookId) }
    }

    fun playPause() = playbackController.playPause()
    fun seekBack() = playbackController.seekBack()
    fun seekForward() = playbackController.seekForward()
    fun seekTo(positionMs: Long) = playbackController.seekToBookPosition(positionMs)
    fun jumpToChapter(chapterId: String) = playbackController.jumpToChapter(chapterId)
    fun setSleepTimer(timer: SleepTimerState) = playbackController.setSleepTimer(timer)
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