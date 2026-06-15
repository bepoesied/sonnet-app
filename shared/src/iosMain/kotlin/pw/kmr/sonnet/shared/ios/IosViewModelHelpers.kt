package pw.kmr.sonnet.shared.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import pw.kmr.sonnet.shared.auth.LoginEffect
import pw.kmr.sonnet.shared.auth.LoginUiState
import pw.kmr.sonnet.shared.auth.LoginRepository
import pw.kmr.sonnet.shared.auth.LoginViewModel
import pw.kmr.sonnet.shared.auth.PlatformAuthProvider
import pw.kmr.sonnet.shared.core.AppUiState
import pw.kmr.sonnet.shared.core.AppViewModel
import pw.kmr.sonnet.shared.core.AppViewModelRepository
import pw.kmr.sonnet.shared.auth.AuthSessionManager
import pw.kmr.sonnet.shared.library.LibraryUiState
import pw.kmr.sonnet.shared.library.LibraryViewModel
import pw.kmr.sonnet.shared.library.LibraryViewModelRepository
import pw.kmr.sonnet.shared.model.LibraryBook
import pw.kmr.sonnet.shared.playback.PlaybackOrchestrator
import pw.kmr.sonnet.shared.playback.PlayerUiState
import pw.kmr.sonnet.shared.playback.SleepTimerState

class IosLoginHelper(
    loginRepository: LoginRepository,
    platformAuthProvider: PlatformAuthProvider
) {
    private val viewModel = LoginViewModel(loginRepository, platformAuthProvider)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val uiState: StateFlow<LoginUiState> get() = viewModel.uiState

    fun onServerUrlChange(serverUrl: String) = viewModel.onServerUrlChange(serverUrl)

    fun startLogin(onSuccess: () -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                viewModel.startLogin()
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Sign in failed.")
            }
        }
    }

    fun completeLogin(authResult: Any, onSuccess: () -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                viewModel.completeLogin(authResult)
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Sign in failed.")
            }
        }
    }

    fun collectEffects(onEffect: (LoginEffect) -> Unit) {
        scope.launch {
            viewModel.loginEffects.collectLatest { effect ->
                onEffect(effect)
            }
        }
    }

    fun savedServerUrl(): String? = loginRepository.savedServerUrl()
}

class IosAppHelper(
    repository: AppViewModelRepository,
    authSessionManager: AuthSessionManager
) {
    private val viewModel = AppViewModel(repository, authSessionManager)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val uiState: StateFlow<AppUiState> get() = viewModel.uiState

    fun bootstrapSession(onComplete: () -> Unit) {
        scope.launch {
            try {
                viewModel.bootstrapSession()
            } finally {
                onComplete()
            }
        }
    }

    fun logout(onComplete: () -> Unit = {}) {
        scope.launch {
            try {
                viewModel.logout()
            } finally {
                onComplete()
            }
        }
    }

    fun observeUiState(onChange: (AppUiState) -> Unit) {
        scope.launch {
            viewModel.uiState.collectLatest { state ->
                onChange(state)
            }
        }
    }
}

class IosLibraryHelper(
    repository: LibraryViewModelRepository
) {
    val viewModel = LibraryViewModel(repository)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val uiState: StateFlow<LibraryUiState> get() = viewModel.uiState

    fun refresh() = viewModel.refresh()
    fun clearError() = viewModel.clearError()
    fun swipeDownloadAction(book: LibraryBook) = viewModel.swipeDownloadAction(book)
    fun swipeCompletionAction(book: LibraryBook) = viewModel.swipeCompletionAction(book)

    fun observeUiState(onChange: (LibraryUiState) -> Unit) {
        scope.launch {
            viewModel.uiState.collectLatest { state ->
                onChange(state)
            }
        }
    }
}

class IosPlaybackHelper(
    private val orchestrator: PlaybackOrchestrator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val state: StateFlow<PlayerUiState> get() = orchestrator.state

    fun load(bookId: String, onComplete: () -> Unit = {}) {
        scope.launch {
            try {
                orchestrator.load(bookId)
            } finally {
                onComplete()
            }
        }
    }

    fun playPause() = orchestrator.playPause()
    fun seekBack() = orchestrator.seekBack()
    fun seekForward() = orchestrator.seekForward()
    fun seekToBookPosition(positionMs: Long) = orchestrator.seekToBookPosition(positionMs)
    fun jumpToChapter(chapterId: String) = orchestrator.jumpToChapter(chapterId)
    fun setSleepTimer(timer: SleepTimerState) = orchestrator.setSleepTimer(timer)
    fun useRemoteProgress() = orchestrator.useRemoteProgress()
    fun keepLocalProgress() = orchestrator.keepLocalProgress()
    fun clearError() = orchestrator.clearError()
    fun shutdown() = orchestrator.shutdown()

    fun observeState(onChange: (PlayerUiState) -> Unit) {
        scope.launch {
            orchestrator.state.collectLatest { state ->
                onChange(state)
            }
        }
    }
}
