package pw.kmr.sonnet.core.ui

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import pw.kmr.sonnet.auth.LoginRoute
import pw.kmr.sonnet.core.AppContainer
import pw.kmr.sonnet.library.LibraryRoute
import pw.kmr.sonnet.shared.core.AppUiState

@Composable
fun SonnetApp(
    uiState: AppUiState,
    appContainer: AppContainer,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (uiState) {
        AppUiState.Loading -> LoadingApp(modifier = modifier)
        is AppUiState.Ready -> AppNavHost(
            isAuthenticated = uiState.session != null,
            appContainer = appContainer,
            onLogout = onLogout,
            modifier = modifier
        )
    }
}

@Composable
private fun AppNavHost(
    isAuthenticated: Boolean,
    appContainer: AppContainer,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val startKey = if (isAuthenticated) LibraryKey else LoginKey
    val backStack = rememberNavBackStack(startKey)
    val playerState by appContainer.playbackOrchestrator.state.collectAsStateWithLifecycle()

    LaunchedEffect(isAuthenticated) {
        val targetKey = if (isAuthenticated) LibraryKey else LoginKey
        if (backStack.lastOrNull() != targetKey) {
            backStack.clear()
            backStack.add(targetKey)
        }
        if (!isAuthenticated) {
            appContainer.playbackOrchestrator.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        appContainer.playbackOrchestrator.pendingPlayerRequest.collect { bookId ->
            if (bookId != null) {
                appContainer.playbackOrchestrator.consumePendingPlayerRequest()
                if (backStack.lastOrNull() !is FullPlayerKey) {
                    backStack.add(FullPlayerKey(bookId, true))
                }
            }
        }
    }

    val provider = entryProvider {
        entry<LoginKey>(
            metadata = MiniPlayerDecoratorStrategy.loginSceneMetadata()
        ) {
            LoginRoute(
                loginRepository = appContainer.loginRepository,
                platformAuthProvider = appContainer.platformAuthProvider
            )
        }
        entry<LibraryKey> {
            LibraryRoute(
                repository = appContainer.libraryRepository,
                onOpenPlayer = { book ->
                    backStack.add(FullPlayerKey(book.id, book.isDownloaded))
                },
                onLogout = onLogout
            )
        }
        entry<FullPlayerKey>(
            metadata = MiniPlayerDecoratorStrategy.fullPlayerMetadata()
        ) { key ->
            pw.kmr.sonnet.player.PlayerRoute(
                bookId = key.bookId,
                isDownloaded = key.isDownloaded,
                playbackOrchestrator = appContainer.playbackOrchestrator,
                onBack = { backStack.removeLastOrNull() }
            )
        }
    }

    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = provider
    )

    NavDisplay(
        entries = entries,
        onBack = { backStack.removeLastOrNull() },
        sceneDecoratorStrategies = listOf(
            MiniPlayerDecoratorStrategy(
                playerState = playerState,
                onTapMiniPlayer = {
                    val bookId = playerState.bookId
                    if (bookId != null) {
                        backStack.add(FullPlayerKey(bookId, true))
                    }
                },
                onPlayPause = appContainer.playbackOrchestrator::playPause
            )
        ),
        transitionSpec = {
            slideInHorizontally(initialOffsetX = { it }) togetherWith
                slideOutHorizontally(targetOffsetX = { -it })
        },
        popTransitionSpec = {
            slideInHorizontally(initialOffsetX = { -it }) togetherWith
                slideOutHorizontally(targetOffsetX = { it })
        },
        predictivePopTransitionSpec = {
            slideInHorizontally(initialOffsetX = { -it }) togetherWith
                slideOutHorizontally(targetOffsetX = { it })
        },
        modifier = modifier.fillMaxSize()
    )
}

@Composable
private fun LoadingApp(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
