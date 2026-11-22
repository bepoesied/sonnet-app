package pw.kmr.sonnet.shared.core

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.Flow
import pw.kmr.sonnet.shared.auth.AuthSessionManager
import pw.kmr.sonnet.shared.auth.LoginEffect
import pw.kmr.sonnet.shared.auth.LoginRepository
import pw.kmr.sonnet.shared.auth.LoginUiState
import pw.kmr.sonnet.shared.auth.LoginViewModel
import pw.kmr.sonnet.shared.auth.OidcAuthorizationResult
import pw.kmr.sonnet.shared.auth.PlatformAuthProvider
import pw.kmr.sonnet.shared.auth.loginViewModelFactory
import pw.kmr.sonnet.shared.library.BookDownloadScheduler
import pw.kmr.sonnet.shared.library.LibraryUiState
import pw.kmr.sonnet.shared.library.LibraryViewModel
import pw.kmr.sonnet.shared.library.LibraryViewModelRepository
import pw.kmr.sonnet.shared.library.libraryViewModelFactory
import pw.kmr.sonnet.shared.model.LibraryBook
import pw.kmr.sonnet.shared.playback.PlaybackOrchestrator
import pw.kmr.sonnet.shared.playback.PlayerUiState
import pw.kmr.sonnet.shared.playback.PlayerViewModel
import pw.kmr.sonnet.shared.playback.SleepTimerState
import pw.kmr.sonnet.shared.playback.playerViewModelFactory

/**
 * Lifecycle-managed wrappers around shared AndroidX [androidx.lifecycle.ViewModel] instances for
 * non-Android UI hosts such as SwiftUI. Calling [clear] disposes the backing [ViewModelStore]
 * and cancels `viewModelScope`.
 */
class AppViewModelHandle(
    repository: AppViewModelRepository,
    authSessionManager: AuthSessionManager
) {
    private val viewModelStore = ViewModelStore()
    val viewModel: AppViewModel = ViewModelProvider.create(
        store = viewModelStore,
        factory = appViewModelFactory(repository, authSessionManager)
    )[AppViewModel::class]

    val currentState: AppUiState
        get() = viewModel.uiState.value

    fun observeState(onChange: (AppUiState) -> Unit): FlowSubscription =
        viewModel.uiState.subscribe(onEach = onChange)

    fun logout() = viewModel.logout()

    fun clear() = viewModelStore.clear()
}

class LoginViewModelHandle(
    loginRepository: LoginRepository,
    platformAuthProvider: PlatformAuthProvider
) {
    private val viewModelStore = ViewModelStore()
    val viewModel: LoginViewModel = ViewModelProvider.create(
        store = viewModelStore,
        factory = loginViewModelFactory(loginRepository, platformAuthProvider)
    )[LoginViewModel::class]

    val currentState: LoginUiState
        get() = viewModel.uiState.value

    fun observeState(onChange: (LoginUiState) -> Unit): FlowSubscription =
        viewModel.uiState.subscribe(onEach = onChange)

    fun observeEffects(onEffect: (LoginEffect) -> Unit): FlowSubscription =
        viewModel.loginEffects.subscribe(onEach = onEffect)

    fun onServerUrlChange(serverUrl: String) = viewModel.onServerUrlChange(serverUrl)

    fun startLogin() = viewModel.startLogin()

    fun completeLogin(result: OidcAuthorizationResult?) = viewModel.completeLogin(result)

    fun clear() = viewModelStore.clear()
}

class LibraryViewModelHandle(
    repository: LibraryViewModelRepository,
    requiresReauth: Flow<Boolean>,
    downloadScheduler: BookDownloadScheduler?,
    isOnline: Flow<Boolean>
) {
    private val viewModelStore = ViewModelStore()
    val viewModel: LibraryViewModel = ViewModelProvider.create(
        store = viewModelStore,
        factory = libraryViewModelFactory(repository, requiresReauth, downloadScheduler, isOnline)
    )[LibraryViewModel::class]

    val currentState: LibraryUiState
        get() = viewModel.uiState.value

    fun observeState(onChange: (LibraryUiState) -> Unit): FlowSubscription =
        viewModel.uiState.subscribe(onEach = onChange)

    fun refresh(showProgress: Boolean) = viewModel.refresh(showProgress = showProgress)

    fun clearError() = viewModel.clearError()

    fun updateSearchQuery(query: String) = viewModel.updateSearchQuery(query)

    fun swipeCompletionAction(book: LibraryBook) = viewModel.swipeCompletionAction(book)

    fun swipeDownloadAction(book: LibraryBook) = viewModel.swipeDownloadAction(book)

    fun clear() = viewModelStore.clear()
}

class PlayerViewModelHandle(
    orchestrator: PlaybackOrchestrator,
    bookId: String
) {
    private val viewModelStore = ViewModelStore()
    val viewModel: PlayerViewModel = ViewModelProvider.create(
        store = viewModelStore,
        factory = playerViewModelFactory(orchestrator, bookId)
    )[PlayerViewModel::class]

    val currentState: PlayerUiState
        get() = viewModel.uiState.value

    fun observeState(onChange: (PlayerUiState) -> Unit): FlowSubscription =
        viewModel.uiState.subscribe(onEach = onChange)

    fun load() = viewModel.load()

    fun playPause() = viewModel.playPause()

    fun seekBack() = viewModel.seekBack()

    fun seekForward() = viewModel.seekForward()

    fun seekTo(positionMs: Long) = viewModel.seekTo(positionMs)

    fun jumpToChapter(chapterId: String) = viewModel.jumpToChapter(chapterId)

    fun setSleepTimer(timer: SleepTimerState) = viewModel.setSleepTimer(timer)

    fun useRemoteProgress() = viewModel.useRemoteProgress()

    fun keepLocalProgress() = viewModel.keepLocalProgress()

    fun clearError() = viewModel.clearError()

    fun clear() = viewModelStore.clear()
}
